package com.codepulse_backend.execution.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SubmissionEvaluatedStubListener {

    @EventListener
    public void handle(SubmissionEvaluatedEvent event) {

        log.info(
                "Submission evaluated: submissionId={}, outcomes={}",
                event.submissionId(),
                event.outcomes().size()
        );
    }
}