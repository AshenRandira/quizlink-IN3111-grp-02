package lk.quizlink.host;

import lk.quizlink.common.model.Question;
import lk.quizlink.common.model.Quiz;
import lk.quizlink.common.protocol.Message;
import lk.quizlink.common.protocol.MessageType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockHostGatewayTest {

    private static Quiz sampleQuiz() {
        Question q = new Question("q1", "What is TCP?", List.of("Stream", "Datagram"), 0, 20, 10);
        return new Quiz("quiz-mock", "Mock Quiz", List.of(q));
    }

    @Test
    void createRoomGeneratesCodeAndEmitsParticipantsUpdated() throws Exception {
        CountDownLatch eventLatch = new CountDownLatch(1);
        AtomicReference<Message> receivedEvent = new AtomicReference<>();

        try (MockHostGateway gateway = new MockHostGateway()) {
            gateway.setEventListener(event -> {
                receivedEvent.set(event);
                eventLatch.countDown();
            });

            CompletableFuture<String> future = gateway.createRoom(sampleQuiz());
            String roomCode = future.get(1, TimeUnit.SECONDS);

            assertNotNull(roomCode);
            assertEquals(6, roomCode.length());
            assertTrue(roomCode.matches("^[A-Z0-9]{6}$"), "Room code should be 6 uppercase alphanumeric chars");

            boolean eventArrived = eventLatch.await(1, TimeUnit.SECONDS);
            assertTrue(eventArrived, "PARTICIPANTS_UPDATED event should be emitted");
            assertEquals(MessageType.PARTICIPANTS_UPDATED, receivedEvent.get().type());
            assertEquals(roomCode, receivedEvent.get().payload().get("roomCode").getAsString());
        }
    }

    @Test
    void startAndEndQuizStateTransitions() throws Exception {
        try (MockHostGateway gateway = new MockHostGateway()) {
            String roomCode = gateway.createRoom(sampleQuiz()).get(1, TimeUnit.SECONDS);

            // Starting valid created room succeeds
            assertDoesNotThrow(() -> gateway.startQuiz(roomCode).get(1, TimeUnit.SECONDS));

            // Starting twice fails with ALREADY_STARTED
            ExecutionException doubleStartEx = assertThrows(ExecutionException.class,
                    () -> gateway.startQuiz(roomCode).get(1, TimeUnit.SECONDS));
            assertInstanceOf(HostException.class, doubleStartEx.getCause());
            assertEquals("ALREADY_STARTED", ((HostException) doubleStartEx.getCause()).getCode());

            // Ending valid started room succeeds
            assertDoesNotThrow(() -> gateway.endQuiz(roomCode).get(1, TimeUnit.SECONDS));

            // Ending twice fails with ALREADY_ENDED
            ExecutionException doubleEndEx = assertThrows(ExecutionException.class,
                    () -> gateway.endQuiz(roomCode).get(1, TimeUnit.SECONDS));
            assertInstanceOf(HostException.class, doubleEndEx.getCause());
            assertEquals("ALREADY_ENDED", ((HostException) doubleEndEx.getCause()).getCode());
        }
    }

    @Test
    void errorPathsOnUnknownRoomOrUnstartedQuiz() {
        try (MockHostGateway gateway = new MockHostGateway()) {
            // Starting unknown room
            ExecutionException startUnknownEx = assertThrows(ExecutionException.class,
                    () -> gateway.startQuiz("UNKNOWN").get(1, TimeUnit.SECONDS));
            assertInstanceOf(HostException.class, startUnknownEx.getCause());
            assertEquals("INVALID_ROOM", ((HostException) startUnknownEx.getCause()).getCode());

            // Ending unstarted room
            String roomCode = assertDoesNotThrow(() -> gateway.createRoom(sampleQuiz()).get(1, TimeUnit.SECONDS));
            ExecutionException endUnstartedEx = assertThrows(ExecutionException.class,
                    () -> gateway.endQuiz(roomCode).get(1, TimeUnit.SECONDS));
            assertInstanceOf(HostException.class, endUnstartedEx.getCause());
            assertEquals("QUIZ_NOT_STARTED", ((HostException) endUnstartedEx.getCause()).getCode());
        }
    }
}
