package com.codepulse_backend.common.exception;

public class InvalidStateException extends AppException{
    public InvalidStateException(String message) {
        super("INVALID_STATE", message);
    }
}
