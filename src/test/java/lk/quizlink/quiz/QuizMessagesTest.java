package lk.quizlink.quiz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.concurrent.CompletionException;
import lk.quizlink.common.model.LeaderboardEntry;
import lk.quizlink.common.model.Question;
import lk.quizlink.common.protocol.Message;
import lk.quizlink.common.protocol.MessageCodec;
import lk.quizlink.common.protocol.MessageType;
import org.junit.jupiter.api.Test;

class QuizMessagesTest {
    private final MessageCodec codec = new MessageCodec();

    @Test
    void buildsQuestionEventsWithoutTheAnswerKey() {
        Question question = new Question("q1", "Pick TCP", List.of("UDP", "TCP"), 1, 20, 10);

        Message message = roundTrip(QuizMessages.question("AB12CD", question.toView(), 1, 3));

        JsonObject payload = message.payload();
        assertEquals(MessageType.QUESTION, message.type());
        assertEquals("", message.requestId());
        assertEquals("AB12CD", payload.get("roomCode").getAsString());
        assertEquals(1, payload.get("questionNumber").getAsInt());
        assertEquals(3, payload.get("totalQuestions").getAsInt());
        assertEquals("q1", payload.getAsJsonObject("question").get("id").getAsString());
        assertFalse(payload.toString().contains("correctOptionIndex"));
    }

    @Test
    void buildsLeaderboardAndFinalResultEvents() {
        List<LeaderboardEntry> entries = List.of(new LeaderboardEntry("p1", "Alice", 10, 1));

        Message leaderboard = roundTrip(QuizMessages.leaderboard("AB12CD", entries));
        Message ended = roundTrip(QuizMessages.quizEnded("AB12CD", entries));

        assertEquals(MessageType.LEADERBOARD, leaderboard.type());
        assertEquals(MessageType.QUIZ_ENDED, ended.type());
        JsonObject entry = ended.payload().getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals("p1", entry.get("participantId").getAsString());
        assertEquals(10, entry.get("score").getAsInt());
        assertEquals(1, entry.get("rank").getAsInt());
        assertEquals("AB12CD", ended.payload().get("roomCode").getAsString());
    }

    @Test
    void answerResultPreservesTheRequestIdAndOmitsReasonWhenAccepted() {
        Message message = roundTrip(QuizMessages.answerResult("req-7", AnswerResult.accepted("q1")));

        assertEquals(MessageType.ANSWER_RESULT, message.type());
        assertEquals("req-7", message.requestId());
        assertTrue(message.payload().get("accepted").getAsBoolean());
        assertFalse(message.payload().has("reason"));
    }

    @Test
    void answerResultIncludesTheRejectionReason() {
        Message message = roundTrip(QuizMessages.answerResult("req-8",
                AnswerResult.rejected("q1", QuizError.ALREADY_ANSWERED)));

        assertFalse(message.payload().get("accepted").getAsBoolean());
        assertEquals("ALREADY_ANSWERED", message.payload().get("reason").getAsString());
    }

    @Test
    void errorUnwrapsQuizExceptionsFromFutures() {
        Throwable failure = new CompletionException(new QuizException(QuizError.INVALID_ROOM, "Room not found"));

        Message message = roundTrip(QuizMessages.error("req-9", failure));

        assertEquals(MessageType.ERROR, message.type());
        assertEquals("req-9", message.requestId());
        assertEquals("INVALID_ROOM", message.payload().get("code").getAsString());
        assertEquals("Room not found", message.payload().get("message").getAsString());
    }

    @Test
    void errorHidesDetailsOfUnexpectedFailures() {
        Message message = roundTrip(QuizMessages.error("req-10", new NullPointerException("secret internals")));

        assertEquals("INTERNAL_ERROR", message.payload().get("code").getAsString());
        assertFalse(message.payload().toString().contains("secret"));
    }

    private Message roundTrip(Message message) {
        return codec.decode(codec.encode(message));
    }
}
