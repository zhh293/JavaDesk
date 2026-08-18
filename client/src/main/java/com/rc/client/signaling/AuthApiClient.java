package com.rc.client.signaling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 账户 REST 客户端：登录 / 注册，对接服务端 {@code /api/auth/**}。
 * 返回的访问令牌用于后续信令注册（{@code RegisterReq.token}）与设备信息查询。
 */
public final class AuthApiClient {

    /** 登录结果（访问令牌 + 刷新令牌 + 有效期）。 */
    public record LoginResult(String accessToken, String refreshToken, long expiresInSeconds) {
    }

    private static final int SUCCESS_CODE = 0;

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthApiClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** 登录，失败抛 {@link IOException}（含服务端错误信息）。 */
    public LoginResult login(String username, String password) throws IOException, InterruptedException {
        JsonNode data = post("/api/auth/login", username, password);
        return new LoginResult(
                data.path("accessToken").asText(),
                data.path("refreshToken").asText(),
                data.path("expiresInSeconds").asLong());
    }

    /** 注册，失败抛 {@link IOException}（如用户名已存在）。 */
    public void register(String username, String password) throws IOException, InterruptedException {
        post("/api/auth/register", username, password);
    }

    private JsonNode post(String path, String username, String password) throws IOException, InterruptedException {
        String body = mapper.createObjectNode()
                .put("username", username)
                .put("password", password)
                .toString();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(response.body());
        int code = root.path("code").asInt(-1);
        if (code != SUCCESS_CODE) {
            throw new IOException("auth failed: " + root.path("message").asText("unknown error"));
        }
        return root.path("data");
    }
}
