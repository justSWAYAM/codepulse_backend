package com.codepulse_backend.result.service;

import com.codepulse_backend.contest.entity.Contest;
import com.codepulse_backend.contest.repository.ContestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The single place Module 8 asks whether a candidate may see verdicts after the exam.
 * Nothing else reads results_published_at for visibility.
 */
@Service
@RequiredArgsConstructor
public class ResultVisibilityService {

    private final ContestRepository contestRepository;

    public boolean isPublished(UUID contestId) {
        return contestRepository.findById(contestId)
                .map(Contest::isResultsPublished)
                .orElse(false);
    }
}
