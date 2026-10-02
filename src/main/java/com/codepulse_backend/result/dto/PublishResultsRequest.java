package com.codepulse_backend.result.dto;

/** acknowledgeFlagged: publish even though some results are NEEDS_REVIEW. */
public record PublishResultsRequest(boolean acknowledgeFlagged) {}
