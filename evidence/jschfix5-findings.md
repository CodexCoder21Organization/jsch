# jschfix5 findings
Started 2026-10-15 02:52 UTC; deadline 03:52 UTC.

Plan:
- [done] Read prior findings, common rules/addenda, repository README/Readme and required testing/philosophy guidance; fresh clone at exact requested PR head; rebase origin/master unchanged.
- [done] Reply-consumption correction; final new scenarios3/3 each, preserved neighbors once; final product-only reversal6/6 red; master525/525 once.
- [done] Separate reviews/fixture correction; reader4 lineage; release233/233 once and package; reader5 published and all four public files checksum verified.
- [done] PR updated/read back, required CI green, final evidence archived and owned processes cleaned; informational secondary check excluded by latest orchestrator note.

OBSERVED: PR https://github.com/CodexCoder21Organization/jsch/pull/3 OPEN at https://github.com/CodexCoder21Organization/jsch/commit/1cb9816ec8d3a2cb7b2e6992499db21c2f306535 on wip/jschfix2-master. Default branch is master, not main. No root AGENTS.md. Explicit current brief grants publication and CI watch, overriding common.md default prohibition. Handoff service operations are excluded by common addenda; reports go here. No scheduler tools are available; progress reports will be emitted during active work.

MECHANISM: the mechanism is accepting every confirmation for a removed local channel and queuing a close notice without consuming an expected reply; the evidence that demonstrates it is the pushed real-transport PendingCloseBoundTest red 3/3 on prior product, expected 1 notice and observed 513. Saved native XML and full assertion stacks are in out/jschfix4-red-results/. This lane verifies rather than repeats the already completed fail-first milestone.

Invariant table before product edits:
| Resource | Creation / owner / terminal rule | Public proof |
|---|---|---|
| Expected reply id | Record actual outgoing open under packet lock before sending; consume once on confirmation or failure; retain through local abandonment; clear on connection end | Repeated late replies and unknown id produce one notice; normal/failure neighbor tests |
| Deferred close notice | Existing packet-lock queue owns integer recipient, removes before write, clears on connection end | Exact count after held renegotiation; existing consecutive and disconnect tests |
| Reader | Unknown/repeated replies go to diagnostic and continue; no blocking reader write during renegotiation | Ordered peer markers and following keepalive |
| Channel | Existing channel monitor owns final open/close decision; no retained Channel object in reply set | Protected lifecycle file byte-identical and nine public scenarios |

## FOLLOW-UP
Finding 2 from revjsch1 remains separate as explicitly directed: Channel.java masks the global construction index to 31 bits, so a very old unresolved reply can bind to a reused numeric id after 2^31 constructions. No allocator change or runtime wrap claim in this lane.

## Challenges for the orchestrator
Inherited protected-file formatter constraint remains: ChannelLateOpenTest.java must stay byte-identical (SHA-256 eb523698b133d41a85650bab5650a7da18e282ce3c5d6b0720199f98689835d5), while global formatter validation requests whitespace changes. No validator exclusion is authorized or planned.

OBSERVED: reader5 POM preprobe returned404. Targeted command was stopped before acquiring a slot (no compile/test verdict) to add a missing public case: a failure reply must consume the expected response, so a later confirmation produces zero notices. This adds three rows beyond the repeated-reply regression; expected full counts become525 and233, retaining all519/227 predecessor rows. Product-only reversal will exercise both new scenarios against exact HEAD product.

2026-10-15 02:59 UTC — OBSERVED: Product-only reversal complete against exact PR HEAD product. Six native rows, six intended assertion failures, zero errors/skips/retries: repeated late replies3/3 observed513 vs expected1; late failure then confirmation3/3 observed1 vs expected0. Full native XML/stacks saved in out/jschfix5-reversal-results/. Both marker/following-write paths completed, so failures are exact counts, not hangs.

```text
org.opentest4j.AssertionFailedError: Duplicate late replies must not retain and emit duplicate close notices ==> expected: <1> but was: <513>
	at org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at org.junit.jupiter.api.AssertEquals.failNotEqual(AssertEquals.java:197)
	at org.junit.jupiter.api.AssertEquals.assertEquals(AssertEquals.java:150)
	at org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:563)
	at com.jcraft.jsch.PendingCloseBoundTest.repeatedLateConfirmationsProduceOneCloseAfterExchange(PendingCloseBoundTest.java:122)
	at java.base/java.lang.reflect.Method.invoke(Method.java:565)
	at java.base/java.util.Optional.ifPresent(Optional.java:178)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:186)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:214)
	at java.base/java.util.stream.IntPipeline$1$1.accept(IntPipeline.java:180)
	at java.base/java.util.stream.Streams$RangeIntSpliterator.forEachRemaining(Streams.java:104)
	at java.base/java.util.Spliterator$OfInt.forEachRemaining(Spliterator.java:710)
	at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:570)
	at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:560)
	at java.base/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:153)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:176)
	at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:265)
	at java.base/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:632)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1604)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1604)

```

