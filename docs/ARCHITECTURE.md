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

## Work still required

No transport, lobby, quiz engine, storage service or announcement service is implemented yet. Confirm service APIs between owners before adding dependencies. In particular, decide how a host connection is authorized to control its room, how the server obtains quiz definitions, and how quiz workers deliver results back to the Selector thread. Do not treat a message's declared role or participant ID as proof of authority.
