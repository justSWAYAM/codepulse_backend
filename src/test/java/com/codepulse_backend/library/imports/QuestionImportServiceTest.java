package com.codepulse_backend.library.imports;

import com.codepulse_backend.common.audit.AuditService;
import com.codepulse_backend.common.bulkimport.BulkImportService;
import com.codepulse_backend.common.enums.Difficulty;
import com.codepulse_backend.common.enums.QuestionType;
import com.codepulse_backend.common.exception.ResourceNotFoundException;
import com.codepulse_backend.library.imports.config.ImportProperties;
import com.codepulse_backend.library.imports.dto.ImportQuestionsRequest;
import com.codepulse_backend.library.imports.dto.QuestionImportResponse;
import com.codepulse_backend.question.dto.CreateLibraryQuestionRequest;
import com.codepulse_backend.question.entity.Subject;
import com.codepulse_backend.question.repository.SubjectRepository;
import com.codepulse_backend.user.User;
import com.codepulse_backend.user.repository.UserRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuestionImportServiceTest {

    @Mock private SubjectRepository subjectRepository;
    @Mock private UserRepository userRepository;
    @Mock private QuestionImportRowWriter rowWriter;
    @Mock private AuditService auditService;

    private QuestionImportService service;
    private final UUID subjectId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ImportProperties props = new ImportProperties(1048576L, 200, 2000, 262144);
        QuestionImportParser parser = new QuestionImportParser(props);
        QuestionImportValidator validator = new QuestionImportValidator();
        QuestionImportMapper mapper = new QuestionImportMapper(props);
        BulkImportService bulkImportService = new BulkImportService();
        Validator beanValidator = Validation.buildDefaultValidatorFactory().getValidator();

        service = new QuestionImportService(
                subjectRepository,
                userRepository,
                parser,
                validator,
                mapper,
                bulkImportService,
                rowWriter,
                beanValidator,
                auditService
        );

        User user = new User();
        user.setId(userId);
        user.setEmail("evaluator@codepulse.dev");
        lenient().when(userRepository.findByEmail("evaluator@codepulse.dev")).thenReturn(Optional.of(user));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("evaluator@codepulse.dev", "password")
        );
    }

    @Test
    @DisplayName("unknown subject throws ResourceNotFoundException before parsing")
    void unknownSubjectThrowsBeforeParsing() {
        when(subjectRepository.findById(subjectId)).thenReturn(Optional.empty());

        ImportQuestionsRequest request = new ImportQuestionsRequest(
                subjectId, QuestionType.DSA, "[{\"title\":\"Q1\"}]");

        assertThatThrownBy(() -> service.process(request, true))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Subject folder not found");

        verifyNoInteractions(rowWriter, auditService);
    }

    @Test
    @DisplayName("dry run never calls rowWriter and does not audit")
    void dryRunNeverCallsWriterOrAudit() {
        when(subjectRepository.findById(subjectId)).thenReturn(Optional.of(new Subject()));

        String payload = """
                [
                  {"title":"Q1","description":"D1","difficulty":"EASY","points":5},
                  {"title":"Q2","description":"D2","difficulty":"MEDIUM","points":10}
                ]
                """;
        ImportQuestionsRequest request = new ImportQuestionsRequest(subjectId, QuestionType.DSA, payload);

        QuestionImportResponse response = service.process(request, true);

        assertThat(response.dryRun()).isTrue();
        assertThat(response.result().totalRows()).isEqualTo(2);
        assertThat(response.result().succeededCount()).isEqualTo(2);
        assertThat(response.result().failedCount()).isEqualTo(0);
        assertThat(response.rows()).hasSize(2);
        assertThat(response.rows().get(0).valid()).isTrue();
        assertThat(response.rows().get(1).valid()).isTrue();

        verifyNoInteractions(rowWriter);
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("real import calls rowWriter only for valid rows and logs audit")
    void realImportCallsWriterForValidRowsAndAudits() {
        when(subjectRepository.findById(subjectId)).thenReturn(Optional.of(new Subject()));

        String payload = """
                [
                  {"title":"Valid Q","description":"D1","difficulty":"EASY","points":5},
                  {"title":"","description":"Missing title","difficulty":"EASY","points":5}
                ]
                """;
        ImportQuestionsRequest request = new ImportQuestionsRequest(subjectId, QuestionType.DSA, payload);

        QuestionImportResponse response = service.process(request, false);

        assertThat(response.dryRun()).isFalse();
        assertThat(response.result().totalRows()).isEqualTo(2);
        assertThat(response.result().succeededCount()).isEqualTo(1);
        assertThat(response.result().failedCount()).isEqualTo(1);

        verify(rowWriter, times(1)).write(any(CreateLibraryQuestionRequest.class));
        verify(auditService, times(1)).log(
                eq(userId),
                eq("LIBRARY_QUESTIONS_IMPORTED"),
                eq("SUBJECT"),
                eq(subjectId),
                contains("imported=1, failed=1")
        );
    }
}
