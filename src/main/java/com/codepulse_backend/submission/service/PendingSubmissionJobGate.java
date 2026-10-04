package com.codepulse_backend.submission.service;

import com.codepulse_backend.common.enums.SubmissionStatus;
import com.codepulse_backend.execution.JobGate;
import com.codepulse_backend.submission.repository.SubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PendingSubmissionJobGate implements JobGate {

    private final SubmissionRepository submissionRepository;

    /** Only PENDING rows are judged; unknown IDs are let through (the listener drops them). */
    @Override
    public boolean shouldExecute(UUID submissionId) {
        return submissionRepository.findById(submissionId)
                .map(submission -> submission.getStatus() == SubmissionStatus.PENDING)
                .orElse(true);
    }
}
