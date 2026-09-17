package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IShopService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;

@RestController
public class ShopQaController {
    private final IShopService shopService;
    private final RestTemplate ragClient;
    private final String ragUrl;

    public ShopQaController(IShopService shopService, RestTemplateBuilder builder,
                            @Value("${shop.qa.url:http://127.0.0.1:8003}") String ragUrl) {
        this.shopService = shopService;
        this.ragClient = builder.setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(45)).build();
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
            ResponseEntity<Map> response = ragClient.postForEntity(
                    ragUrl + "/shops/" + shopId + "/ask",
                    Map.of("question", request.getQuestion().trim()), Map.class);
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
