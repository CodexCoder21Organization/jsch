# jschfix5 review findings

## Review status

- [x] Verify baseline, protected files, repository instructions, and required documents.
- [x] Review `PendingCloseBoundTest.java` for public behavior, peer ordering, exact counts, and cleanup.
- [x] Independently review the `Session.java` change for reply registration/consumption, lock order, lifecycle behavior, and adjacent correctness.
- [x] Check the reader4-lineage release application.
- [x] Record findings with evidence and mark the current test gate accurately.

This was a read-only review. I did not edit the repository, run builds or tests, push, merge, publish, or spawn agents. I wrote only this findings file outside the repository.

## Progress and evidence

OBSERVED — The master checkout is at `c89636447f36d21681b8b47b06b7f6c4b6647a3b`. The Session change is the `c896364` commit relative to its parent `ca942b7`; the test includes the prior `1cb9816` scenario and the added failure-then-confirmation scenario from `ca942b7`. The master working tree has no source edits. `git diff --check` reports no whitespace errors.

OBSERVED — The protected files remain byte-identical to current master HEAD. Their SHA-256 values match both the worktree and `git show HEAD:<path>`:

- `ChannelLateOpenTest.java`: `eb523698b133d41a85650bab5650a7da18e282ce3c5d6b0720199f98689835d5`
- `ChannelLateOpenRekeyTest.java`: `39bbbd85799c59c241935de8944ab5bc36c44ca801affc91a545f1a01fdfb8ad`

The same hashes match the release worktree's reader4 parent. The master and release copies of `PendingCloseBoundTest.java` compare byte-for-byte equal.

OBSERVED — The release worktree is based on reader4 at `039816edbddec37610c1223524c4337a81a9a433`; its pending changes set the version to `0.2.18-reader5`, update `Readme.md`, apply the same 31-line reply tracking and consumption behavior, and add the same pending-close test. The release `_write` path retains its existing `receiveCreditOwner` guard. `git -C release diff --check` reports no whitespace errors.

OBSERVED — Required reading completed: `/home/u/lanes/util20261014/br/common.md` in full, including all addenda; the repository `Readme.md`; no root `AGENTS.md` was present; `/home/u/lanes/util20261014/out/jschfix4-findings.md`, `revjsch2-findings.md`, and `jschfix3-findings.md` in full; `/home/u/lanes/util20261014/out/jschfix5-testing.md` and `jschfix5-philosophy.md` in full. The latter files are local copies of [Testing Architecture](https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/architecture/TESTING.md) and [Engineering Philosophy](https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/PHILOSOPHY.md). I also read `/home/u/lanes/util20261014/out/jschfix5-peer-session.java`, the Apache SSHD 2.12.0 `AbstractSession` source used to verify the peer replay path.

### Test review

The test exercises a real JSch session over loopback against an Apache SSHD peer; it uses no mocks or reflection. The peer holds a real open confirmation, drives packet ordering over the connection, and observes actual close notices.

Coverage is aimed at the right public behavior. The first scenario times out an open, holds rekey negotiation, sends 512 late confirmations in two batches, and puts an `UNIMPLEMENTED` marker after each batch. Those markers establish that the client reader passed the replies while rekey remained pending. It checks that no close notice is sent during that exchange, then checks an unknown local ID and expects exactly one close notice addressed to the saved peer channel ID after the exchange. The second scenario sends a late open failure, then a held confirmation for the same local ID, followed by a marker and a keepalive; it expects zero close notices. The peer counts notices and wrong recipients exactly. Both scenarios use a loopback ephemeral port, bounded waits, `@Timeout(30)`, and `finally` cleanup for the session, writer executor, and server.

FINDING — The rekey replay bypasses Apache SSHD's serialized message path and makes the first scenario unreliable. At `PendingCloseBoundTest.java:78-80`, `resumeProposal()` calls `super.doHandleMessage(...)` directly on the test thread. Apache SSHD 2.12.0 normally enters message handling from `messageReceived()` while synchronized on `decodeLock`, then calls `handleMessage()` which runs the handler in its internal session context. The direct test call takes neither path. The saved KEXINIT can therefore be handled concurrently with the peer's normal decoder. In `AbstractSession.doKexNegotiation`, the peer sends its KEXINIT before it assigns and initializes the `kex` object, so the client response can arrive while this setup is still in progress. The test then waits for a client keepalive to reach the peer. This is consistent with the reported run where the client-side write future completed but the peer never observed the request.

Action: resume the held proposal through a peer path that serializes with `decodeLock` and uses the normal message handler context, then keep an explicit completion point before the follow-up exchange. Adding only another delay or post-write latch would not repair the handler overlap.

OBSERVED — The parent reports the current targeted gate at 26/27: one of three repetitions of `repeatedLateConfirmationsProduceOneCloseAfterExchange` timed out at `followingWrite.await(5s)` after the client write future completed; the other two repetitions passed. The failure-then-confirmation repetitions and 21 protected rows passed. The parent is investigating the peer ordering and has not called the gate green. This review did not run or reproduce the gate.

The test cases themselves cover the expected failure modes. The specific gap is the peer-side rekey replay mechanism above, which undermines the repeatability of the first case. The parent's prior baseline report says the old product failed all six intended assertions across the two repeated scenarios, including a repeated-close count of 513 where one was expected and one close notice after failure where zero was expected.

### Session source review

I compared the `Session.java` patch with `ca942b7`. The code extracts the outgoing local channel ID from a copied `Buffer`, preserving the caller's cursor. It adds the ID under the existing packet lock before `io.put`, so a fast reply cannot be consumed before registration. Both confirmation and failure handling remove the ID once under the same lock before looking up the channel. A first valid late confirmation still follows the existing path that queues or sends a close notice; unknown and repeated replies are dropped. Disconnect marks close handling complete and clears both pending collections.

No lock-order issue surfaced. Reply consumption releases the packet lock before channel lookup. The disconnect path releases that lock before iterating and disconnecting channels. Rekey completion flushes queued close notices under the same reentrant packet monitor, and does not call into the channel collection. I found no adjacent behavior regression in these paths.

No finding on retaining IDs for opens that have not received a reply: each such ID represents an unresolved peer open and is needed to recognize its eventual response. The ID is removed when a confirmation or failure arrives, and all remaining IDs are cleared when the session disconnects. A response cap would discard information needed to handle a late reply and is not supported by this review.

## Findings summary

1. **Action required before relying on the repeated late-confirmation test:** serialize the held KEXINIT replay with Apache SSHD's normal decode and handler path. The current targeted gate is not green.
2. **No Session bookkeeping or lock-order finding:** registration, one-time consumption, late confirmation cleanup, failure consumption, and disconnect cleanup are consistent in both master and reader4-lineage release applications.
3. **Protected test files preserved:** both are byte-identical to their respective baseline commits.
