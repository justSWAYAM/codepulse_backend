-- Module 9: results, manual evaluations and the per-contest publish state.

-- Publish state is per contest, so a contest can never be half-published
ALTER TABLE contests
    ADD COLUMN results_published_at TIMESTAMPTZ,
    ADD COLUMN results_published_by UUID REFERENCES users(id);

CREATE TABLE results (
    id                  UUID PRIMARY KEY,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    created_by          UUID,
    updated_by          UUID,

    contest_id          UUID          NOT NULL REFERENCES contests(id),
    candidate_id        UUID          NOT NULL REFERENCES users(id),
    session_id          UUID          REFERENCES assessment_sessions(id),

    status              VARCHAR(20)   NOT NULL CHECK (status IN ('SCORED','NEEDS_REVIEW','ABSENT')),
    review_reasons      TEXT,
    auto_score          NUMERIC(10,2) NOT NULL DEFAULT 0,
    total_score         NUMERIC(10,2) NOT NULL DEFAULT 0,
    max_score           NUMERIC(10,2) NOT NULL DEFAULT 0,
    adjusted            BOOLEAN       NOT NULL DEFAULT FALSE,
    time_taken_seconds  BIGINT,
    rank                INT,
    computed_at         TIMESTAMPTZ   NOT NULL,

    version             BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT uq_results_contest_candidate UNIQUE (contest_id, candidate_id)
);

CREATE INDEX idx_results_contest_rank ON results (contest_id, rank);

CREATE TABLE manual_evaluations (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    created_by      UUID,
    updated_by      UUID,

    submission_id   UUID         NOT NULL REFERENCES submissions(id),
    session_id      UUID         NOT NULL REFERENCES assessment_sessions(id),
    question_id     UUID         NOT NULL REFERENCES questions(id),
    evaluator_id    UUID         NOT NULL REFERENCES users(id),

    -- NULL = reverted to the automatic score
    adjusted_score  NUMERIC(8,2) CHECK (adjusted_score IS NULL OR adjusted_score >= 0),
    comments        TEXT         NOT NULL,
    evaluated_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_manual_eval_session_question
    ON manual_evaluations (session_id, question_id, evaluated_at DESC);

CREATE TABLE result_question_scores (
    id                     UUID PRIMARY KEY,
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    created_by             UUID,
    updated_by             UUID,

    result_id              UUID         NOT NULL REFERENCES results(id) ON DELETE CASCADE,
    question_id            UUID         NOT NULL REFERENCES questions(id),
    counted_submission_id  UUID         REFERENCES submissions(id),
    manual_evaluation_id   UUID         REFERENCES manual_evaluations(id),

    max_points             INT          NOT NULL,
    auto_score             NUMERIC(8,2) NOT NULL DEFAULT 0,
    final_score            NUMERIC(8,2) NOT NULL DEFAULT 0,
    override_outdated      BOOLEAN      NOT NULL DEFAULT FALSE,

    CONSTRAINT uq_result_question UNIQUE (result_id, question_id)
);

CREATE INDEX idx_result_question_scores_result ON result_question_scores (result_id);
