# Member 3 integration handoff

This document describes how the completed standalone discovery and lobby APIs can be integrated
with the other QuizLink modules. It does not claim that the central TCP workflow is currently
implemented. In the current local workspace, `ServerApplication` is a placeholder and opens no
listening socket.

For diagrams, standalone discovery commands, and verified test evidence, see
[`DISCOVERY_LOBBY.md`](DISCOVERY_LOBBY.md) and
[`MEMBER3_DEMONSTRATION.md`](MEMBER3_DEMONSTRATION.md).

## Member 1 — Student application

### Discovery API

```java
new UdpDiscoveryClient().discover()
```

The call returns `Optional<DiscoveryResult>` and throws `IOException` for socket setup or total
send failure. It blocks until the first valid reply or the overall timeout; the default timeout
is 1.5 seconds. Call it from a background worker, not Swing's Event Dispatch Thread.

`DiscoveryResult` is an immutable record with:

- `InetAddress serverAddress()`
- `int tcpPort()`
- `String serverIpAddress()` — numeric address string without a hostname lookup.

The existing Student controller already keeps connection work off the Swing EDT:
`StudentClientController.connectAndJoin(String serverHost, StudentJoinRequest request)` submits
its work to a network executor and dispatches view updates through `SwingUtilities.invokeLater`
by default. A future discovery caller can run discovery on a background worker and, when a result
exists, pass its `serverIpAddress()` to that existing controller method. If discovery times out
or throws `IOException`, leave the existing manual server-host entry usable; do not make room
joining depend on broadcast.

Example of the actual discovery call and controller API, to be run from a worker:

```java
Optional<DiscoveryResult> result = new UdpDiscoveryClient().discover();
if (result.isPresent()) {
    controller.connectAndJoin(result.get().serverIpAddress(), joinRequest);
}
```

The variables `controller` and `joinRequest` represent the existing controller and validated
join form values; this is a usage fragment, not a new UI API. On an empty result, keep the user
on the existing manual-host path.

### TCP port and join contract

`StudentTcpClient.connect(String host, int port)` accepts an explicit TCP port. However, the
public `StudentClientController` constructor currently uses `ProtocolConstants.TCP_PORT` (5000),
and `connectAndJoin` accepts a host but no port. Therefore the currently agreed port works
without a Student API change. If the application must honor a non-default advertised
`DiscoveryResult.tcpPort()`, coordinate a Student-owned controller configuration change; do not
silently ignore a non-default endpoint.

The existing join operation sends `JOIN_ROOM` with `roomCode` and `displayName`, then expects a
correlated `ROOM_JOINED` containing the canonical room code. It reads room-scoped
`PARTICIPANTS_UPDATED` events and rejects events for another room. A client-supplied participant
ID is not part of the trusted join identity. The current shared protocol has no `ROOM_LEFT`
message; the Student controller sends `LEAVE_ROOM` with the room code and closes its connection.

`StudentJoinRequest` currently trims its fields and uppercases room codes with the default JVM
locale. Member 3 uses `Locale.ROOT` for room-code normalization. Coordinate this existing
cross-module normalization difference with the Student owner; no Student source is changed by
this handoff.

## Member 2 — Central NIO server

### Discovery service lifecycle

The actual Member 3 lifecycle API is:

```java
UdpDiscoveryServer discovery = new UdpDiscoveryServer();
discovery.start();
// On server shutdown:
discovery.stop();
```

`start()` binds the configured UDP port synchronously and starts a dedicated daemon listener
thread. It can throw `IOException` immediately if binding fails. `stop()` closes the socket,
unblocks the receive, and waits for the listener to finish; it is idempotent. `close()` delegates
to `stop()`, and an instance can be restarted after `stop()` returns. Start discovery only after
the central TCP listener successfully binds, and stop discovery during server shutdown. Do not
perform blocking lifecycle calls on the NIO Selector thread.

### Lobby and protocol APIs

`LobbyService` public operations:

```java
Room createRoom(Quiz quiz);
Optional<Room> findRoom(String roomCode);
boolean roomExists(String roomCode);
boolean closeRoom(String roomCode);
Participant joinRoom(String roomCode, String displayName);
Optional<Participant> findParticipant(String roomCode, String participantId);
List<Participant> getParticipants(String roomCode);
int getParticipantCount(String roomCode);
boolean leaveRoom(String roomCode, String participantId);
```

