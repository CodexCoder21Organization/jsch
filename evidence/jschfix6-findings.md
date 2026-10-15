# jschfix6 findings
Started 2026-10-15 04:01 UTC; deadline 04:46 UTC.

Plan:
- [done] Required reading, exact fresh checkout and public fail-first regression.
- [in progress] Remove failed-write expectations under the packet lock; green new cases and preserved neighbors; product reversal.
- [pending] Full master and reader6 release suites once; publish and verify public checksums.
- [pending] Update PR, required remote check, separate reviews, evidence checkpoint and process cleanup.

OBSERVED: Fresh clone is exactly 48ad529432ac01a78c9c3a712be072a4e46b1c8c on wip/jschfix2-master. Rebase origin/master unchanged; default branch is master. No root AGENTS.md. README and Readme.md read. Explicit task authorizes reader6 publication and required CI watch. No handoff operations or report-challenge action under lane addenda.

MECHANISM: the mechanism is registering an expected reply before IO.put without removing it when IO.put throws; the evidence that demonstrates it is the review public socket probe: 64 failed outgoing opens, still connected, zero of their later replies ignored versus expected64. A fresh native fail-first run follows before product edits.

Invariant table:
| Resource | Ownership / terminal rule | Public proof |
|---|---|---|
| Open expectation | Session packet lock owns insertion before IO.put; retain only on successful put, remove on exceptional exit in same lock; consume once on reply; disconnect clears | Real socket output fails before bytes, after partial bytes, and during flush; later reply is ignored |
| Successful abandoned open | Retain expectation until first reply despite channel removal | Byte-identical PendingCloseBoundTest and protected lifecycle/rekey tests |
| Reader and queue | Ignore replies for removed failed ids, keep reading ordered marker; no close notice for those ids | Public logger exact messages and ordered peer marker |
| Connection shutdown | Existing disconnect terminal state prevents new expectations and clears both collections | Existing protected tests; no lifecycle change here |

FOLLOW-UPS: numeric channel id reuse after2^31 constructions and inherited protected-file formatter complaint remain unchanged. No timeout increase, weakened assertion, existing test edit, merge, queue action or deployment authorized.

OBSERVED: First compile rejected the external probe's Java10 var declaration under this repository's Java8 test source contract. Replaced it with the actual IoWriteFuture type. No test rows executed; not a red verdict. Full compile output preserved in jschfix6-red.log.

2026-10-15 04:06 UTC — OBSERVED: Added self-contained real-peer FailedOpenReplyTest with three repetitions each of before-byte64 failed opens, one-byte partial write, and complete write/flush failure. Caller receives the full expected IOException message, channel closes, connection stays connected, peer failure replies plus unknown-id control precede an ordered reader marker; exact diagnostic count proves expectation release. Product remains exact48ad5294. Two compile-only adaptations of the external review probe were required: explicit IoWriteFuture instead of var, and byte-array rather than deprecated string setter. No test verdict was counted for either. Native red run is active under one shared slot and a hard600second deadline.
OBSERVED: reader6 POM preprobe404. Reader6 release worktree is based on exact reader5 ae0fffafae970cd1abe760ec8c4d8b5bc6ebfe13; published lineage/receive behavior will stay intact. Separate gpt-6-luna max read-only test review is active. Required documents read: common.md all addenda, br/jschfix5.md, out/jschfix5-findings.md, out/revjsch3-findings.md, repository README and Readme.md, complete Testing Architecture and Engineering Philosophy via authenticated GitHub raw API; repository/publishing/status/watch skills. No scheduler tools available; periodic reports will be appended here.

2026-10-15T04:07:43.163504+00:00 — OBSERVED: {"errors": 0, "failures": 9, "kind": "red", "retry_records": 0, "rows": 9, "skipped": 0}; native XML and full stacks preserved in /home/u/lanes/util20261014/out/jschfix6-red-results.

OBSERVED: Fresh baseline48ad5294 red9/9 intended assertion failures; before-byte3/3 expected64 ignored replies observed0, partial3/3 and flush3/3 expected1 observed0. Zero errors/skips/retries. Unknown-id control and ordered marker pass in every row. Full native XML retained. First assertion stack per scenario:

