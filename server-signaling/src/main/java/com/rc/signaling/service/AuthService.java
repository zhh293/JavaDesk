package com.rc.signaling.service;

import com.rc.common.constant.ErrorCode;
import com.rc.common.model.User;
import com.rc.signaling.api.ApiException;
import com.rc.signaling.api.dto.TokenResponse;
import com.rc.signaling.api.dto.UserResponse;
import com.rc.signaling.dao.UserMapper;
import io.jsonwebtoken.JwtException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final int USERNAME_MIN = 3;
    private static final int USERNAME_MAX = 64;
    private static final int PASSWORD_MIN = 6;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtService jwtService,
                       AuditService auditService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
    }

    @Transactional
    public UserResponse register(String username, String password) {
        validate(username, password);
        if (userMapper.findByUsername(username) != null) {
            throw new ApiException(ErrorCode.USERNAME_EXISTS, HttpStatus.CONFLICT);
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(User.ROLE_USER);
        user.setCreatedAt(System.currentTimeMillis());
        userMapper.insert(user);
        auditService.record(user.getId(), null, AuditService.ACTION_REGISTER, "username=" + username);
        return new UserResponse(user.getId(), user.getUsername());
    }

    public TokenResponse login(String username, String password) {
        User user = userMapper.findByUsername(username);
        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ApiException(ErrorCode.AUTH_INVALID, HttpStatus.UNAUTHORIZED);
        }
        JwtService.TokenPair pair = jwtService.issue(user.getId(), user.getUsername(), roleOf(user));
        auditService.record(user.getId(), null, AuditService.ACTION_LOGIN, "method=password username=" + username);
        return toResponse(pair);
    }

    /**
     * SSO/OIDC 登录：按 IdP {@code sub} 查找本地用户，无则创建（密码为空、以 {@code sso_subject}
     * 关联）。返回与用户名密码登录一致的 JWT 令牌对。
     */
    @Transactional
    public TokenResponse loginViaSso(String ssoSubject, String preferredUsername) {
        User user = userMapper.findBySsoSubject(ssoSubject);
        if (user == null) {
            user = new User();
            user.setSsoSubject(ssoSubject);
            user.setUsername(resolveSsoUsername(preferredUsername, ssoSubject));
            user.setRole(User.ROLE_USER);
            user.setCreatedAt(System.currentTimeMillis());
            userMapper.insert(user);
        }
        TokenResponse response = toResponse(jwtService.issue(user.getId(), user.getUsername(), roleOf(user)));
        auditService.record(user.getId(), null, AuditService.ACTION_LOGIN, "method=sso subject=" + ssoSubject);
        return response;
    }

    /** 从 OIDC 用户名派生本地唯一用户名；冲突或缺失时回退 {@code sso_<sub>}。 */
    private String resolveSsoUsername(String preferred, String sub) {
        if (preferred != null && !preferred.isBlank()) {
            User existing = userMapper.findByUsername(preferred);
            if (existing == null || sub.equals(existing.getSsoSubject())) {
                return truncate(preferred, 64);
            }
        }
        return truncate("sso_" + sub, 64);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    public TokenResponse refresh(String refreshToken) {
        try {
            long userId = jwtService.userIdOf(jwtService.parse(refreshToken, JwtService.TYPE_REFRESH));
            User user = userMapper.findById(userId);
            if (user == null) {
                throw new ApiException(ErrorCode.AUTH_INVALID, HttpStatus.UNAUTHORIZED);
            }
            return toResponse(jwtService.issue(user.getId(), user.getUsername(), roleOf(user)));
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(ErrorCode.AUTH_INVALID, HttpStatus.UNAUTHORIZED, "refresh token invalid or expired");
        }
    }

    private TokenResponse toResponse(JwtService.TokenPair pair) {
        return new TokenResponse(pair.accessToken(), pair.refreshToken(), "Bearer", pair.expiresInSeconds());
    }

    /** 解析用户角色（缺失/空回退 {@link User#ROLE_USER}），写入 JWT 供鉴权过滤器映射授权。 */
    private static String roleOf(User user) {
        String role = user.getRole();
        return role == null || role.isBlank() ? User.ROLE_USER : role;
    }

    private void validate(String username, String password) {
        if (username == null || username.length() < USERNAME_MIN || username.length() > USERNAME_MAX) {
            throw new ApiException(ErrorCode.AUTH_INVALID,
                    "username length must be " + USERNAME_MIN + "~" + USERNAME_MAX);
        }
        if (password == null || password.length() < PASSWORD_MIN) {
            throw new ApiException(ErrorCode.AUTH_INVALID, "password too short");
        }
    }
}
