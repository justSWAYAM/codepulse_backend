-- Module 5A: Question Library
-- Creates subjects table; alters questions table to support library storage
-- and deep-copy referencing. All changes are backwards-compatible with
-- existing DSA-in-contest flow (contest_id stays NOT NULL for existing rows
-- via the default; new library questions supply NULL explicitly).

-- ─── subjects ─────────────────────────────────────────────────────────────────
CREATE TABLE subjects (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(100) NOT NULL UNIQUE,
    created_by UUID         NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ─── questions alterations ────────────────────────────────────────────────────

-- 1. question_type discriminator (defaulted to 'DSA' so all existing rows stay valid)
ALTER TABLE questions
    ADD COLUMN IF NOT EXISTS question_type VARCHAR(20) NOT NULL DEFAULT 'DSA'
        CHECK (question_type IN ('DSA', 'SQL', 'MCQ', 'THEORY'));

-- 2. SQL-specific fields
ALTER TABLE questions
    ADD COLUMN IF NOT EXISTS schema_sql    TEXT,
    ADD COLUMN IF NOT EXISTS order_matters BOOLEAN;

-- 3. MCQ / THEORY
ALTER TABLE questions
    ADD COLUMN IF NOT EXISTS model_answer  TEXT;

-- 4. Library linkage: subject folder
ALTER TABLE questions
    ADD COLUMN IF NOT EXISTS subject_id UUID REFERENCES subjects(id) ON DELETE SET NULL;

-- 5. Deep-copy lineage: points back to the library question this was copied from.
--    ON DELETE SET NULL: deleting a library question never affects contest copies.
ALTER TABLE questions
    ADD COLUMN IF NOT EXISTS source_question_id UUID REFERENCES questions(id) ON DELETE SET NULL;

-- 6. Make contest_id nullable so library questions (contest_id IS NULL) are valid.
ALTER TABLE questions
    ALTER COLUMN contest_id DROP NOT NULL;

-- ─── MCQ options ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS mcq_options (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    question_id  UUID         NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
    text         TEXT         NOT NULL,
    is_correct   BOOLEAN      NOT NULL DEFAULT false,
    order_index  INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ─── indexes ──────────────────────────────────────────────────────────────────

-- Library-question scan (contest_id IS NULL)
CREATE INDEX IF NOT EXISTS idx_questions_library
    ON questions(subject_id, question_type)
    WHERE contest_id IS NULL;

-- Fast subject lookup for library browsing
CREATE INDEX IF NOT EXISTS idx_questions_subject_id
    ON questions(subject_id);

-- Deep-copy duplicate check
CREATE INDEX IF NOT EXISTS idx_questions_source_id
    ON questions(source_question_id)
    WHERE source_question_id IS NOT NULL;

-- MCQ option lookup by question
CREATE INDEX IF NOT EXISTS idx_mcq_options_question_id
    ON mcq_options(question_id, order_index ASC);

-- NOTE: The existing ux_questions_contest_order unique index
-- (contest_id, order_index) remains valid because library questions all have
-- contest_id = NULL, and PostgreSQL does NOT enforce uniqueness across NULLs
-- in a multi-column unique index. No migration change needed there.
