package com.codepulse_backend.contest.service;

import com.codepulse_backend.common.audit.AuditService;
import com.codepulse_backend.common.dto.PagedResponse;
import com.codepulse_backend.common.enums.ContestStatus;
import com.codepulse_backend.common.enums.Role;
import com.codepulse_backend.common.exception.AccessDeniedException;
import com.codepulse_backend.common.exception.InvalidStateException;
import com.codepulse_backend.common.exception.ResourceNotFoundException;
import com.codepulse_backend.contest.dto.*;
import com.codepulse_backend.contest.entity.Contest;
import com.codepulse_backend.contest.entity.ContestCandidate;
import com.codepulse_backend.contest.repository.ContestCandidateRepository;
import com.codepulse_backend.contest.repository.ContestRepository;
import com.codepulse_backend.user.User;
import com.codepulse_backend.user.dto.UserSummaryResponse;
import com.codepulse_backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContestService {

    private final ContestRepository contestRepository;
    private final ContestCandidateRepository contestCandidateRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    // ─── Create ───────────────────────────────────────────────────────────────

    @Transactional
    public ContestResponse createContest(CreateContestRequest request) {
        User currentUser = getCurrentAuthenticatedUser();

        if (!request.endTime().isAfter(request.startTime())) {
            throw new InvalidStateException("End time must be after start time");
        }

        Contest contest = Contest.builder()
                .title(request.title())
                .description(request.description())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .durationMinutes(request.durationMinutes())
                .allowedLanguages(request.allowedLanguages())
                .status(ContestStatus.DRAFT)
                .build();

        Contest saved = contestRepository.save(contest);
        auditService.log(currentUser.getId(), "CONTEST_CREATED", "CONTEST", saved.getId(),
                "Admin created contest: " + saved.getTitle());

        return toContestResponse(saved);
    }

    // ─── Update ───────────────────────────────────────────────────────────────

    @Transactional
    public ContestResponse updateContest(UUID id, UpdateContestRequest request) {
        Contest contest = findContestById(id);

        if (contest.getStatus() != ContestStatus.DRAFT) {
            throw new InvalidStateException("Only DRAFT contests can be updated. Current status: " + contest.getStatus());
        }

        if (request.title() != null)            contest.setTitle(request.title());
        if (request.description() != null)      contest.setDescription(request.description());
        if (request.startTime() != null)        contest.setStartTime(request.startTime());
        if (request.endTime() != null)          contest.setEndTime(request.endTime());
        if (request.durationMinutes() != null)  contest.setDurationMinutes(request.durationMinutes());
        if (request.allowedLanguages() != null) contest.setAllowedLanguages(request.allowedLanguages());

        // Re-validate time range if either was changed
        if (!contest.getEndTime().isAfter(contest.getStartTime())) {
            throw new InvalidStateException("End time must be after start time");
        }

        return toContestResponse(contestRepository.save(contest));
    }

    // ─── Publish ──────────────────────────────────────────────────────────────

    @Transactional
    public ContestResponse publishContest(UUID id) {
        User currentUser = getCurrentAuthenticatedUser();
        Contest contest = findContestById(id);

        if (contest.getStatus() != ContestStatus.DRAFT) {
            throw new InvalidStateException("Only DRAFT contests can be published. Current status: " + contest.getStatus());
        }

        long candidateCount = contestCandidateRepository.countByContestId(id);
        if (candidateCount == 0) {
            throw new InvalidStateException("Cannot publish a contest with no candidates assigned");
        }

        contest.setStatus(ContestStatus.PUBLISHED);
        Contest saved = contestRepository.save(contest);

        auditService.log(currentUser.getId(), "CONTEST_PUBLISHED", "CONTEST", saved.getId(),
                "Admin published contest: " + saved.getTitle());
        log.info("Contest {} published by {}", id, currentUser.getEmail());

        return toContestResponse(saved);
    }

    // ─── List ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<ContestResponse> getContests(ContestStatus statusFilter, Pageable pageable) {
        User currentUser = getCurrentAuthenticatedUser();
        Page<Contest> page;

        if (currentUser.getRole() == Role.CANDIDATE) {
            // Candidates see only their assigned contests (PUBLISHED + ONGOING only by default)
            List<ContestStatus> visibleStatuses = statusFilter != null
                    ? List.of(statusFilter)
                    : List.of(ContestStatus.PUBLISHED, ContestStatus.ONGOING);
            page = contestRepository.findAllByCandidateIdAndStatusIn(
                    currentUser.getId(), visibleStatuses, pageable);
        } else {
            // Admin and Evaluator see all contests
            page = statusFilter != null
                    ? contestRepository.findByStatus(statusFilter, pageable)
                    : contestRepository.findAll(pageable);
        }

        List<ContestResponse> content = page.getContent().stream()
                .map(this::toContestResponse)
                .toList();

        return new PagedResponse<>(content, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    // ─── Detail ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ContestDetailResponse getContestDetail(UUID id) {
        User currentUser = getCurrentAuthenticatedUser();
        Contest contest = findContestById(id);

        // Candidates can only view contests they are assigned to
        if (currentUser.getRole() == Role.CANDIDATE) {
            boolean isAssigned = contestCandidateRepository
                    .existsByContestIdAndCandidateId(id, currentUser.getId());
            if (!isAssigned) {
                throw new AccessDeniedException("You are not assigned to this contest");
            }
        }

        List<UserSummaryResponse> candidates = null;

        // Only Admin and Evaluator see the candidate list in detail response
        if (currentUser.getRole() != Role.CANDIDATE) {
            candidates = contestCandidateRepository.findAllByContestId(id).stream()
                    .map(cc -> toUserSummary(cc.getCandidate()))
                    .toList();
        }

        long candidateCount = contestCandidateRepository.countByContestId(id);

        return new ContestDetailResponse(
                contest.getId(),
                contest.getTitle(),
                contest.getDescription(),
                contest.getStartTime(),
                contest.getEndTime(),
                contest.getDurationMinutes(),
                contest.getAllowedLanguages(),
                contest.getStatus(),
                candidateCount,
                contest.getCreatedAt(),
                candidates
        );
    }

    // ─── Assign Candidates ────────────────────────────────────────────────────

    @Transactional
    public AssignCandidatesResult assignCandidates(UUID contestId, AssignCandidatesRequest request) {
        Contest contest = findContestById(contestId);
        User currentUser = getCurrentAuthenticatedUser();

        int assignedCount = 0;
        int alreadyAssignedCount = 0;
        int notFoundCount = 0;
        List<UUID> failedIds = new ArrayList<>();

        for (UUID candidateId : request.candidateIds()) {
            Optional<User> candidateOpt = userRepository.findById(candidateId);

            if (candidateOpt.isEmpty()) {
                notFoundCount++;
                failedIds.add(candidateId);
                log.warn("Candidate with id {} not found during contest assignment", candidateId);
                continue;
            }

            User candidate = candidateOpt.get();

            if (candidate.getRole() != Role.CANDIDATE) {
                notFoundCount++;
                failedIds.add(candidateId);
                log.warn("User {} is not a CANDIDATE — skipping assignment", candidateId);
                continue;
            }

            if (contestCandidateRepository.existsByContestIdAndCandidateId(contestId, candidateId)) {
                alreadyAssignedCount++;
                continue;
            }

            ContestCandidate cc = ContestCandidate.builder()
                    .contest(contest)
                    .candidate(candidate)
                    .build();
            contestCandidateRepository.save(cc);
            assignedCount++;
        }

        auditService.log(currentUser.getId(), "CANDIDATES_ASSIGNED", "CONTEST", contestId,
                String.format("%d candidates assigned to contest %s", assignedCount, contest.getTitle()));

        return new AssignCandidatesResult(assignedCount, alreadyAssignedCount, notFoundCount, failedIds);
    }

    // ─── Get Assigned Candidates ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<UserSummaryResponse> getCandidates(UUID contestId) {
        findContestById(contestId); // existence check
        return contestCandidateRepository.findAllByContestId(contestId).stream()
                .map(cc -> toUserSummary(cc.getCandidate()))
                .toList();
    }

    // ─── Scheduler Transition Methods (package-visible) ───────────────────────

    @Transactional
    public void transitionToOngoing(Contest contest) {
        contest.setStatus(ContestStatus.ONGOING);
        contestRepository.save(contest);
    }

    @Transactional
    public void transitionToCompleted(Contest contest) {
        contest.setStatus(ContestStatus.COMPLETED);
        contestRepository.save(contest);
    }

    // ─── Private Helpers ──────────────────────────────────────────────────────

    private Contest findContestById(UUID id) {
        return contestRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Contest not found with id: " + id));
    }

    private User getCurrentAuthenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String email = auth.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
    }

    private ContestResponse toContestResponse(Contest contest) {
        long candidateCount = contestCandidateRepository.countByContestId(contest.getId());
        return new ContestResponse(
                contest.getId(),
                contest.getTitle(),
                contest.getDescription(),
                contest.getStartTime(),
                contest.getEndTime(),
                contest.getDurationMinutes(),
                contest.getAllowedLanguages(),
                contest.getStatus(),
                candidateCount,
                contest.getCreatedAt()
        );
    }

    private UserSummaryResponse toUserSummary(User user) {
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
