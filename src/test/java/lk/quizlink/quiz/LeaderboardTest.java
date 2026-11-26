package lk.quizlink.quiz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import lk.quizlink.common.model.LeaderboardEntry;
import lk.quizlink.common.model.Participant;
import org.junit.jupiter.api.Test;

class LeaderboardTest {

    @Test
    void ordersByScoreDescendingAndSharesRanksForTies() {
        List<Participant> participants = List.of(
                new Participant("p1", "Cara"), new Participant("p2", "alice"),
                new Participant("p3", "Bob"), new Participant("p4", "Dan"));
        Map<String, Integer> scores = Map.of("p1", 10, "p2", 20, "p3", 10, "p4", 0);

        List<LeaderboardEntry> board = Leaderboard.rank(participants, scores);

        assertEquals(List.of(
                new LeaderboardEntry("p2", "alice", 20, 1),
                new LeaderboardEntry("p3", "Bob", 10, 2),
                new LeaderboardEntry("p1", "Cara", 10, 2),
                new LeaderboardEntry("p4", "Dan", 0, 4)), board);
    }

    @Test
    void treatsMissingScoresAsZero() {
        List<LeaderboardEntry> board = Leaderboard.rank(List.of(new Participant("p1", "Alice")), Map.of());

        assertEquals(new LeaderboardEntry("p1", "Alice", 0, 1), board.get(0));
    }

    @Test
    void returnsAnImmutableEmptyBoardWhenThereAreNoParticipants() {
        List<LeaderboardEntry> board = Leaderboard.rank(List.of(), Map.of());

        assertTrue(board.isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> board.add(new LeaderboardEntry("p1", "Alice", 0, 1)));
    }
}