```text
org.opentest4j.AssertionFailedError: A reply after an open failure must not produce a close notice ==> expected: <0> but was: <1>
	at org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at org.junit.jupiter.api.AssertEquals.failNotEqual(AssertEquals.java:197)
	at org.junit.jupiter.api.AssertEquals.assertEquals(AssertEquals.java:150)
	at org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:563)
	at com.jcraft.jsch.PendingCloseBoundTest.lateFailureConsumesExpectedReplyBeforeConfirmation(PendingCloseBoundTest.java:178)
	at java.base/java.lang.reflect.Method.invoke(Method.java:565)
	at java.base/java.util.Optional.ifPresent(Optional.java:178)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:186)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:214)
	at java.base/java.util.stream.IntPipeline$1$1.accept(IntPipeline.java:180)
	at java.base/java.util.stream.Streams$RangeIntSpliterator.forEachRemaining(Streams.java:104)
	at java.base/java.util.Spliterator$OfInt.forEachRemaining(Spliterator.java:710)
	at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:570)
	at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:560)
	at java.base/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:153)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:176)
	at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:265)
	at java.base/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:632)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1604)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1604)

```

Native assertion stack repeatedLateConfirmationsProduceOneCloseAfterExchange()[1]:
```text
org.opentest4j.AssertionFailedError: Duplicate late replies must not retain and emit duplicate close notices ==> expected: <1> but was: <513>
	at org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at org.junit.jupiter.api.AssertEquals.failNotEqual(AssertEquals.java:197)
	at org.junit.jupiter.api.AssertEquals.assertEquals(AssertEquals.java:150)
	at org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:563)
	at com.jcraft.jsch.PendingCloseBoundTest.repeatedLateConfirmationsProduceOneCloseAfterExchange(PendingCloseBoundTest.java:122)
	at java.base/java.lang.reflect.Method.invoke(Method.java:565)
	at java.base/java.util.Optional.ifPresent(Optional.java:178)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:186)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:214)
	at java.base/java.util.stream.IntPipeline$1$1.accept(IntPipeline.java:180)
	at java.base/java.util.stream.Streams$RangeIntSpliterator.forEachRemaining(Streams.java:104)
	at java.base/java.util.Spliterator$OfInt.forEachRemaining(Spliterator.java:710)
	at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:570)
	at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:560)
	at java.base/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:153)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:176)
	at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:265)
	at java.base/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:632)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1604)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1604)

```

Native assertion stack lateFailureConsumesExpectedReplyBeforeConfirmation()[1]:
```text
org.opentest4j.AssertionFailedError: A reply after an open failure must not produce a close notice ==> expected: <0> but was: <1>
	at org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at org.junit.jupiter.api.AssertEquals.failNotEqual(AssertEquals.java:197)
	at org.junit.jupiter.api.AssertEquals.assertEquals(AssertEquals.java:150)
	at org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:563)
	at com.jcraft.jsch.PendingCloseBoundTest.lateFailureConsumesExpectedReplyBeforeConfirmation(PendingCloseBoundTest.java:178)
	at java.base/java.lang.reflect.Method.invoke(Method.java:565)
	at java.base/java.util.Optional.ifPresent(Optional.java:178)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:186)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:214)
	at java.base/java.util.stream.IntPipeline$1$1.accept(IntPipeline.java:180)
	at java.base/java.util.stream.Streams$RangeIntSpliterator.forEachRemaining(Streams.java:104)
	at java.base/java.util.Spliterator$OfInt.forEachRemaining(Spliterator.java:710)
	at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:570)
	at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:560)
	at java.base/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:153)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:176)
	at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:265)
	at java.base/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:632)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1604)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1604)

```

