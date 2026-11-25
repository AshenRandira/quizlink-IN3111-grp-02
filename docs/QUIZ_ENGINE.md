# Quiz engine (Member 4)

Package `lk.quizlink.quiz` runs live quiz sessions: question progression, countdown timers, answer validation, scoring and leaderboards. It has no network code of its own. The NIO server (Member 2) calls it and forwards the messages it produces.

## Threading model

| Work | Runs on |
| --- | --- |
| `startQuiz`, `submitAnswer`, `endQuiz` | `quiz-worker-N` fixed pool (`ExecutorService`), returns `CompletableFuture` |
| Question timeouts, pause between questions, closing early | `quiz-timer-1` (`ScheduledExecutorService`) |
| Per-room state changes | Guarded by that room's lock, so rooms run independently |

The Selector thread only calls methods that return immediately. It never waits on a future (no `join()` or `get()`). It reacts to completion callbacks and `QuizEventSink` calls by enqueueing the outgoing message for the right connection(s) and calling `selector.wakeup()`.

## Server integration

```java
QuizEngine engine = new QuizEngine((roomCode, event) -> {
    // Called from quiz threads. Must be thread-safe, non-blocking, and must not call back into the engine.
    outbound.broadcastToRoom(roomCode, event);   // enqueue + selector.wakeup()
});

// CREATE_ROOM (after the lobby assigns a room code)
engine.createSession(roomCode, quiz);

// ROOM_JOINED / LEAVE_ROOM / disconnect (from the lobby)
engine.addParticipant(roomCode, participant);
engine.removeParticipant(roomCode, participantId);

// START_QUIZ / END_QUIZ (only after checking the sender is the room's host)
engine.startQuiz(roomCode).whenComplete((ok, error) -> reply(requestId, error));
engine.endQuiz(roomCode).whenComplete((finalBoard, error) -> reply(requestId, error));

// SUBMIT_ANSWER: participantId is the one bound to this connection, never taken from the payload
engine.submitAnswer(roomCode, participantId, questionId, selectedOptionIndex)
      .whenComplete((result, error) -> send(connection, error == null
              ? QuizMessages.answerResult(requestId, result)
              : QuizMessages.error(requestId, error)));
```

`QuizMessages.error` converts a `QuizException` into the matching protocol `ERROR` code and message. Any other failure becomes `INTERNAL_ERROR` without exposing details.

## Events published through the sink

| Event | When |
| --- | --- |
| `QUESTION` | Quiz starts, and after each intermission (`QuestionView` only, no answer key) |
| `LEADERBOARD` | A question closes and more questions remain |
| `QUIZ_ENDED` | The last question closes, or the host ends the quiz (final entries) |

Payloads match `docs/PROTOCOL.md`; events have an empty `requestId` and include `roomCode`.

## Rules implemented

- A question closes when its time limit expires **or** as soon as every active participant has answered.
- One accepted answer per participant per question. Later attempts get `ALREADY_ANSWERED`.
- Rejections: `QUIZ_NOT_STARTED` before start; `QUESTION_EXPIRED` after the deadline, after closing, or for an earlier question; `INVALID_MESSAGE` for an unknown question or an out-of-range option (this does not use up the participant's answer); `FORBIDDEN` for participants not in the session.
- Scoring follows the agreed starting rule: full question points for a correct answer, zero otherwise. `ANSWER_RESULT` acknowledges receipt only. Correctness shows up in the next leaderboard.
- Leaderboard order: score (highest first), then display name, then ID. Equal scores share a rank (1, 2, 2, 4).
- A participant who leaves before the start is removed. One who leaves during the quiz keeps their score but no longer holds up early closing.
- `startQuiz` requires at least one participant and can only run once. `endQuiz` is idempotent. `removeSession` cancels a room's timers.

Defaults: 4 worker threads and a 3-second intermission. Both can be set through the four-argument constructor.

## Testing and demonstration

```bash
mvn -Dtest='lk.quizlink.quiz.*Test' test
mvn compile exec:java -Dexec.mainClass=lk.quizlink.quiz.QuizEngineDemo
```

The tests cover scoring, every rejection reason, deadline expiry (with a controllable clock), timer-driven and early closing, 40 participants answering at once, 20 simultaneous duplicate submissions (exactly one accepted), the host ending a quiz early, and timers being cancelled.
