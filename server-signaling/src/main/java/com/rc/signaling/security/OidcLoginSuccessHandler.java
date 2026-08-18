package com.rc.signaling.security;

import com.rc.signaling.api.dto.TokenResponse;
import com.rc.signaling.config.SecurityProperties;
import com.rc.signaling.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * OIDC 登录成功处理器：把 IdP 返回的 {@link OidcUser} 映射为本地用户（按 {@code sub} → 无则建），
 * 签发本系统 JWT，并重定向回前端回调地址（{@code ssoRedirectUri}），以 query 携带
 * {@code access_token / refresh_token / expires_in}。后端保持无状态，后续 API 仍走 JWT。
 */
@Component
public class OidcLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final AuthService authService;
    private final SecurityProperties props;

    public OidcLoginSuccessHandler(AuthService authService, SecurityProperties props) {
        this.authService = authService;
        this.props = props;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
        String sub = oidcUser.getSubject();
        String username = preferredUsername(oidcUser);
        TokenResponse tokens = authService.loginViaSso(sub, username);

        String redirect = props.getSsoRedirectUri()
                + "?access_token=" + encode(tokens.accessToken())
                + "&refresh_token=" + encode(tokens.refreshToken())
                + "&expires_in=" + tokens.expiresInSeconds();
        response.sendRedirect(redirect);
    }

    /** 优先 {@code preferred_username}，其次 {@code email}，最后 {@code name}。 */
    private static String preferredUsername(OidcUser user) {
        for (String claim : new String[]{"preferred_username", "email", "name"}) {
            Object value = user.getClaims().get(claim);
            if (value instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
