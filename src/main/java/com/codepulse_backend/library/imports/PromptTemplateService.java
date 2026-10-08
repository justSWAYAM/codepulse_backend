package com.codepulse_backend.library.imports;

import com.codepulse_backend.common.enums.QuestionType;
import com.codepulse_backend.library.imports.config.ImportProperties;
import com.codepulse_backend.library.imports.dto.ImportTemplateResponse;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

@Service
public class PromptTemplateService {

    private final Map<QuestionType, String> templates = new EnumMap<>(QuestionType.class);
    private final ImportProperties props;

    public PromptTemplateService(ImportProperties props) {
        this.props = props;
    }

    @PostConstruct
    void load() {
        for (QuestionType type : QuestionType.values()) {
            String path = "prompts/" + type.name().toLowerCase(Locale.ROOT) + ".txt";
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                throw new IllegalStateException("Missing prompt template: " + path);
            }
            String text;
            try (InputStream in = resource.getInputStream()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").strip();
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read prompt template: " + path, e);
            }
            // Drift guard (D8): every field the parser understands must be named in the prompt.
            for (String field : ImportFieldSpec.fieldsFor(type)) {
                if (!text.contains("\"" + field + "\"")) {
                    throw new IllegalStateException("Prompt " + path + " does not mention field \"" + field + "\"");
                }
            }
            templates.put(type, text);
        }
    }

    public ImportTemplateResponse get(QuestionType type) {
        return new ImportTemplateResponse(type, templates.get(type), props.maxQuestions(), props.maxPayloadBytes());
    }
}
