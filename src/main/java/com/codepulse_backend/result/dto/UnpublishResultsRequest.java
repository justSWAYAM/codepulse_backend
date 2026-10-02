package com.codepulse_backend.result.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UnpublishResultsRequest(
        @NotBlank @Size(max = 500) String reason
) {}
