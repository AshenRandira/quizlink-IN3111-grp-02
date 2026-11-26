# QuizLink — IN3111 Network Programming

A Java desktop quiz platform for computers on the same LAN. A host manages quizzes; students join a room, answer timed questions and view a leaderboard.

## Current status

The shared foundation is in place and the student client is implemented as an incremental feature branch. It provides a Swing join form, manual server-host entry, lobby participant list, question display, answer submission, leaderboard display, leaving a room and returning to the join form after final results. It uses a blocking TCP `Socket` on background threads and the shared JSON message protocol.

The quiz engine (`lk.quizlink.quiz`) is implemented: it runs sessions, timed questions, answer validation, scoring and leaderboards on its own worker and timer threads, ready for the NIO server to call. See [the quiz engine guide](docs/QUIZ_ENGINE.md).

The central NIO server, UDP discovery and authoritative lobby management remain separate team modules. Until those modules are integrated, the student application can be launched and tested against a compatible server but does not constitute a complete standalone quiz system.

## Technology

Java 17, Swing, Core Java (`java.net` and `java.nio`), Maven, Gson and JUnit 5. Quiz definitions and results use JSON files. One Maven project contains the server and both desktop clients.

## Setup

Install JDK 17 and Maven 3.9 or newer. Verify `java -version` and `mvn -version`; Maven must use JDK 17 or newer. Clone the shared repository:

```bash
git clone https://github.com/AshenRandira/quizlink-IN3111-grp-02.git
cd quizlink-IN3111-grp-02
mvn clean verify
```

Import `pom.xml` as a Maven project in IntelliJ IDEA, Eclipse or VS Code. Set the project language level to Java 17. The first build needs internet access to download dependencies.

## Run the applications

Use separate terminals from the repository root:

```bash
mvn compile exec:java -Dexec.mainClass=lk.quizlink.server.ServerApplication
mvn compile exec:java -Dexec.mainClass=lk.quizlink.host.HostApplication
mvn compile exec:java -Dexec.mainClass=lk.quizlink.student.StudentApplication
```

To demonstrate the quiz engine on its own (simulated students answering concurrently, no network needed):

```bash
mvn compile exec:java -Dexec.mainClass=lk.quizlink.quiz.QuizEngineDemo
```

The server and host launchers remain module placeholders until their owners complete them. The student launcher opens the working Swing client and requires a graphical desktop.

To join a running compatible server, enter its LAN IP address (or `localhost` when it runs on the same computer), the six-character room code and a display name. The client connects to TCP port `5000` by default. It keeps network reads and writes off Swing's Event Dispatch Thread, validates room-scoped events against the joined room code, and only displays question data as `QuestionView` so the correct answer is never sent to students.

## Package ownership

All paths below are under `src/main/java/lk/quizlink/`.

| Member | Packages | Responsibility |
| --- | --- | --- |
| 1 | `common`, `student` | Shared foundation coordination, student UI and Socket client |
| 2 | `server` | Central NIO server, connections and message routing |
| 3 | `discovery`, `lobby` | UDP discovery, rooms and participants |
| 4 | `quiz` | Session engine, answers, scoring, timers and worker threads |
| 5 | `host`, `storage`, `multicast` | Host UI, JSON persistence and announcements |

`common` is a shared contract: all affected owners review changes before merging. The five member numbers follow the team plan; names can be added when confirmed.

## Proposed network settings

| Service | Value |
| --- | --- |
| TCP quiz communication | Port `5000` |
| UDP discovery | Port `5001` |
| Announcement multicast | `239.10.10.10:5002` |
| TCP message framing | UTF-8 JSON, one message per line |

These defaults are declared in `ProtocolConstants`. Firewall and network checks become relevant once the transport modules are implemented. Manual IP entry must remain available when discovery is unavailable.

## Team development

Read [CONTRIBUTING.md](CONTRIBUTING.md), [the architecture guide](docs/ARCHITECTURE.md) and [the draft protocol](docs/PROTOCOL.md) before starting a module. Review the protocol together before treating it as final.

The initial scope prioritizes the core quiz and required networking demonstrations. Live polling is optional after integration is reliable.
