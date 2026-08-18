package com.rc.signaling.service;

import com.rc.signaling.config.SecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发与校验（HS256）。访问令牌短 TTL，刷新令牌长 TTL，均以 {@code typ} 声明区分。
 */
@Service
public class JwtService {

    public static final String CLAIM_TOKEN_TYPE = "typ";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";
    public static final String CLAIM_USERNAME = "username";
    public static final String CLAIM_ROLE = "role";

    private final SecretKey key;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;

    public JwtService(SecurityProperties props) {
        this.key = Keys.hmacShaKeyFor(props.getJwtSecret().getBytes(StandardCharsets.UTF_8));
        this.accessTtlSeconds = props.getAccessTokenTtlSeconds();
        this.refreshTtlSeconds = props.getRefreshTokenTtlSeconds();
    }

    public record TokenPair(String accessToken, String refreshToken, long expiresInSeconds) {
    }

    public TokenPair issue(long userId, String username, String role) {
        Instant now = Instant.now();
        String access = build(userId, username, role, TYPE_ACCESS, now, accessTtlSeconds);
        String refresh = build(userId, username, role, TYPE_REFRESH, now, refreshTtlSeconds);
        return new TokenPair(access, refresh, accessTtlSeconds);
    }

    private String build(long userId, String username, String role, String type, Instant now, long ttlSeconds) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_TOKEN_TYPE, type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }

    /** 解析并校验签名/过期/类型，失败抛 {@link JwtException}。 */
    public Claims parse(String token, String expectedType) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        String type = claims.get(CLAIM_TOKEN_TYPE, String.class);
        if (!expectedType.equals(type)) {
            throw new JwtException("unexpected token type: " + type);
        }
        return claims;
    }

    public long userIdOf(Claims claims) {
        return Long.parseLong(claims.getSubject());
    }
}
