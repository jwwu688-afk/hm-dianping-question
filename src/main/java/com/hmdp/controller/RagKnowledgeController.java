package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 新版「知识库问答」前端（nginx root = html/hmdp-next）所需的转发层。
 *
 * <p>背景：新版前端照的是 zgws 原版「三服务」架构，调用的是
 * /rag/upload、/rag/import/status/{id}、/rag/query、/rag/stream/{id}、/rag/history/{id}，
 * 这些接口此前 Java 侧并不存在，前端因此报 404 →「服务器异常」。
 *
 * <p>而 Python 侧这些能力早已现成，只是分在两个服务里，本类只做转发，不改 Python 任何代码：
 * <ul>
 *   <li>导入服务 8002（app/import_process/api/file_import_service.py）：
 *       POST /upload（字段名 files，可多个）、GET /status/{task_id}</li>
 *   <li>问答服务 8001（app/query_process/api/query_service.py）：
 *       POST /query、GET /stream/{session_id}（SSE）、
 *       GET /history/{session_id}、DELETE /history/{session_id}</li>
 * </ul>
 *
 * <p>与旧版 RagController（对接 shop_qa 8003）互不影响：方法级路径无重叠。
 */
// —— 本类的通用套路 ——
// 所有接口先做登录校验（UserHolder，由 JWT 拦截器填充）与参数校验
// （ID 只允许 UUID 形态，防止路径穿越），再转发到对应 Python 服务；
// Python 返回什么就包成 Result.ok 原样透传，不做二次加工，保持前后端协议一致。
// —— 为什么转发层值得单独一个类 ——
// Python 两个服务（8001/8002）的接口风格、超时需求、错误兜底各不相同，
// 集中在这里可以统一治理代理设置、超时与服务不可用时的降级文案。
// 所有 Python 服务的地址都走配置项（shop.qa.query-url / import-url），
// 默认 127.0.0.1:8001 / 8002，部署时改配置即可换环境，无需动代码。
@RestController
@RequestMapping("/rag")
public class RagKnowledgeController {
    private static final Logger log = LoggerFactory.getLogger(RagKnowledgeController.class);
    /** 一次最多接收的文件数，防止误选整目录造成内存打爆 */
    private static final int MAX_FILES = 20;
    /** 单文件大小上限 20MB：兼顾 PDF 扫描件的体积与 multipart 内存驻留成本 */
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    /** 会话ID / 任务ID 只允许 UUID 形态，同时避免路径穿越 */
    private static final String ID_PATTERN = "[0-9a-zA-Z\\-]{8,64}";
    // 两个下游服务各配一个 RestTemplate：问答（SSE 握手与同步问答）需要更长的读超时，
    // 导入（上传 + 解析触发）相对快；分开建实例才能互不影响地设置不同 readTimeout
    private final RestTemplate queryClient;
    private final RestTemplate importClient;
    // 下游地址从配置读取，默认指向本机的 8001（问答）/ 8002（导入）
    // 问答管 /query /stream /history，导入管 /upload /status，职责按服务边界划分
    // 两个 client 都在构造器里通过 buildClient 创建，保证超时配置集中可见
    private final String queryUrl;
    private final String importUrl;

    // —— 读超时差异 ——
    // 问答服务的 /query 在 is_stream=false 时要同步等 LLM 跑完才返回，
    // 可能耗时数分钟，故给 300s；导入服务的 /upload 只负责接收文件并启动
    // 异步任务，120s 足够。连接超时统一 3s，下游没起时快速失败。
    /** 构造时分别创建两个带定制超时的 RestTemplate，超时差异见上方注释。 */
    public RagKnowledgeController(
            @Value("${shop.qa.query-url:http://127.0.0.1:8001}") String queryUrl,
            @Value("${shop.qa.import-url:http://127.0.0.1:8002}") String importUrl) {
        this.queryUrl = queryUrl;
        this.importUrl = importUrl;
        // 本地 Python 服务必须绕开系统 HTTP 代理（HTTP_PROXY 会把 127.0.0.1 也转发给代理导致连接失败）
        // 300s：覆盖 is_stream=false 时 LLM 的最坏生成耗时
        this.queryClient = buildClient(300);
        // 120s：上传接口本身不跑 LLM，超时给短一些，失败尽早暴露
        this.importClient = buildClient(120);
    }

