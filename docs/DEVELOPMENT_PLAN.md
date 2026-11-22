# QuizLink Development Plan

## IN3111 Network Programming Assignment

This document defines the incremental plan for QuizLink, including shared-model work, feature branches, meaningful commit points, testing gates, and module ownership boundaries.

## 1. Development principles

- Work with the existing Maven repository; do not recreate the project.
- Preserve package names, model field names, protocol names, and constructor signatures.
- Keep each member's implementation within their assigned module.
- Add tests with every new behavior.
- Use focused tests during development and `mvn clean verify` at substantial milestones.
- Make reviewable commits after a meaningful amount of work, not after every small edit.
- Do not commit or push changes without explicit approval.
- Keep authorization decisions in server-side services. A client-supplied ID, role, or room code is not proof of authority.

## 2. Branch strategy

The current branch is:

```
feature/shared-models
```

The five shared models form one common contract, so they should remain together on this branch rather than using one branch per model.

Recommended later feature branches:

```
feature/student-client
feature/tcp-client
feature/server-nio
feature/udp-discovery
feature/lobby-management
feature/quiz-engine
feature/host-dashboard
feature/json-storage
feature/multicast-announcements
```

Each branch should represent a meaningful feature or team module. Avoid branches such as `feature/quiz-model`, `feature/question-model`, and `feature/participant-model`, because those changes are tightly coupled and would create unnecessary merge conflicts.

Before starting a new branch, confirm the working tree and update from the agreed integration branch:

```powershell
git status --short --branch
git switch main
git pull
git switch -c feature/<feature-name>
```

Branch creation, merging, and pushing should only be performed after team approval.

## 3. Shared-model scope

The first development part covers:

```
src/main/java/lk/quizlink/common/model/Quiz.java
src/main/java/lk/quizlink/common/model/Question.java
src/main/java/lk/quizlink/common/model/QuestionView.java
src/main/java/lk/quizlink/common/model/Participant.java
src/main/java/lk/quizlink/common/model/LeaderboardEntry.java
```

The work must preserve:

- Package names
- Record names
- Record component names
- Constructor signatures
- Gson field names
- Message envelope structure
- The distinction between `Question` and `QuestionView`

`Question` contains the answer key for host/server use. `QuestionView` excludes `correctOptionIndex` and is the only question representation that should be sent to students.

## 4. Shared-model milestones

### Milestone 1: Add model validation

Add compact constructors without changing the public record APIs.

Validation requirements:

- `Quiz`: non-blank ID and title, non-null/non-empty questions, no null questions, unique question IDs.
- `Question`: non-blank ID and text, non-empty options, no null/blank options, valid answer index, positive time limit, non-negative points.
- `QuestionView`: valid public question data, without an answer key.
- `Participant`: non-blank ID and display name; reject line breaks/control characters in display names.
- `LeaderboardEntry`: non-blank participant ID and display name, non-negative score, rank at least one.

Model validation must not attempt to prove server ownership or authorization. Those checks belong to the server and lobby modules.

Add tests for valid objects, nulls, blank strings, empty lists, null list elements, invalid indexes, invalid times, invalid scores, invalid ranks, and duplicate question IDs.

Commit after implementation and focused tests pass:

```
feat(models): add shared model validation
```

### Milestone 2: Make collection fields immutable

Defensively copy these collections during construction:

- `Quiz.questions`
- `Question.options`
- `QuestionView.options`

The copy must preserve ordering and prevent callers from modifying the model through the accessor. This also prevents `Question.toView()` from sharing mutable option data with the answer-bearing question.

Add tests proving that:

- Mutating an input list does not change the model.
- Accessor lists cannot be modified.
- Option order is preserved.
- `Question.toView()` retains the expected public data.

Commit after implementation and focused tests pass:

```
refactor(models): defensively copy shared collections
```

### Milestone 3: Verify Gson and protocol compatibility

Add tests confirming that model hardening does not change the existing JSON contract.

Verify that a full `Quiz` and `Question` retain these field names:

```
id
title
questions
text
options
correctOptionIndex
timeLimitSeconds
points
```

Verify that `QuestionView` never serializes `correctOptionIndex`.

Verify that `Participant` continues to use:

```
id
displayName
```

Verify that `LeaderboardEntry` continues to use:

```
participantId
displayName
score
rank
```

Verify that the sample quiz can be deserialized and serialized, and that valid model objects survive a Gson round trip with equivalent values. Invalid JSON should fail during model construction rather than produce an invalid object.

