-- Module 10: indexes for the analytics aggregates. No tables, no data.

-- Per-test-case pass rates
CREATE INDEX idx_stcr_test_case ON submission_test_case_results (test_case_id);

-- Activity aggregates: SUBMIT/RUN counts and verdicts per session
CREATE INDEX idx_submissions_session_type_status ON submissions (session_id, submission_type, status);

-- Overview aggregates filter results by contest and status
CREATE INDEX idx_results_contest_status ON results (contest_id, status);
