CREATE TABLE contests (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    title               VARCHAR(255) NOT NULL,
    description         TEXT,
    start_time          TIMESTAMPTZ  NOT NULL,
    end_time            TIMESTAMPTZ  NOT NULL,
    duration_minutes    INT          NOT NULL,
    allowed_languages   TEXT         NOT NULL DEFAULT '[]',
    status              VARCHAR(50)  NOT NULL DEFAULT 'DRAFT',
    created_by          UUID         NOT NULL REFERENCES users(id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by          UUID
);

CREATE TABLE contest_candidates (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    contest_id   UUID         NOT NULL REFERENCES contests(id) ON DELETE CASCADE,
    candidate_id UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status       VARCHAR(50)  NOT NULL DEFAULT 'INVITED',
    invited_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_contest_candidate UNIQUE (contest_id, candidate_id)
);

CREATE INDEX idx_contests_status      ON contests(status);
CREATE INDEX idx_contests_created_by  ON contests(created_by);
CREATE INDEX idx_cc_contest_id        ON contest_candidates(contest_id);
CREATE INDEX idx_cc_candidate_id      ON contest_candidates(candidate_id);
