package lk.quizlink.lobby;

import lk.quizlink.common.model.Participant;
import lk.quizlink.common.model.Question;
import lk.quizlink.common.model.Quiz;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LobbyMembershipTest {

    private static final Quiz QUIZ = new Quiz("join-quiz", "Join Quiz", List.of(
            new Question("q1", "Select TCP", List.of("TCP", "UDP"), 0, 10, 5)));
    private static final Quiz SECOND_QUIZ = new Quiz("second-quiz", "Second Quiz", QUIZ.questions());

    @Test
    void joinsRoomWithServerGeneratedIdAndTrimmedDisplayName() {
        LobbyService lobby = newLobby();
        Room room = lobby.createRoom(QUIZ);

        Participant participant = lobby.joinRoom(" " + room.roomCode().toLowerCase() + " ", " Student 1 ");

        assertTrue(participant.id().startsWith("participant-"));
        assertFalse(participant.id().isBlank());
        assertEquals("Student 1", participant.displayName());
        assertEquals(participant, lobby.findParticipant(room.roomCode(), participant.id()).orElseThrow());
    }

    @Test
    void generatesDistinctParticipantIdsAndAllowsDuplicateDisplayNames() {
        LobbyService lobby = newLobby();
        Room room = lobby.createRoom(QUIZ);

        Participant first = lobby.joinRoom(room.roomCode(), "Alex");
        Participant second = lobby.joinRoom(room.roomCode(), "Alex");

        assertEquals("Alex", first.displayName());
        assertEquals("Alex", second.displayName());
        assertFalse(first.id().equals(second.id()));
        assertEquals(2, lobby.getParticipantCount(room.roomCode()));
    }

    @Test
    void rejectsMalformedUnknownAndRemovedRoomsForJoin() {
        LobbyService lobby = newLobby();

        assertLobbyReason(LobbyException.Reason.INVALID_ROOM,
                () -> lobby.joinRoom("bad", "Student"));
        assertLobbyReason(LobbyException.Reason.INVALID_ROOM,
                () -> lobby.joinRoom("ABC123", "Student"));

        Room room = lobby.createRoom(QUIZ);
        assertTrue(lobby.closeRoom(room.roomCode()));
        assertLobbyReason(LobbyException.Reason.INVALID_ROOM,
                () -> lobby.joinRoom(room.roomCode(), "Student"));
    }

    @ParameterizedTest
    @MethodSource("invalidDisplayNames")
    void rejectsMissingBlankAndControlCharacterDisplayNames(String displayName) {
        LobbyService lobby = newLobby();
        Room room = lobby.createRoom(QUIZ);

        assertLobbyReason(LobbyException.Reason.INVALID_DISPLAY_NAME,
                () -> lobby.joinRoom(room.roomCode(), displayName));
        assertEquals(0, lobby.getParticipantCount(room.roomCode()));
    }

    static Stream<String> invalidDisplayNames() {
        return Stream.of(null, "", "   ", "Bad\nName", "Bad\u0000Name");
    }

    @Test
    void acceptsUnicodeDisplayNamesAndTrimsSurroundingWhitespace() {
        LobbyService lobby = newLobby();
        Room room = lobby.createRoom(QUIZ);

        Participant participant = lobby.joinRoom(room.roomCode(), "  Amaya 李 🌐  ");

        assertEquals("Amaya 李 🌐", participant.displayName());
    }

    @Test
    void keepsMembershipIndependentBetweenRoomsAndScopesLookupByRoom() {
        LobbyService lobby = newLobby();
        Room firstRoom = lobby.createRoom(QUIZ);
        Room secondRoom = lobby.createRoom(SECOND_QUIZ);
        Participant first = lobby.joinRoom(firstRoom.roomCode(), "Same Name");
        Participant second = lobby.joinRoom(secondRoom.roomCode(), "Same Name");

        assertEquals(List.of(first), lobby.getParticipants(firstRoom.roomCode()));
        assertEquals(List.of(second), lobby.getParticipants(secondRoom.roomCode()));
        assertEquals(1, lobby.getParticipantCount(firstRoom.roomCode()));
        assertTrue(lobby.findParticipant(firstRoom.roomCode(), second.id()).isEmpty());
        assertTrue(lobby.findParticipant(secondRoom.roomCode(), first.id()).isEmpty());
    }

    @Test
    void listsAndFindsParticipantsInRegistrationOrder() {
        LobbyService lobby = newLobby();
        Room room = lobby.createRoom(QUIZ);
        Participant first = lobby.joinRoom(room.roomCode(), "First");
        Participant second = lobby.joinRoom(room.roomCode(), "Second");

        assertEquals(List.of(first, second), lobby.getParticipants(room.roomCode()));
        assertEquals(first, lobby.findParticipant(room.roomCode(), first.id()).orElseThrow());
        assertTrue(lobby.findParticipant(room.roomCode(), "missing-id").isEmpty());
        assertTrue(lobby.findParticipant(room.roomCode(), null).isEmpty());
    }

    @Test
    void participantSnapshotsAreImmutableAndRemainUnchanged() {
        LobbyService lobby = newLobby();
        Room room = lobby.createRoom(QUIZ);
        Participant first = lobby.joinRoom(room.roomCode(), "First");
        List<Participant> snapshot = lobby.getParticipants(room.roomCode());

        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        Participant second = lobby.joinRoom(room.roomCode(), "Second");
        lobby.leaveRoom(room.roomCode(), first.id());

        assertEquals(List.of(first), snapshot);
        assertEquals(List.of(second), lobby.getParticipants(room.roomCode()));
    }

    @Test
    void leavesOnlyTheSpecifiedParticipantAndIsIdempotent() {
        LobbyService lobby = newLobby();
        Room room = lobby.createRoom(QUIZ);
        Room otherRoom = lobby.createRoom(SECOND_QUIZ);
        Participant first = lobby.joinRoom(room.roomCode(), "First");
        Participant second = lobby.joinRoom(room.roomCode(), "Second");
        Participant unrelated = lobby.joinRoom(otherRoom.roomCode(), "Other");

        assertFalse(lobby.leaveRoom(room.roomCode(), "missing-id"));
        assertFalse(lobby.leaveRoom(room.roomCode(), unrelated.id()));
        assertTrue(lobby.leaveRoom(room.roomCode(), first.id()));
        assertFalse(lobby.leaveRoom(room.roomCode(), first.id()));
        assertFalse(lobby.leaveRoom(room.roomCode(), null));
        assertEquals(List.of(second), lobby.getParticipants(room.roomCode()));
        assertEquals(List.of(unrelated), lobby.getParticipants(otherRoom.roomCode()));
    }

    @Test
    void invalidOrUnknownRoomMembershipReadsReportInvalidRoom() {
        LobbyService lobby = newLobby();

        assertLobbyReason(LobbyException.Reason.INVALID_ROOM,
                () -> lobby.getParticipants("not-valid"));
        assertLobbyReason(LobbyException.Reason.INVALID_ROOM,
                () -> lobby.getParticipants("ABC123"));
        assertLobbyReason(LobbyException.Reason.INVALID_ROOM,
                () -> lobby.findParticipant("ABC123", "participant-1"));
        assertLobbyReason(LobbyException.Reason.INVALID_ROOM,
                () -> lobby.getParticipantCount("ABC123"));
        assertLobbyReason(LobbyException.Reason.INVALID_ROOM,
                () -> lobby.leaveRoom("ABC123", "participant-1"));
    }

    @Test
    void closingRoomRemovesMembershipAndReusedCodeStartsEmpty() {
        LobbyService lobby = newFixedCodeLobby();
        Room original = lobby.createRoom(QUIZ);
        Participant participant = lobby.joinRoom(original.roomCode(), "Student");
        List<Participant> historicalSnapshot = lobby.getParticipants(original.roomCode());

        assertTrue(lobby.closeRoom(original.roomCode()));
        assertFalse(lobby.closeRoom(original.roomCode()));
        assertLobbyReason(LobbyException.Reason.INVALID_ROOM,
                () -> lobby.getParticipants(original.roomCode()));

        Room replacement = lobby.createRoom(SECOND_QUIZ);
        assertEquals(original.roomCode(), replacement.roomCode());
        assertEquals(0, lobby.getParticipantCount(replacement.roomCode()));
        assertEquals(List.of(participant), historicalSnapshot);
    }

    @Test
    void staleDisconnectCleanupCannotRemoveMemberOfRecreatedRoom() {
        LobbyService lobby = newLobby();
        Room oldRoom = lobby.createRoom(QUIZ);
        Participant oldParticipant = lobby.joinRoom(oldRoom.roomCode(), "Old member");
        lobby.closeRoom(oldRoom.roomCode());

        Room replacement = lobby.createRoom(SECOND_QUIZ);
        Participant currentParticipant = lobby.joinRoom(replacement.roomCode(), "Current member");

        assertFalse(lobby.leaveRoom(replacement.roomCode(), oldParticipant.id()));
        assertEquals(List.of(currentParticipant), lobby.getParticipants(replacement.roomCode()));
        assertFalse(oldParticipant.id().equals(currentParticipant.id()));
    }

    @Test
    void failedJoinDoesNotRegisterAnyParticipant() {
        LobbyService lobby = newLobby();
        Room room = lobby.createRoom(QUIZ);

        assertLobbyReason(LobbyException.Reason.INVALID_DISPLAY_NAME,
                () -> lobby.joinRoom(room.roomCode(), " \t "));
        assertLobbyReason(LobbyException.Reason.INVALID_ROOM,
                () -> lobby.joinRoom("ZZZZZZ", "Student"));
        assertEquals(List.of(), lobby.getParticipants(room.roomCode()));
    }

    @Test
    void participantIdCollisionIsRetriedAndIdsAreNotReusedAfterLeave() {
        AtomicInteger generated = new AtomicInteger();
        LobbyService lobby = new LobbyService(new RoomCodeGenerator(bound -> 0), 2,
                () -> {
                    int number = generated.getAndIncrement();
                    return number < 2 ? "participant-fixed" : "participant-" + number;
                });
        Room room = lobby.createRoom(QUIZ);
        Participant first = lobby.joinRoom(room.roomCode(), "First");
        Participant second = lobby.joinRoom(room.roomCode(), "Second");

        assertEquals("participant-fixed", first.id());
        assertEquals("participant-2", second.id());
        assertTrue(lobby.leaveRoom(room.roomCode(), first.id()));
        assertEquals("participant-3", lobby.joinRoom(room.roomCode(), "Third").id());
    }

    @Test
    void reportsExhaustedParticipantIdAllocation() {
        LobbyService lobby = new LobbyService(new RoomCodeGenerator(bound -> 0), 2,
                () -> "same-participant");
        Room room = lobby.createRoom(QUIZ);
        Participant first = lobby.joinRoom(room.roomCode(), "First");

        assertLobbyReason(LobbyException.Reason.PARTICIPANT_ID_ALLOCATION_FAILED,
                () -> lobby.joinRoom(room.roomCode(), "Second"));
        assertEquals(List.of(first), lobby.getParticipants(room.roomCode()));
    }

    @Test
    void rejectsBlankGeneratedParticipantIdsWithoutRegisteringMember() {
        LobbyService lobby = new LobbyService(new RoomCodeGenerator(bound -> 0), 2,
                () -> " \t ");
        Room room = lobby.createRoom(QUIZ);

        assertLobbyReason(LobbyException.Reason.PARTICIPANT_ID_ALLOCATION_FAILED,
                () -> lobby.joinRoom(room.roomCode(), "Student"));
        assertEquals(List.of(), lobby.getParticipants(room.roomCode()));
    }

    @Test
    void concurrentJoinsProduceUniqueParticipantsAndConsistentSnapshots() throws Exception {
        int joins = 24;
        LobbyService lobby = newLobby();
        Room room = lobby.createRoom(QUIZ);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Participant>> joinFutures = new ArrayList<>();
        List<Future<Integer>> snapshotFutures = new ArrayList<>();

        try {
            for (int i = 0; i < joins; i++) {
                final int number = i;
                joinFutures.add(workers.submit(() -> {
                    awaitStart(start);
                    return lobby.joinRoom(room.roomCode(), "Student " + number);
                }));
            }
            for (int i = 0; i < 4; i++) {
                snapshotFutures.add(workers.submit(() -> {
                    awaitStart(start);
                    int largestSnapshot = 0;
                    for (int j = 0; j < 40; j++) {
                        List<Participant> snapshot = lobby.getParticipants(room.roomCode());
                        largestSnapshot = Math.max(largestSnapshot, snapshot.size());
                        if (snapshot.size() > joins) {
                            throw new AssertionError("Snapshot exceeded total successful joins");
                        }
                    }
                    return largestSnapshot;
                }));
            }
            start.countDown();

            Set<String> ids = new HashSet<>();
            for (Future<Participant> future : joinFutures) {
                assertTrue(ids.add(future.get(4, TimeUnit.SECONDS).id()));
            }
            for (Future<Integer> future : snapshotFutures) {
                assertTrue(future.get(4, TimeUnit.SECONDS) <= joins);
            }
            assertEquals(joins, lobby.getParticipantCount(room.roomCode()));
            assertEquals(joins, lobby.getParticipants(room.roomCode()).size());
        } finally {
            start.countDown();
            stopWorkers(workers);
        }
    }

    @Test
    void concurrentJoinsAndLeavesKeepTheFinalMembershipConsistent() throws Exception {
        int members = 12;
        LobbyService lobby = newLobby();
        Room room = lobby.createRoom(QUIZ);
        List<Participant> existing = new ArrayList<>();
        for (int i = 0; i < members; i++) {
            existing.add(lobby.joinRoom(room.roomCode(), "Existing " + i));
        }

        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Participant>> joinFutures = new ArrayList<>();
        List<Future<Boolean>> leaveFutures = new ArrayList<>();
        try {
            for (int i = 0; i < members; i++) {
                final int number = i;
                joinFutures.add(workers.submit(() -> {
                    awaitStart(start);
                    return lobby.joinRoom(room.roomCode(), "New " + number);
                }));
                Participant toRemove = existing.get(i);
                leaveFutures.add(workers.submit(() -> {
                    awaitStart(start);
                    return lobby.leaveRoom(room.roomCode(), toRemove.id());
                }));
            }
            start.countDown();

            Set<String> newIds = new HashSet<>();
            for (Future<Participant> future : joinFutures) {
                assertTrue(newIds.add(future.get(4, TimeUnit.SECONDS).id()));
            }
            for (Future<Boolean> future : leaveFutures) {
                assertTrue(future.get(4, TimeUnit.SECONDS));
            }

            List<Participant> finalSnapshot = lobby.getParticipants(room.roomCode());
            assertEquals(members, finalSnapshot.size());
            assertEquals(newIds, finalSnapshot.stream().map(Participant::id).collect(java.util.stream.Collectors.toSet()));
        } finally {
            start.countDown();
            stopWorkers(workers);
        }
    }

    @Test
    void concurrentRoomClosureAndJoiningCannotLeaveMembershipInReplacementRoom() throws Exception {
        LobbyService lobby = newFixedCodeLobby();
        Room room = lobby.createRoom(QUIZ);
        Participant oldMember = lobby.joinRoom(room.roomCode(), "Original");
        int attempts = 16;
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> joinFutures = new ArrayList<>();

        try {
            for (int i = 0; i < attempts; i++) {
                final int number = i;
                joinFutures.add(workers.submit(() -> {
                    awaitStart(start);
                    try {
                        lobby.joinRoom(room.roomCode(), "Racer " + number);
                        return true;
                    } catch (LobbyException exception) {
                        if (exception.reason() == LobbyException.Reason.INVALID_ROOM) {
                            return false;
                        }
                        throw exception;
                    }
                }));
            }
            Future<Boolean> closeFuture = workers.submit(() -> {
                awaitStart(start);
                return lobby.closeRoom(room.roomCode());
            });
            start.countDown();

            assertTrue(closeFuture.get(4, TimeUnit.SECONDS));
            for (Future<Boolean> future : joinFutures) {
                future.get(4, TimeUnit.SECONDS);
            }
            assertFalse(lobby.roomExists(room.roomCode()));

            Room replacement = lobby.createRoom(SECOND_QUIZ);
            assertEquals(room.roomCode(), replacement.roomCode());
            assertEquals(List.of(), lobby.getParticipants(replacement.roomCode()));
            Participant replacementMember = lobby.joinRoom(replacement.roomCode(), "Replacement");
            assertFalse(lobby.leaveRoom(replacement.roomCode(), oldMember.id()));
            assertEquals(List.of(replacementMember), lobby.getParticipants(replacement.roomCode()));
        } finally {
            start.countDown();
            stopWorkers(workers);
        }
    }

    private static LobbyService newLobby() {
        AtomicInteger codeDraws = new AtomicInteger();
        AtomicInteger participantIds = new AtomicInteger();
        IntUnaryOperator deterministicIndices = bound ->
                (codeDraws.getAndIncrement() / RoomCodeGenerator.CODE_LENGTH) % bound;
        return new LobbyService(new RoomCodeGenerator(deterministicIndices),
                LobbyService.DEFAULT_MAX_CODE_ATTEMPTS,
                () -> "participant-" + participantIds.incrementAndGet());
    }

    private static LobbyService newFixedCodeLobby() {
        AtomicInteger participantIds = new AtomicInteger();
        return new LobbyService(new RoomCodeGenerator(bound -> 0),
                LobbyService.DEFAULT_MAX_CODE_ATTEMPTS,
                () -> "participant-" + participantIds.incrementAndGet());
    }

    private static void assertLobbyReason(LobbyException.Reason expected, Runnable operation) {
        LobbyException exception = assertThrows(LobbyException.class, operation::run);
        assertEquals(expected, exception.reason());
    }

    private static void awaitStart(CountDownLatch start) throws InterruptedException {
        if (!start.await(2, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent test did not start");
        }
    }

    private static void stopWorkers(ExecutorService workers) throws InterruptedException {
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS),
                "Membership test workers should terminate");
    }
}