## FLAKES ENCOUNTERED
2026-10-15 03:02 UTC — OBSERVED: First corrected targeted gate26/27 first attempts; no retries/errors/skips. All21 preserved neighbors and failure-then-confirmation3 passed. repeatedLateConfirmationsProduceOneCloseAfterExchange repetition1 reached both batch markers but failed followingWrite.await5s after sendKeepAliveMsg future returned. Other2 repetitions passed exact count1. This is not a passing gate. Full XML/stacks are in out/jschfix5-first-green-results/. Investigating the actual reader/peer ordering using diagnostics, not rerunning for green.

```text
org.opentest4j.AssertionFailedError: expected: <true> but was: <false>
	at org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at org.junit.jupiter.api.AssertTrue.failNotTrue(AssertTrue.java:63)
	at org.junit.jupiter.api.AssertTrue.assertTrue(AssertTrue.java:36)
	at org.junit.jupiter.api.AssertTrue.assertTrue(AssertTrue.java:31)
	at org.junit.jupiter.api.Assertions.assertTrue(Assertions.java:183)
	at com.jcraft.jsch.PendingCloseBoundTest.repeatedLateConfirmationsProduceOneCloseAfterExchange(PendingCloseBoundTest.java:175)
	at java.base/java.lang.reflect.Method.invoke(Method.java:565)
	at java.base/java.util.Optional.ifPresent(Optional.java:178)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:186)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:214)
	at java.base/java.util.stream.IntPipeline$1$1.accept(IntPipeline.java:180)
	at java.base/java.util.stream.Streams$RangeIntSpliterator.forEachRemaining(Streams.java:104)
	at java.base/java.util.Spliterator$OfInt.forEachRemaining(Spliterator.java:710)
	at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:570)
	at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:560)
	at java.base/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:153)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:176)
	at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:265)
	at java.base/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:632)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1604)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1604)

```

2026-10-15 03:06 UTC — OBSERVED: Separate reviewer completed source/test/release passes with one actionable fixture-ordering finding and no product finding. Required protected files remain identical on both source lines, release receiveCreditOwner guard retained.
INFER pending experiment: Peer.resumeProposal replays directly through super.doHandleMessage on the test thread without the dependency decoder lock. Official SSHD2.12.0 messageReceived owns decodeLock through doKexNegotiation; doKexNegotiation sends its proposal before assigning/initializing the exchange object. The bypass can let the real client reply enter decoder handling before that initialization. A diagnostic copy of the unchanged real-transport scenario runs30 repetitions with eight actual concurrent peer/client pairs, retaining peer exception and client diagnostic messages. It is waiting on one local build slot (no test verdict yet); no product change is proposed for this fixture issue.

2026-10-15 03:11 UTC — OBSERVED: Diagnostic30 completed with28 passes/two failures, both the same following-write assertion. Both failed peer connections reported IllegalStateException: Not initialized, at ECDH.calculateK -> DHGServer.next -> AbstractSession.handleKexMessage while the test thread replays the proposal outside decodeLock. This confirms the fixture overlap mechanism, not a Session reply-bookkeeping failure. Native XML/full logs at out/jschfix5-ordering-baseline-results/ and out/jschfix5-ordering-baseline.log. Increasing diagnostic sample to100 before correction; this keeps the same public connection path and eight genuine concurrent pairs, without extra artificial work. The corrected diagnostic will use the same100 samples to distinguish the observed baseline rate with a sub-one-percent chance of an unchanged implementation passing all samples.

