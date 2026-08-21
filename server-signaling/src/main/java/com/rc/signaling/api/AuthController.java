package com.rc.signaling.api;

import com.rc.common.constant.ErrorCode;
import com.rc.signaling.api.dto.HandoffExchangeRequest;
import com.rc.signaling.api.dto.LoginRequest;
import com.rc.signaling.api.dto.RefreshRequest;
import com.rc.signaling.api.dto.RegisterRequest;
import com.rc.signaling.api.dto.TokenResponse;
import com.rc.signaling.api.dto.UserResponse;
import com.rc.signaling.service.AuthService;
import com.rc.signaling.security.AuthorizationHandoffService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthorizationHandoffService handoffService;

    public AuthController(AuthService authService, AuthorizationHandoffService handoffService) {
        this.authService = authService;
        this.handoffService = handoffService;
    }

    @PostMapping("/register")
    public ApiResult<UserResponse> register(@RequestBody RegisterRequest request) {
        return ApiResult.ok(authService.register(request.username(), request.password()));
    }

    @PostMapping("/login")
    public ApiResult<TokenResponse> login(@RequestBody LoginRequest request) {
        return ApiResult.ok(authService.login(request.username(), request.password()));
    }

    @PostMapping("/refresh")
    public ApiResult<TokenResponse> refresh(@RequestBody RefreshRequest request) {
        return ApiResult.ok(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/sso/handoff")
    public ApiResult<TokenResponse> exchangeHandoff(@RequestBody HandoffExchangeRequest request) {
        TokenResponse tokens = handoffService.consume(request.code()).orElseThrow(() ->
                new ApiException(ErrorCode.AUTH_INVALID, HttpStatus.UNAUTHORIZED,
                        "SSO handoff code invalid, expired or already consumed"));
        return ApiResult.ok(tokens);
    }
}
