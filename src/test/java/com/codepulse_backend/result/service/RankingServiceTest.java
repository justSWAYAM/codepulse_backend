package com.codepulse_backend.result.service;

import com.codepulse_backend.common.enums.ResultStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RankingServiceTest {

    private final RankingService ranking = new RankingService();

    private static RankingService.RankInput entry(UUID id, String score, Long seconds) {
        return new RankingService.RankInput(id, ResultStatus.SCORED, new BigDecimal(score), seconds);
    }

    @Test
    void higherScoreRanksFirst() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();

        Map<UUID, Integer> ranks = ranking.rank(List.of(entry(a, "50", 100L), entry(b, "90", 900L), entry(c, "70", 10L)));

        assertEquals(1, ranks.get(b));
        assertEquals(2, ranks.get(c));
        assertEquals(3, ranks.get(a));
    }

    @Test
    void equalScoreFasterTimeWins() {
        UUID slow = UUID.randomUUID(), fast = UUID.randomUUID();

        Map<UUID, Integer> ranks = ranking.rank(List.of(entry(slow, "80", 3000L), entry(fast, "80", 1200L)));

        assertEquals(1, ranks.get(fast));
        assertEquals(2, ranks.get(slow));
    }

    @Test
    void fullTiesShareARankAndTheNextRankSkips() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();

        Map<UUID, Integer> ranks = ranking.rank(List.of(
                entry(a, "100", 600L),
                entry(b, "80", 900L),
                entry(c, "80.00", 900L),   // same score written with a different scale
                entry(d, "10", 60L)));

        assertEquals(1, ranks.get(a));
        assertEquals(2, ranks.get(b));
        assertEquals(2, ranks.get(c));
        assertEquals(4, ranks.get(d));
    }

    @Test
    void absentCandidatesAreUnranked() {
        UUID present = UUID.randomUUID(), absent = UUID.randomUUID();

        Map<UUID, Integer> ranks = ranking.rank(List.of(
                entry(present, "0", null),
                new RankingService.RankInput(absent, ResultStatus.ABSENT, BigDecimal.ZERO, null)));

        assertEquals(1, ranks.get(present));
        assertFalse(ranks.containsKey(absent));
    }

    @Test
    void missingTimeSortsAfterAKnownTime() {
        UUID noTime = UUID.randomUUID(), timed = UUID.randomUUID();

        Map<UUID, Integer> ranks = ranking.rank(List.of(entry(noTime, "0", null), entry(timed, "0", 5L)));

        assertEquals(1, ranks.get(timed));
        assertEquals(2, ranks.get(noTime));
    }

    @Test
    void needsReviewIsRankedProvisionally() {
        UUID flagged = UUID.randomUUID();

        Map<UUID, Integer> ranks = ranking.rank(List.of(
                new RankingService.RankInput(flagged, ResultStatus.NEEDS_REVIEW, new BigDecimal("40"), 100L)));

        assertEquals(1, ranks.get(flagged));
    }
}