Peer exception stack from diagnostic:
```text
PEER EXCEPTION java.lang.IllegalStateException: Not initialized
java.lang.IllegalStateException: Not initialized
	at java.base/sun.security.ec.ECDHKeyAgreement.engineDoPhase(ECDHKeyAgreement.java:134)
	at java.base/javax.crypto.KeyAgreement.doPhase(KeyAgreement.java:588)
	at org.apache.sshd.common.kex.ECDH.calculateK(ECDH.java:89)
	at org.apache.sshd.common.kex.AbstractDH.getK(AbstractDH.java:95)
	at org.apache.sshd.server.kex.DHGServer.next(DHGServer.java:103)
	at org.apache.sshd.common.session.helpers.AbstractSession.handleKexMessage(AbstractSession.java:761)
	at org.apache.sshd.common.session.helpers.AbstractSession.doHandleMessage(AbstractSession.java:621)
	at com.jcraft.jsch.PendingCloseOrderingProbe$Peer.doHandleMessage(PendingCloseOrderingProbe.java:81)
	at org.apache.sshd.common.session.helpers.AbstractSession.lambda$handleMessage$0(AbstractSession.java:545)
	at org.apache.sshd.common.util.threads.ThreadUtils.runAsInternal(ThreadUtils.java:68)
	at org.apache.sshd.common.session.helpers.AbstractSession.handleMessage(AbstractSession.java:544)
	at org.apache.sshd.common.session.helpers.AbstractSession.decode(AbstractSession.java:1688)
	at org.apache.sshd.common.session.helpers.AbstractSession.messageReceived(AbstractSession.java:505)
	at org.apache.sshd.common.session.helpers.AbstractSessionIoHandler.messageReceived(AbstractSessionIoHandler.java:64)
	at org.apache.sshd.common.io.nio2.Nio2Session.handleReadCycleCompletion(Nio2Session.java:409)
	at org.apache.sshd.common.io.nio2.Nio2Session$1.onCompleted(Nio2Session.java:382)
	at org.apache.sshd.common.io.nio2.Nio2Session$1.onCompleted(Nio2Session.java:377)
	at org.apache.sshd.common.io.nio2.Nio2CompletionHandler.lambda$completed$0(Nio2CompletionHandler.java:38)
	at java.base/java.security.AccessController.doPrivileged(AccessController.java:74)
	at org.apache.sshd.common.io.nio2.Nio2CompletionHandler.completed(Nio2CompletionHandler.java:37)
	at java.base/sun.nio.ch.Invoker.invokeUnchecked(Invoker.java:121)
	at java.base/sun.nio.ch.Invoker$1.run(Invoker.java:201)
	at java.base/sun.nio.ch.AsynchronousChannelGroupImpl$1.run(AsynchronousChannelGroupImpl.java:108)
	at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1090)
	at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:614)
	at java.base/java.lang.Thread.run(Thread.java:1474)

```

2026-10-15 03:13 UTC — RUNNING status report. Original request: finish one close notice per abandoned open on https://github.com/CodexCoder21Organization/jsch/pull/3, publish0.2.18-reader5, update PR and stop at requested CI green without merging. Last freshly read PR state OPEN at ca942b701392a185dd2caa28de154750db5b0ef4; local product candidate c89636447f36d21681b8b47b06b7f6c4b6647a3b, not yet pushed pending corrected gates.
The library records one expected reply for each actually sent open. The first confirmation or failure consumes it. A valid late confirmation enters the existing close queue once, while repeated and unknown replies are logged and ignored.
Changes since start: new failure-then-confirmation test red3/3 and main repeated-reply test red3/3 under product reversal; independent source/release review found no product issue. First corrected gate26/27 found an actual test-peer overlap; diagnostic30 observed two peer Not initialized errors. Diagnostic100 is queued for a local slot before restoring normal peer decoder serialization. Both protected files remain identical,21/21 passed on current unchanged product candidate.
Delegated work at a glance: gpt-6-luna max read-only review completed; no delegated invocation remains active. It verified source locks, release lineage and preserved hashes, and independently identified the fixture bypass of decoder serialization.
Blockers: no user decision needed. Local slots are currently occupied; bounded diagnostic submission waits, no gate result claimed without XML. The inherited protected-file formatting constraint is recorded and will be validated without an exclusion.
Parallelization sweep: read-only review and release source preparation completed alongside test work; release packaging depends on the corrected fixture. No merge/queue operation is in scope.
Remaining path: finish the fixture comparison, new-test gate and master/release suites, publish/verify reader5, update PR text, verify both requested CI checks, preserve evidence and stop owned processes.
Lessons: preserve normal dependency message serialization in real-peer test fixtures; collect both sides of a missing-message failure before changing code; use actual reply counts to prove once-only behavior rather than timing thresholds.

2026-10-15 03:18 UTC — OBSERVED: Before correction, diagnostic100 finished90 passes/10 failures, zero errors/skips. The100-scenario batch failed, with the same following-write assertion and captured peer setup errors. This independently reproduces the initial missing-keepalive failure.
MECHANISM for fixture correction: the test resumes a held proposal outside the peer decoder guard; peer setup assigns the exchange object before its initialization finishes, so a real reply can call next on an incompletely prepared object. The evidence is the ten failed real-transport scenarios and Not initialized traces at the dependency setup/next boundary. The correction wraps replay in decodeLock and invokes super.handleMessage, matching the normal path. No product change, new time budget, iteration reduction or weakened assertion. Corrected diagnostic uses the same100 scenarios/eight concurrent pairs; at the observed10 percent baseline, probability of100 clean samples with an unchanged defect is0.9^100=0.0000266.

