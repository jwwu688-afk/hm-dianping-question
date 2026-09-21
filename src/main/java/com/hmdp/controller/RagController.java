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

@RestController
@RequestMapping("/rag")
public class RagController {
    private final RestTemplate client;
    private final IShopService shopService;
    private final String url;

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

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result upload(@RequestPart("file") MultipartFile file,
                         @RequestParam(value = "shopId", required = false) Long shopId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) return Result.fail("请先登录");
        if (file.isEmpty() || file.getSize() > 20L * 1024 * 1024) return Result.fail("文件为空或超过20MB");
        if (shopId != null && (shopId <= 0 || shopService.getById(shopId) == null)) {
            return Result.fail("店铺不存在");
        }
        String name = file.getOriginalFilename();
        if (name == null || !(name.toLowerCase().endsWith(".pdf") || name.toLowerCase().endsWith(".md"))) {
            return Result.fail("仅支持 PDF 或 Markdown 文件");
        }
        try {
            ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                @Override public String getFilename() { return name; }
            };
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", resource);
            body.add("shop_id", shopId == null ? "0" : shopId.toString());
            body.add("owner_id", user.getId().toString());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            ResponseEntity<Map> response = client.postForEntity(url + "/documents", new HttpEntity<>(body, headers), Map.class);
            return Result.ok(response.getBody());
        } catch (IOException | RestClientException exception) {
            return Result.fail("上传或导入服务暂不可用");
        }
    }

    @GetMapping("/documents")
    public Result listDocuments() {
        UserDTO user = UserHolder.getUser();
        if (user == null) return Result.fail("请先登录");
        try {
            return Result.ok(client.getForObject(url + "/documents?owner_id=" + user.getId(), Map.class));
        } catch (RestClientException exception) {
            return Result.fail("文档服务暂不可用");
        }
    }

    @GetMapping("/documents/{id}")
    public Result documentStatus(@PathVariable String id) {
        UserDTO user = UserHolder.getUser();
        if (user == null) return Result.fail("请先登录");
        if (!id.matches("[0-9a-f]{32}")) return Result.fail("文档ID无效");
        try {
            return Result.ok(client.getForObject(url + "/documents/" + id + "?owner_id=" + user.getId(), Map.class));
        } catch (RestClientException exception) {
            return Result.fail("文档服务暂不可用");
        }
    }

    @DeleteMapping("/documents/{id}")
    public Result deleteDocument(@PathVariable String id) {
        UserDTO user = UserHolder.getUser();
        if (user == null) return Result.fail("请先登录");
        if (!id.matches("[0-9a-f]{32}")) return Result.fail("文档ID无效");
        try {
            ResponseEntity<Map> response = client.exchange(
                    url + "/documents/" + id + "?owner_id=" + user.getId(), HttpMethod.DELETE, null, Map.class);
            return Result.ok(response.getBody());
        } catch (RestClientException exception) {
            return Result.fail("删除文档失败");
        }
    }

    @PostMapping("/ask")
    public Result ask(@RequestBody Map<String, Object> request) {
        UserDTO user = UserHolder.getUser();
        if (user == null) return Result.fail("请先登录");
        Object question = request.get("question");
        if (!(question instanceof String) || ((String) question).trim().length() < 2) {
            return Result.fail("请输入问题");
        }
        try {
            return Result.ok(client.postForObject(url + "/ask", request, Map.class));
        } catch (RestClientException exception) {
            return Result.fail("问答服务暂不可用");
        }
    }
}
