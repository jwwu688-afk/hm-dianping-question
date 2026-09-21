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
@RestController
@RequestMapping("/rag")
public class RagKnowledgeController {
    private static final Logger log = LoggerFactory.getLogger(RagKnowledgeController.class);
    /** 一次最多接收的文件数，防止误选整目录造成内存打爆 */
    private static final int MAX_FILES = 20;
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    /** 会话ID / 任务ID 只允许 UUID 形态，同时避免路径穿越 */
    private static final String ID_PATTERN = "[0-9a-zA-Z\\-]{8,64}";

    private final RestTemplate queryClient;
    private final RestTemplate importClient;
    private final String queryUrl;
    private final String importUrl;

    public RagKnowledgeController(
            @Value("${shop.qa.query-url:http://127.0.0.1:8001}") String queryUrl,
            @Value("${shop.qa.import-url:http://127.0.0.1:8002}") String importUrl) {
        this.queryUrl = queryUrl;
        this.importUrl = importUrl;
        // 本地 Python 服务必须绕开系统 HTTP 代理（HTTP_PROXY 会把 127.0.0.1 也转发给代理导致连接失败）
        this.queryClient = buildClient(300);
        this.importClient = buildClient(120);
    }

    private RestTemplate buildClient(int readTimeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setProxy(Proxy.NO_PROXY);
        factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(readTimeoutSeconds).toMillis());
        return new RestTemplate(factory);
    }

    // ------------------------------------------------------------------
    // 导入：前端 POST /rag/upload（multipart，字段名 files，可多个）
    //   → Python 8002 POST /upload        返回 {task_ids:[...]}
    // ------------------------------------------------------------------
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result upload(@RequestParam("files") MultipartFile[] files) {
        UserDTO user = UserHolder.getUser();
        if (user == null) return Result.fail("请先登录");
        if (files == null || files.length == 0) return Result.fail("请选择要上传的文件");
        if (files.length > MAX_FILES) return Result.fail("一次最多上传 " + MAX_FILES + " 个文件");

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        try {
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) continue;
                // 文件大小/后缀由 Java 侧先挡一道，Python 侧仍会再校验一次
                if (file.getSize() > MAX_FILE_BYTES) return Result.fail("单个文件不能超过 20MB");
                String name = file.getOriginalFilename();
                if (name == null || !(name.toLowerCase().endsWith(".pdf") || name.toLowerCase().endsWith(".md"))) {
                    return Result.fail("仅支持 PDF 或 Markdown 文件");
                }
                // 必须用 ByteArrayResource 并覆写 getFilename，否则 multipart 会丢掉文件名
                ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                    @Override
                    public String getFilename() {
                        return name;
                    }
                };
                // 字段名固定为 files，与 FastAPI 的 files: List[UploadFile] = File(...) 对齐
                body.add("files", resource);
            }
        } catch (IOException exception) {
            return Result.fail("读取上传文件失败");
        }
        if (body.isEmpty()) return Result.fail("请选择要上传的文件");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        try {
            ResponseEntity<Map> response = importClient.postForEntity(
                    importUrl + "/upload", new HttpEntity<>(body, headers), Map.class);
            return Result.ok(response.getBody());
        } catch (RestClientException exception) {
            log.warn("转发 /rag/upload 到导入服务失败：{}", exception.getMessage());
            return Result.fail("上传或导入服务暂不可用");
        }
    }

    // ------------------------------------------------------------------
    // 导入进度：前端 GET /rag/import/status/{taskId}
    //   → Python 8002 GET /status/{task_id}   返回 {status, done_list, running_list}
    // ------------------------------------------------------------------
    @GetMapping("/import/status/{taskId}")
    public Result importStatus(@PathVariable String taskId) {
        if (UserHolder.getUser() == null) return Result.fail("请先登录");
        if (taskId == null || !taskId.matches(ID_PATTERN)) return Result.fail("任务ID无效");
        try {
            return Result.ok(importClient.getForObject(importUrl + "/status/" + taskId, Map.class));
        } catch (RestClientException exception) {
            return Result.fail("导入进度查询失败");
        }
    }

    // ------------------------------------------------------------------
    // 提问：前端 POST /rag/query  body: {query, session_id, is_stream}
    //   → Python 8001 POST /query
    //   is_stream=true 时 Python 立即返回 {session_id}，答案走 /rag/stream 推送
    //   is_stream=false 时 Python 同步跑完再返回 {session_id, answer}
    // ------------------------------------------------------------------
    @PostMapping("/query")
    public Result query(@RequestBody Map<String, Object> request) {
        if (UserHolder.getUser() == null) return Result.fail("请先登录");
        Object query = request == null ? null : request.get("query");
        if (!(query instanceof String) || ((String) query).trim().length() < 2) {
            return Result.fail("问题至少输入两个字");
        }
        if (((String) query).length() > 500) return Result.fail("问题最多 500 字");

        Map<String, Object> payload = new HashMap<>();
        payload.put("query", ((String) query).trim());
        Object sessionId = request.get("session_id");
        payload.put("session_id", sessionId instanceof String ? sessionId : "");
        payload.put("is_stream", Boolean.TRUE.equals(request.get("is_stream")));
        try {
            ResponseEntity<Map> response = queryClient.postForEntity(
                    queryUrl + "/query", payload, Map.class);
            return Result.ok(response.getBody());
        } catch (RestClientException exception) {
            log.warn("转发 /rag/query 到问答服务失败：{}", exception.getMessage());
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
    @GetMapping("/stream/{sessionId}")
    public void stream(@PathVariable String sessionId, HttpServletResponse response) {
        if (UserHolder.getUser() == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (sessionId == null || !sessionId.matches(ID_PATTERN)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(queryUrl + "/stream/" + sessionId)
                    .openConnection(Proxy.NO_PROXY);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
            // 不设 0（无限等待）：万一 Python 侧卡住不发任何数据，线程会永久阻塞。
            // 正常流式过程中 delta 会持续到达，5 分钟只针对"彻底没动静"的异常情况。
            connection.setReadTimeout((int) Duration.ofSeconds(300).toMillis());
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.connect();

            response.setStatus(connection.getResponseCode());
            response.setContentType("text/event-stream;charset=UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("X-Accel-Buffering", "no");

            // 边收边发，保证 delta 的实时性；同时用一个滚动缓冲识别终止帧
            StringBuilder seen = new StringBuilder();
            OutputStream out = response.getOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = in.read(buffer)) != -1) {
                    out.write(buffer, 0, length);
                    out.flush();
                    seen.append(new String(buffer, 0, length, StandardCharsets.UTF_8));
                    if (containsTerminalEvent(seen)) {
                        log.debug("会话 {} 收到终止帧，主动结束 SSE 转发", sessionId);
                        break;
                    }
                }
            }
        } catch (IOException exception) {
            // Python 未启动，或浏览器提前关闭了连接
            log.warn("透传 /rag/stream/{} 失败：{}", sessionId, exception.getMessage());
            try {
                if (!response.isCommitted()) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.setContentType("text/event-stream;charset=UTF-8");
                    response.setHeader("X-Accel-Buffering", "no");
                }
                OutputStream out = response.getOutputStream();
                out.write("event: error\ndata: {\"error\":\"流式问答连接失败，请确认 Python 问答服务(8001)已启动\"}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException ignored) {
                // 连响应流都写不进去，说明客户端已断开，无需处理
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * 判断已收到的 SSE 文本里是否出现了终止帧。
     *
     * <p>Python 侧 _sse_pack 的格式固定为 "event: {name}\ndata: {json}\n\n"，
     * 因此只要出现 "event: final" 或 "event: error" 就说明本次问答已经结束。
     * 按块解码可能把多字节字符切开，但这两个标记都是纯 ASCII，不受影响。
     */
    private static boolean containsTerminalEvent(CharSequence received) {
        return indexOf(received, "event: final") >= 0 || indexOf(received, "event: error") >= 0;
    }

    private static int indexOf(CharSequence text, String target) {
        int limit = text.length() - target.length();
        outer:
        for (int i = 0; i <= limit; i++) {
            for (int j = 0; j < target.length(); j++) {
                if (text.charAt(i + j) != target.charAt(j)) continue outer;
            }
            return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 会话历史：前端 GET /rag/history/{sessionId}
    //   → Python 8001 GET /history/{session_id}   返回 {session_id, items:[...]}
    // ------------------------------------------------------------------
    @GetMapping("/history/{sessionId}")
    public Result history(@PathVariable String sessionId) {
        if (UserHolder.getUser() == null) return Result.fail("请先登录");
        if (sessionId == null || !sessionId.matches(ID_PATTERN)) return Result.fail("会话ID无效");
        try {
            return Result.ok(queryClient.getForObject(queryUrl + "/history/" + sessionId, Map.class));
        } catch (RestClientException exception) {
            return Result.fail("会话记录读取失败");
        }
    }

    // ------------------------------------------------------------------
    // 清空会话：前端 DELETE /rag/history/{sessionId}
    //   → Python 8001 DELETE /history/{session_id}
    // ------------------------------------------------------------------
    @DeleteMapping("/history/{sessionId}")
    public Result clearHistory(@PathVariable String sessionId) {
        if (UserHolder.getUser() == null) return Result.fail("请先登录");
        if (sessionId == null || !sessionId.matches(ID_PATTERN)) return Result.fail("会话ID无效");
        try {
            ResponseEntity<Map> response = queryClient.exchange(
                    queryUrl + "/history/" + sessionId, HttpMethod.DELETE, null, Map.class);
            return Result.ok(response.getBody());
        } catch (RestClientException exception) {
            return Result.fail("清空对话失败");
        }
    }
}