    // —— buildClient 的三个关键点 ——
    // 1) Proxy.NO_PROXY：显式禁用代理。开发机若设置了 HTTP_PROXY 环境变量，
    //    默认实现会把 127.0.0.1 的请求也丢给代理，导致连接失败或走公网绕一圈；
    // 2) 连接超时 3s：下游未启动时快速失败，前端能立刻拿到「服务不可用」；
    // 3) 读超时由调用方指定：同步问答与文件导入的耗时特征不同，不能共用一个值。
    // 为什么每个 client 单独 new 而不注入全局 RestTemplate：超时是连接级别的
    // 配置，共用实例会让问答与导入互相牵制。
    // RestTemplate 是线程安全的，单例复用即可，无需每次请求新建。
    /** 构建直连本机 Python 服务的 RestTemplate，三个关键配置见上方注释。 */
    private RestTemplate buildClient(int readTimeoutSeconds) {
        // SimpleClientHttpRequestFactory：JDK 原生 HttpURLConnection 的封装，够用且无额外依赖
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 绕开系统代理：本地回环地址直连
        factory.setProxy(Proxy.NO_PROXY);
        // 连接超时 3s：TCP 握手 + 建连的上限
        factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        // 读超时由调用方按下游特征指定
        factory.setReadTimeout((int) Duration.ofSeconds(readTimeoutSeconds).toMillis());
        return new RestTemplate(factory);
    }

    // ------------------------------------------------------------------
    // 导入：前端 POST /rag/upload（multipart，字段名 files，可多个）
    //   → Python 8002 POST /upload        返回 {task_ids:[...]}
    // ------------------------------------------------------------------
    // —— /rag/upload 协议与职责链 ——
    // 协议：前端以 multipart/form-data 上传，字段名固定 files（可多个文件）；
    // Python 侧接收后逐个解析入库，立即返回 {task_ids:[...]}，
    // 之后前端用这些 task_id 去 /rag/import/status/{id} 轮询进度。
    // 职责链：登录校验 → 文件数量/大小/后缀白名单校验（Java 先挡一道，
    // Python 侧还会再校验一次，双层防线）→ 重组 multipart（必须用
    // ByteArrayResource 并覆写 getFilename，否则 RestTemplate 组包时会丢
    // 文件名）→ 转发 → 异常时降级为友好提示而非 500。
    // 失败语义：任何一步失败都返回 Result.fail（HTTP 200），前端按 code 判断，
    // 不依赖 HTTP 状态码，与其他接口的失败语义保持一致。
    /** 知识库文件上传（代理转发 → Python 8002 POST /upload），协议见上方注释。 */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result upload(@RequestParam("files") MultipartFile[] files) {
        // 登录校验：知识库按用户隔离，未登录一律拒绝
        // UserHolder 由 JWT 拦截器填充，这里只做判空
        UserDTO user = UserHolder.getUser();
        if (user == null) return Result.fail("请先登录");
        // 数量前置校验：空文件列表与超量都直接挡下，不消耗下游资源
        // MAX_FILES=20：防止误选整目录把内存打爆
        if (files == null || files.length == 0) return Result.fail("请选择要上传的文件");
        if (files.length > MAX_FILES) return Result.fail("一次最多上传 " + MAX_FILES + " 个文件");

        // 逐个校验并重组 multipart 请求体；任何一个文件非法即整体失败返回
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        try {
            // 逐个文件校验：任何一个非法即整体失败，避免半次上传
            for (MultipartFile file : files) {
                // 空文件直接跳过，不算错误
                // 浏览器端通常不会送空文件，这是对非浏览器客户端的防御
                if (file == null || file.isEmpty()) continue;
                // 文件大小/后缀由 Java 侧先挡一道，Python 侧仍会再校验一次
                // MAX_FILE_BYTES=20MB：Java 侧省带宽，Python 侧省解析成本
                if (file.getSize() > MAX_FILE_BYTES) return Result.fail("单个文件不能超过 20MB");
                // 后缀白名单：只放行 PDF / Markdown，转小写再比对，防住 .PDF/.Md 这类写法
                // getOriginalFilename 可能返回 null（非浏览器客户端），也要挡下
                String name = file.getOriginalFilename();
                if (name == null || !(name.toLowerCase().endsWith(".pdf") || name.toLowerCase().endsWith(".md"))) {
                    return Result.fail("仅支持 PDF 或 Markdown 文件");
                }
                // 必须用 ByteArrayResource 并覆写 getFilename，否则 multipart 会丢掉文件名
                // （默认实现拿不到原始文件名，Python 侧就无法判断后缀）
                // getBytes() 一次性读入内存：20MB 上限内是安全的
                ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                    // 覆写后 RestTemplate 组包才能带上正确的 filename
                    @Override
                    public String getFilename() {
                        return name;
                    }
                };
                // 字段名固定为 files，与 FastAPI 的 files: List[UploadFile] = File(...) 对齐
                body.add("files", resource);
            }
        } catch (IOException exception) {
            // 读取文件字节流失败：本地 IO 问题，与下游无关
            // getBytes() 把整个文件读进内存，20MB 上限保证了这里不会 OOM
            return Result.fail("读取上传文件失败");
        }
        if (body.isEmpty()) return Result.fail("请选择要上传的文件");
        // 全部文件都是空文件时 body 仍为空，同样视为没有有效上传

