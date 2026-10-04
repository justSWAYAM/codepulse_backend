package com.codepulse_backend.common.enums;

/**
 * Discriminator for the unified {@code questions} table.
 *
 * <ul>
 *   <li>DSA  – coding problem judged by Judge0 (stdin/stdout).</li>
 *   <li>SQL  – SQL query judged against a schema + expected JSON result.</li>
 *   <li>MCQ  – multiple-choice; options live in {@code mcq_options}.</li>
 *   <li>THEORY – open-ended text answer; manually evaluated by Evaluator.</li>
 * </ul>
 *
 * Stored as {@code VARCHAR(20)} in the DB (see {@code V14__question_library.sql}).
 */
public enum QuestionType {
    DSA,
    SQL,
    MCQ,
    THEORY
}
