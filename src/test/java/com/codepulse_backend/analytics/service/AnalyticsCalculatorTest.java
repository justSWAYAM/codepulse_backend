package com.codepulse_backend.analytics.service;

import com.codepulse_backend.analytics.dto.ContestAnalyticsResponse.Bucket;
import com.codepulse_backend.common.enums.Difficulty;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnalyticsCalculatorTest {

    private final AnalyticsCalculator calc = new AnalyticsCalculator();

    @Test
    void alwaysTenBucketsWithCountsInPlace() {
        List<Bucket> empty = calc.fillBuckets(Map.of());
        assertEquals(10, empty.size());
        assertTrue(empty.stream().allMatch(b -> b.count() == 0));

        List<Bucket> filled = calc.fillBuckets(Map.of(1, 2L, 10, 3L));
        assertEquals(2, filled.get(0).count());
        assertEquals(3, filled.get(9).count());
        assertEquals(new BigDecimal("0.9"), filled.get(9).fromRatio());
        assertEquals(new BigDecimal("1.0"), filled.get(9).toRatio());
    }

    @Test
    void difficultyThresholds() {
        assertEquals(Difficulty.EASY, calc.observedDifficulty(new BigDecimal("70"), 100, 5));
        assertEquals(Difficulty.MEDIUM, calc.observedDifficulty(new BigDecimal("69.99"), 100, 5));
        assertEquals(Difficulty.MEDIUM, calc.observedDifficulty(new BigDecimal("40"), 100, 5));
        assertEquals(Difficulty.HARD, calc.observedDifficulty(new BigDecimal("39.99"), 100, 5));
    }

    @Test
    void notEnoughDataIsNull() {
        assertNull(calc.observedDifficulty(new BigDecimal("90"), 100, 4));
        assertNull(calc.observedDifficulty(new BigDecimal("0"), 0, 10));
        assertNull(calc.observedDifficulty(null, 100, 10));
    }

    @Test
    void suspiciousNeedsZeroPassesEnoughSamplesAndAPeerMostPassed() {
        assertTrue(calc.suspicious(5, 0, List.of(new BigDecimal("0.6000"))));
        assertFalse(calc.suspicious(4, 0, List.of(new BigDecimal("0.6000"))));      // too few
        assertFalse(calc.suspicious(5, 1, List.of(new BigDecimal("0.6000"))));      // someone passed
        assertFalse(calc.suspicious(5, 0, List.of(new BigDecimal("0.5000"))));      // no peer above 50 %
        assertFalse(calc.suspicious(5, 0, List.of()));                              // only test case
    }

    @Test
    void ratioAndRounding() {
        assertEquals(new BigDecimal("0.0000"), calc.ratio(3, 0));
        assertEquals(new BigDecimal("0.3333"), calc.ratio(1, 3));
        assertEquals(new BigDecimal("1.5000"), calc.ratio(3, 2));
        assertNull(calc.ratio(BigDecimal.ONE, BigDecimal.ZERO));
        assertEquals(new BigDecimal("2.35"), calc.round2(new BigDecimal("2.345")));
        assertEquals(new BigDecimal("22.45"), calc.round2(22.4499));
        assertNull(calc.round2((Double) null));
    }
}
