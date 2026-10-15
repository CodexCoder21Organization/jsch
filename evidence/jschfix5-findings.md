# jschfix5 findings
Started 2026-10-15 02:52 UTC; deadline 03:52 UTC.

Plan:
- [done] Read prior findings, common rules/addenda, repository README/Readme and required testing/philosophy guidance; fresh clone at exact requested PR head; rebase origin/master unchanged.
- [in progress] Review and apply reply-consumption correction; run new regression 3/3, preserved neighbors once; product-only reversal; master suite once.
- [pending] Separate test/source review; preserve reader4 release lineage, apply correction, release suite/package once; publish reader5 and verify public files/checksums.
- [pending] Update PR description with evidence and lifecycle hash; watch requested CI; preserve checkpoints; stop owned processes.

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
