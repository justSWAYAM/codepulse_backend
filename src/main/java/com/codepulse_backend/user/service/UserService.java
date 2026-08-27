package com.codepulse_backend.user.service;

import com.codepulse_backend.common.audit.AuditService;
import com.codepulse_backend.common.dto.PagedResponse;
import com.codepulse_backend.common.enums.Role;
import com.codepulse_backend.common.exception.DuplicateResourceException;
import com.codepulse_backend.common.exception.ResourceNotFoundException;
import com.codepulse_backend.common.exception.UnauthorizedException;
import com.codepulse_backend.common.util.CsvImportService;
import com.codepulse_backend.user.User;
import com.codepulse_backend.user.dto.*;
import com.codepulse_backend.user.repository.UserRepository;
import com.codepulse_backend.user.repository.UserSpecification;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final CsvImportService csvImportService; // Added injection

    @Transactional
    public UserSummaryResponse createUser(CreateUserRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("User with email " + request.email() + " already exists");
        }

        User user = User.builder()
                .email(request.email())
                .fullName(request.fullName())
                .role(request.role())
                .passwordHash(passwordEncoder.encode(request.password()))
                .isActive(true)
                .build();

        User savedUser = userRepository.save(user);

        User admin = getCurrentAuthenticatedUser();
        auditService.log(admin.getId(), "USER_CREATED", "USER", savedUser.getId(), "Admin created user: " + savedUser.getEmail());

        return mapToSummary(savedUser);
    }

    @Transactional
    public UserSummaryResponse updateUser(UUID id, UpdateUserRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));

        user.setRole(request.role());
        user.setActive(request.isActive());

        User updatedUser = userRepository.save(user);

        User admin = getCurrentAuthenticatedUser();
        auditService.log(admin.getId(), "USER_UPDATED", "USER", updatedUser.getId(), "Admin updated user: " + updatedUser.getEmail());

        return mapToSummary(updatedUser);
    }

    @Transactional
    public UserSummaryResponse updateOwnProfile(UpdateOwnProfileRequest request) {
        User user = getCurrentAuthenticatedUser();
        user.setFullName(request.fullName());

        User updatedUser = userRepository.save(user);
        return mapToSummary(updatedUser);
    }

    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        User user = getCurrentAuthenticatedUser();

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new UnauthorizedException("Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        auditService.log(user.getId(), "PASSWORD_CHANGED", "USER", user.getId(), "User changed password");
    }

    @Transactional
    public UserSummaryResponse deactivateUser(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));

        user.setActive(false);
        User updatedUser = userRepository.save(user);

        User admin = getCurrentAuthenticatedUser();
        auditService.log(admin.getId(), "USER_DEACTIVATED", "USER", updatedUser.getId(), "Admin deactivated user: " + user.getEmail());

        return mapToSummary(updatedUser);
    }

    @Transactional
    public UserSummaryResponse reactivateUser(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));

        user.setActive(true);
        User updatedUser = userRepository.save(user);

        User admin = getCurrentAuthenticatedUser();
        auditService.log(admin.getId(), "USER_REACTIVATED", "USER", updatedUser.getId(), "Admin reactivated user: " + user.getEmail());

        return mapToSummary(updatedUser);
    }

    @Transactional(readOnly = true)
    public PagedResponse<UserSummaryResponse> getUsers(Role role, Boolean isActive, String search, Pageable pageable) {
        Page<User> userPage = userRepository.findAll(UserSpecification.withFilters(role, isActive, search), pageable);

        List<UserSummaryResponse> content = userPage.getContent().stream()
                .map(this::mapToSummary)
                .toList();

        return new PagedResponse<UserSummaryResponse>(
                content,
                userPage.getNumber(),
                userPage.getSize(),
                userPage.getTotalElements(),
                userPage.getTotalPages()
        );
    }

    @Transactional(readOnly = true)
    public UserSummaryResponse getCurrentUser() {
        return mapToSummary(getCurrentAuthenticatedUser());
    }

    @Transactional
    public BulkImportResult bulkImportUsers(MultipartFile file) {
        return csvImportService.process(
                file,
                (CSVRecord record) -> {
                    // Map raw CSVRecord to CreateUserRequest
                    if (record.size() < 4) {
                        throw new IllegalArgumentException("Expected at least 4 columns (Email, FullName, Role, Password)");
                    }
                    return new CreateUserRequest(
                            record.get(0).trim(),
                            record.get(1).trim(),
                            Role.valueOf(record.get(2).trim().toUpperCase()),
                            record.get(3).trim()
                    );
                },
                (CreateUserRequest request) -> {
                    // Process row using our existing validation/creation logic
                    try {
                        createUser(request);
                        return null; // success
                    } catch (DuplicateResourceException e) {
                        return "Email already exists: " + request.email();
                    } catch (Exception e) {
                        return e.getMessage();
                    }
                }
        );
    }

    // --- Helper Methods ---

    private User getCurrentAuthenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || auth.getName() == null) {
            throw new UnauthorizedException("No authenticated user found in context");
        }

        String email = auth.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UnauthorizedException("Authenticated user not found in database"));
    }

    private UserSummaryResponse mapToSummary(User user) {
        return new UserSummaryResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.isActive(),
                user.getCreatedAt()
        );
    }
}