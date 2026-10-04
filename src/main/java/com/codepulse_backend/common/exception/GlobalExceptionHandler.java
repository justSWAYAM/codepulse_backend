package com.codepulse_backend.common.exception;


import com.codepulse_backend.common.dto.ApiError;
import com.codepulse_backend.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.time.Instant;
import java.util.List;


@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex , HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND , ex.getCode(), ex.getMessage(), req);
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicate(DuplicateResourceException ex , HttpServletRequest req) {
        return build(HttpStatus.CONFLICT , ex.getCode(), ex.getMessage(), req);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, ex.getCode(), ex.getMessage(), req);
    }

    // Thrown by @PreAuthorize denials (AuthorizationDeniedException extends this).
    // Without this handler they fall through to handleGeneric and surface as 500.
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleSpringAccessDenied(
            org.springframework.security.access.AccessDeniedException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You do not have permission to perform this action", req);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(UnauthorizedException ex, HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, ex.getCode(), ex.getMessage(), req);
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ApiResponse<Void>> handleDisabled(DisabledException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "Your account has been deactivated. Contact an administrator.", req);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(ConflictException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage(), req);
    }

    @ExceptionHandler(InvalidStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidState(InvalidStateException ex, HttpServletRequest req) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getCode(), ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<ApiError>> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<ApiError.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        String traceId = (String) req.getAttribute("traceId");
        ApiError error = new ApiError("VALIDATION_FAILED", "Validation failed", errors, traceId);
        String message = errors.isEmpty()
                ? "Validation failed"
                : "Validation failed: " + errors.get(0).field() + " " + errors.get(0).message();
        return ResponseEntity.badRequest().body(new ApiResponse<>(false, error, message, Instant.now(), traceId));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiResponse<Void>> handleAppBadRequest(BadRequestException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getCode(), ex.getMessage(), req);
    }

    // ?sort=unknownField, or a request to an upload endpoint that is not multipart
    @ExceptionHandler({
            PropertyReferenceException.class,
            InvalidDataAccessApiUsageException.class,
            MultipartException.class,
            HandlerMethodValidationException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadParameter(Exception ex, HttpServletRequest req) {
        log.warn("Rejected request {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid request parameter", req);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Malformed or missing request data", req);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", ex.getMessage(), req);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest req) {
        return build(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "Uploaded file is too large", req);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT, "DATA_CONFLICT", "The request conflicts with existing data", req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong", req);
    }

    private ResponseEntity<ApiResponse<Void>> build(HttpStatus status,String code,String message,HttpServletRequest req){
        String traceId = (String) req.getAttribute("traceId");
        return ResponseEntity.status(status).body(ApiResponse.failure(code + ": " + message, traceId));
    }
    @ExceptionHandler(SubmissionQueueUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleSubmissionQueueUnavailable(
            SubmissionQueueUnavailableException ex,
            HttpServletRequest req
    ) {
        return build(
                HttpStatus.SERVICE_UNAVAILABLE,
                ex.getCode(),
                ex.getMessage(),
                req
        );
    }

    @ExceptionHandler(ExecutionBusyException.class)
    public ResponseEntity<ApiResponse<Void>> handleExecutionBusy(ExecutionBusyException ex, HttpServletRequest req) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getCode(), ex.getMessage(), req);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimited(RateLimitExceededException ex, HttpServletRequest req) {
        return build(HttpStatus.TOO_MANY_REQUESTS, ex.getCode(), ex.getMessage(), req);
    }
}
