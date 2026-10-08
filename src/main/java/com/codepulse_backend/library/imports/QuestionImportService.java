package com.codepulse_backend.library.imports;

import com.codepulse_backend.common.audit.AuditService;
import com.codepulse_backend.common.bulkimport.BulkImportService;
import com.codepulse_backend.common.bulkimport.BulkRun;
import com.codepulse_backend.common.bulkimport.RowOutcome;
import com.codepulse_backend.common.bulkimport.RowRejectedException;
import com.codepulse_backend.common.enums.QuestionType;
import com.codepulse_backend.common.exception.ErrorCode;
import com.codepulse_backend.common.exception.ResourceNotFoundException;
import com.codepulse_backend.library.imports.dto.ImportQuestionItem;
import com.codepulse_backend.library.imports.dto.ImportQuestionsRequest;
import com.codepulse_backend.library.imports.dto.ImportRowPreview;
import com.codepulse_backend.library.imports.dto.QuestionImportResponse;
import com.codepulse_backend.question.dto.CreateLibraryQuestionRequest;
import com.codepulse_backend.question.repository.SubjectRepository;
import com.codepulse_backend.user.User;
import com.codepulse_backend.user.dto.BulkImportResult;
import com.codepulse_backend.user.repository.UserRepository;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class QuestionImportService {

    private static final Logger log = LoggerFactory.getLogger(QuestionImportService.class);

    private final SubjectRepository subjectRepository;
    private final UserRepository userRepository;
    private final QuestionImportParser parser;
    private final QuestionImportValidator validator;
    private final QuestionImportMapper mapper;
    private final BulkImportService bulkImportService;
    private final QuestionImportRowWriter rowWriter;
    private final Validator beanValidator;
    private final AuditService auditService;

    public QuestionImportService(SubjectRepository subjectRepository,
                                 UserRepository userRepository,
                                 QuestionImportParser parser,
                                 QuestionImportValidator validator,
                                 QuestionImportMapper mapper,
                                 BulkImportService bulkImportService,
                                 QuestionImportRowWriter rowWriter,
                                 Validator beanValidator,
                                 AuditService auditService) {
        this.subjectRepository = subjectRepository;
        this.userRepository = userRepository;
        this.parser = parser;
        this.validator = validator;
        this.mapper = mapper;
        this.bulkImportService = bulkImportService;
        this.rowWriter = rowWriter;
        this.beanValidator = beanValidator;
        this.auditService = auditService;
    }

    // Deliberately NOT @Transactional: each row commits on its own (D3).
    public QuestionImportResponse process(ImportQuestionsRequest request, boolean dryRun) {
        subjectRepository.findById(request.subjectId())
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.SUBJECT_NOT_FOUND,
                        "Subject folder not found with id: " + request.subjectId()));

        List<ParsedRow> parsed = parser.parse(request.payload());
        QuestionType type = request.type();

        BulkRun<CreateLibraryQuestionRequest> run = bulkImportService.run(
                parsed,
                row -> toRequest(type, request.subjectId(), row),          // parse stage (+ item rules)
                this::beanValidate,                                        // validate stage
                dryRun ? null : rowWriter::write);                         // write stage

        List<ImportRowPreview> previews = buildPreviews(type, parsed, run);
        BulkImportResult result = toBulkImportResult(run, dryRun);

        if (!dryRun) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            UUID actorId = null;
            if (auth != null && auth.getName() != null) {
                actorId = userRepository.findByEmail(auth.getName()).map(User::getId).orElse(null);
            }
            auditService.log(actorId, "LIBRARY_QUESTIONS_IMPORTED", "SUBJECT", request.subjectId(),
                    "type=" + type + ", imported=" + run.writtenCount() + ", failed=" + (run.total() - run.writtenCount()));
        }
        log.info("question-import subject={} type={} dryRun={} total={} valid={} invalid={} written={}",
                request.subjectId(), type, dryRun, run.total(), run.validCount(), run.invalidCount(), run.writtenCount());
        // never log the payload: it can be large and contains answer keys

        return new QuestionImportResponse(dryRun, result, previews);
    }

    private CreateLibraryQuestionRequest toRequest(QuestionType type, UUID subjectId, ParsedRow row) {
        if (row.parseError() != null) {
            throw new RowRejectedException(row.parseError());
        }
        List<String> problems = validator.validateItem(type, row.item());
        if (!problems.isEmpty()) {
            throw new RowRejectedException(problems);
        }
        return mapper.toRequest(type, subjectId, row.item());
    }

    private List<String> beanValidate(CreateLibraryQuestionRequest req) {
        return beanValidator.validate(req).stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .sorted()
                .toList();
    }

    private List<ImportRowPreview> buildPreviews(QuestionType type, List<ParsedRow> parsed, BulkRun<CreateLibraryQuestionRequest> run) {
        List<ImportRowPreview> out = new ArrayList<>(parsed.size());
        for (int i = 0; i < parsed.size(); i++) {
            ParsedRow row = parsed.get(i);
            RowOutcome<CreateLibraryQuestionRequest> o = run.rows().get(i);     // same order, same length
            ImportQuestionItem it = row.item();
            out.add(new ImportRowPreview(
                    o.rowNumber(),
                    o.valid(),
                    it == null ? null : it.title(),
                    it == null ? null : it.difficulty(),
                    it == null ? null : it.points(),
                    it == null ? "" : mapper.descriptionPreview(it.description()),
                    it == null ? "" : mapper.detail(type, it),
                    o.errors()));
        }
        return out;
    }

    private BulkImportResult toBulkImportResult(BulkRun<CreateLibraryQuestionRequest> run, boolean dryRun) {
        int total = run.total();
        int success = dryRun ? (int) run.validCount() : (int) run.writtenCount();
        int failure = total - success;

        List<BulkImportResult.RowError> errors = run.rows().stream()
                .filter(o -> !o.errors().isEmpty())
                .map(o -> new BulkImportResult.RowError(o.rowNumber(), String.join("; ", o.errors())))
                .toList();

        return new BulkImportResult(total, success, failure, errors);
    }
}
