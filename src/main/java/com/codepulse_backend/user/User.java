package com.codepulse_backend.user;

import com.codepulse_backend.common.entity.BaseEntity;
import com.codepulse_backend.common.enums.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.*;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Role role;

    @Column(name = "roll_number", unique = true, length = 50)
    private String rollNumber;

    @Column(name = "academic_year")
    private Integer year;

    @Column(length = 10)
    private String branch;

    @Column(length = 5)
    private String division;

    @Column(length = 5)
    private String batch;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;
}