Room-code collisions are handled inside `createRoom`: up to 32 candidate attempts by default,
registered atomically with `putIfAbsent`. Membership methods use per-room synchronization and
`getParticipants` returns an immutable, insertion-ordered snapshot. `leaveRoom` takes a
server-resolved participant ID, not an ID supplied by the request. `closeRoom` clears membership
and removes the active room.

`LobbyMessages` public operations:

```java
Quiz parseCreateRoomPayload(JsonObject payload);
LobbyMessages.JoinRequest parseJoinRoomPayload(JsonObject payload);
String parseLeaveRoomPayload(JsonObject payload);
Message roomCreated(String requestId, Room room);
Message roomJoined(String requestId, String roomCode, Participant participant);
Message participantsUpdated(String roomCode, List<Participant> participants);
Message error(String requestId, Throwable failure);
```

These methods validate or build protocol messages only. They do not authorize a host, mutate
membership, map connections, send bytes, or broadcast events.

### CREATE_ROOM integration example

Authorize the host connection before processing the request. Then use the existing helper and
services:

```java
Quiz quiz = LobbyMessages.parseCreateRoomPayload(request.payload());
Room room = lobbyService.createRoom(quiz);
quizEngine.createSession(room.roomCode(), quiz);
Message response = LobbyMessages.roomCreated(request.requestId(), room);
```

The server must enqueue `response` to the requesting host and map exceptions using
`LobbyMessages.error(request.requestId(), failure)`. If session creation fails after the lobby
room is registered, roll back that newly created room before reporting failure. Do not claim
this routing is implemented in Member 3.

### JOIN_ROOM integration example

```java
LobbyMessages.JoinRequest join =
        LobbyMessages.parseJoinRoomPayload(request.payload());
Participant participant = lobbyService.joinRoom(join.roomCode(), join.displayName());
quizEngine.addParticipant(join.roomCode(), participant);
Message response = LobbyMessages.roomJoined(
        request.requestId(), join.roomCode(), participant);
List<Participant> roster = lobbyService.getParticipants(join.roomCode());
Message update = LobbyMessages.participantsUpdated(join.roomCode(), roster);
```

The server must bind the returned participant ID to the trusted TCP connection and send the
correlated response to that connection. Broadcast the roster event to the room, including the
new student as appropriate, after the `ROOM_JOINED` reply has been enqueued for that student.
If quiz-engine registration fails, remove the just-added lobby participant and send an error.
Serialize each room's membership mutation, quiz-engine synchronization, snapshot read, and event
enqueue on a per-room server sequence. The LobbyService does not provide a combined mutation plus
snapshot transaction, and no roster sequence number exists in the protocol.

### LEAVE_ROOM and disconnect integration

`LobbyMessages.parseLeaveRoomPayload(payload)` returns only a normalized room code. Before
calling `leaveRoom`, the server must look up the participant ID from its own connection-to-room
mapping, confirm that the connection belongs to that room, and then coordinate:

1. `lobbyService.leaveRoom(roomCode, trustedParticipantId)`.
2. `quizEngine.removeParticipant(roomCode, trustedParticipantId)` if membership was removed.
3. `lobbyService.getParticipants(roomCode)`.
4. `LobbyMessages.participantsUpdated(roomCode, snapshot)` and a server-owned broadcast.

Use the same cleanup path after unexpected TCP disconnects. The lobby does not detect socket
closure. It keeps IDs reserved for the `LobbyService` lifetime, which prevents stale IDs from
being reused but means the issued-ID set grows with total joins.

There is no `ROOM_LEFT` message. The current Student controller sends `LEAVE_ROOM` and closes
the connection without waiting for a direct leave reply. Notify remaining room members with
`PARTICIPANTS_UPDATED`; any different acknowledgment requires an agreed protocol change.

### Server responsibilities not supplied by Member 3

- Host authorization and connection ownership; room codes are not credentials.
- Trusted connection-to-participant mapping; never trust a participant ID from a payload.
- TCP accept/read/write handling, partial writes, and bounded line/frame size.
- `MessageCodec` decodes one complete JSON line; it is not a stream accumulator and does not
  implement a maximum frame size. Enforce a bounded frame before parsing.
- Per-room ordering across lobby state, QuizEngine changes, roster snapshots, and outbound event
  enqueue. Do not hold lobby locks while doing network I/O.
