package com.codepulse_backend.execution;

import com.codepulse_backend.execution.dto.QueuedSubmissionJob;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class SubmissionQueueServiceTest {

    @Test
    void shouldPushJobToQueue() {

        RedisTemplate<String, Object> redisTemplate =
                mock(RedisTemplate.class);

        var listOperations =
                mock(org.springframework.data.redis.core.ListOperations.class);

        when(redisTemplate.opsForList())
                .thenReturn(listOperations);

        SubmissionQueueService queueService =
                new SubmissionQueueService(redisTemplate);

        QueuedSubmissionJob job =
                new QueuedSubmissionJob(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "print(\"hello\")",
                        "PYTHON",
                        List.of(
                                new QueuedSubmissionJob.TestCasePayload(
                                        UUID.randomUUID(),
                                        "input-1",
                                        "expected output 1",
                                        5000,
                                        256000
                                ),
                                new QueuedSubmissionJob.TestCasePayload(
                                        UUID.randomUUID(),
                                        "input-2",
                                        "expected output 2",
                                        5000,
                                        256000
                                )
                        )
                );

        queueService.push(job);

        verify(listOperations)
                .rightPush("codepulse:execution:queue", job);
    }

    @Test
    void shouldPopJobFromQueue() {

        RedisTemplate<String, Object> redisTemplate =
                mock(RedisTemplate.class);

        var listOperations =
                mock(org.springframework.data.redis.core.ListOperations.class);

        when(redisTemplate.opsForList())
                .thenReturn(listOperations);

        SubmissionQueueService queueService =
                new SubmissionQueueService(redisTemplate);

        QueuedSubmissionJob job =
                new QueuedSubmissionJob(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "print(\"hello\")",
                        "PYTHON",
                        List.of(
                                new QueuedSubmissionJob.TestCasePayload(
                                        UUID.randomUUID(),
                                        "input-1",
                                        "expected output 1",
                                        5000,
                                        256000
                                )
                        )
                );

        when(listOperations.leftPop("codepulse:execution:queue"))
                .thenReturn(job);

        Object result = queueService.pop();

        assertEquals(job, result);

        verify(listOperations)
                .leftPop("codepulse:execution:queue");
    }
}