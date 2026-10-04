package com.codepulse_backend.user.repository;

import com.codepulse_backend.common.enums.Role;
import com.codepulse_backend.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** Login and duplicate checks ignore case: "Rahul.P@x.edu" and "rahul.p@x.edu" are one account. */
    Optional<User> findFirstByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    long countByRoleAndIsActiveTrue(Role role);

    Optional<User> findByRollNumber(String rollNumber);

    boolean existsByRollNumber(String rollNumber);
}