package com.codepulse_backend.analytics.service;

import com.codepulse_backend.analytics.dto.ContestAnalyticsResponse.Bucket;
import com.codepulse_backend.common.enums.Difficulty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure analytics rules (plan 2.4, 2.5, 2.7). No repositories, so every threshold
 * and edge case is unit-testable. Thresholds are constants, not configuration:
 * two settings would give two answers for the same contest.
 */
@Component
public class AnalyticsCalculator {

    public static final int BUCKETS = 10;
    public static final int MIN_SAMPLE = 5;
    public static final BigDecimal EASY_AT = new BigDecimal("0.70");
    public static final BigDecimal MEDIUM_AT = new BigDecimal("0.40");
    public static final BigDecimal SUSPICIOUS_PEER_PASS_RATE = new BigDecimal("0.50");

    /** Always all 10 buckets (1..10), so charts never shift. */
    public List<Bucket> fillBuckets(Map<Integer, Long> countsByBucket) {
        List<Bucket> buckets = new ArrayList<>(BUCKETS);
        for (int i = 1; i <= BUCKETS; i++) {
            buckets.add(new Bucket(
                    i,
                    BigDecimal.valueOf(i - 1, 1),            // 0.0, 0.1, …
                    BigDecimal.valueOf(i, 1),                // 0.1, 0.2, …
                    countsByBucket.getOrDefault(i, 0L)
            ));
        }
        return buckets;
    }

    /** Null = not enough data (fewer than MIN_SAMPLE attempted, or a 0-point question). */
    public Difficulty observedDifficulty(BigDecimal averageScore, int points, long attempted) {
        if (attempted < MIN_SAMPLE || points <= 0 || averageScore == null) {
            return null;
        }
        BigDecimal averageRatio = averageScore.divide(BigDecimal.valueOf(points), 6, RoundingMode.HALF_UP);
        if (averageRatio.compareTo(EASY_AT) >= 0) return Difficulty.EASY;
        if (averageRatio.compareTo(MEDIUM_AT) >= 0) return Difficulty.MEDIUM;
        return Difficulty.HARD;
    }

    /**
     * Nobody passed this test case, while most candidates passed another case of the
     * same question: usually a wrong expected output.
     */
    public boolean suspicious(long evaluated, long passed, List<BigDecimal> peerPassRates) {
        if (evaluated < MIN_SAMPLE || passed > 0) {
            return false;
        }
        return peerPassRates.stream().anyMatch(rate -> rate.compareTo(SUSPICIOUS_PEER_PASS_RATE) > 0);
    }

    /** part ÷ whole with 4 decimals; 0 when whole is 0. */
    public BigDecimal ratio(long part, long whole) {
        if (whole <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(part).divide(BigDecimal.valueOf(whole), 4, RoundingMode.HALF_UP);
    }

    public BigDecimal ratio(BigDecimal part, BigDecimal whole) {
        if (part == null || whole == null || whole.signum() <= 0) {
            return null;
        }
        return part.divide(whole, 4, RoundingMode.HALF_UP);
    }

    public BigDecimal round2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal round2(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
