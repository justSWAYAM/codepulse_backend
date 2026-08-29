package com.codepulse_backend.contest.service;

import com.codepulse_backend.common.enums.ContestStatus;
import com.codepulse_backend.contest.entity.Contest;
import com.codepulse_backend.contest.repository.ContestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContestSchedulerService {

    private final ContestRepository contestRepository;
    private final ContestService contestService;

    /**
     * Runs every 60 seconds.
     * Finds all PUBLISHED contests whose startTime has now passed and transitions them to ONGOING.
     */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void transitionPublishedToOngoing() {
        List<Contest> contests = contestRepository
                .findAllByStatusAndStartTimeBefore(ContestStatus.PUBLISHED, Instant.now());

        if (!contests.isEmpty()) {
            log.info("Scheduler: {} PUBLISHED contest(s) transitioning to ONGOING", contests.size());
        }

        contests.forEach(contest -> {
            contestService.transitionToOngoing(contest);
            log.info("Contest [{}] '{}' transitioned PUBLISHED -> ONGOING", contest.getId(), contest.getTitle());
        });
    }

    /**
     * Runs every 60 seconds.
     * Finds all ONGOING contests whose endTime has now passed and transitions them to COMPLETED.
     */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void transitionOngoingToCompleted() {
        List<Contest> contests = contestRepository
                .findAllByStatusAndEndTimeBefore(ContestStatus.ONGOING, Instant.now());

        if (!contests.isEmpty()) {
            log.info("Scheduler: {} ONGOING contest(s) transitioning to COMPLETED", contests.size());
        }

        contests.forEach(contest -> {
            contestService.transitionToCompleted(contest);
            log.info("Contest [{}] '{}' transitioned ONGOING -> COMPLETED", contest.getId(), contest.getTitle());
        });
    }
}
