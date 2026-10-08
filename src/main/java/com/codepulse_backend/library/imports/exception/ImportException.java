package com.codepulse_backend.library.imports.exception;

import com.codepulse_backend.common.exception.AppException;
import com.codepulse_backend.common.exception.ErrorCode;

public class ImportException extends AppException {

    private final ErrorCode errorCode;

    public ImportException(ErrorCode code, String message) {
        super(code.name(), message);
        this.errorCode = code;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public static ImportException invalid(String message) {
        return new ImportException(ErrorCode.IMPORT_PAYLOAD_INVALID, message);
    }

    public static ImportException tooLarge(long maxBytes) {
        return new ImportException(ErrorCode.IMPORT_PAYLOAD_TOO_LARGE,
                "Pasted text is larger than " + (maxBytes / 1024) + " KB. Import fewer questions at a time.");
    }

    public static ImportException limit(int found, int max) {
        return new ImportException(ErrorCode.IMPORT_LIMIT_EXCEEDED,
                "Found " + found + " questions; the maximum is " + max + " per import. Split the list and import in batches.");
    }
}
