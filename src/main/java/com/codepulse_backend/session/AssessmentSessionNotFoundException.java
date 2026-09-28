package com.codepulse_backend.session;

public class AssessmentSessionNotFoundException extends RuntimeException {
    public AssessmentSessionNotFoundException() {
        super("Assessment session not found");
    }
}