Retain and expand the existing answer-key exclusion test.

Commit after compatibility tests pass:

```
test(models): verify Gson and protocol compatibility
```

### Milestone 4: Document model invariants

Update `docs/PROTOCOL.md` and/or `docs/ARCHITECTURE.md` only where necessary to document:

- Zero-based option indexes
- Positive question time limits
- Non-negative point values
- One-based leaderboard ranks
- Unique question IDs within a quiz
- Immutable model collections
- `QuestionView` as the student-safe question type
- Server ownership of participant identity
- The difference between model validation and server authorization

Optional commit:

```
docs(models): document shared model invariants
```

Do not create this commit if the existing documentation already covers the rules clearly.

## 5. Test plan

Add a focused test class such as:

```
src/test/java/lk/quizlink/common/model/ModelValidationTest.java
```

Organize tests around behavior:

1. Valid model construction
2. Required field validation
3. Collection validation
4. Numeric range validation
5. Answer-index validation
6. Defensive-copy behavior
7. Gson serialization/deserialization
8. Protocol field-name compatibility
9. Student-safe `QuestionView` behavior

Focused test command:

```powershell
mvn -q -Dtest=ModelValidationTest test
```

Full verification command after a substantial milestone:

```powershell
mvn clean verify
```

The existing `MessageCodecTest` must continue to pass.

## 6. Commit policy

A commit should contain a coherent, reviewable amount of work. Do not create commits for one-line changes, individual validation conditions, temporary debugging output, unrelated refactoring, generated build output, or another member's module.

Recommended shared-model history:

```
feat(models): add shared model validation
refactor(models): defensively copy shared collections
test(models): verify Gson and protocol compatibility
docs(models): document shared model invariants
```

The documentation commit is optional.

Before each commit:

```powershell
git status --short
git diff --check
mvn -q -Dtest=<focused-test-class> test
git diff
```

Before considering the branch complete:

```powershell
mvn clean verify
git status --short --branch
```

Do not push or merge until the team has reviewed the model contract and explicitly approved integration.

## 7. Ownership boundaries

The shared-model branch must not implement:

- TCP client networking
- NIO selectors or server routing
- UDP discovery
- Lobby room creation and membership
- Quiz timers or score calculation
- Swing student screens
- Swing host dashboard
- JSON storage services
- Multicast networking
- Server-side authorization

These should be implemented on their assigned feature branches after the shared model contract is stable.

## 8. Later feature development

### Student client and TCP client

Branch:

```
feature/student-client
feature/tcp-client
```

Responsibilities include student Swing screens, joining, question display, answer submission, results, leaderboard display, and the blocking `Socket` client on background threads.

Students may receive `QuestionView`, but must never receive `Question.correctOptionIndex`.

### Central NIO server

Branch:

```
feature/server-nio
```

Responsibilities include `ServerSocketChannel`, `SocketChannel`, `Selector`, per-connection buffers, UTF-8 framing, partial reads/writes, outbound queues, and message routing.

The server must validate payloads and enforce connection-level ownership.

### UDP discovery and lobby

Branches:

```
feature/udp-discovery
feature/lobby-management
```

Responsibilities include UDP discovery, room codes, participant ID assignment, membership, capacity, and host connection ownership.

### Quiz engine

Branch:

```
feature/quiz-engine
```

Responsibilities include active sessions, timers, answer validation, duplicate-answer rejection, expiry handling, score calculation, and leaderboard construction.

### Host dashboard and storage

Branches:

```
feature/host-dashboard
feature/json-storage
```

Responsibilities include quiz editing, quiz catalogue, host controls, JSON persistence, and loading validated quiz definitions.

### Multicast announcements

Branch:

```
feature/multicast-announcements
```

Responsibilities include the multicast sender/listener, room filtering, LAN interface selection, and socket lifecycle. Multicast must not carry authoritative quiz state, answers, or scores.

## 9. Definition of done for shared models

The shared-model work is complete when:

- All five models reject invalid required values.
- Answer indexes are validated.
- Question IDs are unique within a quiz.
- Shared lists are defensively copied and immutable to callers.
- `Question.toView()` never exposes the answer key.
- Gson round trips work for valid models.
- The sample quiz remains compatible.
- Protocol field names remain unchanged.
- New model tests pass.
- Existing `MessageCodecTest` passes.
- `mvn clean verify` passes.
- No unrelated module implementation has been added.
- The branch contains only meaningful, reviewable commits.

No changes should be pushed or merged until the team reviews and approves the shared model contract.