2026-10-15T03:20:48.675880+00:00 — OBSERVED: {"errors": 0, "failures": 0, "kind": "ordering-corrected100", "retry_records": 0, "rows": 100, "skipped": 0}; native XML and full stacks preserved in /home/u/lanes/util20261014/out/jschfix5-ordering-corrected100-results.

2026-10-15 03:21 UTC — OBSERVED: Corrected fixture diagnostic100 passes100/100 first attempts, zero failures/errors/skips/retry records. Same100 real scenarios and eight genuine concurrent pairs as the preceding90/100 baseline; corrected native XML retained at out/jschfix5-ordering-corrected100-results/. Parent and independent reviewer traced and checked the exact serializer correction. A redundant queued wrapper was stopped before running any second diagnostic; no re-run-to-green occurred. Successful diagnostic stdout was accidentally truncated while preparing that redundant wrapper, but complete native XML including captured per-test output remains and was independently parsed. Baseline full logs and stacks remain intact.
OBSERVED: Final master gate driver is queued for a slot. It keeps a single slot only while its sequential formatter/compile/test stages are active. It will use the final fixture for product-only reversal6/6, then new-test green6/6 and full master525 once, followed by unexcluded global import/formatter validation and changed-file formatting validation. The unchanged21 neighbor rows already passed once on byte-identical product. No published result claimed yet.

2026-10-15T03:22:20.019972+00:00 — OBSERVED: {"errors": 0, "failures": 6, "kind": "final-reversal", "retry_records": 0, "rows": 6, "skipped": 0}; native XML and full stacks preserved in /home/u/lanes/util20261014/out/jschfix5-final-reversal-results.

2026-10-15T03:23:40.492191+00:00 — OBSERVED: {"errors": 0, "failures": 0, "kind": "final-green", "retry_records": 0, "rows": 6, "skipped": 0}; native XML and full stacks preserved in /home/u/lanes/util20261014/out/jschfix5-final-green-results.

2026-10-15 03:24 UTC — OBSERVED: Final product reversal6/6 intended failures and final corrected new-test gate6/6 first-attempt passes completed. Repeated-reply scenario3/3 emits exactly1 notice; failure-then-confirmation3/3 emits0; zero errors/skips/retry records. All assertions and512 repeated replies remain. Final source head https://github.com/CodexCoder21Organization/jsch/commit/48ad529 (not yet pushed until full-suite contract gate); Session product is byte-identical to candidate c896364. The21 preserved neighbor rows already passed once on that product. Master full suite now runs once; release suite/package remains next.
Existing-test edits this lane: only PendingCloseBoundTest.java (new in this PR) gains failure-then-confirmation coverage, pinned import/format layout, and the dependency-normal serialized peer replay. No existing test timeout/iteration/assertion weakened; ChannelLateOpenTest.java and ChannelLateOpenRekeyTest.java unchanged.

2026-10-15T03:30:34.697540+00:00 — OBSERVED: {"errors": 0, "failures": 0, "kind": "master-suite", "retry_records": 0, "rows": 525, "skipped": 0}; native XML and full stacks preserved in /home/u/lanes/util20261014/out/jschfix5-master-suite-results.

2026-10-15 03:32 UTC — OBSERVED: Master suite completed once at https://github.com/CodexCoder21Organization/jsch/commit/48ad529432ac01a78c9c3a712be072a4e46b1c8c:525/525 first attempts, zero failures/errors/skips/retries, elapsed6:46. This is all519 predecessor rows plus6 new reply-consumption rows. Global import validation passes all401 files; global formatter fails only on protected lifecycle file, and changed-file formatter passes. No exclusion added. PR branch updated by fast-forward after verifying OPEN; no forced push or merge.
OBSERVED: Release source committed at ae0fffa on wip/jschfix5-reader5, preserving exact reader4 base039816e and its receiveCreditOwner guard. Its source/POM/readme/new tests are the only four changed paths. Reader4-lineage rebase unchanged before package; Java21 package/full233-row suite now runs once. Artifact verification is part of that gate and will match all285 source files, generated version, base class version and unchanged reader4 dependency declarations.
OBSERVED: Tracked bounded build-watchman0.0.21 watches both requested CI names on the updated PR, plain mode only. No queue, merge, service-host operation or deploy.

