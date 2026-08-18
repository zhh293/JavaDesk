package com.rc.signaling.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 安全配置（{@code rc.security.*}）：JWT 密钥与令牌 TTL。
 */
@Component
@ConfigurationProperties(prefix = "rc.security")
public class SecurityProperties {

    /** HS256 签名密钥（>= 32 字节；prod 必须经环境变量覆盖，禁止用默认值）。 */
    private String jwtSecret = "rc-signaling-dev-secret-change-me-in-prod-0123456789";

    private long accessTokenTtlSeconds = 900;

    private long refreshTokenTtlSeconds = 604800;

    /** SSO/OIDC 登录成功后，携带 JWT 重定向回前端回调地址（如 {@code http://localhost:3000/sso/callback}）。 */
    private String ssoRedirectUri = "http://localhost:3000/sso/callback";

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public String getSsoRedirectUri() {
        return ssoRedirectUri;
    }

    public void setSsoRedirectUri(String ssoRedirectUri) {
        this.ssoRedirectUri = ssoRedirectUri;
    }

    public long getAccessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    public void setAccessTokenTtlSeconds(long accessTokenTtlSeconds) {
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
    }

    public long getRefreshTokenTtlSeconds() {
        return refreshTokenTtlSeconds;
    }

    public void setRefreshTokenTtlSeconds(long refreshTokenTtlSeconds) {
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
    }
}
