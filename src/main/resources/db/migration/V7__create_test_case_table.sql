
CREATE TABLE test_cases (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    question_id     UUID        NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
    input           TEXT        NOT NULL,
    expected_output TEXT        NOT NULL,
    is_sample       BOOLEAN     NOT NULL DEFAULT false,
    weight          INT         NOT NULL DEFAULT 0 CHECK (weight >= 0),
    order_index     INT         NOT NULL DEFAULT 0,
    created_by      UUID        NOT NULL REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      UUID        REFERENCES users(id)
);

-- Fast retrieval of all test cases for a question
CREATE INDEX idx_test_cases_question_id
    ON test_cases(question_id, order_index ASC);

-- Candidate-facing query: sample cases only
CREATE INDEX idx_test_cases_question_sample
    ON test_cases(question_id)
    WHERE is_sample = true;