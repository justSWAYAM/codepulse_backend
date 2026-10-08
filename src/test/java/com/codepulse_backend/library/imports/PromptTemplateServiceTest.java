package com.codepulse_backend.library.imports;

import com.codepulse_backend.common.enums.QuestionType;
import com.codepulse_backend.library.imports.config.ImportProperties;
import com.codepulse_backend.library.imports.dto.ImportTemplateResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptTemplateServiceTest {

    private PromptTemplateService service;
    private ImportProperties props;

    @BeforeEach
    void setUp() {
        props = new ImportProperties(1048576L, 200, 2000, 262144);
        service = new PromptTemplateService(props);
        service.load();
    }

    @Test
    @DisplayName("all four templates load successfully and contain 'Return ONLY the JSON'")
    void allTemplatesLoadSuccessfully() {
        for (QuestionType type : QuestionType.values()) {
            ImportTemplateResponse response = service.get(type);
            assertThat(response).isNotNull();
            assertThat(response.type()).isEqualTo(type);
            assertThat(response.maxQuestions()).isEqualTo(200);
            assertThat(response.maxPayloadBytes()).isEqualTo(1048576L);
            assertThat(response.prompt()).isNotBlank();
            assertThat(response.prompt()).contains("Return ONLY the JSON");
        }
    }

    @Test
    @DisplayName("every field in ImportFieldSpec appears in its corresponding template")
    void allFieldsPresentInTemplates() {
        for (QuestionType type : QuestionType.values()) {
            String prompt = service.get(type).prompt();
            for (String field : ImportFieldSpec.fieldsFor(type)) {
                assertThat(prompt).contains("\"" + field + "\"");
            }
        }
    }
}
