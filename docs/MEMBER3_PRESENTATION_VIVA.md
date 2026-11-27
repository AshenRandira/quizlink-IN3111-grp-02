# Member 3 presentation and viva preparation

## Two-minute presentation script

Hi, my contribution is Member 3's UDP discovery and lobby module. The goal of discovery is to
let a student find a QuizLink server on the local network without typing its IP address first.
UDP works well for this initial probe because the client can broadcast before it knows the
server's address. It is best effort, though, so the client uses a timeout and does not assume
that a request or reply will always arrive.

The client sends the exact message `QUIZLINK_DISCOVER_V1` to UDP port 5001. The server checks
the packet and, if it is valid, sends a JSON response by unicast to the requester's address and
UDP port. The client takes the server IP from the response packet's source address and reads
the advertised TCP port, normally 5000, from the JSON. This only discovers an endpoint; it
doesn't establish the TCP connection or prove that the central server is running.

The lobby service creates six-character uppercase letters-and-digits room codes. It retries
collisions a bounded number of times and uses an atomic registration operation so concurrent
room creation cannot overwrite an active room. When a student joins, the service validates the
room and display name and assigns a server-generated participant ID. Per-room synchronization
protects simultaneous joins, leaves, and closure. Participant lists are immutable snapshots.

I tested the module with focused UDP and lobby tests, as well as the full project suite. The
Stage 11 evidence is 91 Member 3 tests and 186 total tests passing, with no failures, errors, or
skips. On one laptop I also tested broadcast and loopback discovery, timeout, shutdown, and
release of UDP port 5001. Two-computer LAN testing and integration with the central NIO server
are still pending, so I am not claiming a complete end-to-end quiz.

## One-minute backup script

I'm Member 3, responsible for UDP discovery and the in-memory lobby. The discovery client
broadcasts `QUIZLINK_DISCOVER_V1` to UDP port 5001. A server validates it and unicasts JSON with
its advertised TCP port, normally 5000. The client gets the IP from the response packet's
source address. Because UDP is not guaranteed, discovery has a bounded timeout. It finds an
endpoint but does not connect to TCP.

The lobby creates six-character room codes with collision checks, registers participants with
server-generated IDs, and synchronizes membership per room. It returns immutable participant
snapshots. Stage 11 tests passed for 91 Member 3 tests and 186 total tests. Same-computer UDP
checks passed; two-computer testing and central-server integration remain pending.

## Three-slide outline

### Slide 1 — My Role and Networking Concept

- Member 3: UDP discovery plus room and participant services.
- UDP broadcast finds a server without a typed IP.
- TCP is the later reliable quiz-message channel.
- Discovery result is an endpoint, not a joined session.

**Suggested visual:** Small LAN sketch with Student, UDP discovery service, and a separate TCP
connection arrow. Clearly label central-server integration as pending.

**Speaker notes:** “My module finds the server and manages lobby state independently. Discovery
and quiz communication are separate steps.”

### Slide 2 — UDP Discovery Workflow

- Send exact `QUIZLINK_DISCOVER_V1` to UDP port `5001`.
- Server validates request length and bytes.
- Server unicasts JSON to the requester's source IP and UDP port.
- Client validates response; IP comes from packet source, TCP port from JSON.
- Timeout and socket cleanup are part of the client lifecycle.

**Suggested visual:** Use the UDP discovery sequence diagram from
[`DISCOVERY_LOBBY.md`](DISCOVERY_LOBBY.md).

**Speaker notes:** “Port 5001 is UDP discovery. Port 5000 is only the advertised TCP destination;
the discovery demo does not start or test that TCP service.”

### Slide 3 — Lobby Management and Testing

- Six-character uppercase alphanumeric room-code candidates.
- Atomic active-room registration with bounded collision retries.
- Server-generated participant IDs and per-room synchronization.
- Immutable participant snapshots; leave and closure cleanup.
- 91 Member 3 tests; 186 total Stage 11 tests passed.

**Suggested visual:** Room creation and join/leave sequence diagrams, plus a short test-summary
screen capture. Do not show personal data or imply a live end-to-end integration.

**Speaker notes:** “The lobby API is implemented and tested, but the central server must still
route requests, authorize hosts, map TCP connections to participant IDs, and coordinate the
quiz engine.”

## Viva questions and concise answers

1. **What is your role in QuizLink?**  
   I implemented UDP discovery and the standalone in-memory room and participant lobby services.
   The central TCP server and UI integration belong to other team modules.

2. **What is UDP?**  
   UDP is a connectionless transport that sends datagrams. It does not guarantee delivery or
   ordering.

3. **Why use UDP for discovery?**  
   The client can broadcast a small request before it knows the server IP or has a TCP
   connection.

4. **Why not use TCP for discovery?**  
   A TCP connection normally needs a destination IP first. The discovery goal is to find that
   destination on the LAN.

5. **What is a `DatagramSocket`?**  
   It is the Java socket API used to send and receive UDP datagrams.

6. **What is a `DatagramPacket`?**  
   It holds datagram bytes and addressing information, including source address and port for a
   received packet.

7. **What is UDP port 5001 used for?**  
   It is the configured discovery port where the UDP server listens and the client sends its
   discovery request.

8. **What is TCP port 5000 used for?**  
   It is the project's default TCP quiz-server port advertised in the response. The UDP demo
   does not start that TCP server.

9. **What does broadcast mean here?**  
   The client sends a request to broadcast addresses for active IPv4 interfaces and also tries
   limited broadcast so servers on the local segment may receive it.