```text
org.opentest4j.AssertionFailedError: A failed outgoing open must not remain an expected reply ==> expected: <64> but was: <0>
	at org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at org.junit.jupiter.api.AssertEquals.failNotEqual(AssertEquals.java:197)
	at org.junit.jupiter.api.AssertEquals.assertEquals(AssertEquals.java:166)
	at org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:664)
	at com.jcraft.jsch.FailedOpenReplyTest.checkFailedWrite(FailedOpenReplyTest.java:143)
	at com.jcraft.jsch.FailedOpenReplyTest.failedWritesMustNotLeaveExpectedReplies(FailedOpenReplyTest.java:27)
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
org.opentest4j.AssertionFailedError: A failed outgoing open must not remain an expected reply ==> expected: <1> but was: <0>
	at org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at org.junit.jupiter.api.AssertEquals.failNotEqual(AssertEquals.java:197)
	at org.junit.jupiter.api.AssertEquals.assertEquals(AssertEquals.java:166)
	at org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:664)
	at com.jcraft.jsch.FailedOpenReplyTest.checkFailedWrite(FailedOpenReplyTest.java:143)
	at com.jcraft.jsch.FailedOpenReplyTest.partialWritesMustNotLeaveExpectedReplies(FailedOpenReplyTest.java:33)
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
org.opentest4j.AssertionFailedError: A failed outgoing open must not remain an expected reply ==> expected: <1> but was: <0>
	at org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at org.junit.jupiter.api.AssertEquals.failNotEqual(AssertEquals.java:197)
	at org.junit.jupiter.api.AssertEquals.assertEquals(AssertEquals.java:166)
	at org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:664)
	at com.jcraft.jsch.FailedOpenReplyTest.checkFailedWrite(FailedOpenReplyTest.java:143)
	at com.jcraft.jsch.FailedOpenReplyTest.flushFailuresMustNotLeaveExpectedReplies(FailedOpenReplyTest.java:39)
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

2026-10-15 04:08 UTC — OBSERVED: Failing-test milestone pushed to PR branch at https://github.com/CodexCoder21Organization/jsch/commit/b695917. Native red9/9 XML and findings checkpoint pushed to https://github.com/CodexCoder21Organization/jsch/tree/wip/jschfix6-evidence. Product change is a try/finally around IO.put; unsuccessful put removes only this id before releasing existing packet lock and propagates the original failure. README explicitly records failed attempts discard subsequent replies even after partial/full delivery, per current brief. Targeted green36 rows (new9 plus existing27) is active.

2026-10-15T04:11:19.095929+00:00 — OBSERVED: {"errors": 0, "failures": 0, "kind": "green", "retry_records": 0, "rows": 36, "skipped": 0}; native XML and full stacks preserved in /home/u/lanes/util20261014/out/jschfix6-green-results.

2026-10-15 04:11 UTC — OBSERVED: Targeted native gate36/36 first attempts (new9/9, protected lifecycle9/9, renegotiation12/12, PendingCloseBound6/6); zero failures/errors/skips/retry records. Both independent test and production/release review passes found no concrete blocker; review file jschfix6-review.md. Product commit ae7ffa2085031d69e2d6d4c0dc5e55b9879de5dc is awaiting the once-only full-suite pre-push gate. Reversal uses exact48ad5294 Session compiled outside source and final formatted new test unchanged. No existing test behavior changed; new test's second commit is import/layout formatting only.
OBSERVED: Targeted Maven itself exited successfully (BUILD SUCCESS,36/36 native rows). Its shell wrapper then exited2 because I edited that wrapper while it was still running; Bash read part of the replacement tail after Maven finished. That wrapper-only error does not invalidate the native rows and no test was rerun. The replacement complete wrapper passes syntax inspection; active master driver reads it from its start. Future active scripts will remain unchanged until exit.

OBSERVED: External reversal initially stopped at JUnit discovery before any row: OutputDirectoryCreator not available; probably due to unaligned versions of the junit-platform-engine and junit-platform-launcher jars on the classpath/module path. Its source compilation succeeded. Added the same launcher1.14.4 used by Maven to the external console classpath (as in the prior reviewer runner); no test/source change, no verdict or full-suite run occurred. Full original stack remains in jschfix6-reversal.log, archived as jschfix6-reversal-discovery.log before rerun.

2026-10-15T04:13:11.312820+00:00 — OBSERVED: {"errors": 0, "failures": 9, "kind": "reversal", "retry_records": 0, "rows": 9, "skipped": 0}; native XML and full stacks preserved in /home/u/lanes/util20261014/out/jschfix6-reversal-results.

2026-10-15 04:14 UTC — OBSERVED: Final formatted new test with exact48ad5294 product overlay is red9/9 at intended count assertions, zero errors/skips/retries. Full master suite now executes once on local commit ae7ffa2085031d69e2d6d4c0dc5e55b9879de5dc. Final release source/test checkpoint prepared at abf4a15, based on reader5; package gate follows master to keep full suites serialized. Both code/test review passes completed with no blocker.
