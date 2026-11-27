# Member 3 demonstration guide

This guide covers the standalone UDP discovery demonstration and existing automated lobby tests.
The UDP demo starts only the discovery service; it does not start the central TCP server or
provide an end-to-end student quiz. Same-computer UDP checks below are completed evidence.
Two-computer LAN steps are a proposed future test plan.

## Standalone UDP discovery on one computer

Open PowerShell terminals in the repository root.

### 1. Compile

```powershell
mvn compile
```

### 2. Terminal 1: start the UDP discovery server

```powershell
mvn compile exec:java "-Dexec.mainClass=lk.quizlink.discovery.DiscoveryServerDemo"
```

The demo binds UDP port `5001` and reports the advertised TCP port `5000`. The discovery listener
binds the UDP socket; the demo explicitly reports that the central TCP server is not started.
Leave Terminal 1 running while testing.

### 3. Terminal 2: automatic broadcast discovery

```powershell
mvn exec:java "-Dexec.mainClass=lk.quizlink.discovery.DiscoveryClientDemo"
```

With no arguments, the client broadcasts to the available IPv4 broadcast destinations and
waits up to its default 1.5-second overall timeout for a valid reply.

### 4. Terminal 2: direct unicast discovery

```powershell
mvn exec:java "-Dexec.mainClass=lk.quizlink.discovery.DiscoveryClientDemo" "-Dexec.args=--direct 127.0.0.1"
```

This probes the local computer at `127.0.0.1`; it is a direct unicast check, not LAN broadcast.

### 5. Stop the server

Focus Terminal 1 and press **Ctrl+C**. If PowerShell asks `Terminate batch job (Y/N)?`, enter
`Y`. The demo shutdown hook stops the server and closes its socket.

### 6. Demonstrate timeout handling

With the server stopped, run either client command from Terminal 2 again. It should report that
no valid server response arrived before the timeout. A timeout is an expected result when no
discovery server responds; it is not evidence that a TCP connection was attempted.

### 7. Check UDP port release

```powershell
Get-NetUDPEndpoint -LocalPort 5001
```

After shutdown, no UDP endpoint for port `5001` is expected. If an endpoint is shown, identify
the owning process before taking action; do not terminate an unknown process.

### Previously observed manual results

The completed same-computer checks reported:

- The discovery server started and bound UDP port `5001`.
- Automatic broadcast discovery returned `192.168.56.1`; this may be a virtual network adapter.
- Direct unicast discovery to `127.0.0.1` succeeded.
- The response advertised TCP port `5000`.
- With the server unavailable, discovery timed out.
- Graceful server shutdown released UDP port `5001`.

These are the values previously reported for the manual tests, not a verbatim console transcript.
They do not demonstrate TCP reachability, a successful Student application join, or discovery
between two computers.

## Lobby demonstration using existing tests

No separate lobby demonstration application is part of Member 3's implementation. Run the
existing room, membership, and protocol tests:

```powershell
mvn "-Dtest=RoomCodeGeneratorTest,LobbyServiceTest,LobbyMembershipTest,LobbyMessagesTest" test
```

Useful existing test cases include:

- `RoomCodeGeneratorTest.generatesSixUppercaseAlphanumericCharacters` and
  `validatesSyntaxAndNormalizesUserInput`: six-character candidates and canonicalization.
- `LobbyServiceTest.createsAndRetrievesRoomForItsQuiz`,
  `createsIndependentRoomsAndRetriesCollidingCandidatesWithoutReplacement`,
  `reportsBoundedAllocationFailureWithoutOverwritingExistingRoom`, and
  `concurrentRoomCreationNeverReplacesOrDuplicatesActiveRooms`: room creation, collision
  retries, duplicate prevention, bounded failure, and concurrent registration.
- `LobbyMembershipTest.joinsRoomWithServerGeneratedIdAndTrimmedDisplayName`,
  `generatesDistinctParticipantIdsAndAllowsDuplicateDisplayNames`,
  `leavesOnlyTheSpecifiedParticipantAndIsIdempotent`,
  `participantSnapshotsAreImmutableAndRemainUnchanged`,
  `closingRoomRemovesMembershipAndReusedCodeStartsEmpty`,
  `staleDisconnectCleanupCannotRemoveMemberOfRecreatedRoom`, and
  `concurrentJoinsAndLeavesKeepTheFinalMembershipConsistent`: registration, unique IDs, join
  and leave, immutable snapshots, closure, stale cleanup, and concurrent operations.
- `LobbyMessagesTest.parsesFullSharedQuizDefinitionAndIgnoresUnknownFields`,
  `buildsCorrelatedRoomCreatedFromRegisteredRoomAndRoundTrips`,
  `buildsRoomJoinedWithActualServerParticipantWithoutJoiningAgain`,
  `participantUpdateIsRoomScopedUnsolicitedCopiedAndAllowsEmptyList`, and
  `mapsLobbyAndPayloadFailuresToExistingSafeCorrelatedErrors`: payload validation, protocol
  compatibility, room replies, roster events, and safe error mapping.

The tests exercise the standalone in-memory lobby and protocol helpers. They do not prove that a
real Student application can join a room: the central NIO server remains a placeholder in the
current local workspace.

## Proposed two-computer LAN test plan

This is a future test plan, not completed test evidence.

### Computer A: host the discovery service

1. Connect Computer A to the trusted project LAN over Wi-Fi or Ethernet.
2. In PowerShell, run `ipconfig` and note the IPv4 address and subnet mask for the active,
   connected Wi-Fi or Ethernet adapter. Ignore disconnected adapters, loopback, VPN, and virtual
   interfaces unless the team intentionally chose one for the test.
3. From the repository root, start the discovery server:

   ```powershell
   mvn compile exec:java "-Dexec.mainClass=lk.quizlink.discovery.DiscoveryServerDemo"
   ```

4. Confirm the console reports UDP port `5001`. The listener is intended to receive requests on
   the host's available interfaces.

### Computer B: discover Computer A

1. Connect Computer B to the same trusted LAN and confirm it is on a compatible subnet.
2. From the repository root, run:

   ```powershell
   mvn compile exec:java "-Dexec.mainClass=lk.quizlink.discovery.DiscoveryClientDemo"
   ```

3. Compare the reported server IP with Computer A's active Wi-Fi/Ethernet IPv4 address. A
   different result may indicate that broadcast used a VPN or virtual adapter, or that a
   different discovery server responded.
4. Stop Computer A's server with Ctrl+C after the test.

### Troubleshooting safely

- **Windows Firewall:** it may block inbound UDP port `5001` or replies. Do not disable the
  firewall. If a rule is needed, ask the authorized device/network administrator to review a
  narrowly scoped rule for the trusted private network and the Java process.
- **VPN and virtual adapters:** the client enumerates active IPv4 interfaces and may send
  through adapters that are not the intended LAN. Compare the discovered source IP with the
  host's active physical adapter; do not change adapter configuration automatically.
- **Wi-Fi client isolation:** some access points prevent devices on the same SSID from sending
  traffic directly to one another. Ask the network administrator whether peer-to-peer LAN
  traffic is permitted.
- **Different subnets or broadcast restrictions:** ordinary IPv4 broadcast is usually limited to
  a local segment and is not generally routed between subnets. Confirm both computers are on an
  approved, compatible LAN; do not alter router settings for this demo.
- **Multiple servers:** the client returns the first valid response it receives. There is no
  server-selection prompt or authentication. Stop unintended test servers and repeat; do not
  present the first response as proof that it is the intended host.

Even successful discovery only returns an IP address and advertised TCP port. It does not show
that TCP port `5000` is listening or that the full quiz application is integrated.
