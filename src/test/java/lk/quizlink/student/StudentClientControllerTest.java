package lk.quizlink.student;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lk.quizlink.common.model.LeaderboardEntry;
import lk.quizlink.common.model.Participant;
import lk.quizlink.common.model.QuestionView;
import lk.quizlink.common.protocol.Message;
import lk.quizlink.common.protocol.MessageCodec;
import lk.quizlink.common.protocol.MessageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudentClientControllerTest {
    @Test
    void routesJoinedQuestionAndFinalLeaderboardEvents() throws Exception {
        TestView view = new TestView(3);
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        MessageCodec codec = new MessageCodec();
        Gson gson = new Gson();

        try (ServerSocket server = new ServerSocket(0)) {
            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(
                             socket.getInputStream(), StandardCharsets.UTF_8));
                     BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                             socket.getOutputStream(), StandardCharsets.UTF_8))) {
                    Message join = codec.decode(reader.readLine());
                    assertEquals(MessageType.JOIN_ROOM, join.type());
                    assertEquals("AB12CD", join.payload().get("roomCode").getAsString());
                    assertEquals("Student 1", join.payload().get("displayName").getAsString());

                    JsonObject payload = new JsonObject();
                    payload.addProperty("roomCode", "AB12CD");
                    writer.write(codec.encode(Message.of(MessageType.ROOM_JOINED,
                            join.requestId(), payload)));

                    JsonObject questionPayload = new JsonObject();
                    questionPayload.addProperty("roomCode", "AB12CD");
                    questionPayload.add("question", gson.toJsonTree(new QuestionView(
                            "q1", "Which protocol is reliable?", List.of("UDP", "TCP"), 20, 10)));
                    questionPayload.addProperty("questionNumber", 1);
                    questionPayload.addProperty("totalQuestions", 1);
                    writer.write(codec.encode(Message.of(MessageType.QUESTION, "", questionPayload)));

                    JsonObject resultsPayload = new JsonObject();
                    resultsPayload.addProperty("roomCode", "AB12CD");
                    resultsPayload.add("entries", gson.toJsonTree(List.of(
                            new LeaderboardEntry("p1", "Student 1", 10, 1))));
                    writer.write(codec.encode(Message.of(MessageType.QUIZ_ENDED, "", resultsPayload)));
                    writer.flush();
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            });
            serverThread.start();

            try (StudentClientController controller = new StudentClientController(
                    view, server.getLocalPort(), Runnable::run)) {
                controller.connectAndJoin("127.0.0.1",
                        new StudentJoinRequest("AB12CD", "Student 1"));
                assertTrue(view.completed.await(3, TimeUnit.SECONDS));
                assertEquals("AB12CD", view.joinedRequest.roomCode());
                assertEquals("q1", view.question.id());
                assertEquals(1, view.questionNumber);
                assertEquals(1, view.totalQuestions);
                assertEquals("Student 1", view.entries.get(0).displayName());
                assertTrue(view.finalResults);
                assertNull(view.errorMessage);
            }

            serverThread.join(3_000);
            assertTrue(!serverThread.isAlive(), "test server did not finish");
            if (serverFailure.get() != null) {
                throw new AssertionError("test server failed", serverFailure.get());
            }
        }
    }

    @Test
    void showsServerErrorWhenJoinIsRejected() throws Exception {
        TestView view = new TestView(1);
        MessageCodec codec = new MessageCodec();

        try (ServerSocket server = new ServerSocket(0)) {
            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(
                             socket.getInputStream(), StandardCharsets.UTF_8));
                     BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                             socket.getOutputStream(), StandardCharsets.UTF_8))) {
                    Message join = codec.decode(reader.readLine());
                    JsonObject payload = new JsonObject();
                    payload.addProperty("code", "INVALID_ROOM");
                    payload.addProperty("message", "Room does not exist");
                    writer.write(codec.encode(Message.of(MessageType.ERROR,
                            join.requestId(), payload)));
                    writer.flush();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            serverThread.start();

            try (StudentClientController controller = new StudentClientController(
                    view, server.getLocalPort(), Runnable::run)) {
                controller.connectAndJoin("127.0.0.1",
                        new StudentJoinRequest("BAD123", "Student 1"));
                assertTrue(view.completed.await(3, TimeUnit.SECONDS));
                assertEquals("Room does not exist", view.errorMessage);
            }

            serverThread.join(3_000);
            assertTrue(!serverThread.isAlive(), "test server did not finish");
        }
    }

    @Test
    void sendsSelectedAnswerAndShowsServerAcknowledgement() throws Exception {
        TestView view = new TestView(3);
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        MessageCodec codec = new MessageCodec();
        Gson gson = new Gson();

        try (ServerSocket server = new ServerSocket(0)) {
            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(
                             socket.getInputStream(), StandardCharsets.UTF_8));
                     BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                             socket.getOutputStream(), StandardCharsets.UTF_8))) {
                    Message join = codec.decode(reader.readLine());
                    writer.write(codec.encode(Message.of(MessageType.ROOM_JOINED,
                            join.requestId(), new JsonObject())));

                    JsonObject questionPayload = new JsonObject();
                    questionPayload.addProperty("roomCode", "AB12CD");
                    questionPayload.add("question", gson.toJsonTree(new QuestionView(
                            "q1", "Which protocol is reliable?", List.of("UDP", "TCP"), 20, 10)));
                    questionPayload.addProperty("questionNumber", 1);
                    questionPayload.addProperty("totalQuestions", 1);
                    writer.write(codec.encode(Message.of(MessageType.QUESTION, "", questionPayload)));
                    writer.flush();

                    Message answer = codec.decode(reader.readLine());
                    assertEquals(MessageType.SUBMIT_ANSWER, answer.type());
                    assertEquals("AB12CD", answer.payload().get("roomCode").getAsString());
                    assertEquals("q1", answer.payload().get("questionId").getAsString());
                    assertEquals(1, answer.payload().get("selectedOptionIndex").getAsInt());

                    JsonObject resultPayload = new JsonObject();
                    resultPayload.addProperty("questionId", "q1");
                    resultPayload.addProperty("accepted", true);
                    writer.write(codec.encode(Message.of(MessageType.ANSWER_RESULT,
                            answer.requestId(), resultPayload)));
                    writer.flush();
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            });
            serverThread.start();

            try (StudentClientController controller = new StudentClientController(
                    view, server.getLocalPort(), Runnable::run)) {
                controller.connectAndJoin("127.0.0.1",
                        new StudentJoinRequest("AB12CD", "Student 1"));
                assertTrue(view.questionShown.await(3, TimeUnit.SECONDS));
                controller.submitAnswer("q1", 1);
                assertTrue(view.completed.await(3, TimeUnit.SECONDS));
                assertEquals("q1", view.answerQuestionId);
                assertTrue(view.answerAccepted);
                assertNull(view.answerReason);
            }

            serverThread.join(3_000);
            assertTrue(!serverThread.isAlive(), "test server did not finish");
            if (serverFailure.get() != null) {
                throw new AssertionError("test server failed", serverFailure.get());
            }
        }
    }

    @Test
    void showsReasonWhenServerRejectsAnAnswer() throws Exception {
        TestView view = new TestView(3);
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        MessageCodec codec = new MessageCodec();
        Gson gson = new Gson();

        try (ServerSocket server = new ServerSocket(0)) {
            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(
                             socket.getInputStream(), StandardCharsets.UTF_8));
                     BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                             socket.getOutputStream(), StandardCharsets.UTF_8))) {
                    Message join = codec.decode(reader.readLine());
                    writer.write(codec.encode(Message.of(MessageType.ROOM_JOINED,
                            join.requestId(), new JsonObject())));

                    JsonObject questionPayload = new JsonObject();
                    questionPayload.addProperty("roomCode", "AB12CD");
                    questionPayload.add("question", gson.toJsonTree(new QuestionView(
                            "q1", "Which protocol is reliable?", List.of("UDP", "TCP"), 20, 10)));
                    questionPayload.addProperty("questionNumber", 1);
                    questionPayload.addProperty("totalQuestions", 1);
                    writer.write(codec.encode(Message.of(MessageType.QUESTION, "", questionPayload)));
                    writer.flush();

                    Message answer = codec.decode(reader.readLine());
                    JsonObject resultPayload = new JsonObject();
                    resultPayload.addProperty("questionId", "q1");
                    resultPayload.addProperty("accepted", false);
                    resultPayload.addProperty("reason", "Question time has expired");
                    writer.write(codec.encode(Message.of(MessageType.ANSWER_RESULT,
                            answer.requestId(), resultPayload)));
                    writer.flush();
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            });
            serverThread.start();

            try (StudentClientController controller = new StudentClientController(
                    view, server.getLocalPort(), Runnable::run)) {
                controller.connectAndJoin("127.0.0.1",
                        new StudentJoinRequest("AB12CD", "Student 1"));
                assertTrue(view.questionShown.await(3, TimeUnit.SECONDS));
                controller.submitAnswer("q1", 0);
                assertTrue(view.completed.await(3, TimeUnit.SECONDS));
                assertEquals("q1", view.answerQuestionId);
                assertTrue(!view.answerAccepted);
                assertEquals("Question time has expired", view.answerReason);
            }

            serverThread.join(3_000);
            assertTrue(!serverThread.isAlive(), "test server did not finish");
            if (serverFailure.get() != null) {
                throw new AssertionError("test server failed", serverFailure.get());
            }
        }
    }

    @Test
    void routesParticipantsAndSendsLeaveRoom() throws Exception {
        TestView view = new TestView(2);
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        CountDownLatch leaveReceived = new CountDownLatch(1);
        MessageCodec codec = new MessageCodec();
        Gson gson = new Gson();

        try (ServerSocket server = new ServerSocket(0)) {
            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(
                             socket.getInputStream(), StandardCharsets.UTF_8));
                     BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                             socket.getOutputStream(), StandardCharsets.UTF_8))) {
                    Message join = codec.decode(reader.readLine());
                    writer.write(codec.encode(Message.of(MessageType.ROOM_JOINED,
                            join.requestId(), new JsonObject())));

                    JsonObject participantsPayload = new JsonObject();
                    participantsPayload.addProperty("roomCode", "AB12CD");
                    participantsPayload.add("participants", gson.toJsonTree(List.of(
                            new Participant("p1", "Student 1"),
                            new Participant("p2", "Student 2"))));
                    writer.write(codec.encode(Message.of(MessageType.PARTICIPANTS_UPDATED,
                            "", participantsPayload)));
                    writer.flush();

                    Message leave = codec.decode(reader.readLine());
                    assertEquals(MessageType.LEAVE_ROOM, leave.type());
                    assertEquals("AB12CD", leave.payload().get("roomCode").getAsString());
                    leaveReceived.countDown();
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            });
            serverThread.start();

            try (StudentClientController controller = new StudentClientController(
                    view, server.getLocalPort(), Runnable::run)) {
                controller.connectAndJoin("127.0.0.1",
                        new StudentJoinRequest("AB12CD", "Student 1"));
                assertTrue(view.participantsShown.await(3, TimeUnit.SECONDS));
                assertEquals(List.of("Student 1", "Student 2"),
                        view.participants.stream().map(Participant::displayName).toList());
                controller.leaveRoom();
                assertTrue(view.returnedToJoin.await(3, TimeUnit.SECONDS));
                assertTrue(leaveReceived.await(3, TimeUnit.SECONDS));
            }

            serverThread.join(3_000);
            assertTrue(!serverThread.isAlive(), "test server did not finish");
            if (serverFailure.get() != null) {
                throw new AssertionError("test server failed", serverFailure.get());
            }
        }
    }

    @Test
    void rejectsRoomScopedEventForAnotherRoom() throws Exception {
        TestView view = new TestView(1);
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        MessageCodec codec = new MessageCodec();
        Gson gson = new Gson();

        try (ServerSocket server = new ServerSocket(0)) {
            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(
                             socket.getInputStream(), StandardCharsets.UTF_8));
                     BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                             socket.getOutputStream(), StandardCharsets.UTF_8))) {
                    Message join = codec.decode(reader.readLine());
                    writer.write(codec.encode(Message.of(MessageType.ROOM_JOINED,
                            join.requestId(), new JsonObject())));

                    JsonObject participantsPayload = new JsonObject();
                    participantsPayload.addProperty("roomCode", "OTHER1");
                    participantsPayload.add("participants", gson.toJsonTree(List.of(
                            new Participant("p1", "Student 1"))));
                    writer.write(codec.encode(Message.of(MessageType.PARTICIPANTS_UPDATED,
                            "", participantsPayload)));
                    writer.flush();
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            });
            serverThread.start();

            try (StudentClientController controller = new StudentClientController(
                    view, server.getLocalPort(), Runnable::run)) {
                controller.connectAndJoin("127.0.0.1",
                        new StudentJoinRequest("AB12CD", "Student 1"));
                assertTrue(view.connectionErrorShown.await(3, TimeUnit.SECONDS));
                assertEquals("Server event was for a different quiz room", view.errorMessage);
                assertNull(view.participants);
            }

            serverThread.join(3_000);
            assertTrue(!serverThread.isAlive(), "test server did not finish");
            if (serverFailure.get() != null) {
                throw new AssertionError("test server failed", serverFailure.get());
            }
        }
    }

    private static final class TestView implements StudentClientView {
        private final CountDownLatch completed;
        private final CountDownLatch questionShown = new CountDownLatch(1);
        private final CountDownLatch participantsShown = new CountDownLatch(1);
        private final CountDownLatch returnedToJoin = new CountDownLatch(1);
        private final CountDownLatch connectionErrorShown = new CountDownLatch(1);
        private StudentJoinRequest joinedRequest;
        private QuestionView question;
        private int questionNumber;
        private int totalQuestions;
        private List<LeaderboardEntry> entries;
        private boolean finalResults;
        private String answerQuestionId;
        private boolean answerAccepted;
        private String answerReason;
        private String errorMessage;
        private List<Participant> participants;

        private TestView(int expectedEvents) {
            completed = new CountDownLatch(expectedEvents);
        }

        @Override
        public void showConnecting() {
        }

        @Override
        public void showLobbyScreen(StudentJoinRequest request) {
            joinedRequest = request;
            completed.countDown();
        }

        @Override
        public void showParticipants(List<Participant> participants) {
            this.participants = participants;
            participantsShown.countDown();
            completed.countDown();
        }

        @Override
        public void setLeaveHandler(Runnable leaveHandler) {
        }

        @Override
        public void showJoinScreen() {
            returnedToJoin.countDown();
        }

        @Override
        public void showQuestion(QuestionView question, int questionNumber, int totalQuestions) {
            this.question = question;
            this.questionNumber = questionNumber;
            this.totalQuestions = totalQuestions;
            completed.countDown();
            questionShown.countDown();
        }

        @Override
        public void setAnswerHandler(java.util.function.BiConsumer<String, Integer> answerHandler) {
        }

        @Override
        public void showAnswerSubmitting(String questionId) {
        }

        @Override
        public void showAnswerResult(String questionId, boolean accepted, String reason) {
            answerQuestionId = questionId;
            answerAccepted = accepted;
            answerReason = reason;
            completed.countDown();
        }

        @Override
        public void showLeaderboard(List<LeaderboardEntry> entries, boolean finalResults) {
            this.entries = entries;
            this.finalResults = finalResults;
            completed.countDown();
        }

        @Override
        public void showConnectionError(String message) {
            errorMessage = message;
            connectionErrorShown.countDown();
            completed.countDown();
        }
    }
}
