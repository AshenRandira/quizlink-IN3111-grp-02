package lk.quizlink.lobby;

import lk.quizlink.common.model.Question;
import lk.quizlink.common.model.Quiz;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LobbyServiceTest {

    private static final Quiz QUIZ = new Quiz("network-quiz", "Network Basics", List.of(
            new Question("q1", "Which protocol is connection-oriented?",
                    List.of("UDP", "TCP"), 1, 20, 10)));

    @Test
    void createsAndRetrievesRoomForItsQuiz() {
        LobbyService lobby = new LobbyService(new RoomCodeGenerator(bound -> 0), 4);

        Room created = lobby.createRoom(QUIZ);

        assertEquals("AAAAAA", created.roomCode());
        assertSame(QUIZ, created.quiz());
        assertTrue(lobby.roomExists("AAAAAA"));
        assertSame(created, lobby.findRoom("AAAAAA").orElseThrow());
    }

    @Test
    void normalizesInputBeforeLookupAndClose() {
        LobbyService lobby = new LobbyService(new RoomCodeGenerator(bound -> 0), 2);
        Room room = lobby.createRoom(QUIZ);

        assertSame(room, lobby.findRoom("  aaaaaa ").orElseThrow());
        assertTrue(lobby.roomExists(" aaaaaa "));
        assertTrue(lobby.closeRoom(" aaaaaa "));
        assertFalse(lobby.roomExists("AAAAAA"));
    }

    @Test
    void invalidAndUnknownCodesConsistentlyReturnEmptyOrFalse() {
        LobbyService lobby = new LobbyService(new RoomCodeGenerator(), 2);

        for (String code : new String[]{null, "", "   ", "ABC1234", "AB12!D", "UNKNOWN"}) {
            assertTrue(lobby.findRoom(code).isEmpty(), "Should not find code: " + code);
            assertFalse(lobby.roomExists(code), "Should not exist: " + code);
            assertFalse(lobby.closeRoom(code), "Should not close code: " + code);
        }
    }

    @Test
    void createsIndependentRoomsAndRetriesCollidingCandidatesWithoutReplacement() {
        AtomicInteger drawCount = new AtomicInteger();
        IntUnaryOperator firstCollisionThenUnique = bound -> {
            int candidateNumber = drawCount.getAndIncrement() / RoomCodeGenerator.CODE_LENGTH;
            return candidateNumber < 2 ? 0 : 1;
        };
        LobbyService lobby = new LobbyService(new RoomCodeGenerator(firstCollisionThenUnique), 3);
        Quiz otherQuiz = new Quiz("other-quiz", "Other Quiz", QUIZ.questions());

        Room original = lobby.createRoom(QUIZ);
        Room second = lobby.createRoom(otherQuiz);

        assertEquals("AAAAAA", original.roomCode());
        assertEquals("BBBBBB", second.roomCode());
        assertSame(original, lobby.findRoom("AAAAAA").orElseThrow());
        assertSame(QUIZ, original.quiz());
        assertSame(otherQuiz, second.quiz());
        assertNotSame(original, second);
    }

    @Test
    void reportsBoundedAllocationFailureWithoutOverwritingExistingRoom() {
        LobbyService lobby = new LobbyService(new RoomCodeGenerator(bound -> 0), 2);
        Room original = lobby.createRoom(QUIZ);

        LobbyException exception = assertThrows(LobbyException.class, () -> lobby.createRoom(
                new Quiz("second-quiz", "Second Quiz", QUIZ.questions())));

        assertEquals(LobbyException.Reason.ROOM_CODE_ALLOCATION_FAILED, exception.reason());
        assertSame(original, lobby.findRoom("AAAAAA").orElseThrow());
    }

    @Test
    void rejectsNullQuizWithDomainFailure() {
        LobbyService lobby = new LobbyService();

        LobbyException exception = assertThrows(LobbyException.class, () -> lobby.createRoom(null));

        assertEquals(LobbyException.Reason.INVALID_QUIZ, exception.reason());
    }

    @Test
    void closeIsIdempotentAndRemovedCodeMayBeReused() {
        LobbyService lobby = new LobbyService(new RoomCodeGenerator(bound -> 0), 1);
        Room first = lobby.createRoom(QUIZ);

        assertTrue(lobby.closeRoom(first.roomCode()));
        assertFalse(lobby.closeRoom(first.roomCode()));
        assertTrue(lobby.findRoom(first.roomCode()).isEmpty());

        Room recreated = lobby.createRoom(QUIZ);
        assertEquals(first.roomCode(), recreated.roomCode());
        assertNotSame(first, recreated);
    }

    @Test
    void roomRejectsInvalidCodeOrNullQuiz() {
        assertThrows(IllegalArgumentException.class, () -> new Room("bad", QUIZ));
        assertThrows(NullPointerException.class, () -> new Room("ABC123", null));
    }

    @Test
    void roomDoesNotExposeMutableQuizContents() {
        List<Question> sourceQuestions = new ArrayList<>(QUIZ.questions());
        Quiz quizFromMutableInput = new Quiz("copied-quiz", "Copied Quiz", sourceQuestions);
        Room room = new Room("ABC123", quizFromMutableInput);
        sourceQuestions.clear();

        assertEquals(1, room.quiz().questions().size());
        assertThrows(UnsupportedOperationException.class,
                () -> room.quiz().questions().clear());
    }

    @Test
    void concurrentRoomCreationNeverReplacesOrDuplicatesActiveRooms() throws Exception {
        int roomCount = 24;
        AtomicInteger drawCount = new AtomicInteger();
        IntUnaryOperator uniqueCandidates = bound -> {
            int draw = drawCount.getAndIncrement();
            int candidateNumber = draw / RoomCodeGenerator.CODE_LENGTH;
            return draw % RoomCodeGenerator.CODE_LENGTH == 0 ? candidateNumber % bound : 0;
        };
        LobbyService lobby = new LobbyService(new RoomCodeGenerator(uniqueCandidates), 4);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Room>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < roomCount; i++) {
                futures.add(workers.submit(() -> {
                    if (!start.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent test did not start");
                    }
                    return lobby.createRoom(QUIZ);
                }));
            }
            start.countDown();

            Set<String> codes = new HashSet<>();
            for (Future<Room> future : futures) {
                Room room = future.get(3, TimeUnit.SECONDS);
                assertTrue(codes.add(room.roomCode()), "Duplicate active room code: " + room.roomCode());
                assertSame(room, lobby.findRoom(room.roomCode()).orElseThrow());
            }
            assertEquals(roomCount, codes.size());
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS),
                    "Concurrent room creation workers should terminate");
        }
    }
}
