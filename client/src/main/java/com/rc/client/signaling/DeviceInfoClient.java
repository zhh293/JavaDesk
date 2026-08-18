package com.rc.client.signaling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * REST 客户端：取目标设备公钥 / 指纹 / NAT 类型，供控制端 E2EE 邀请加密。
 */
public final class DeviceInfoClient {

    /** 目标设备信息（服务端 {@code GET /api/devices/{code}} 返回）。 */
    public record DeviceInfo(long deviceId, String deviceCode, String deviceName,
                             String publicKey, String fingerprint, int natType, boolean online) {
    }

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeviceInfoClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public DeviceInfo fetch(String deviceCode, String token) throws IOException, InterruptedException {
        String path = baseUrl + "/api/devices/" + encode(deviceCode);
        HttpRequest request = HttpRequest.newBuilder(URI.create(path))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("fetch device info failed, status=" + response.statusCode());
        }
        JsonNode root = mapper.readTree(response.body());
        JsonNode data = root.get("data");
        if (data == null || data.isNull()) {
            throw new IOException("fetch device info: empty data, msg=" + root.path("message").asText());
        }
        return new DeviceInfo(
                data.path("deviceId").asLong(),
                data.path("deviceCode").asText(),
                data.path("deviceName").asText(),
                data.path("publicKey").asText(),
                data.path("fingerprint").asText(),
                data.path("natType").asInt(),
                data.path("online").asBoolean());
    }

    private static String encode(String code) {
        return URLEncoder.encode(code, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
