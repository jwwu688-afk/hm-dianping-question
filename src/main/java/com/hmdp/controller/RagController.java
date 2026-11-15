package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IShopService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.Map;

/** RAG 知识库管理与问答转发控制器：Java 后端与本地 Python RAG 服务之间的网关。 */
// ==================== RAG 知识库业务域（路由前缀 /rag） ====================
// 职责一览：
// 1) 文档上传与向量化导入：POST /rag/documents（multipart，PDF/MD，≤20MB）；
// 2) 文档列表查询：GET /rag/documents（按当前登录用户 owner_id 过滤）；
// 3) 导入状态查询：GET /rag/documents/{id}（32 位 hex 文档 id）；
// 4) 文档删除：DELETE /rag/documents/{id}；
// 5) 问答转发：POST /rag/ask（请求体原样转发给 Python 服务 /ask）。
// 所有接口均要求登录：UserHolder 取不到用户即返回"请先登录"。
// 文档归属：以登录用户 id 作为 owner_id 传给 Python 侧做数据隔离，
// 防止越权读取/删除他人文档。
// 本类不做向量检索与 LLM 调用，只做参数校验、归属注入与 HTTP 转发。
// Python 服务地址通过配置项 shop.qa.url 注入，本地默认 8003 端口。
// 与 ShopQaController 的区别：本控制器管理知识库文档本身，
// ShopQaController 面向 C 端提供单店铺问答。
// 问答检索范围 = 当前用户上传的通用文档 + 绑定店铺的文档。
// 所有转发接口的响应体均为 Python 侧原始 Map 结构，直接透传。
@RestController
@RequestMapping("/rag")
public class RagController {
    // 转发用 HTTP 客户端（NO_PROXY + 3s 连接 / 120s 读超时，见构造器）
    private final RestTemplate client;
    // 用于校验上传文档所绑定店铺的存在性
    private final IShopService shopService;
    // Python RAG 服务地址（配置项 shop.qa.url，默认 127.0.0.1:8003）
    private final String url;

