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

@RestController
public class ShopQaController {
    private final IShopService shopService;
    private final RestTemplate ragClient;
    private final String ragUrl;

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

    @PostMapping("/shop/{shopId}/ask")
    public Result ask(@PathVariable Long shopId, @RequestBody AskRequest request) {
        if (shopId == null || shopId <= 0 || shopService.getById(shopId) == null) {
            return Result.fail("店铺不存在");
        }
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
            ResponseEntity<Map> response = ragClient.postForEntity(
                    ragUrl + "/shops/" + shopId + "/ask", payload, Map.class);
            return Result.ok(response.getBody());
        } catch (RestClientException exception) {
            return Result.fail("店铺问答服务暂不可用");
        }
    }

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