        // 重组请求头：multipart 的 Content-Type 必须显式指定
        // 否则 RestTemplate 可能按默认类型发送，FastAPI 无法解析出 UploadFile
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        try {
            // 转发到导入服务：Python 返回的 task_ids 原样包进 Result 返回给前端
            ResponseEntity<Map> response = importClient.postForEntity(
                    importUrl + "/upload", new HttpEntity<>(body, headers), Map.class);
            // 透传成功结果：前端拿到 task_ids 后开始轮询进度
            return Result.ok(response.getBody());
        } catch (RestClientException exception) {
            // 下游不可用（未启动/超时）时降级为业务失败提示，避免向前端抛 500
            log.warn("转发 /rag/upload 到导入服务失败：{}", exception.getMessage());
            // 120s 读超时也走这里：上传本身不该这么久
            return Result.fail("上传或导入服务暂不可用");
        }
    }

    // ------------------------------------------------------------------
    // 导入进度：前端 GET /rag/import/status/{taskId}
    //   → Python 8002 GET /status/{task_id}   返回 {status, done_list, running_list}
    // ------------------------------------------------------------------
    // —— /rag/import/status 协议 ——
    // 前端在上传拿到 task_ids 后，按固定间隔轮询本接口；Python 返回
    // {status, done_list, running_list}，原样透传给前端渲染进度。
    // taskId 先过 ID_PATTERN 白名单，杜绝把非法字符串拼进下游 URL 造成
    // 路径穿越或注入。
    // 轮询不做聚合：一次请求只查一个任务，前端并行发起，避免单点慢查询拖垮整页。
    /** 导入进度查询（代理转发 → Python 8002 GET /status/{task_id}），协议见上方注释。 */
    @GetMapping("/import/status/{taskId}")
    public Result importStatus(@PathVariable String taskId) {
        // 轮询属于读操作，但知识库按用户隔离，未登录仍要拒绝
        if (UserHolder.getUser() == null) return Result.fail("请先登录");
        // 白名单校验：8~64 位字母数字与连字符
        if (taskId == null || !taskId.matches(ID_PATTERN)) return Result.fail("任务ID无效");
        try {
            // GET 转发：反序列化成 Map 后再包一层 Result，保持统一的响应外壳
            return Result.ok(importClient.getForObject(importUrl + "/status/" + taskId, Map.class));
        } catch (RestClientException exception) {
            // 导入服务未启动或超时：轮询方拿到失败提示后可继续重试
            return Result.fail("导入进度查询失败");
        }
    }

    // ------------------------------------------------------------------
    // 提问：前端 POST /rag/query  body: {query, session_id, is_stream}
    //   → Python 8001 POST /query
    //   is_stream=true 时 Python 立即返回 {session_id}，答案走 /rag/stream 推送
    //   is_stream=false 时 Python 同步跑完再返回 {session_id, answer}
    // ------------------------------------------------------------------
    // —— /rag/query 协议 ——
    // 请求体为 {query, session_id, is_stream}：
    // is_stream=true：Python 立即返回 {session_id}，答案由前端再连
    //   /rag/stream/{sessionId} 以 SSE 流式接收；
    // is_stream=false：Python 同步跑完检索 + 生成，返回 {session_id, answer}。
    // 校验规则在 Java 侧完成：问题 2~500 字（太短无检索意义，太长浪费
    // token 且拖慢 embedding）；session_id 缺省传空串，由 Python 侧新建会话。
    // 注意 session_id 不在本方法做格式校验——空串是合法值，非空串的合法性
    // 由下游负责，转发层只守住 query 的字数硬门槛。
    // 字数用 trim 后的长度判断下限、原始长度判断上限，两个口径都守住。
    /** 知识库提问（代理转发 → Python 8001 POST /query），协议见上方注释。 */
    @PostMapping("/query")
    public Result query(@RequestBody Map<String, Object> request) {
        // 提问接口必须登录：会话历史按用户归属
        if (UserHolder.getUser() == null) return Result.fail("请先登录");
        // 防御式取参：request 或 query 缺失都按“问题太短”处理
        Object query = request == null ? null : request.get("query");
        // 下限 2 字：单字问题检索不出有效语义，直接挡下
        if (!(query instanceof String) || ((String) query).trim().length() < 2) {
            return Result.fail("问题至少输入两个字");
        }
        if (((String) query).length() > 500) return Result.fail("问题最多 500 字");
        // 上限 500 字：embedding 与 LLM 上下文都有成本，超长问题应让用户拆分提问

        // 重组转发载荷：只挑协议约定的三个字段，多余字段不透传
        // 这样 Java 侧与 Python 侧的请求契约是显式的，Python 改字段能立即暴露
        Map<String, Object> payload = new HashMap<>();
        // trim 后转发，避免前后空白影响检索召回
        payload.put("query", ((String) query).trim());
        // session_id 缺省传空串：由 Python 侧新建会话并回发真实 id
        Object sessionId = request.get("session_id");
        payload.put("session_id", sessionId instanceof String ? sessionId : "");
        payload.put("is_stream", Boolean.TRUE.equals(request.get("is_stream")));
        // is_stream 只认 true / 非 true 两态，前端传 "true" 字符串也会被规范化为布尔
        try {
            // 转发到问答服务：is_stream=false 时会阻塞到 LLM 生成完毕
            ResponseEntity<Map> response = queryClient.postForEntity(
                    queryUrl + "/query", payload, Map.class);
            // 同步问答/流式握手都走这里：Python 返回的 session_id（及 answer）原样透传
            return Result.ok(response.getBody());
        } catch (RestClientException exception) {
            log.warn("转发 /rag/query 到问答服务失败：{}", exception.getMessage());
            // 300s 内没等到响应，按服务不可用降级
            return Result.fail("问答服务暂不可用");
        }
    }

    // ------------------------------------------------------------------
    // 流式回答：前端 GET /rag/stream/{sessionId}（SSE）
    //   → Python 8001 GET /stream/{session_id}，逐字节透传，不能攒包
    //   这里不用 ResponseEntity/RestTemplate，而是直接写 HttpServletResponse，
    //   避免 Spring 异步超时（默认 30s）把长连接掐断。
    //
    //   ⚠️ 重要：Python 的 sse_generator 在发完 final/error 之后【不会】关闭连接
    //   （它只定义了 __close__ 信号但全项目无人推送，循环里仅靠 is_disconnected 退出）。
    //   如果在这里傻等，浏览器会因为 Nginx 的 proxy_read_timeout(默认60s) 掐断而报
    //   "TypeError: network error"，同时本线程会永久阻塞、每一次提问泄漏一个 Tomcat 线程。
    //   所以收到终止帧后必须主动收尾：直接 return，让 Tomcat 正常结束响应，
    //   finally 里 disconnect() 又会促使 Python 侧 is_disconnected 成立、清理生成器。
    // ------------------------------------------------------------------
    // —— 为什么不用 RestTemplate / ResponseEntity ——
    // Spring MVC 对异步响应有全局超时（默认 30s），长回答会中途被掐断；
    // 直接写 HttpServletResponse 才能让这条连接存活到问答真正结束。
    // RestTemplate 也不适合拉流：SSE 需要「收到一点就转发一点」，
    // 攒包会造成明显的首字延迟，所以这里用 HttpURLConnection 建长连接逐字节透传。
    // —— 终止协议与主动断开（重要） ——
    // Python 的 _sse_pack 固定输出 "event: {name}\ndata: {json}\n\n"，
    // event 为 final（正常结束）或 error（异常结束）即问答终止。
    // 但 Python 的 sse_generator 发完终止帧后【不会主动关连接】，
    // 若本方法傻等 EOF：Nginx 的 proxy_read_timeout（默认 60s）会先掐断前端连接，
    // 浏览器报 "TypeError: network error"，同时本 Tomcat 线程永久阻塞——
    // 每提问一次泄漏一个线程。因此维护滚动缓冲 seen，识别到终止帧就主动
    // break 返回，让 Tomcat 正常结束响应；finally 的 disconnect() 会让
    // Python 侧 is_disconnected 成立，生成器随之清理。
    // —— 超时设计 ——
    // read 超时 300s 而不是 0（无限等待）：正常流式过程中 delta 持续到达不会
    // 触发它，只有 Python 彻底卡死才会兜底断开，避免线程无限期挂起。
    // —— 响应头 ——
    // X-Accel-Buffering: no 显式告诉 Nginx 不要缓冲本响应，否则 Nginx 会攒满
    // 缓冲区才下发，SSE 的实时性将被完全破坏。
    // —— 透传原则 ——
    // Python 输出的每一帧（event/data）字节层面原样到达浏览器，EventSource
    // 的解析逻辑完全由 Python 的帧格式决定，Java 不做任何改写，避免两套协议定义。
    /** 流式问答（SSE 逐字节透传 → Python 8001 GET /stream/{session_id}），设计要点见上方注释。 */
    @GetMapping("/stream/{sessionId}")
    public void stream(@PathVariable String sessionId, HttpServletResponse response) {
        // SSE 场景不能返回 JSON 错误体，只能用 HTTP 状态码表达失败：
        // EventSource 对非 200 会触发 onerror，前端据此提示
        if (UserHolder.getUser() == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        // sessionId 同样先过白名单，防止拼 URL 注入
        if (sessionId == null || !sessionId.matches(ID_PATTERN)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        HttpURLConnection connection = null;
        try {
            // 直连 Python 问答服务，同样显式绕开系统代理
            connection = (HttpURLConnection) new URL(queryUrl + "/stream/" + sessionId)
                    .openConnection(Proxy.NO_PROXY);
            connection.setRequestMethod("GET");
            // 连接超时 3s：Python 未启动时快速失败，进入下面的 error 帧兜底
            connection.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
            // 不设 0（无限等待）：万一 Python 侧卡住不发任何数据，线程会永久阻塞。
            // 正常流式过程中 delta 会持续到达，5 分钟只针对"彻底没动静"的异常情况。
            connection.setReadTimeout((int) Duration.ofSeconds(300).toMillis());
            // 与 Python 协商 SSE 内容类型
            connection.setRequestProperty("Accept", "text/event-stream");
            // 建立到 Python 问答服务的 SSE 长连接
            connection.connect();

            // 把 Python 的状态码与 SSE 响应头原样落到前端响应上：
            // 状态码透传，前端 EventSource 据此判断是否重连
            // getResponseCode() 会阻塞到收到响应头为止
            response.setStatus(connection.getResponseCode());
            response.setContentType("text/event-stream;charset=UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            // 关键：禁用 Nginx 对本响应的缓冲，保证 delta 一到就推给浏览器
            response.setHeader("X-Accel-Buffering", "no");

            // 边收边发，保证 delta 的实时性；同时用一个滚动缓冲识别终止帧
            // 滚动缓冲 seen：不清洗、只追加，用于在字节流里识别终止帧标记
            // 内存开销可控：一次问答的 SSE 帧总量只有几 KB 到几十 KB
            StringBuilder seen = new StringBuilder();
            // 前端响应输出流：SSE 帧从 Python 流向这里再流向浏览器
            OutputStream out = response.getOutputStream();
            try (InputStream in = connection.getInputStream()) {
                // 1024B 缓冲：一次 read 最多读 1KB，粒度足够小，实时性好
                byte[] buffer = new byte[1024];
                int length;
                // 阻塞读：Python 每推一段，这里就醒来一次
                // read 返回 -1 表示对端关闭流，循环随之结束
                while ((length = in.read(buffer)) != -1) {
                    // 收到多少写多少并立即 flush：SSE 的实时性靠 flush 保证
                    // flush 会把 Tomcat 缓冲与 Nginx 缓冲一起推动下发
                    out.write(buffer, 0, length);
                    out.flush();
                    // 追加进滚动缓冲用于终止帧识别（按块解码可能切碎多字节字符，
                    // 但终止帧标记是纯 ASCII，不受影响）
                    // 标记识别放在 write 之后：先让数据到达浏览器，再做终止判断，顺序不能反
                    seen.append(new String(buffer, 0, length, StandardCharsets.UTF_8));
                    // 终止帧（final/error）已到达：主动断开而不是等 Python 关连接，
                    // 否则线程会被 Nginx 60s 超时或 Python 不关连接拖入永久阻塞
                    if (containsTerminalEvent(seen)) {
                        log.debug("会话 {} 收到终止帧，主动结束 SSE 转发", sessionId);
                        // 直接 return/break 让 Tomcat 正常结束响应
                        break;
                    }
                }
                // 正常读完（Python 关闭了流）也会走到 try-with-resources 的自动关闭
            }
        } catch (IOException exception) {
            // Python 未启动，或浏览器提前关闭了连接
            // 兜底写一个 error 帧：前端 EventSource 收到后能展示原因，而不是静默卡死
            log.warn("透传 /rag/stream/{} 失败：{}", sessionId, exception.getMessage());
            try {
                // 响应还没提交过才补写响应头，避免与已发送的头冲突
                if (!response.isCommitted()) {
                    // 状态码仍给 200：SSE 协议里错误用 error 帧表达，前端按帧处理
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.setContentType("text/event-stream;charset=UTF-8");
                    response.setHeader("X-Accel-Buffering", "no");
                }
                // 兜底帧与正常帧同格式（event: error + data JSON），前端无需特判
                OutputStream out = response.getOutputStream();
                out.write("event: error\ndata: {\"error\":\"流式问答连接失败，请确认 Python 问答服务(8001)已启动\"}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException ignored) {
                // 连响应流都写不进去，说明客户端已断开，无需处理
            }
        } finally {
            // 断开到 Python 的连接：促使 Python 侧 is_disconnected 成立、清理生成器
            // 无论正常结束、终止帧断开还是异常，都必须走到这里
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * 判断已收到的 SSE 文本里是否出现了终止帧。
     *
     * <p>Python 侧 _sse_pack 的格式固定为 "event: {name}\ndata: {json}\n\n"，
     * 因此只要出现 "event: final" 或 "event: error" 就说明本次问答已经结束。
     * 按块解码可能把多字节字符切开，但这两个标记都是纯 ASCII，不受影响。</p>
     */
    // 补充说明（扩展原 Javadoc）：缓冲不截断——seen 只增不减，标记一旦出现过的
    // 判断不可逆，对 SSE 一次性会话来说足够，也省去维护滑动窗口的复杂度。
    private static boolean containsTerminalEvent(CharSequence received) {
        // 两个标记任一出现即终止：final 正常结束，error 异常结束
        return indexOf(received, "event: final") >= 0 || indexOf(received, "event: error") >= 0;
    }

    // 为什么自己写 indexOf 而不用 String.indexOf：避免先调用 toString()
    // 拷贝整个滚动缓冲；朴素双指针匹配在几 KB 的缓冲上性能足够，不值得引入 KMP。
    /** 在 CharSequence 中查找目标子串首次出现的下标，实现取舍见上方注释。 */
    private static int indexOf(CharSequence text, String target) {
        // 最后一个可能的起点是 text.length() - target.length()
        int limit = text.length() - target.length();
        // outer 标签：内层失配时直接跳到下一个起点，避免多层 break
        outer:
        for (int i = 0; i <= limit; i++) {
            // 从起点 i 开始逐字符比对
            for (int j = 0; j < target.length(); j++) {
                // 逐字符比对，失配则跳回外层换起点
                if (text.charAt(i + j) != target.charAt(j)) continue outer;
            }
            // 内层全部匹配成功：i 即目标子串的起始下标
            return i;
        }
        // 整个文本扫描完仍未命中
        return -1;
    }

    // ------------------------------------------------------------------
    // 会话历史：前端 GET /rag/history/{sessionId}
    //   → Python 8001 GET /history/{session_id}   返回 {session_id, items:[...]}
    // ------------------------------------------------------------------
    // —— /rag/history GET 协议 ——
    // Python 返回 {session_id, items:[...]}，items 按时间序存放用户提问与
    // 模型回答，Java 原样透传，由前端渲染气泡列表。
    // 历史由 Python 侧持久化，Java 不做任何缓存，保证多端一致。
    // 不做分页：单会话消息量有限（几十条），整段返回更简单且前端渲染无压力。
    /** 读取会话历史（代理转发 → Python 8001 GET /history/{session_id}），协议见上方注释。 */
    @GetMapping("/history/{sessionId}")
    public Result history(@PathVariable String sessionId) {
        // 历史按用户归属，未登录拒绝
        if (UserHolder.getUser() == null) return Result.fail("请先登录");
        // 会话 ID 白名单校验，防注入
        if (sessionId == null || !sessionId.matches(ID_PATTERN)) return Result.fail("会话ID无效");
        try {
            // GET 转发，历史记录原样透传
            // Java 不缓存历史：Python 侧是唯一权威数据源
            return Result.ok(queryClient.getForObject(queryUrl + "/history/" + sessionId, Map.class));
        } catch (RestClientException exception) {
            // 问答服务不可用时不影响页面其他功能，只提示历史读取失败
            return Result.fail("会话记录读取失败");
        }
    }

    // ------------------------------------------------------------------
    // 清空会话：前端 DELETE /rag/history/{sessionId}
    //   → Python 8001 DELETE /history/{session_id}
    // ------------------------------------------------------------------
    // —— /rag/history DELETE 协议 ——
    // 用 RestTemplate 的 exchange 发 DELETE（快捷方法没有 DELETE 语义），
    // 成功与否都把 Python 的响应体透传回去，前端据此刷新页面上的气泡列表。
    // 清空后 session_id 仍可复用：Python 侧会重建空会话，前端无需换 id。
    /** 清空会话历史（代理转发 → Python 8001 DELETE /history/{session_id}），协议见上方注释。 */
    @DeleteMapping("/history/{sessionId}")
    public Result clearHistory(@PathVariable String sessionId) {
        // 清空历史同样要求登录，防止匿名清别人的会话
        if (UserHolder.getUser() == null) return Result.fail("请先登录");
        // 会话 ID 白名单校验
        if (sessionId == null || !sessionId.matches(ID_PATTERN)) return Result.fail("会话ID无效");
        try {
            // DELETE 必须走 exchange：RestTemplate 的快捷方法不支持 DELETE 语义
            ResponseEntity<Map> response = queryClient.exchange(
                    queryUrl + "/history/" + sessionId, HttpMethod.DELETE, null, Map.class);
            // 透传 Python 的确认结果，前端据此清空气泡列表
            return Result.ok(response.getBody());
        } catch (RestClientException exception) {
            // 清除失败仅提示，不重试：历史仍在，用户可再次点击清空
            // DELETE 属于幂等操作，重试是安全的，但这里选择让用户显式重试
            return Result.fail("清空对话失败");
        }
    }
}
// —— 本类小结 ——
// 五组接口全部是「登录/参数校验 → 转发 → 透传/降级」的薄壳：
// upload/importStatus 对接导入服务 8002，query/stream/history/clearHistory 对接问答服务 8001。
// 唯一的复杂点在 stream：SSE 逐字节透传 + 滚动缓冲识别终止帧 + 主动断开防线程泄漏。
// 超时按下游特征分别配置（300s/120s/3s），并且全部显式绕开系统代理直连 127.0.0.1。
// 新增接口时沿用同样的三段式套路，并把协议约定写在方法上方的注释块里。
