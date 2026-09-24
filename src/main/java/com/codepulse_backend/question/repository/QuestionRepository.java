package com.codepulse_backend.question.repository;

import com.codepulse_backend.question.entity.Question;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface QuestionRepository extends JpaRepository<Question, UUID> {

    /**
     * All questions for a contest, sorted by their display order.
     */
    List<Question> findAllByContestIdOrderByOrderIndex(UUID contestId);

    /**
     * Verifies a question belongs to a specific contest — used as an ownership check
     * in service methods to prevent cross-contest question mutations.
     */
    boolean existsByIdAndContestId(UUID id, UUID contestId);

    /**
     * Count of questions in a contest — used to compute the next order_index
     * when a new question is appended.
     */
    long countByContestId(UUID contestId);

    /**
     * Fetch only the questions being reordered, in one query.
     * Used by the reorder endpoint to validate all IDs belong to the contest.
     */
    @Query("SELECT q FROM Question q WHERE q.id IN :ids AND q.contestId = :contestId")
    List<Question> findAllByIdInAndContestId(@Param("ids") List<UUID> ids,
                                              @Param("contestId") UUID contestId);
}