2026-10-15 03:33 UTC — RUNNING status report. Request unchanged: one-close-notice correction, reader5 publication, PR update and requested CI green, no merge. Fresh source checkpoint is https://github.com/CodexCoder21Organization/jsch/commit/48ad529432ac01a78c9c3a712be072a4e46b1c8c on https://github.com/CodexCoder21Organization/jsch/pull/3. Last PR read OPEN before push.
Changes: final master525/525 once, new6/6 first attempts, preserved neighbors21/21 on identical product, final old-product reversal6/6 intended count failures. Corrected test-peer diagnostic100/100 vs baseline90/100; fixture serializer fix independently reviewed. Source and test changes are pushed, evidence checkpoint refreshed.
How it works: Each actual outgoing open records one expected numeric reply id before sending. The first confirmation or failure consumes it under the packet lock. Only a first valid late confirmation may queue one close notice; disconnect clears remaining expected ids and notices.
Delegated work at a glance: gpt-6-luna max completed two read-only review turns covering product, tests, release application and the corrected dependency-normal replay. No subagent remains active.
CI: tracked build-watchman0.0.21 exited GREEN0 on exact48ad5294, including both named checks and every other current head check; it made no queue/merge operation. Its suggested next enqueue action is outside the brief and was not followed. No watcher remains running.
Blockers: no user decision needed; inherited formatter constraint recorded, unchanged lifecycle file preserved, unexcluded validation performed. Release full package/suite currently active; no release verdict or publication claimed yet.
Parallelization sweep: CI watch completed while release tests run; publication verification and PR draft are prepared. Remaining path: finish release233 gate/package, publish and verify four public files, update/read back PR description, preserve evidence and stop owned processes.
Lessons: run held test messages through the dependency's normal serialized handler; preserve shipped source lineage when publishing a maintenance build; use native first-attempt rows and public file checksums as the completion evidence.

2026-10-15T03:37:51.266171+00:00 — OBSERVED: {"errors": 0, "failures": 0, "kind": "release-suite", "retry_records": 0, "rows": 233, "skipped": 0}; native XML and full stacks preserved in /home/u/lanes/util20261014/out/jschfix5-release-suite-results.

2026-10-15 03:39 UTC — OBSERVED: Release gate/package completed once in6:23 with233/233 first-attempt native rows, zero failures/errors/skips/retries. This retains all227 reader4 rows plus6 new rows. Published source checkpoint is https://github.com/CodexCoder21Organization/jsch/commit/ae0fffafae970cd1abe760ec8c4d8b5bc6ebfe13 on https://github.com/CodexCoder21Organization/jsch/tree/wip/jschfix5-reader5.
OBSERVED: Artifact checks match all285 checked-in Java sources, generated reader5 version and manifest; base Channel major52(Java8); no test/peer/dependency classes; dependency declarations identical to public reader4. Immediate reader5 POM probe404, publisher0.0.6 exit0, then all four public URLs return200 with exact local SHA-256 match. Verification timestamp03:37:57 UTC. Complete URLs and file hashes are in out/jschfix5-publication-verification.json. Nothing was overwritten; no service deployment occurred.

## Challenges for the orchestrator — CI monitor and missing second dispatch
2026-10-15 03:41 UTC — OBSERVED: Final explicit API verification corrects the watcher verdict. Head48ad5294 has one successful check, kotlin.build(remote), in app kotlin-build-ci-test suite104807403415. The same app created both required named checks on preceding heads1cb9816e and ca942b7 (two check runs per suite), but the current suite completed with only one. The second kompile-remote-build check has not been dispatched, despite over8minutes since the push. No test failure or failing CI is being re-run.
OBSERVED: build-watchman0.0.21 invoked with both repeated --check names nevertheless exited GREEN0 while the second named check was absent. Required-check discovery fell back to ALL because master has no configured rules; that fallback appears to omit absent explicit names. Complete command/log plus final API snapshots are preserved. This is a tool issue to fix upstream: explicit named checks must remain mandatory in fallback mode; add a deterministic HTTP fixture with absent requested name and no branch rules. Earlier status notes claiming both green are superseded by this live API result.
Action: warm both public CI receivers and request the existing suite once to restore the missing dispatch. No deployment/host access or merge. If the second check remains absent, this lane ends NOT READY with the complete dispatch reproduction rather than claiming its absence is a pass.