- Error routing. `LobbyMessages.error` maps malformed payloads and invalid names to
  `INVALID_MESSAGE`, invalid rooms to `INVALID_ROOM`, and allocation/unexpected failures to
  `INTERNAL_ERROR`. Authorization failures must be handled as `FORBIDDEN` by the server.
- Coordinating `LobbyService.closeRoom(roomCode)` with `QuizEngine.removeSession(roomCode)`.

The central NIO server is a placeholder in the current local workspace; all the routing above
is a handoff, not a claim of completed integration.

## Member 4 — Quiz engine

The actual `QuizEngine` API relevant to lobby coordination is:

```java
void createSession(String roomCode, Quiz quiz);
boolean hasSession(String roomCode);
void addParticipant(String roomCode, Participant participant);
void removeParticipant(String roomCode, String participantId);
CompletableFuture<Void> startQuiz(String roomCode);
CompletableFuture<AnswerResult> submitAnswer(
        String roomCode, String participantId, String questionId, int selectedOptionIndex);
CompletableFuture<List<LeaderboardEntry>> endQuiz(String roomCode);
List<LeaderboardEntry> leaderboard(String roomCode);
SessionStatus status(String roomCode);
void removeSession(String roomCode);
```

The server creates the session after the lobby assigns a room code, adds participants after
successful lobby registration, removes them after leave/disconnect, and removes the session
when the room closes. Use the participant ID attached to the trusted connection for
`submitAnswer`; never use a payload-supplied identity. `startQuiz`, `submitAnswer`, and
`endQuiz` return futures and must not be waited on from the Selector thread. The engine owns
quiz timing, scoring, answer validation, and leaderboard state; the lobby does not.

## Member 5 — Host application, storage, and multicast

The host application's existing contract is `HostGateway`:

```java
CompletableFuture<String> createRoom(Quiz quiz);
CompletableFuture<Void> startQuiz(String roomCode);
CompletableFuture<Void> endQuiz(String roomCode);
void setEventListener(Consumer<Message> listener);
void setDisconnectListener(Runnable listener);
void close();
```

`TcpHostGateway` sends `CREATE_ROOM` with the quiz under `payload.quiz` and expects a correlated
reply containing `roomCode`. The server should reply with the actual code returned by
`LobbyService.createRoom` via `LobbyMessages.roomCreated(requestId, room)`. `TcpHostGateway`
currently checks that a correlated non-`ERROR` response contains `roomCode` but does not verify
that its type is `ROOM_CREATED`; coordinate strict response-type validation with the Host owner.

The Host event listener should handle room-scoped `PARTICIPANTS_UPDATED` events and display the
server roster. The room code is generated by the server, not by the host UI. Preserve the
existing `MockHostGateway` offline demonstration mode: it simulates room creation and a mock
participant event and is not evidence of a network connection.

Storage remains responsible for quiz-file persistence; multicast remains a separate,
best-effort announcement channel. Neither is implemented by Member 3.

## Shared protocol and API notes

- Discovery is exact UTF-8 `QUIZLINK_DISCOVER_V1` over UDP port 5001. The default response is
  JSON with `service: "QuizLink"`, version `1`, and `tcpPort: 5000`.
- TCP messages use the shared `Message` envelope and existing `MessageType` values. Direct
  replies preserve `requestId`; `PARTICIPANTS_UPDATED` is unsolicited and uses an empty ID.
- `LobbyMessages` reuses shared `Quiz`, `Question`, `Participant`, `Message`,
  `MessageType`, and `ProtocolConstants`. Unknown payload fields are ignored, but required
  fields must have the expected JSON types.
- Student and host clients should preserve their manual/offline workflows when the server is
  unavailable. UDP discovery does not replace manual IP entry.
- Room codes and participant IDs are identifiers, not credentials. The server is the authority
  for roles and connection identity.

## Handoff verification boundary

Stage 11 verified 91 Member 3 tests and 186 project tests passing, with no failures, errors, or
skips. Same-computer UDP startup, broadcast, loopback unicast, timeout, shutdown, and port
release were manually reported. Stage 12 does not rerun or expand that evidence. Two-computer
LAN discovery, central-server startup integration, live host room creation, Student discovery
integration, and complete end-to-end quiz flow remain unverified.
