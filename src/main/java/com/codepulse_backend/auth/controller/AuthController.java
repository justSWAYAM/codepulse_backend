package com.codepulse_backend.auth.controller;

import com.codepulse_backend.auth.dto.LoginRequest;
import com.codepulse_backend.auth.dto.LoginResponse;
import com.codepulse_backend.auth.dto.RefreshResponse;
import com.codepulse_backend.auth.service.AuthService;
import com.codepulse_backend.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {

        String traceId = (String) httpRequest.getAttribute("traceId");
        LoginResponse data = authService.login(request, response);
        return ResponseEntity.ok(ApiResponse.success(data, "Login successful", traceId));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<RefreshResponse>> refresh(
            HttpServletRequest httpRequest,
            HttpServletResponse response) {

        String traceId = (String) httpRequest.getAttribute("traceId");
        RefreshResponse data = authService.refresh(httpRequest, response);
        return ResponseEntity.ok(ApiResponse.success(data, "Token refreshed successfully", traceId));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            HttpServletRequest httpRequest,
            HttpServletResponse response) {

        String traceId = (String) httpRequest.getAttribute("traceId");
        authService.logout(httpRequest, response);
        return ResponseEntity.ok(ApiResponse.success(null, "Logged out successfully", traceId));
    }
}