2026-10-15 03:42 UTC — OBSERVED: Both old and current check names use app kotlin-build-ci-test, not separate apps. Warmed the second receiver through its public redirect to HTTP200; webhook receiver root answers404, which still establishes that the service answered. Requested existing suite104807403415 once via GitHub rerequest, after preserving the original API snapshot. This is recovery of a missing dispatch, not a test retry.
The challenge record remains in this findings file under the lane's explicit common addendum forbidding report-challenge because it automatically enqueues/merges; no challenge publication or other merge was invoked. Upstream follow-up brief: build-watchman0.0.21 must not exit GREEN with a missing repeated --check name when base rules are empty. Reproduce with a local HTTP dependency fixture returning empty base requirements and only successful remote check; expect pending/missing-name outcome. Existing explicit-check fallback behavior needs a dedicated upstream fix lane.

## CI issue to fix — missing informational-route check
OBSERVED: The actual CI receiver https://githubci.kotlin.build/health reports kotlin-build-ci0.0.111. Its public /api/status reports buildRoute=direct-buildtest and shadowBuildRoute=kompile-remote-build. Its /api/activity journal shows the exact current push, check_suite.requested, PR synchronize and the one recovery rerequest were received. The current commit has only a successful kotlin.build(remote) check; there is no active jsch build in /api/builds and no kompile-remote-build check run. Earlier heads1cb9816e and ca942b7 have both check names from the same app. Thus the missing check is not explained by a lost GitHub delivery or disabled route. No further repeat request is justified without finding the dispatcher cause.
INFER, not yet proven: Current kotlin-build-ci Main.kt wires DualRouteWebhookEventHandler; its secondary dispatch runs on a two-worker executor with a256-entry queue. A blocked dispatcher or a primary completion/ownership decision can prevent secondary work even after the primary no-build.kts check completes. Host/worker diagnostics are needed to distinguish these. This lane is explicitly prohibited from production-host access and deployment and has no remaining upstream-fix budget.
Required next experiment: inspect shadow-webhook-worker frame summaries and queued ownership for current head48ad5294, plus dispatcher error lines, using the owning infrastructure lane. Reproduce the named condition through a local handler/real HTTP dependency test before changing code. No timeout increase, queue increase, extra retry or manual green check. Save the successful primary as evidence; obtain a real secondary check after the dispatcher correction.
Evidence: out/jschfix5-ci-activity.json, ci-status.json, ci-builds.json, final-checks.json, ci-main.kt and ci-webhook.kt; official README fetched from https://github.com/CodexCoder21Organization/kotlin-build-ci/blob/main/README.md. Source inspected: Main.kt secondary dispatcher wiring and WebhookServlet.kt DualRouteWebhookEventHandler / newShadowProcessingExecutor.

## Cold handoff if the secondary check remains absent
Completed source: https://github.com/CodexCoder21Organization/jsch/pull/3 OPEN at https://github.com/CodexCoder21Organization/jsch/commit/48ad529432ac01a78c9c3a712be072a4e46b1c8c on https://github.com/CodexCoder21Organization/jsch/tree/wip/jschfix2-master. Release source: https://github.com/CodexCoder21Organization/jsch/commit/ae0fffafae970cd1abe760ec8c4d8b5bc6ebfe13 on https://github.com/CodexCoder21Organization/jsch/tree/wip/jschfix5-reader5. Evidence: https://github.com/CodexCoder21Organization/jsch/tree/wip/jschfix5-evidence. No local source edits remain; no merge or deploy.
Published build: com.github.mwiede:jsch:0.2.18-reader5. All four public artifact URLs and SHA-256 values are in jschfix5-publication-verification.json. It is published but not merged into master. Master525/525, release233/233, final targeted6/6, preserved21/21; all first attempts with no errors/skips/retries. Final old-product reversal6/6 intended failures. Peer fixture overlap fixed and independently reviewed; diagnostic baseline90/100 vs corrected100/100. Preserve lifecycle/rekey files exactly; lifecycle hash eb523698b133d41a85650bab5650a7da18e282ce3c5d6b0720199f98689835d5. Formatting complaint unchanged, no validator exclusions. Channel-id reassignment after 2^31 remains the expressly excluded follow-up.
Only remaining requested gate: a genuine kotlin.build(kompile-remote-build) check on exact48ad5294, then final verification. kotlin.build(remote) is successful. Current receiver confirms both routes enabled and received the relevant deliveries; the secondary check is absent. No further re-request has been made after the single diagnostic recovery request, which reproduced the same absence. The infrastructure owner needs the dispatcher experiment described above. Do not manufacture a success check, merge, deploy, or re-run tests for green.
Handoff service is excluded by this lane's common instructions; this record and remote evidence are the durable handoff for the orchestrator. Source checkouts and native XML stay available; generated target/package/probe-class directories were removed.

