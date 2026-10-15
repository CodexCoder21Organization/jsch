# Independent test and code review

## Plan

- [x] Locate the requested checkout, test, production class, README, and repository instructions.
- [ ] Read the full lane brief/addenda and required Testing Architecture and Engineering Philosophy documents.
- [ ] Inspect the new test and nearby public API/protocol code for determinism, cleanup, realistic dependencies, failure points, and assertion coverage.
- [ ] Record review findings with OBSERVED evidence separated from INFER conclusions; send concise results to the orchestrator.

## Progress (2026-10-15)

- OBSERVED: Checkout is `/home/u/lanes/util20261014/workspace/jschfix6`; `src/test/java/com/jcraft/jsch/FailedOpenReplyTest.java` and `src/main/java/com/jcraft/jsch/Session.java` are present. Root `README` exists; `rg --files` found no `AGENTS.md` in this checkout.
- OBSERVED: Read began for `/home/u/lanes/util20261014/br/common.md`, the requested Testing Architecture and Engineering Philosophy files, and the root README. The first combined output was truncated, so I am reading the required documents in bounded sections before reviewing code.
- OBSERVED: Read all of `/home/u/lanes/util20261014/br/common.md` (including session addenda), all of `/home/u/lanes/util20261014/out/jschfix6-testing.md`, all of `/home/u/lanes/util20261014/out/jschfix6-philosophy.md`, and the repository `README`. The review criteria most relevant here are public-API assertions, controlled real loopback I/O, deterministic fault timing, no mocks/reflection, cleanup on every path, and testing both full and partial writes without weakening the stated contract.
- INFER: The explicit task contract controls the expected behavior after `io.put` throws even when delivery may have completed; I will assess whether the assertions faithfully encode that contract, without proposing a disconnect policy.

## Independent test review

### OBSERVED

- `FailedOpenReplyTest.java:42-108` starts Apache SSHD on `127.0.0.1` with OS-assigned port `0`, completes a real SSH handshake and password authentication, then arms a public JSch `SocketFactory` after connect. There are no mocks, reflection, fixed ports, production endpoints, or sleeps.
- The three injected write cases are distinct and deterministic (`:82-96`): throw before writing bytes; write one byte then throw; write and flush the full byte array, then throw. `IO.put(Packet)` calls `out.write(...)` followed by `out.flush()` (`IO.java:70-73`), so the wrapper covers the actual production write seam, including the after-flush case.
- The test checks the exact `JSchException` message, closed channel, and still-connected session for every failed call (`:111-117`). It then sends a real SSH `CHANNEL_OPEN_FAILURE` packet for each collected channel ID and one unknown-ID control. A following `UNIMPLEMENTED` packet and latch provide an ordering barrier before inspecting the logger output (`:119-143`).
- Resources are released in `finally` (`:144-146`): the client session is disconnected and the local SSHD server is stopped. The diagnostics list is safe for the client reader thread to append to.
- The repository root has `README` and no `AGENTS.md` was found by the tracked-file search. No build or test was run by this review lane. The orchestrator reports the native red result as 9/9 intended assertion failures, with 64 failed opens and zero ignored replies in the first case, and zero ignored replies in each single-open case; I did not independently run that command.

### INFER

- The setup makes each failure point observable without depending on scheduling: the expected exception must come from the wrapper, and the post-reply marker confirms the client reader processed all earlier replies before the assertion. The tests therefore exercise the stated pending-reply rule through JSch's public session/channel calls while using a real loopback SSH peer.
- The coverage is sufficient to demonstrate cleanup after pre-write, partial-write, and post-flush exceptions. A separate `SSH_MSG_CHANNEL_OPEN_CONFIRMATION` case would make the other reply type explicit; however, both confirmation and failure dispatch use the same `consumeChannelOpenReply` helper, so this is an optional coverage addition rather than a gap in the tested cleanup mechanism. The current test directly asserts only the failure-reply branch.

**Test review result:** no test-construction blocker found. The one reply-type note is recorded above for the orchestrator's scope decision.

## Plan status

- [x] Read requested instructions and project context.
- [x] Review deterministic public behavior, dependency realism, cleanup, and write-failure cases.
- [ ] Await the orchestrator's request before reviewing the production delta and release application.

## Production and release review

### OBSERVED

- The master change is limited to `Session._write` around `Session.java:1942-1954`. The existing packet lock still covers publication, `io.put(packet)`, and cleanup. The sequence-number and strict-KEX handling remains after the write block and is unchanged.
- The release application at `.reader6` is based on `ae0fffa`. Its `Session._write` has the same cleanup block at `.reader6/src/main/java/com/jcraft/jsch/Session.java:1820-1832`; the existing `receiveCreditOwner` guard remains before packet handling (`:1808-1810`). Its POM changes only `0.2.18-reader5` to `0.2.18-reader6`, and its `Readme.md` states the same reply policy.
- `git diff --exit-code ae0fffa -- ...` returned clean for `.reader6/src/main/java/com/jcraft/jsch/Channel.java` and the protected `ChannelLateOpenTest`, `ChannelLateOpenRekeyTest`, and `PendingCloseBoundTest` files. On the master checkout, the only tracked changes are `Session.java`, `Readme.md`, and the new regression test.
- Existing protected tests exercise late confirmations and failures: `PendingCloseBoundTest.java:251-264` sends an open failure followed by the saved confirmation, and `ChannelLateOpenTest` exercises a delayed confirmation. This resolves the earlier optional confirmation-coverage note at the suite level.
- `.reader6/src/test/java/com/jcraft/jsch/FailedOpenReplyTest.java` is currently untracked. The orchestrator said it will be refreshed from the formatted master copy before packaging.

### INFER

- The `sent` flag is set only after `io.put` returns. If `io.put` throws at any point, `finally` removes the open ID while the existing monitor is still held. A reader that already received a reply cannot inspect the set until the write path releases that monitor, so it will see the removed ID and take the ignore path. If the write returns normally, the ID remains available for the eventual reply. This covers an exception before bytes, after some bytes, or after all bytes reached the output stream, as required.
- I found no issue in the master change or its release application. The release guard, version update, documentation, and protected tests remain consistent with the reviewed change.

**Code and release review result:** no concrete blocker found. No build or test was run by this review lane.

## Plan status

- [x] Read requested instructions and project context.
- [x] Review the new test and report test coverage.
- [x] Review the production change and release application without running builds or tests.