10. **What is unicast?**  
    Unicast is communication from one sender to one destination. The server replies directly to
    the request packet's source address and source UDP port.

11. **Why does the server reply by unicast?**  
    Only the requesting client needs the response; broadcasting every reply would create
    unnecessary LAN traffic.

12. **How does the client identify the server IP?**  
    It uses the source address of the valid response datagram. The JSON response contains the
    service, protocol version, and TCP port, not an IP field.

13. **What happens when there is no response?**  
    `discover()` returns `Optional.empty()` when its overall deadline expires. The default
    timeout is 1.5 seconds.

14. **Does UDP guarantee that the server receives the request?**  
    No. Requests and replies can be lost, so the client times out instead of waiting forever.

15. **How are invalid discovery requests handled?**  
    The server requires the exact UTF-8 request and exact length. Invalid or oversized packets
    are ignored without a response.

16. **How are invalid discovery responses handled?**  
    The client ignores malformed, unsupported, truncated, oversized, or incorrectly typed JSON
    responses until a valid response arrives or the deadline expires.

17. **What if several servers respond?**  
    The first valid response received is returned. The current protocol has no authentication or
    server-selection UI.

18. **How does the UDP client close its socket?**  
    Each `discover()` call owns a socket in try-with-resources, so it closes on success, timeout,
    or exception.

19. **How does the UDP server stop?**  
    `stop()` closes the socket to unblock `receive`, waits for the listener thread, and is
    idempotent. The service can be restarted after `stop()` returns.

20. **Why use a background listener thread?**  
    `DatagramSocket.receive()` blocks. A dedicated daemon thread keeps it off the caller and any
    future NIO Selector thread.

21. **What room-code format is used?**  
    Six characters from `A-Z` and `0-9`, normalized by trimming and uppercasing with
    `Locale.ROOT`.

22. **How are room codes generated?**  
    `RoomCodeGenerator` uses `SecureRandom` to create a candidate. The generator alone does not
    reserve or guarantee uniqueness.

23. **How are duplicate active room codes prevented?**  
    `LobbyService` tries candidates up to 32 times by default and uses `ConcurrentMap.putIfAbsent`
    to atomically register a room without replacing an existing one.

24. **What is `SecureRandom` doing here?**  
    It provides a strong random source for candidate room-code characters. Room codes are
    identifiers, not authentication credentials.

25. **What is a `ConcurrentHashMap` used for?**  
    The active-room map and issued-participant-ID set use concurrent collections so independent
    lookups and updates can operate safely across threads.

26. **Why is `putIfAbsent` useful?**  
    It makes the check-and-register operation atomic, so two simultaneous room creations cannot
    replace the same active room.

27. **How are participant IDs generated?**  
    The default `LobbyService` uses UUID strings and reserves issued IDs for the lifetime of that
    service instance. It retries collisions a bounded number of times.

28. **Why use UUIDs for participants?**  
    UUIDs provide opaque, unlikely-to-collide IDs that the server can assign independently of a
    display name. They are not a substitute for trusted connection mapping.

29. **How are simultaneous joins handled?**  
    Each room has its own monitor. The service verifies the room is still the active room and
    registers the participant while holding that room's lock.

30. **What happens when a student leaves?**  
    `leaveRoom(roomCode, participantId)` removes that participant and returns whether it removed
    one. Repeating the operation returns false.

31. **Does `LobbyService` detect a TCP disconnect by itself?**  
    No. The central server must map the disconnected connection to its participant ID and call
    `leaveRoom`; then it should coordinate the quiz engine and roster event.

32. **What is an immutable participant snapshot?**  
    `getParticipants()` returns a copied, unmodifiable list. Later membership changes do not
    alter a snapshot already returned to a caller.

33. **How is stale cleanup after room-code reuse prevented?**  
    A removed room has a distinct room state, and issued participant IDs are not reused during
    the service lifetime. A stale ID therefore cannot remove a new participant with a recycled
    room code.

34. **What is Gson used for?**  
    Gson serializes the small discovery response and supports lobby JSON/message payloads.
    `LobbyMessages` checks required JSON types before constructing shared models.

35. **What is JUnit 5 used for?**  
    It runs the automated unit and loopback-network tests for discovery, room management,
    membership, and protocol helpers.

36. **How did you test your module?**  
    Stage 11 verified 91 Member 3 tests and 186 project tests passing. Manual same-computer checks
    covered broadcast, loopback unicast, timeout, shutdown, and UDP port release.

37. **What are the remaining limitations?**  
    The central NIO server is still a placeholder; full host/student routing, two-computer LAN
    testing, and end-to-end application integration remain unverified.

38. **How does your module integrate with the central server?**  
    Member 2 should start/stop discovery with the server, route room messages through
    `LobbyMessages` and `LobbyService`, maintain trusted connection-to-participant mappings,
    and coordinate membership with `QuizEngine`.

39. **Does the lobby authorize a host or trust a client participant ID?**  
    No. Host authorization and connection identity are server responsibilities. The join parser
    ignores client-supplied participant IDs, and the server must use the ID returned by
    `LobbyService.joinRoom`.

40. **Does discovery prove that a quiz room exists or TCP is reachable?**  
    No. It only returns a valid advertised endpoint. TCP connection, authorization, room
    existence, and joining are separate steps.

41. **What is the role of `LobbyMessages`?**  
    It validates lobby payloads and builds existing protocol messages. It does not perform
    networking, authorization, routing, or membership mutation.

42. **Is there a `ROOM_LEFT` response?**  
    No. The current shared message types do not define one. The server should update the
    remaining participants with `PARTICIPANTS_UPDATED` after a successful leave.
