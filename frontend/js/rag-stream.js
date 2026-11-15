// =============================================================================
// 文件职责：RAG 智能问答的 SSE（Server-Sent Events）流式客户端。
// 后端问答接口 /rag/stream/{id} 以 text/event-stream 的形式逐帧推送回答内容，
// 本文件提供一个轻量的 SSE 解析器 RagStream.createParser：
// 调用方把流式读取到的文本块 push 进来，解析器按 SSE 协议拆出「事件帧」，
// 并把每帧解析为 {event, data} 后分派给回调函数 onEvent。
// 页面侧（rag.html / rag.js）通常配合 fetch + ReadableStream 使用本解析器，
// 从而实现「打字机式」的流式问答渲染。
// =============================================================================

// 使用立即执行函数（IIFE）包裹整个模块，避免污染全局作用域。
// 页面只需关注 window.RagStream 这一个全局变量，其余实现细节全部封闭在闭包内。
// 'use strict' 开启严格模式，让潜在的错误（如未声明变量）直接抛出。
(function () {
  'use strict';

  /**
   * 创建一个 SSE 帧解析器（工厂函数）。
   * 之所以自己实现解析器而不是直接用浏览器内置的 EventSource：
   * 1) EventSource 无法自定义请求头，而本项目所有接口都要求携带
   *    authorization 请求头做登录鉴权，因此只能用 fetch 手动读取响应流；
   * 2) fetch 拿到的是字节流片段，块的切分位置是随机的（可能把一帧切成两半），
   *    需要解析器内部做缓冲与容错，这正是本函数的核心职责。
   * @param {Function} onEvent 帧回调，每解析出一帧就调用一次，
   *                           入参为 {event: 事件名, data: 已解析的数据}
   * @returns {Object} 返回 {push, finish} 两个方法组成的解析器实例
   */
  function createParser(onEvent) {
    // 内部缓冲区：暂存尚未凑齐一个完整帧（没有出现空行分隔符）的文本
    // 网络层送来的块边界与 SSE 帧边界并不同步，缓冲区就是为了抹平这种错位。
    let buffer = '';
    // 结束标记：finish() 调用后置为 true，之后的 push/finish 都会被忽略，
    // 防止流结束后仍有残留数据被重复解析。
    let finished = false;

    /**
     * 解析单个 SSE 帧并触发回调。
     * 按 SSE 规范，一个帧内每行是一个「字段: 值」，
     * 常见字段有 event（事件名）和 data（数据行）；冒号开头的行是注释，直接忽略。
     * @param {string} frame 一个完整帧的文本（不含结尾的空行分隔符）
     */
    function emit(frame) {
      // 行拆分前先做换行符归一化：把 \r\n（Windows）和 \r（旧 Mac）
      // 统一替换成 \n，兼容不同服务端实现的换行风格。
      // 拆分后逐行遍历，按「字段: 值」的格式解析出 event 与 data。
      const lines = frame.replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n');
      // 事件名默认为 message：SSE 规范规定帧中未写 event 字段时，
      // 消息按默认事件 message 处理。
      // 后端如果显式声明了 event 字段，这里的值会被后续解析覆盖。
      let event = 'message';
      // 收集本帧内所有的 data 行；SSE 允许一帧内有多行 data，
      // 规范要求最终用换行符把它们拼接成完整数据。
      // 只有 event 没有 data 的帧不会被回调（见下方 length 判断）。
      const dataLines = [];
      lines.forEach(line => {
        // 空行以及以冒号开头的注释行（如 ": keep-alive"）不参与解析，直接跳过
        // 空行在帧内部是非法的（帧分隔符已在 drain 阶段处理），这里是防御性判断；
        // 冒号开头的行是 SSE 心跳/注释行，按规范必须忽略。
        if (!line || line.charAt(0) === ':') return;
        // 找到第一个冒号的位置：冒号前是字段名，冒号后是字段值。
        // 注意用 indexOf 而不是 split，因为 data 的内容里可能包含冒号。
        const separator = line.indexOf(':');
        // 整行没有冒号时，SSE 规范把整行当作字段名、值为空字符串
        const field = separator < 0 ? line : line.slice(0, separator);
        let value = separator < 0 ? '' : line.slice(separator + 1);
        // 规范规定字段值若以一个空格开头，需去掉这个前导空格
        // （即 "data: xxx" 与 "data:xxx" 等价）。
        if (value.charAt(0) === ' ') value = value.slice(1);
        // event 字段：覆盖本帧的事件名（同名重复出现时以后者为准）
        if (field === 'event') event = value;
        // data 字段：收集到数组里，稍后统一拼接
        // 其余字段（如 id、retry）本客户端暂不关心，直接忽略。
        if (field === 'data') dataLines.push(value);
      });
      // 本帧没有任何 data 行（例如只发了 event 或只有注释）则不触发回调
      if (!dataLines.length) return;
      // 多行 data 用 \n 拼接为一条完整数据（符合 SSE 规范）
      // 拼接后的 raw 是服务端推送的原始数据文本，后续尝试按 JSON 解析。
      const raw = dataLines.join('\n');
      let data;
      try {
        // 服务端推送的正常数据是 JSON 字符串（如 {"message":"...", "done":true}），
        // 解析成对象后再交给回调，调用方无需自行 JSON.parse。
        data = JSON.parse(raw);
      } catch (_) {
        // 容错：若某帧不是合法 JSON（如纯文本提示、心跳文本），
        // 则包装成 {message: 原始文本} 的统一结构，保证回调拿到的 data 始终是对象。
        data = {message: raw};
      }
      // 分派：把事件名与数据一起交给外部回调，由调用方按 event 类型分别处理
      // 典型事件：message（增量回答内容）、done（回答结束）等，视后端实现而定。
      onEvent({event, data});
    }

    /**
     * 从缓冲区中切出所有完整的帧并逐帧解析。
     * SSE 以「空行」（连续两个换行符 \n\n）作为帧与帧之间的分隔符。
     * @param {boolean} allowTrailingFrame 是否允许把缓冲区里没有以空行结尾的
     *                                     残留内容作为最后一帧强制解析（流结束时使用）
     */
    function drain(allowTrailingFrame) {
      // 先把缓冲区里的所有换行风格归一化成 \n，保证 indexOf('\n\n') 切分正确
      // 若不归一化，"\r\n\r\n" 分隔符就无法用 indexOf('\n\n') 稳定匹配。
      buffer = buffer.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
      let boundary;
      // 循环查找「空行」分隔符：每次取出一个完整帧交给 emit 解析，
      // 再把缓冲区收缩到分隔符之后的内容，直到缓冲区里不再有完整帧。
      while ((boundary = buffer.indexOf('\n\n')) >= 0) {
        // slice 出分隔符之前的整帧文本
        const frame = buffer.slice(0, boundary);
        // 缓冲区跳过分隔符（两个字符），只保留剩余的半截数据
        buffer = buffer.slice(boundary + 2);
        // 非空帧才解析，避免空帧触发多余的回调
        if (frame) emit(frame);
      }
      // 流结束时（allowTrailingFrame=true）缓冲区可能还剩最后一帧：
      // 由于服务端发送完即关闭连接，最后一段数据后面可能没有空行分隔符，
      // 若不强制解析就会丢失最后一帧，所以这里把残留内容当作末帧整体 emit。
      if (allowTrailingFrame && buffer) {
        emit(buffer);
        buffer = '';
      }
    }

    // 返回解析器实例，对外仅暴露 push 与 finish 两个方法
    return {
      /** 流式喂入：每从 ReadableStream 读到一个文本块就调用一次 */
      push(chunk) {
      // 流已结束或本次喂入为空时直接忽略，保证幂等
      // finished 保护防止流结束后被误用；!chunk 保护空字符串块。
        if (finished || !chunk) return;
        // 追加到缓冲区。注意：chunk 可能刚好把一个帧切成两半
        //（例如前半段 "event: message\nda"，后半段 "ta: xxx\n\n"），
        // 此时 drain 找不到 \n\n 分隔符，残片会留在缓冲区，
        // 等下一次 push 拼齐后再统一切帧——这就是分帧容错的核心。
        // 网络块追加到缓冲区后立即尝试切帧；
        // buffer += chunk 是字符串拼接，只处理文本，
        // 因此调用方需先用 TextDecoder 把 fetch 读到的字节流转成字符串再 push。
        buffer += chunk;
        // 尝试切帧：allowTrailingFrame=false，只解析已凑齐的完整帧
        drain(false);
      },
      /** 结束流：通知解析器不会再有新数据，把缓冲区残留作为最后一帧解析 */
      finish() {
        // 重复调用 finish 无副作用，直接返回（幂等保护）
        if (finished) return;
        // 置结束标记，之后 push 进来的任何数据都会被丢弃
        finished = true;
        // 最后一次切帧：allowTrailingFrame=true，强制把没有空行结尾的残留帧解析掉
        drain(true);
      }
    };
  }

  // 对外暴露的模块 API：目前仅包含解析器工厂函数 createParser
  const api = {createParser};
  // 环境适配：若在 Node 环境（如做单元测试），挂到 module.exports 上支持 require 引入；
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  // 若在浏览器环境，挂到 window.RagStream 上，页面脚本可直接通过 RagStream.createParser 使用。
  if (typeof window !== 'undefined') window.RagStream = api;
})();
