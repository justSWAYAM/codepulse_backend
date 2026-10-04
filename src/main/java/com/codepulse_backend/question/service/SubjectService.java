package com.codepulse_backend.question.service;

import com.codepulse_backend.common.exception.DuplicateResourceException;
import com.codepulse_backend.common.exception.ResourceNotFoundException;
import com.codepulse_backend.question.dto.CreateSubjectRequest;
import com.codepulse_backend.question.dto.SubjectResponse;
import com.codepulse_backend.question.entity.Subject;
import com.codepulse_backend.question.repository.SubjectRepository;
import com.codepulse_backend.user.User;
import com.codepulse_backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Manages subject folders in the global question library.
 * Subjects are a simple, globally shared flat list — no nesting.
 * Name uniqueness is enforced case-insensitively.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubjectService {

    private final SubjectRepository subjectRepository;
    private final UserRepository userRepository;

    // ─── List ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SubjectResponse> getAllSubjects() {
        return subjectRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ─── Create ───────────────────────────────────────────────────────────────

    @Transactional
    public SubjectResponse createSubject(CreateSubjectRequest request) {
        String trimmedName = request.name().trim();

        if (subjectRepository.existsByNameIgnoreCase(trimmedName)) {
            throw new DuplicateResourceException(
                    "A subject with the name '" + trimmedName + "' already exists");
        }

        User currentUser = getCurrentUser();

        Subject subject = Subject.builder()
                .name(trimmedName)
                .createdBy(currentUser.getId())
                .build();

        Subject saved = subjectRepository.save(subject);
        log.info("Subject '{}' created by {} (id: {})", saved.getName(),
                currentUser.getEmail(), saved.getId());

        return toResponse(saved);
    }

    // ─── Get by ID (used internally by library service) ──────────────────────

    @Transactional(readOnly = true)
    public Subject getSubjectEntityById(UUID id) {
        return subjectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Subject not found with id: " + id));
    }

    // ─── Mapper ───────────────────────────────────────────────────────────────

    private SubjectResponse toResponse(Subject s) {
        return new SubjectResponse(s.getId(), s.getName(), s.getCreatedBy(), s.getCreatedAt());
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
    }
}
