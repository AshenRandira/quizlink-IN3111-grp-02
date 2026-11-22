# Team development guide

## Start a feature

Keep `main` buildable. Each member creates a branch for their assigned module:

```bash
git switch main
git pull origin main
git switch -c feature/student-client
```

Suggested branch names: `feature/student-client`, `feature/nio-server`, `feature/discovery-lobby`, `feature/quiz-engine`, and `feature/host-storage-multicast`.

Commit small, meaningful changes. Run `mvn clean verify`, push the branch and open a pull request into `main`. Include what works, how to run it, test evidence and remaining limitations. Ask an affected module owner to review shared contract changes. This is a team convention; branch protection and required reviews are not configured by the starter project.

## Agree before integration

- Confirm message types, payloads, model fields and network defaults with the team.
- Implement your assigned packages; coordinate edits to another member's module.
- Use `MessageCodec` for TCP JSON lines and UTF-8 for socket encoding.
- Keep blocking socket calls off Swing's Event Dispatch Thread. Post UI updates through `SwingUtilities.invokeLater`.
- Keep quiz calculations off the NIO Selector thread. Worker results must return through a thread-safe queue; only the Selector thread should manage connection buffers and interest operations.
- Test your module and provide a short demonstration command or sequence.

## Integration checklist

1. Start the NIO server, host client and at least two student clients.
2. Create/save a quiz and create a room.
3. Discover the server and test manual-IP fallback.
4. Test valid and invalid room codes, join/leave and participant updates.
5. Start a quiz, deliver questions, submit answers and test duplicate/late answers.
6. Verify timeouts, scores, leaderboard and quiz completion.
7. Verify multicast announcements on the LAN.
8. Disconnect a client unexpectedly and confirm the server and remaining clients continue.

The current starter does not implement these flows. Each module owner should add tests for their actual behavior as implementation progresses.
