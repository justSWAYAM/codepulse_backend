package com.codepulse_backend.library.imports.dto;

import java.util.List;

public record ImportRowPreview(
        int rowNumber,
        boolean valid,
        String title,
        String difficulty,
        Integer points,
        String descriptionPreview,   // first 160 chars
        String detail,               // e.g. "4 options · correct: 2,3"
        List<String> errors) {
}