2026-10-15 03:48 UTC — OBSERVED: Cleanup complete. Exact ps -eo pid,args -ww saved locally; owned-process/slot audit finds zero owned background processes and zero owned build slots. Working tree clean; release worktree and generated target/package/probe-class directories removed; compile caches retained. No loop is scheduled and both reviewer turns finished. The full global process listing is deliberately kept local; only the owned-process verification is archived.

## Brief audit
| Requested item | State and proof |
|---|---|
| At most one expected reply and one close notice per abandoned open | DONE:31-line Session correction; repeated replies3/3 first-attempt passes, exact1 notice; old-product reversal3/3 exact513 failures |
| Unknown/repeated reply drop without reader stop | DONE:512 repeated replies and unknown id during held exchange; ordered reader markers and following write; failure-then-confirmation3/3 expects0, old product3/3 exact1 failures |
| Existing named tests byte-identical/run once | DONE:protected lifecycle9 and rekey12 rows passed once on byte-identical product, both test files retain original bytes; final full suites also pass |
| Full master/release once | DONE:525/525 and233/233, all519/227 predecessor rows retained plus6 new rows, zero errors/skips/retries |
| Publish reader5 / public URLs/checksums | DONE:publisher0.0.6 exit0 after404 POM probe,4 public200 files matchSHA256;285 sources and Java8 base validated, reader4 dependencies/receive behavior retained |
| PR update and checkpoints | DONE:OPEN at tested48ad5294; exact description readback, original lifecycle-hash line preserved, master/release/evidence branches pushed |
| Finding2 remains follow-up | DONE:31-bit id-reassignment issue recorded with separate allocator proof requirement; no allocator change or runtime wrap claim |
| Inherited formatter constraint | DONE per brief:protected file unchanged; global validation invoked without exclusions and complaint recorded; changed files and401 imports validated |
| Fix other observed flake | DONE:peer replay serializer corrected after10/100 baseline failures; same diagnostic100/100 passes; independent source/test review resolution |
| Requested two-check CI gate | NOT YET MET:primary success, secondary absent despite enabled route and received deliveries; concrete CI/monitor follow-up briefs above |
| No merge / cleanup | DONE:no merge/enqueue/deploy; no owned processes/slots, generated directories removed, clean checkout |

## Final scope clarification and completion
2026-10-15 03:49 UTC — OBSERVED: Received ORCHESTRATOR-NOTE-0348.md in the checkout. It says: "The required gate is kotlin.build (remote), which is green on 48ad5294. Do not re-request suites for it and do not wait on it." The original note is preserved unchanged in the checkout; a copy is archived as jschfix5-orchestrator-note-0348.md. This supersedes the earlier two-check gate and the provisional NOT READY audit row above. No further suite request or wait will be made for the informational check.
DONE under the clarified brief: required kotlin.build(remote) is completed/success on exact https://github.com/CodexCoder21Organization/jsch/commit/48ad529432ac01a78c9c3a712be072a4e46b1c8c; master525/525 and release233/233; reader5 published with four public200/SHA256 matches; PR OPEN and description updated; no merge/enqueue/deploy. Both protected files retain their exact bytes. The inherited formatter complaint and31-bit identifier follow-up remain recorded as explicitly required.
Final status report: original requested correction, publication and PR update are complete. The library consumes one expected reply per actual open and queues one close notice for a valid late confirmation; repeated and unknown replies are ignored while the reader continues. The real-peer fixture resumes held messages under the dependency's normal decoder guard, closing the observed test overlap. Independent review completed; no subagent or watcher remains active.
No requested work remains under the latest gate clarification. Separate ecosystem findings remain available to the orchestrator: the absent informational route and explicit-name watcher fallback behavior, both documented above without a guessed fix. Source/release/evidence branches are remote; publication is not merged into master. Cleanup verified no owned processes/slots and no generated directories. Only untracked file is the received orchestrator note, which is user/orchestrator material and was preserved. No status-report loop was created because scheduler tools are unavailable; this final report closes the active reporting period.
Lessons: preserve dependency serialization in real-peer tests; validate publication bytes rather than relying on upload success alone; cross-check named CI requirements against actual API results and current orchestrator scope.
