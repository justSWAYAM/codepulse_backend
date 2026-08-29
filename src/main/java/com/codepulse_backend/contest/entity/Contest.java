package com.codepulse_backend.contest.entity;

import com.codepulse_backend.common.converter.StringListConverter;
import com.codepulse_backend.common.entity.BaseEntity;
import com.codepulse_backend.common.enums.ContestStatus;
import com.codepulse_backend.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "contests")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Contest extends BaseEntity {

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "allowed_languages", nullable = false)
    @Convert(converter = StringListConverter.class)
    @Builder.Default
    private List<String> allowedLanguages = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private ContestStatus status = ContestStatus.DRAFT;

}
