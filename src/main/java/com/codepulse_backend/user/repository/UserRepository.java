package com.codepulse_backend.user.repository;

import com.codepulse_backend.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByRollNumber(String rollNumber);

    boolean existsByRollNumber(String rollNumber);
}
