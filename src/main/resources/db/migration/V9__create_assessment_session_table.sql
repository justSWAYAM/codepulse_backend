CREATE TABLE assessment_sessions (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    contest_id    UUID         NOT NULL REFERENCES contests(id),
    candidate_id  UUID         NOT NULL REFERENCES users(id),
    started_at    TIMESTAMPTZ  NOT NULL,
    ends_at       TIMESTAMPTZ  NOT NULL,
    status        VARCHAR(50)  NOT NULL DEFAULT 'IN_PROGRESS',
    submitted_at  TIMESTAMPTZ,
    created_by    UUID         NOT NULL REFERENCES users(id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by    UUID         REFERENCES users(id),

    UNIQUE (contest_id, candidate_id),
    CHECK (ends_at > started_at),
    CHECK (status IN ('IN_PROGRESS', 'SUBMITTED', 'AUTO_SUBMITTED', 'EXPIRED'))
);

CREATE INDEX idx_sessions_expiry
    ON assessment_sessions(ends_at)
    WHERE status = 'IN_PROGRESS';

CREATE INDEX idx_sessions_contest_id
    ON assessment_sessions(contest_id);