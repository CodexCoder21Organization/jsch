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
