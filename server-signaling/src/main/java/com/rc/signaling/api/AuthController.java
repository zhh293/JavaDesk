package com.rc.signaling.api;

import com.rc.signaling.api.dto.LoginRequest;
import com.rc.signaling.api.dto.RefreshRequest;
import com.rc.signaling.api.dto.RegisterRequest;
import com.rc.signaling.api.dto.TokenResponse;
import com.rc.signaling.api.dto.UserResponse;
import com.rc.signaling.service.AuthService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
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
}
