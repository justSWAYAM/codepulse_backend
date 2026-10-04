package com.codepulse_backend.question.repository;

import com.codepulse_backend.question.entity.McqOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Data access for MCQ options.
 * Options are always fetched in display order (order_index ASC).
 *
 * SECURITY: callers must use the appropriate DTO projection that omits or includes
 * {@code isCorrect} depending on whether the requester is Admin/Evaluator or Candidate.
 */
@Repository
public interface McqOptionRepository extends JpaRepository<McqOption, UUID> {

    /**
     * All options for a question, in display order.
     * Used by admin views and the deep-copy service.
     */
    List<McqOption> findByQuestionIdOrderByOrderIndexAsc(UUID questionId);

    /**
     * Highest order_index for a question (0 if empty).
     * Used when appending a new option to avoid gaps after deletes.
     */
    default int findMaxOrderIndexByQuestionId(UUID questionId) {
        return findByQuestionIdOrderByOrderIndexAsc(questionId)
                .stream()
                .mapToInt(McqOption::getOrderIndex)
                .max()
                .orElse(0);
    }
}
