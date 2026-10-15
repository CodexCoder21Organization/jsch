# Read-only review: deferred close across renegotiation

Review target: corrected `Session.java` from `21d416b`, with follow-up test cleanup at `c8d4c4d`.

## Review findings

- OBSERVED: I found no concrete defect in the corrected product flow. `pendingChannelCloses` and its terminal flag are private and guarded by `lock` (`Session.java:709-711`). A late confirmation passes its remote recipient to `closeLateChannel` (`Session.java:2112-2115`, `1640-1647`). Rekey completion clears `in_kex` and drains the queue while holding the same lock (`Session.java:1607-1625`), while `disconnect()` marks the queue terminal and clears it before closing channels and the transport (`Session.java:2282-2288`).
- OBSERVED: The consecutive-renegotiation test establishes a deterministic order. It arms the input gate before replaying the peer's late confirmation, starts rekey, waits until the peer holds its KEXINIT response, then releases the confirmation and sends a marker after it on the same connection (`ChannelLateOpenRekeyTest.java:162-180`). Observing the marker through the public JSch logger proves the reader passed the preceding late-confirmation handling while rekey remained pending. The test checks zero close notices before releasing the peer response; after rekey, it sends a public keepalive and waits for the peer to receive it before checking one close for that recipient (`181-197`). The inline comment at line 161 explains why the two rounds are serial. `c8d4c4d` also waits for the peer's channel-release callback before the next round (`190`).
- OBSERVED: The disconnect test uses the same held-confirmation and marker ordering, checks zero close notices while rekey is pending, calls public `disconnect()`, and waits for peer connection end and channel closure before asserting that no close was sent and no allocation remains (`ChannelLateOpenRekeyTest.java:209-244`). This is sufficient public behavioral proof that connection end discards a close already queued during rekey.
- OBSERVED: Client behavior is driven through public JSch APIs, including `connect`, `openChannel`, `rekey`, `sendKeepAliveMsg`, `disconnect`, `isConnected`, and channel methods. The fixture starts a real Apache SSHD server on an OS-assigned port and uses server-side hooks to hold and replay protocol packets. It does not read or mutate JSch internals, use reflection, or use mocks. `ChannelLateOpenTest.java` is byte-identical to the corrected source commit (`git diff --quiet` exit 0).
- OBSERVED: The earlier executor concern is addressed in `c8d4c4d`: its writer now uses a daemon thread, and shutdown remains bounded and asserted (`ChannelLateOpenRekeyTest.java:152-156`, `198-204`). One cleanup gap remains: if `awaitTermination` returns false, the assertion at line 202 throws before `server.stop(true)` at line 203, leaving the peer server running on this failure path. Move the assertion after `server.stop(true)` or put server shutdown in a nested `finally`.

## Documents read

- `/home/u/lanes/util20261014/br/common.md`
- `/home/u/lanes/util20261014/out/jschfix3-findings.md`
- `/home/u/lanes/util20261014/out/jschfix3-testing.md`, including deterministic tests, real dependencies, public API testing, ordering dependencies, side-effect isolation, and timeout guidance
- `/home/u/lanes/util20261014/out/jschfix3-philosophy.md`
- Repository `README`; `AGENTS.md` is absent.
- Compared `git diff 764cda9c..21d416b -- src/main/java/com/jcraft/jsch/Session.java` and inspected the corrected file at `21d416b`.

## Validation limits

OBSERVED: This was a read-only review. I ran no build or tests and edited no repository files. The review report is the only file I wrote.

## Follow-up cleanup check

OBSERVED: The current worktree wraps the bounded writer termination assertion in `try/finally` and calls `server.stop(true)` from that `finally` (`ChannelLateOpenRekeyTest.java:202-206`). If the assertion fails, server shutdown still runs. This resolves the remaining cleanup concern recorded above. I verified the diff and lines only; no build or test was run.

## Reader4 source review

Review target: `wip/jschfix3-reader4` at base `1c05727`, compared with the corrected helper flow in `21d416b`.

- OBSERVED: The current diff contains `Readme.md`, `pom.xml`, and `Session.java`; it does not change either test file. The POM changes only the version from `0.2.18-reader3` to `0.2.18-reader4`. The README adds the renegotiation wait and disconnect behavior to the existing stream-open cleanup description.
- OBSERVED: `Session.java` changes are limited to the intended recipient queue and terminal flag, queue initialization on connect, synchronized renegotiation start and completion, routing late confirmations through the queue helper, and clearing the queue on disconnect. The queue helpers and their lock use match `21d416b` semantically. The older reader3 code's `Channel.getChannel(i, this)` lookup remains unchanged; the new close route uses the recipient parsed from the confirmation, as the corrected master version does.
- OBSERVED: The same `lock` protects rekey state and queue state. Queue draining uses `_write` while holding that monitor, matching the master correction; `_write` uses the same Java monitor, which is reentrant. I found no additional lock-order or recipient-ownership issue in this diff.
- OBSERVED: `ChannelLateOpenRekeyTest.java` is byte-identical to `b531bee`; `ChannelLateOpenTest.java` is byte-identical to `1c05727` (`git diff --quiet` returned 0 for both comparisons). No test change was introduced by this source-porting step.
- OBSERVED: This was read-only review. No build or test was run, and no repository file was edited.
