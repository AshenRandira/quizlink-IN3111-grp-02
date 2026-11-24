# Architecture and module boundaries

## Runtime design

Student and host Swing applications connect to one central server over TCP. Clients use blocking `Socket` calls on background threads. The server uses `ServerSocketChannel`, `SocketChannel`, `Selector` and per-connection buffers. Supporting services handle UDP discovery, lobby state, concurrent quiz processing, JSON persistence and multicast announcements.

| Package | Owns | Coordinates with |
| --- | --- | --- |
| `common` | Wire envelope, message types, shared data models | All members |
| `student` | Join, quiz, results UI and student TCP client | Server and discovery |
| `server` | Connections, framing, outbound queues and routing | Lobby and quiz engine |
| `discovery` | UDP request/response and client discovery | Server startup and student UI |
| `lobby` | Room codes, room membership and participant IDs | Server and quiz engine |
| `quiz` | Active sessions, timers, validation, scores and workers | Lobby, server and quiz definitions |
| `host` | Quiz editor, catalogue and host session controls | Storage and server |
| `storage` | Quiz definitions and result files | Host and quiz engine |
| `multicast` | Announcement sender and subscribed client listener | Host actions and both clients |

## Foundation decisions

- One repository and one Maven project; separate Java entry points for each process.
- `Question` includes the stored answer key; `QuestionView` excludes it and is the only question model sent to students.
- Option indexes are zero-based. Question duration is expressed in seconds and scores are integers.
- The server owns participant identity, session timing and score calculation. Client-supplied IDs must be checked against the associated connection.
- The lobby owns room membership; the quiz engine owns active question and score state.
- JSON files under `data/quizzes/` and `data/results/` are resolved relative to the repository root. Only the sample quiz is tracked.
- Multicast is for announcements. Questions, answers and authoritative scores remain on TCP.

## Implementation status and integration handoff

The shared `common` foundation is implemented: validated models, the JSON message envelope and codec, message types, protocol constants, and Gson compatibility tests are in place. The Student client is also implemented: its Swing interface uses a blocking TCP `Socket` on background threads to join a room, receive lobby/question/leaderboard events, submit answers, leave a room, and recover from a lost server connection. The client validates correlated replies and room-scoped events before updating the UI.

The central NIO server, UDP discovery, authoritative lobby, quiz engine, host dashboard, JSON storage and multicast services remain to be implemented by their assigned owners. Integration should use `docs/PROTOCOL.md` as the shared payload contract. In particular, the server must preserve a direct request's `requestId`, send empty `requestId` values for unsolicited events, include `roomCode` on room-scoped events, and send `QuestionView` rather than `Question` to students.

Before full integration, agree how a host connection is authorized to control its room, how the server obtains quiz definitions, and how quiz workers deliver results back to the Selector thread. Do not treat a message's declared role or participant ID as proof of authority.
