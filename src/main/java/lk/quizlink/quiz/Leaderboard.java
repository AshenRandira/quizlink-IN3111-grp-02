package lk.quizlink.quiz;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lk.quizlink.common.model.LeaderboardEntry;
import lk.quizlink.common.model.Participant;

/** Builds ranked leaderboards. Equal scores share a rank (1, 2, 2, 4). */
final class Leaderboard {
    private Leaderboard() {
    }

    static List<LeaderboardEntry> rank(Collection<Participant> participants, Map<String, Integer> scores) {
        List<Participant> ordered = participants.stream()
                .sorted(Comparator.comparing((Participant p) -> scoreOf(p, scores), Comparator.reverseOrder())
                        .thenComparing(Participant::displayName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Participant::id))
                .toList();

        List<LeaderboardEntry> entries = new ArrayList<>(ordered.size());
        int rank = 0;
        Integer previousScore = null;
        for (int position = 0; position < ordered.size(); position++) {
            Participant participant = ordered.get(position);
            int score = scoreOf(participant, scores);
            if (previousScore == null || score != previousScore) {
                rank = position + 1;
                previousScore = score;
            }
            entries.add(new LeaderboardEntry(participant.id(), participant.displayName(), score, rank));
        }
        return List.copyOf(entries);
    }

    private static int scoreOf(Participant participant, Map<String, Integer> scores) {
        return scores.getOrDefault(participant.id(), 0);
    }
}
