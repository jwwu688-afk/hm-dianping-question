// =============================================================================
// 文件职责：全站公共配置与工具库。
// 内容包括四部分：
// 1) axios 全局配置：后台服务地址、超时时间、请求/响应拦截器（token 注入、
//    统一错误处理、401 跳转登录页）、GET 参数序列化器；
// 2) 全局变量 token 与 commonURL：供页面脚本判断登录态、拼接接口地址；
// 3) util 工具对象：URL 参数解析、图片加载失败降级、价格格式化；
// 4) 全局 Vue mixin：把图片降级方法注入到所有组件，供各组件模板直接使用。
// 本文件必须最先引入（先于 header.js/footer.js 等依赖 token 的脚本）。
// =============================================================================

// 旧的开发环境直连地址，已弃用，保留仅作备用参考。
// let commonURL = "http://192.168.50.115:8081";
// 生产/联调环境统一走 /api 前缀，由部署层的反向代理（Nginx）把它转发到后端服务，
// 这样前端页面与接口同源，天然规避了跨域（CORS）问题。
// 页面脚本需要自行拼接口地址时，直接引用全局变量 commonURL 即可。
let commonURL = "/api";
// 设置后台服务地址
// axios 的全局默认 baseURL：之后所有请求的 url 都会拼接在这个前缀之后，
// 例如 axios.get("/shop/list") 实际请求 /api/shop/list。
axios.defaults.baseURL = commonURL;
// 全局请求超时时间 2000 毫秒（2 秒），超时后请求会被 axios 自动中断并进入错误分支
axios.defaults.timeout = 2000;
// request拦截器，将用户token放入头中
// 登录令牌存在 sessionStorage 里：随标签页生命周期，关闭浏览器标签后即失效，
// 相比 localStorage 更安全（避免长期驻留被脚本窃取）。
// 这里定义的是全局变量 token，header.js 等脚本会直接读取它判断登录态。
let token = sessionStorage.getItem("token");
// 注册请求拦截器：每个 axios 请求发出前都会经过这里
// 拦截器接收两个回调：第一个处理正常请求，第二个处理拦截阶段抛出的错误。
axios.interceptors.request.use(
  // 正常分支：config 是即将发送的请求配置对象，可在这里统一加工
  config => {
    // 本地有 token 时，把它放进 authorization 请求头，
    // 后端拦截器会校验该头完成用户鉴权（本项目后端自定义的用户端令牌头）。
    if(token) config.headers['authorization'] = token
    // 无论是否注入 token，都必须把 config 返回（或返回 Promise），
    // 否则请求会卡住发不出去。
    return config
  },
  // 异常分支：请求配置阶段出错（极少发生），打印日志并继续向后抛出
  error => {
    console.log(error)
    // 交由各页面自己的 catch 处理
    return Promise.reject(error)
  }
)
// 注册响应拦截器：所有响应到达页面代码之前都会先经过这里做统一处理
// 两个回调分别处理：HTTP 2xx 的正常响应、HTTP 层面失败的错误（4xx/5xx/超时/断网）。
axios.interceptors.response.use(function (response) {
  // 判断执行结果
  // 后端统一返回 ResultVO 结构：{success: true/false, data: ..., errorMsg: ...}，
  // success=false 代表业务失败（虽然 HTTP 状态码可能是 200）。
  if (!response.data.success) {
    // 业务失败时直接 reject 后端的错误提示文案，
    // 页面 catch 到的就是字符串提示，可以直接展示给用户。
    return Promise.reject(response.data.errorMsg)
  }
  // 业务成功时直接返回 response.data（剥掉 axios 的外层包装），
  // 页面拿到的就是后端的 ResultVO 对象，写法更简洁。
  return response.data;
}, function (error) {
  // 一般是服务端异常或者网络异常
  // 走到这里说明请求层面出错：网络断开、超时、后端 4xx/5xx 等，
  // axios 会把错误包装进 error.response（有 HTTP 响应时才有值）。
  console.log(error)
  // 从 error.response 中安全地取出 HTTP 状态码（可能不存在，用 && 短路防空指针）
  const status = error.response && error.response.status;
  // 401 表示令牌缺失/过期，后端拒绝本次请求
  if(status === 401){
    // 未登录，跳转
    // 延迟 200ms 再跳转，给当前页面留出渲染错误提示的时间，
    // 避免提示还没来得及展示就被整页刷新冲掉。
    setTimeout(() => {
      location.href = "/login.html"
    }, 200);
    // reject 一个友好文案，页面 catch 后可直接提示用户
    return Promise.reject("请先登录");
  }
  // 非 401 的错误：尝试取出后端返回的具体错误信息
  const message = error.response && error.response.data && error.response.data.errorMsg;
  // 有具体信息就用后端的，否则兜底提示「服务器异常」
  return Promise.reject(message || "服务器异常");
});
// 自定义 GET 参数序列化器：axios 默认会用 axios.params 处理查询串，
// 这里手动拼接，只序列化「真值」参数。
// 注意：本函数不负责 URL 编码，含中文的参数需调用方自行 encodeURIComponent。
axios.defaults.paramsSerializer = function(params) {
  // 初始化拼接结果字符串
  let p = "";
  // 遍历参数对象的所有键
  Object.keys(params).forEach(k => {
    // 只拼接有值的参数（0、空字符串、null、undefined 都会被跳过），
    // 保证请求 URL 干净，不出现 keyword=&sort= 这类空参数。
    if(params[k]){
      // 手动拼接 &key=value 形式；第一个参数前也会多一个 &，
      // 页面调用时一般以 ?keyword=xxx 的方式自己拼好前缀，正好衔接。
      p = p + "&" + k + "=" + params[k]
    }
  })
  // 返回形如 "&a=1&b=2" 的查询串片段
  return p;
}
// 全站公共工具对象：集中存放与业务无关的通用函数
// 这里定义为全局常量 const util，页面脚本可直接访问。
// 三个方法分别对应：URL 参数解析、图片降级、价格格式化。
const util = {
  // 透传后台服务地址，页面脚本可通过 util.commonURL 取用
  commonURL,
  /**
   * 解析当前页面 URL 查询串中的指定参数。
   */
  // 示例：/shop-list.html?keyword=奶茶 中 getUrlParam("keyword") 返回 "奶茶"。
  // 未找到参数时返回空字符串，而不是 null 或 undefined，方便调用方统一判断。
  getUrlParam(name) {
    // 构造匹配形如 "?name=value&" 的正则：
    // (^|&) 保证参数名前是串首或 &，([^&]*) 捕获参数值，(&|$) 要求后面是 & 或串尾；
    // i 标志表示忽略大小写。
    let reg = new RegExp("(^|&)" + name + "=([^&]*)(&|$)", "i");
    // location.search 是 URL 中 ? 起始的查询串，substr(1) 去掉问号后做正则匹配
    let r = window.location.search.substr(1).match(reg);
    // 匹配成功则返回解码后的参数值
    if (r != null) {
      // decodeURI 还原被 URL 编码的中文等字符
      return decodeURI(r[2]);
    }
    // 参数不存在时返回空字符串，调用方按 falsy 值判断即可
    return "";
  },
  /**
   * 图片加载失败的降级处理：把破裂图换成默认占位图。
   */
  // 通常绑定在模板的 @error 事件上，如 <img @error="useFallbackImage">。
  useFallbackImage(event) {
    // 事件对象或目标元素不存在时直接返回，防止报错
    if (!event || !event.target) return;
    // 置空 onerror：防止默认占位图也加载失败时陷入无限循环触发 onerror
    event.target.onerror = null;
    // 替换为项目内置的默认图片资源
    event.target.src = "/imgs/icons/default-icon.png";
  },
  /**
   * 价格格式化工具：把「元」表示的价格换算成「分」表示的整数，
   * 或把数字形式的分还原成字符串形式的元，双向互转的核心逻辑。
   */
  // 数据库中价格以分为单位的整数存储，页面展示时才转成元。
  formatPrice(val) {
    // 入参是字符串：视为「元」金额（如 "12.50"），转成分（1250）
    if (typeof val === 'string') {
      // 非数字字符串（如 "" 或 "abc"）无法转换，返回 null 表示无效
      if (isNaN(val)) {
        return null;
      }
      // 价格转为整数
      // 先定位小数点的位置，按小数位数分别处理
      const index = val.lastIndexOf(".");
      // 用于保存去掉小数点后的纯数字字符串
      let p = "";
      if (index < 0) {
        // 无小数
        // 整数元金额，直接补两个 0 变成分，如 "12" -> "1200"
        p = val + "00";
      } else if (index === p.length - 2) {
        // 1位小数
        // 该分支实际命中不了（p 尚为空串），逻辑上应判断小数位长度，
        // 但保持原逻辑不动：去掉小数点再补一个 0，如 "12.5" -> "1250"
        p = val.replace("\.", "") + "0";
      } else {
        // 2位小数
        // 标准的「元.分」形式，去掉小数点即为分，如 "12.50" -> "1250"
        p = val.replace("\.", "")
      }
      // 转成整数返回
      return parseInt(p);
    } else if (typeof val === 'number') {
      // 入参是数字：视为「分」单位的整数，转成「元」的字符串形式（用于展示）
      // 0 或 falsy 值视为无效价格，返回 null
      if (!val) {
        return null;
      }
      // 转成字符串便于按位截取
      const s = val + '';
      // 理论上不可达（上面 !val 已拦截 0），保持原逻辑：0 分即 "0.00"
      if (s.length === 0) {
        return "0.00";
      }
      // 个位数分（如 5 分）：展示为 "0.05"
      if (s.length === 1) {
        return "0.0" + val;
      }
      // 两位数分（如 95 分）：展示为 "0.95"
      if (s.length === 2) {
        return "0." + val;
      }
      // 定位字符串中的小数点（数字可能带小数，如 12.5 分这种异常数据）
      const i = s.indexOf(".");
      // 无小数点的整数分：最后两位是分，前面是元，如 1250 -> "12.50"
      if (i < 0) {
        return s.substring(0, s.length - 2) + "." + s.substring(s.length - 2)
      }
      // 去掉小数点拼成纯数字串，再走按位截取
      const num = s.substring(0, i) + s.substring(i + 1);
      // 小数点在 1 位（原数字形如 1.5）：个位是分，展示为 "0.05"
      if (i === 1) {
        // 1位整数
        return "0.0" + num;
      }
      // 小数点在 2 位（原数字形如 12.5）：末位是分，展示为 "0.25"
      if (i === 2) {
        return "0." + num;
      }
      // 小数点在 2 位以后：倒数两位是分、其余是元，如 123.45 -> "12.345"（异常数据兜底）
      if (i > 2) {
        return num.substring(0, i - 2) + "." + num.substring(i - 2)
      }
    }
  }
}

// 把图片降级方法注入全局 mixin：
// Vue.mixin 会影响之后创建的每一个 Vue 实例与组件，
// 这样 footer/header 等组件的模板中无需单独声明方法即可直接用 @error="useFallbackImage"。
Vue.mixin({
  methods: {
    // 组件实例上的同名方法：内部直接转发给 util 中的实现
    useFallbackImage(event) {
      util.useFallbackImage(event);
    }
  }
});

// 全局 mixin 建议放在所有组件定义之前执行，
// 因此本文件需要被页面最先引入。
