-- Module 8: at most one PENDING SUBMIT per (session, question).
-- The service checks this too, but two concurrent requests could both pass that check.

-- Older duplicates (from before this index) keep only the newest PENDING row.
UPDATE submissions s
SET status = 'SYSTEM_ERROR',
    evaluated_at = now()
WHERE s.submission_type = 'SUBMIT'
  AND s.status = 'PENDING'
  AND EXISTS (
      SELECT 1
      FROM submissions o
      WHERE o.session_id = s.session_id
        AND o.question_id = s.question_id
        AND o.submission_type = 'SUBMIT'
        AND o.status = 'PENDING'
        AND (o.submitted_at > s.submitted_at
             OR (o.submitted_at = s.submitted_at AND o.id > s.id))
  );

CREATE UNIQUE INDEX uq_submissions_one_pending_submit
    ON submissions (session_id, question_id)
    WHERE submission_type = 'SUBMIT' AND status = 'PENDING';
