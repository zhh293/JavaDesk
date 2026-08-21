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

    /** SSO/OIDC 登录成功后，携带一次性交接码重定向回前端回调地址。 */
    private String ssoRedirectUri = "http://localhost:3000/sso/callback";

    /** Defense-in-depth credential for internal APIs; production also requires network mTLS. */
    private String internalServiceToken = "rc-internal-dev-token-change-me";

    /** Ed25519 PKCS#8/X.509 keys. Blank in dev generates an ephemeral pair exposed to Relay over internal API. */
    private String relayTicketPrivateKey = "";
    private String relayTicketPublicKey = "";
    private String relayTicketKeyId = "relay-ticket-dev-1";
    private String relayTicketIssuer = "javadesk-signaling";
    /** Optional overlap key kept during rotation until all old 30-second tickets expire. */
    private String relayTicketPreviousPublicKey = "";
    private String relayTicketPreviousKeyId = "";

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

    public String getInternalServiceToken() {
        return internalServiceToken;
    }

    public void setInternalServiceToken(String internalServiceToken) {
        this.internalServiceToken = internalServiceToken;
    }

    public String getRelayTicketPrivateKey() { return relayTicketPrivateKey; }
    public void setRelayTicketPrivateKey(String value) { relayTicketPrivateKey = value; }
    public String getRelayTicketPublicKey() { return relayTicketPublicKey; }
    public void setRelayTicketPublicKey(String value) { relayTicketPublicKey = value; }
    public String getRelayTicketKeyId() { return relayTicketKeyId; }
    public void setRelayTicketKeyId(String value) { relayTicketKeyId = value; }
    public String getRelayTicketIssuer() { return relayTicketIssuer; }
    public void setRelayTicketIssuer(String value) { relayTicketIssuer = value; }
    public String getRelayTicketPreviousPublicKey() { return relayTicketPreviousPublicKey; }
    public void setRelayTicketPreviousPublicKey(String value) { relayTicketPreviousPublicKey = value; }
    public String getRelayTicketPreviousKeyId() { return relayTicketPreviousKeyId; }
    public void setRelayTicketPreviousKeyId(String value) { relayTicketPreviousKeyId = value; }
}
