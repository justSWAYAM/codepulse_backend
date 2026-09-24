CREATE TABLE questions (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    contest_id        UUID         NOT NULL REFERENCES contests(id) ON DELETE CASCADE,
    title             VARCHAR(255) NOT NULL,
    description       TEXT         NOT NULL,
    difficulty        VARCHAR(20)  NOT NULL CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD')),
    points            INT          NOT NULL DEFAULT 100,
    time_limit_ms     INT          NOT NULL DEFAULT 2000,
    memory_limit_kb   INT          NOT NULL DEFAULT 262144,
    order_index       INT          NOT NULL DEFAULT 0,
    created_by        UUID         NOT NULL REFERENCES users(id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by        UUID         REFERENCES users(id)
);

-- Prevents two questions from occupying the same position in a contest
CREATE UNIQUE INDEX ux_questions_contest_order ON questions(contest_id, order_index);

-- Fast lookup of all questions in a contest, sorted
CREATE INDEX idx_questions_contest_id ON questions(contest_id, order_index ASC);
