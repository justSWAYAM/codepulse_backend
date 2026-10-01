CREATE TABLE submissions (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    created_by      UUID,
    updated_by      UUID,

    session_id      UUID        NOT NULL REFERENCES assessment_sessions(id),
    question_id     UUID        NOT NULL REFERENCES questions(id),
    candidate_id    UUID        NOT NULL REFERENCES users(id),

    language        VARCHAR(30) NOT NULL,
    source_code     TEXT        NOT NULL,
    submission_type VARCHAR(10) NOT NULL CHECK (submission_type IN ('RUN','SUBMIT')),
    status          VARCHAR(30) NOT NULL,
    score           NUMERIC(8,2),
    passed_count    INT,
    total_count     INT,
    compile_output  TEXT,

    submitted_at    TIMESTAMPTZ NOT NULL,
    evaluated_at    TIMESTAMPTZ,

    queued_at       TIMESTAMPTZ,
    queue_attempts  INT         NOT NULL DEFAULT 0,

    version         BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX idx_submissions_session_question
    ON submissions (session_id, question_id, submission_type, submitted_at DESC);

CREATE INDEX idx_submissions_candidate_question
    ON submissions (candidate_id, question_id, submitted_at DESC);

CREATE INDEX idx_submissions_question
    ON submissions (question_id);

CREATE INDEX idx_submissions_pending
    ON submissions (queued_at)
    WHERE status = 'PENDING';


CREATE TABLE submission_test_case_results (
    id                 UUID PRIMARY KEY,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    created_by         UUID,
    updated_by         UUID,

    submission_id      UUID        NOT NULL
        REFERENCES submissions(id) ON DELETE CASCADE,

    test_case_id       UUID        NOT NULL
        REFERENCES test_cases(id),

    status             VARCHAR(30) NOT NULL,
    actual_output      TEXT,
    stderr             TEXT,
    execution_time_ms  NUMERIC(10,2),
    memory_used_kb     INT,

    weight             INT         NOT NULL,
    is_sample          BOOLEAN     NOT NULL,

    CONSTRAINT uq_result_submission_testcase
        UNIQUE (submission_id, test_case_id)
);

CREATE INDEX idx_results_submission
    ON submission_test_case_results (submission_id);