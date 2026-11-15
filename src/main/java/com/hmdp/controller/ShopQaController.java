package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IShopService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.Proxy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** 店铺智能问答入口控制器：参数校验后转发给本地 Python RAG 服务。 */
// ==================== 店铺智能问答业务域 ====================
// 前端把用户问题 POST 到 /shop/{shopId}/ask，本控制器做店铺存在性
// 与问题合法性校验后，通过 RestTemplate 把问题转发给本地 Python
// RAG 服务（默认 http://127.0.0.1:8003，可用配置项 shop.qa.url 覆盖），
// 并把 Python 侧返回的答案原文包装成统一 Result 回传。
// 本类只做"网关式转发"：不持有问答上下文，不做向量检索、
// Prompt 组装与 LLM 调用，这些都由 Python 服务完成。
// Python 侧根据店铺 id 检索该店铺关联的知识库片段，
// 检索与生成策略对 Java 侧完全透明。
// 转发失败（连接拒绝、超时、5xx）时统一降级为友好文案，
// 不向前端暴露内部异常细节；本控制器无状态，可水平扩展。
// 线程安全：三个字段均为不可变引用，构造后不再修改。
// 问答链路：前端 → 本控制器 → Python RAG（检索+生成）→ 原样回传。
// 店铺与知识库的绑定关系由 /rag/documents 上传文档时的 shopId 维护。
// 接口路径 /shop/{shopId}/ask 与 Python 侧 /shops/{shopId}/ask 保持对应。
// 超时与降级策略保证 Python 服务故障不会拖垮 Java 主服务。
@RestController
public class ShopQaController {
    // 店铺服务：仅用于校验店铺真实存在
    // 问答转发 HTTP 客户端（NO_PROXY + 超时配置，见构造器说明）
    // Python RAG 服务地址（配置项 shop.qa.url，默认 127.0.0.1:8003）
    // 三个字段均为 final，依赖通过构造器注入，便于测试时替换
    private final IShopService shopService;
    private final RestTemplate ragClient;
    private final String ragUrl;

    /** 构造问答转发用的 RestTemplate（NO_PROXY + 超时配置）。 */
    // 关键配置一：显式 Proxy.NO_PROXY —— 本地 Python 服务必须绕开系统
    // HTTP 代理（HTTP_PROXY 会把 127.0.0.1 也转发给代理导致连接失败）。
    // 关键配置二：连接超时 3 秒，Python 服务未启动时快速失败，
    // 不让请求长时间挂起占用容器线程。
    // 关键配置三：读超时 45 秒，给 RAG 向量检索 + LLM 生成留足时间。
    // @param shopService 用于校验店铺是否存在
    // @param builder Spring Boot 的 RestTemplateBuilder（注入但未直接使用）
    // @param ragUrl Python 问答服务地址，默认 http://127.0.0.1:8003
    // 不复用 Spring 默认的 RestTemplate Bean：默认实现会走系统代理，
    // 且超时依赖 JDK 默认值（可能无限等待），不满足本地快速失败要求。
    // RestTemplate 随控制器单例存续，线程安全可并发使用。
    public ShopQaController(IShopService shopService, RestTemplateBuilder builder,
                            @Value("${shop.qa.url:http://127.0.0.1:8003}") String ragUrl) {
        // 本地 Python 服务必须绕开系统 HTTP 代理（HTTP_PROXY 会把 127.0.0.1 也转发给代理导致连接失败）
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setProxy(Proxy.NO_PROXY);
        factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(45).toMillis());
        this.ragClient = new RestTemplate(factory);
        this.shopService = shopService;
        this.ragUrl = ragUrl;
    }

    /** 店铺智能问答接口：POST /shop/{shopId}/ask，请求体 {"question": "..."}。 */
    // 业务流程：
    // 1) 校验店铺 id 合法（>0）且在数据库中真实存在，否则返回"店铺不存在"；
    // 2) 校验问题非空、trim 后不少于 2 字且不超过 500 字，
    //    过短/过长直接拒绝，保护下游检索与 LLM 调用；
    // 3) 组装 {"question": ...} 转发到 Python 服务的 /shops/{shopId}/ask，
    //    必须用可序列化的 HashMap（见方法体内原注释说明）；
    // 4) 透传 Python 侧应答（答案、引用片段等）给前端；
    // 5) RestClientException 统一降级为"店铺问答服务暂不可用"。
    // 权限：本接口需要登录（不在白名单），但未做店铺归属校验——
    // 任何登录用户都可询问任意店铺，与商铺浏览权限口径一致。
    // @param shopId 店铺 id（路径参数）
    // @param request 问题请求体
    // @return RAG 服务返回的问答结果
    @PostMapping("/shop/{shopId}/ask")
    public Result ask(@PathVariable Long shopId, @RequestBody AskRequest request) {
        // 先校验店铺真实存在，防止用任意 id 探测问答能力
        if (shopId == null || shopId <= 0 || shopService.getById(shopId) == null) {
            return Result.fail("店铺不存在");
        }
        // 问题长度 2~500 字：下限过滤无意义输入，上限与前端输入框一致
        if (request == null || request.getQuestion() == null
                || request.getQuestion().trim().length() < 2
                || request.getQuestion().length() > 500) {
            return Result.fail("问题长度应为2到500字");
        }
        try {
            // 注意：不能用 Map.of(...) —— 它返回 java.util.ImmutableCollections$Map1，
            // RestTemplate 的 Jackson 转换器不识别该类型，会抛 "No HttpMessageConverter"。
            // 必须用可序列化的 HashMap。
            Map<String, Object> payload = new HashMap<>();
            payload.put("question", request.getQuestion().trim());
            // payload 仅含 question 字段，店铺上下文由 URL 路径携带
            // postForEntity 将 payload 序列化为 JSON 发送
            ResponseEntity<Map> response = ragClient.postForEntity(
                    ragUrl + "/shops/" + shopId + "/ask", payload, Map.class);
            return Result.ok(response.getBody());
        } catch (RestClientException exception) {
            // 任何 RestClientException（连接拒绝/超时/5xx）都按服务不可用降级，
            // 降级文案不区分超时/连接失败，避免暴露内部实现
            return Result.fail("店铺问答服务暂不可用");
        }
    }

    /** 问答请求体：承载用户的问题文本。 */
    // 用静态内部类而非 Map 接收，让 Jackson 反序列化有明确的字段结构；
    // question 允许带前后空白，校验时统一 trim；
    // getter/setter 是 Jackson 反序列化所必需的，不能省略；
    // 无其他字段：店铺上下文由路径参数 shopId 提供。
    public static class AskRequest {
        private String question;

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question;
        }
    }
}
