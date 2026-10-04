package com.codepulse_backend.question.repository;

import com.codepulse_backend.question.entity.Subject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Data access for subject folders.
 * Name uniqueness is enforced both by the DB UNIQUE constraint and the
 * service-layer duplicate check (which also normalises case).
 */
@Repository
public interface SubjectRepository extends JpaRepository<Subject, UUID> {

    /**
     * Case-insensitive existence check used before creating a new subject.
     * Prevents "DSA" and "dsa" from coexisting.
     */
    boolean existsByNameIgnoreCase(String name);
}
