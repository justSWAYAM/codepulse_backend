package com.codepulse_backend.execution;

import com.codepulse_backend.execution.dto.QueuedSubmissionJob;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class SubmissionQueueService {

    private static final String QUEUE_KEY = "codepulse:execution:queue";

    private final RedisTemplate<String, Object> redisTemplate;

    public SubmissionQueueService(
            RedisTemplate<String, Object> redisTemplate
    ) {
        this.redisTemplate = redisTemplate;
    }

    public void push(QueuedSubmissionJob job) {
        redisTemplate.opsForList().rightPush(QUEUE_KEY, job);
    }

    public Object pop() {
        return redisTemplate.opsForList().leftPop(QUEUE_KEY);
    }

    public long size() {
        Long size = redisTemplate.opsForList().size(QUEUE_KEY);
        return size == null ? 0 : size;
    }
}