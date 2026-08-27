package com.codepulse_backend.user.repository;

import com.codepulse_backend.common.enums.Role;
import com.codepulse_backend.user.User;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;

public class UserSpecification {

    public static Specification<User> withFilters(Role role, Boolean isActive, String search) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (role != null) {
                predicates.add(cb.equal(root.get("role"), role));
            }

            if (isActive != null) {
                predicates.add(cb.equal(root.get("isActive"), isActive));
            }

            if (search != null && !search.trim().isEmpty()) {
                String searchPattern = "%" + search.trim().toLowerCase() + "%";
                Predicate emailLike = cb.like(cb.lower(root.get("email")), searchPattern);
                Predicate nameLike = cb.like(cb.lower(root.get("fullName")), searchPattern);
                Predicate rollLike = cb.like(cb.lower(root.get("rollNumber")), searchPattern); // Added since it's in your entity

                predicates.add(cb.or(emailLike, nameLike, rollLike));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}