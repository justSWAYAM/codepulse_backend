package com.codepulse_backend.user.controller;

import com.codepulse_backend.common.dto.ApiResponse;
import com.codepulse_backend.common.dto.PagedResponse;
import com.codepulse_backend.common.enums.Role;
import com.codepulse_backend.user.dto.*;
import com.codepulse_backend.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    // --- Admin Endpoints ---

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PagedResponse<UserSummaryResponse>> getUsers(
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(required = false) String search,
            Pageable pageable
    ) {
        PagedResponse<UserSummaryResponse> users = userService.getUsers(role, isActive, search, pageable);
        return new ApiResponse<>(true, users, "Users retrieved successfully", Instant.now(), null);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<UserSummaryResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        UserSummaryResponse createdUser = userService.createUser(request);
        return new ApiResponse<>(true, createdUser, "User created successfully", Instant.now(), null);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<UserSummaryResponse> updateUser(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest request
    ) {
        UserSummaryResponse updatedUser = userService.updateUser(id, request);
        return new ApiResponse<>(true, updatedUser, "User updated successfully", Instant.now(), null);
    }

    @PatchMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<UserSummaryResponse> deactivateUser(@PathVariable UUID id) {
        UserSummaryResponse deactivatedUser = userService.deactivateUser(id);
        return new ApiResponse<>(true, deactivatedUser, "User deactivated successfully", Instant.now(), null);
    }

    @PatchMapping("/{id}/reactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<UserSummaryResponse> reactivateUser(@PathVariable UUID id) {
        UserSummaryResponse reactivatedUser = userService.reactivateUser(id);
        return new ApiResponse<>(true, reactivatedUser, "User reactivated successfully", Instant.now(), null);
    }

    @PostMapping("/bulk-import")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<BulkImportResult> bulkImportUsers(@RequestParam("file") MultipartFile file) {
        BulkImportResult result = userService.bulkImportUsers(file);
        return new ApiResponse<>(true, result, "Bulk import completed", Instant.now(), null);
    }

    // --- Self-Service (Authenticated) Endpoints ---

    @GetMapping("/me")
    public ApiResponse<UserSummaryResponse> getCurrentUser() {
        UserSummaryResponse currentUser = userService.getCurrentUser();
        return new ApiResponse<>(true, currentUser, "Profile retrieved successfully", Instant.now(), null);
    }

    @PutMapping("/me")
    public ApiResponse<UserSummaryResponse> updateOwnProfile(@Valid @RequestBody UpdateOwnProfileRequest request) {
        UserSummaryResponse updatedProfile = userService.updateOwnProfile(request);
        return new ApiResponse<>(true, updatedProfile, "Profile updated successfully", Instant.now(), null);
    }

    @PatchMapping("/me/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(request);
        return new ApiResponse<>(true, null, "Password changed successfully", Instant.now(), null);
    }
}