package com.codepulse_backend.common.exception;

public class SubmissionQueueUnavailableException extends AppException {

    public SubmissionQueueUnavailableException(String message) {
        super("SUBMISSION_QUEUE_UNAVAILABLE", message);
    }
}