# Draft protocol v1 — team review required

## TCP envelope and framing

Encode in UTF-8. Every message is one compact JSON object followed by LF (`\n`). The decoder accepts LF or CRLF as an optional terminator. The `version`, `type`, `requestId` and object `payload` fields are required. Clients generate a unique `requestId` for each request; direct replies preserve it. Unsolicited events use an empty string.

```json
{"version":1,"type":"JOIN_ROOM","requestId":"req-001","payload":{"roomCode":"AB12CD","displayName":"Student 1"}}
```

TCP is a byte stream: one channel read can contain part of a message or several messages. The NIO module must accumulate bytes per connection until LF, preserve incomplete UTF-8 sequences, decode every complete frame, and retain the trailing partial frame. Writes can also be partial; queue remaining bytes and resume on `OP_WRITE`. `MessageCodec` handles a **single complete line**, not stream accumulation.

Set a bounded frame size in the transport implementation and handle oversized/malformed frames without crashing the server. The starter codec validates envelope presence, known message types and protocol version; module handlers must validate payload types and values.

## Proposed messages

The following are payload drafts for module owners to review, not implemented handlers. Requests travel to the server; replies/events travel to the relevant clients.

| Type | Direction | Proposed payload fields |
| --- | --- | --- |
| `CREATE_ROOM` | Host request | `quiz` (full Quiz definition) |
| `ROOM_CREATED` | Server reply | `roomCode` |
| `JOIN_ROOM` | Student request | `roomCode`, `displayName` |
| `ROOM_JOINED` | Server reply | `roomCode`, `participant` |
| `LEAVE_ROOM` | Client request | `roomCode` |
| `PARTICIPANTS_UPDATED` | Server event | `roomCode`, `participants` (Participant array) |
| `START_QUIZ` | Host request | `roomCode` |
| `QUESTION` | Server event | `roomCode`, `question` (QuestionView), `questionNumber`, `totalQuestions` |
| `SUBMIT_ANSWER` | Student request | `roomCode`, `questionId`, `selectedOptionIndex` |
| `ANSWER_RESULT` | Server reply | `questionId`, `accepted` (boolean); optional `reason` |
| `LEADERBOARD` | Server event | `roomCode`, `entries` (LeaderboardEntry array) |
| `END_QUIZ` | Host request | `roomCode` |
| `QUIZ_ENDED` | Server event | `roomCode`, `entries` (final LeaderboardEntry array) |
| `SEND_ANNOUNCEMENT` | Host request | `roomCode`, `text` |
| `PING` / `PONG` | Client request / server reply | Empty object |
| `ERROR` | Server reply | `code`, `message` |

`ANSWER_RESULT` acknowledges receipt, not correctness, so the server can withhold answer feedback until the question closes. The server associates each student connection with its participant ID, accepts at most one answer per question and rejects expired or wrong-question submissions. The engine decides score calculation; the proposed starting rule is full question points for a correct answer and zero otherwise.

Proposed error codes: `INVALID_MESSAGE`, `INVALID_ROOM`, `ROOM_FULL`, `QUIZ_NOT_STARTED`, `QUESTION_EXPIRED`, `ALREADY_ANSWERED`, `FORBIDDEN`, `INTERNAL_ERROR`. Responses must not expose server stack traces. Host commands require server-side authorization against the room's host connection; that handling is pending.

## UDP discovery draft

Client broadcasts `QUIZLINK_DISCOVER_V1` to UDP port 5001. The service replies to the sender's address and source port:

```json
{"service":"QuizLink","version":1,"tcpPort":5000}
```

Use the response datagram's source address as the server IP. Apply a discovery timeout, validate responses and keep manual IP entry available. Multiple-server selection remains a discovery/UI integration decision.

## Multicast announcements draft

Subscribers listen on `239.10.10.10:5002`. A multicast datagram carries an `ANNOUNCEMENT` envelope whose payload contains `roomCode` and `text`. Filter by the joined room. This delivery is best effort; do not use it for authoritative quiz state. The multicast owner must coordinate LAN interface selection and socket lifecycle with both client owners.

The proposed settings target a trusted LAN demonstration. Authentication, TLS and internet deployment are outside this starter's implemented scope.