    /** 构造 RAG 转发客户端（NO_PROXY + 超时配置）。 */
    // 关键配置一：显式 Proxy.NO_PROXY —— 本地 Python 服务必须绕开系统
    // HTTP 代理（HTTP_PROXY 会把 127.0.0.1 也转发给代理导致连接失败）。
    // 关键配置二：连接超时 3 秒，Python 服务未启动时快速失败。
    // 关键配置三：读超时 120 秒——文档导入含解析+向量化、
    // 问答含 LLM 生成，耗时远长于普通接口，必须放宽读超时。
    // @param builder Spring Boot 的 RestTemplateBuilder（注入但未直接使用）
    // @param shopService 校验上传文档绑定的店铺是否存在
    // @param url Python RAG 服务地址（配置项 shop.qa.url）
    // 与 ShopQaController 的 45 秒读超时不同：本控制器承载文档导入
    // 等长耗时操作，读超时放宽到 120 秒。
    // RestTemplate 为单例字段，线程安全，可并发转发。
    public RagController(RestTemplateBuilder builder, IShopService shopService,
                         @Value("${shop.qa.url:http://127.0.0.1:8003}") String url) {
        // 本地 Python 服务必须绕开系统 HTTP 代理（HTTP_PROXY 会把 127.0.0.1 也转发给代理导致连接失败）
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setProxy(Proxy.NO_PROXY);
        factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(120).toMillis());
        this.client = new RestTemplate(factory);
        this.shopService = shopService;
        this.url = url;
    }

    /** 上传文档并导入 RAG 知识库：POST /rag/documents（multipart，字段 file，可选 shopId）。 */
    // 业务流程：
    // 1) 登录校验：UserHolder 为空直接拒绝（"请先登录"）；
    // 2) 文件校验：非空且 ≤20MB，防止超大文件阻塞 Python 侧解析；
    // 3) 店铺校验：shopId 传入时必须 >0 且真实存在，避免脏关联；
    // 4) 后缀校验：仅接受 .pdf / .md，与 Python 解析器支持范围一致；
    // 5) 组装 multipart 请求转发给 Python 服务 /documents：
    //    文件字节包装为 ByteArrayResource 并重写 getFilename()
    //    （否则 multipart 编码时文件名会丢失），
    //    shop_id=0 表示通用知识库（未绑定店铺），
    //    owner_id=登录用户 id，供 Python 侧做归属隔离；
    // 6) 透传 Python 侧应答（文档 id、导入任务状态等）。
    // IO 读取失败或转发失败统一降级为"上传或导入服务暂不可用"。
    // 上传成功不代表解析完成：解析与向量化异步进行，
    // 进度可通过 /rag/documents/{id} 查询。
    // @param file 上传的 PDF/Markdown 文档
    // @param shopId 可选，绑定的店铺 id；不传表示通用知识库
    // @return 导入任务信息
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result upload(@RequestPart("file") MultipartFile file,
                         @RequestParam(value = "shopId", required = false) Long shopId) {
        // 登录校验：拦截器放行的请求 UserHolder 必有用户，null 属于防御性判断
        UserDTO user = UserHolder.getUser();
        if (user == null) return Result.fail("请先登录");
        // 文件非空且不超过 20MB：上限保护 Python 侧的解析资源
        if (file.isEmpty() || file.getSize() > 20L * 1024 * 1024) return Result.fail("文件为空或超过20MB");
        // 绑定店铺时校验存在性：防止把文档挂到不存在的店铺上
        if (shopId != null && (shopId <= 0 || shopService.getById(shopId) == null)) {
            return Result.fail("店铺不存在");
        }
        // 仅接受 PDF / Markdown：与 Python 解析器支持的范围一致
        String name = file.getOriginalFilename();
        if (name == null || !(name.toLowerCase().endsWith(".pdf") || name.toLowerCase().endsWith(".md"))) {
            return Result.fail("仅支持 PDF 或 Markdown 文件");
        }
        try {
        // 读取文件字节到内存（≤20MB 可接受）；
        // ByteArrayResource 默认不携带文件名，必须重写 getFilename()
        // 才能让 multipart 编码器在 Content-Disposition 写入原始文件名
        // 若不重写，Python 侧收到的文件名会是 null，后缀校验失败
            ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                @Override public String getFilename() { return name; }
            };
            // multipart 表单：file=文档内容；shop_id=0 表示通用知识库；
            // owner_id=登录用户 id，Python 侧据此做数据隔离
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", resource);
            body.add("shop_id", shopId == null ? "0" : shopId.toString());
            body.add("owner_id", user.getId().toString());
            // 显式指定 multipart/form-data，避免与 JSON 转换器混淆
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            // 转发并透传 Python 侧返回的任务信息（文档 id、状态）
            ResponseEntity<Map> response = client.postForEntity(url + "/documents", new HttpEntity<>(body, headers), Map.class);
            return Result.ok(response.getBody());
        } catch (IOException | RestClientException exception) {
            // 本地文件读取失败或转发失败都按服务不可用降级
            return Result.fail("上传或导入服务暂不可用");
        }
    }

    /** 查询当前登录用户的文档列表：GET /rag/documents。 */
    // 业务语义：以登录用户 id 作为 owner_id 查询参数转发给 Python 服务，
    // 返回该用户名下全部文档及其导入状态（解析中/已就绪等），
    // 是前端知识库管理页的数据源；转发失败降级为"文档服务暂不可用"。
    // @return 文档列表
    // 列表包含每个文档的 id、文件名、导入状态与创建时间，
    // 前端轮询本接口刷新"解析中"状态。
    // owner_id 以查询参数传递，Python 侧据此过滤文档。
    @GetMapping("/documents")
    public Result listDocuments() {
        // 登录校验：与 upload 一致，未登录直接拒绝
        UserDTO user = UserHolder.getUser();
        if (user == null) return Result.fail("请先登录");
        try {
            // owner_id 来自拦截器写入的登录态，非用户输入，无注入风险
            return Result.ok(client.getForObject(url + "/documents?owner_id=" + user.getId(), Map.class));
        } catch (RestClientException exception) {
            // 转发失败统一降级
            return Result.fail("文档服务暂不可用");
        }
    }

    /** 查询单个文档的导入状态：GET /rag/documents/{id}。 */
    // 业务语义：文档 id 必须匹配正则 [0-9a-f]{32}（32 位十六进制），
    // 非法 id 直接拒绝——避免任意字符串拼进 Python 服务路径；
    // 校验通过后按 owner_id 归属转发查询，防止越权读取他人文档。
    // @param id 文档 id（32 位 hex 字符串）
    // @return 文档状态（解析/向量化进度等）
    // 典型状态流转：uploaded → parsing → ready / failed。
    // ready 后文档片段才可被 /rag/ask 问答检索到。
    // @GetMapping("/documents/{id}") 的路径变量与 Python 侧路由一致。
    @GetMapping("/documents/{id}")
    public Result documentStatus(@PathVariable String id) {
        // 登录校验
        UserDTO user = UserHolder.getUser();
        if (user == null) return Result.fail("请先登录");
        // 32 位 hex 白名单校验：文档 id 由 Python 侧以 UUID（去连字符）生成
        // 正则校验同时阻断了路径注入与任意路径探测
        if (!id.matches("[0-9a-f]{32}")) return Result.fail("文档ID无效");
        try {
            // 按 owner_id 归属查询：非本人文档在 Python 侧返回空
            return Result.ok(client.getForObject(url + "/documents/" + id + "?owner_id=" + user.getId(), Map.class));
        } catch (RestClientException exception) {
            // 转发失败统一降级
            return Result.fail("文档服务暂不可用");
        }
    }

    /** 删除知识库文档：DELETE /rag/documents/{id}。 */
    // 业务语义：先做登录校验与 32 位 hex 文档 id 校验（规则同查询接口），
    // 再用 exchange 显式指定 DELETE 方法转发——RestTemplate 的 delete()
    // 无返回体，而这里需要透传 Python 侧的删除结果；
    // Python 侧按 owner_id 校验归属后删除向量与元数据。
    // @param id 文档 id
    // @return 删除结果
    // 删除后该文档片段不再参与问答检索，操作不可恢复。
    // 删除进行中的文档（parsing 状态）由 Python 侧处理取消逻辑。
    @DeleteMapping("/documents/{id}")
    public Result deleteDocument(@PathVariable String id) {
        // 登录校验
        UserDTO user = UserHolder.getUser();
        if (user == null) return Result.fail("请先登录");
        // 同样做 32 位 hex 白名单校验，与查询/删除保持一致
        if (!id.matches("[0-9a-f]{32}")) return Result.fail("文档ID无效");
        try {
            // exchange 显式指定 DELETE 方法并接收返回体
            ResponseEntity<Map> response = client.exchange(
                    url + "/documents/" + id + "?owner_id=" + user.getId(), HttpMethod.DELETE, null, Map.class);
            return Result.ok(response.getBody());
        } catch (RestClientException exception) {
            // 转发失败降级，不区分具体错误类型
            return Result.fail("删除文档失败");
        }
    }

    /** RAG 问答转发：POST /rag/ask，请求体为 JSON（含 question 字段）。 */
    // 业务语义：仅做登录校验与 question 合法性校验
    // （必须是字符串且 trim 后 ≥2 字），请求体原样转发给
    // Python 服务的 /ask；
    // 是否流式、检索哪些知识、使用哪个模型均由 Python 侧决定，
    // Java 侧不做二次封装；转发失败降级为"问答服务暂不可用"。
    // @param request 问答请求体（原样透传）
    // @return RAG 问答结果
    // 请求体可附带 shop_id 等额外字段，Python 侧自行解析。
    // 问答检索范围由 owner_id（登录态注入）与可选 shop_id 共同决定。
    // 长问题不做截断，长度治理由 Python 侧与前端输入框负责。
    @PostMapping("/ask")
    public Result ask(@RequestBody Map<String, Object> request) {
        // 登录校验
        UserDTO user = UserHolder.getUser();
        if (user == null) return Result.fail("请先登录");
        // question 必须是字符串且 trim 后 ≥2 字，过短问题直接拒绝
        Object question = request.get("question");
        if (!(question instanceof String) || ((String) question).trim().length() < 2) {
            return Result.fail("请输入问题");
        }
        try {
            // 请求体原样透传：检索策略与响应结构由 Python 侧决定
            return Result.ok(client.postForObject(url + "/ask", request, Map.class));
        } catch (RestClientException exception) {
            // 转发失败统一降级
            return Result.fail("问答服务暂不可用");
        }
    }
}
