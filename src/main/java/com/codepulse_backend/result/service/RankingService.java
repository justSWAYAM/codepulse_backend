package com.codepulse_backend.result.service;

import com.codepulse_backend.common.enums.ResultStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The only place the ranking rule lives (plan 2.7):
 * total score DESC, then time taken from the candidate's own start ASC (NULL last),
 * competition ranking ("1, 2, 2, 4"), ABSENT unranked.
 */
@Component
public class RankingService {

    private static final Comparator<RankInput> ORDER = Comparator
            .comparing(RankInput::totalScore, Comparator.reverseOrder())
            .thenComparing(RankInput::timeTakenSeconds, Comparator.nullsLast(Comparator.naturalOrder()));

    /** resultId → rank; ABSENT results are left out. */
    public Map<UUID, Integer> rank(List<RankInput> results) {
        List<RankInput> ranked = results.stream()
                .filter(r -> r.status() != ResultStatus.ABSENT)
                .sorted(ORDER)
                .toList();

        Map<UUID, Integer> ranks = new HashMap<>();
        RankInput previous = null;
        int currentRank = 0;

        for (int i = 0; i < ranked.size(); i++) {
            RankInput entry = ranked.get(i);
            if (previous == null || !tied(previous, entry)) {
                currentRank = i + 1;
            }
            ranks.put(entry.resultId(), currentRank);
            previous = entry;
        }
        return ranks;
    }

    // compareTo, not equals: 10.0 and 10.00 are the same score
    private static boolean tied(RankInput a, RankInput b) {
        return a.totalScore().compareTo(b.totalScore()) == 0
                && Objects.equals(a.timeTakenSeconds(), b.timeTakenSeconds());
    }

    public record RankInput(UUID resultId, ResultStatus status, BigDecimal totalScore, Long timeTakenSeconds) {}
}
