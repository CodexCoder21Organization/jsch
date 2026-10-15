# jschfix6 findings
Started 2026-10-15 04:01 UTC; deadline 04:46 UTC.

Plan:
- [done] Required reading, exact fresh checkout and public fail-first regression.
- [done] Remove failed-write expectations under the packet lock; green new cases and preserved neighbors; product reversal.
- [done] Full master and reader6 release suites once; publish and verify public checksums.
- [done] Update PR, required remote check, separate reviews, evidence checkpoint and process cleanup.

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

2026-10-15 04:15 UTC — OBSERVED: Correction milestone checkpoint pushed after36/36 targeted and9/9 intended product-only reversal: https://github.com/CodexCoder21Organization/jsch/commit/ae7ffa2085031d69e2d6d4c0dc5e55b9879de5dc. Release source is remote at https://github.com/CodexCoder21Organization/jsch/commit/abf4a15 (wip/jschfix6-reader6). Native XML and independent review checkpoint at https://github.com/CodexCoder21Organization/jsch/tree/wip/jschfix6-evidence. Full master suite is still active and is not yet claimed passed. First checkpoint was the red test at https://github.com/CodexCoder21Organization/jsch/commit/b695917. No merge/queue/deploy or service-host access occurred.

2026-10-15 04:16 UTC — OBSERVED: GitHub API reports kotlin.build(remote) completed/success on exact ae7ffa2085031d69e2d6d4c0dc5e55b9879de5dc. Informational check was also present/success on that snapshot; no wait or re-request was made for it. Native full master rows completed so far103, zero failures/errors; final verdict still pending. Master/release FailedOpenReplyTest bytes match SHA-2561532d6e33df9326c29b95cf49087220e994ee14cea9e013d3e2485bf92951e79. Release source full head abf4a1524694362e671f3d78a38965ac689281ac.

FOLLOW-UP anchors retained: numeric ids are assigned at https://github.com/CodexCoder21Organization/jsch/blob/48ad529432ac01a78c9c3a712be072a4e46b1c8c/src/main/java/com/jcraft/jsch/Channel.java#L93 using the31-bit mask; generation identity remains a separate allocator correction. Protected lifecycle SHA-256 remains eb523698b133d41a85650bab5650a7da18e282ce3c5d6b0720199f98689835d5; existing formatting complaint is expressly retained. Neither file nor validator configuration is changed.

## 2026-10-15 04:21 UTC — RUNNING status report
Original request: close the failed outgoing-open expected-reply finding on https://github.com/CodexCoder21Organization/jsch/pull/3, publish reader6, update PR and stop at required remote green, without merging. Fresh PR state is OPEN, merge state CLEAN, mergedAt null, exact source head https://github.com/CodexCoder21Organization/jsch/commit/ae7ffa2085031d69e2d6d4c0dc5e55b9879de5dc.
The session records an expected reply before writing an outgoing open under its existing packet lock. A finally block removes that id if the write fails before returning and leaves the original error intact. Subsequent replies for a failed attempt are ignored, while successful abandoned opens keep the established one-reply/one-close policy.
Changes: native new tests red9/9, green9/9 plus unchanged neighbors27/27, final product reversal9/9 intended failures; all first attempts, no errors/skips/retries. Native full master is active with 103 completed rows and no failures/errors so far; KeyExchangeTest's deliberate three-byte enumeration loops explain its long CPU work (prior full suite spent275.6seconds in that class). No test limit or iteration was changed. Release source and all passing evidence are checkpointed remotely; reader6 artifact/package is pending.
Delegated work at a glance: one gpt-6-luna max invocation completed independent test review and a distinct production/release review, with no concrete blocker. Live team status confirms it is completed; no delegated work remains active. Review source/test observations are retained in jschfix6-review.md.
CI: bounded build-watchman0.0.21 single assessment exited GREEN0 on this exact head. Required kotlin.build(remote) is successful; no watcher remains alive and no informational check wait or suite rerequest was made.
Blockers: no user decision required. Inherited formatter and numeric-id follow-ups remain explicitly excluded. Free disk7.2GiB at04:21; every remaining build checks the6GiB minimum before starting. No handoff operation under lane addenda; scheduler tools unavailable, so reports stay here during active work.
Landing parallelization sweep: CI and reviews finished alongside native verification; release source, artifact validation and PR draft are prepared. Full master/release suites remain sequential to respect the one-full-local-suite rule.
Next steps: finish master once, package/test reader6 once, publish and verify public checksums, update/read back the PR, archive final evidence and verify process cleanup.
Lessons: pair state registration with exceptional cleanup in the same critical section; use ordered real-peer markers to prove reader behavior; apply a maintenance correction on the existing published lineage while preserving its unrelated guards.

2026-10-15 04:25 UTC — OBSERVED: KeyExchangeTest15/15 passed once (493.9seconds); full master advanced through remaining classes, no failure/error seen. README wording now omits the internal lock name and states only public reply behavior. This documentation-only follow-up is committed on both lines; master Java/test/POM bytes remain exactly ae7ffa2 (git diff --exit-code ae7ffa2 -- src pom.xml). No extra test batch is warranted for that wording change. Release packaging will use its final committed README.

2026-10-15T04:25:44.996606+00:00 — OBSERVED: {"errors": 0, "failures": 0, "kind": "master-suite", "retry_records": 0, "rows": 534, "skipped": 0}; native XML and full stacks preserved in /home/u/lanes/util20261014/out/jschfix6-master-suite-results.

2026-10-15 04:26 UTC — OBSERVED: Once-only full master suite passed534/534 native first attempts, zero failures/errors/skips/retry records. All525 prior rows plus9 new rows retained. Final master docs-only head is https://github.com/CodexCoder21Organization/jsch/commit/7a7f397; Java/test/POM bytes are identical to the earlier tested product checkpoint https://github.com/CodexCoder21Organization/jsch/commit/ae7ffa2085031d69e2d6d4c0dc5e55b9879de5dc. Native XML copied into out/jschfix6-master-suite-results. Unexcluded global validation is finishing before release suite begins.

2026-10-15 04:27 UTC — OBSERVED: Master driver exited0 after full534/534, unexcluded global import/formatter invocation, and changed-file formatter validation. All402 imports pass. Global formatter reports only protected ChannelLateOpenTest.java as not previously formatted; its bytes remain unchanged and no exclusion was added. Changed two Java files pass formatter validation. Reader6 gate started after the master full-test process completed, overlapping only the remaining formatter validation; full suites are serialized. Release source final docs-only checkpoint is remote at https://github.com/CodexCoder21Organization/jsch/commit/74923e5 on wip/jschfix6-reader6. Final master source is remote at https://github.com/CodexCoder21Organization/jsch/commit/7a7f397 on the existing OPEN PR branch.

2026-10-15 04:28 UTC — OBSERVED: Release wrapper began downloading another copy of the already installed Java21.0.12.1 through Coursier. Stopped owned timeout PID696320 before any native release row or Maven test execution; wrapper exited143, slot released. Restarted the launcher with existing /usr/lib/jvm/temurin-21-jdk-amd64, the exact java.home/version recorded by reader5. No release test has been retried; this was SDK selection only. Free disk6.2GiB; the guard remains6GiB and is checked again before package. Created a separate v2 wrapper while the old one was stopped; no active-script edit.

## Challenges for the orchestrator
2026-10-15 04:31 UTC — OBSERVED: Shared free space fell to5.6GiB after the release package invocation had passed its unchanged6GiB check (6.5GiB at04:28:51). No new build was started below the minimum; the once-only release run is already active. This lane's generated master target is only5.6MiB and release outputs are small; no other lane directory was touched. Remaining planned actions after this run publish prebuilt files and edit PR text, not another build. Further new compilation/test invocations would require free space to recover first. This is recorded for the orchestrator's shared-space cleanup; no guard was lowered.

## FLAKES ENCOUNTERED
OBSERVED: No intermittent test failure or native retry record has appeared in this lane. Baseline red9/9 and product-only reversal9/9 are deterministic intended assertion failures. The two compile adaptations, standalone JUnit classpath alignment, completed-Maven wrapper tail error, and SDK-selection interruption are described above with their native/no-verdict status; none was counted as a passing test or retried past a real assertion failure.

2026-10-15 04:32 UTC — OBSERVED: Release suite is active and has passed its protected late-open9, bounded-close6 and receive-flow8 rows; the new failed-write cases are passing as they complete. No full release verdict or publication is claimed yet. Native current release rows58, no failures/errors. Source and release branches are clean, committed and remote; no source change since README-only wording cleanup.

2026-10-15 04:36 UTC — OBSERVED: Release new regression9/9 first attempts, preserved late-open9, bounded-close6 and receive-flow8 all passed; full release now remains in KeyExchangeTest. Its process continues CPU work, not a test retry. Required remote success is verified on final source head https://github.com/CodexCoder21Organization/jsch/commit/7a7f397cff5006cd3373940b8b004ed80560dd29. Publication preprobe/upload/checksum runner and final PR validation are prepared; no publication claimed before package succeeds.

2026-10-15T04:38:08.675897+00:00 — OBSERVED: {"errors": 0, "failures": 0, "kind": "release-suite", "retry_records": 0, "rows": 242, "skipped": 0}; native XML and full stacks preserved in /home/u/lanes/util20261014/out/jschfix6-release-suite-results.

2026-10-15 04:38 UTC — OBSERVED: Once-only release suite/package completed242/242 first attempts, zero failures/errors/skips/retry records. Publisher input artifacts validated: all285 checked-in Java sources match, generated reader6 version/manifest match, base class major52(Java8), no peer/test/dependency classes, dependency declarations identical to public reader5. Release source head 74923e5073b553e9512029e01ab19ee311b1abe2; final PR source head 7a7f397cff5006cd3373940b8b004ed80560dd29. Native XML and artifact checksums retained. Publishing follows immediate fresh POM preprobe.

2026-10-15 04:39 UTC — OBSERVED: reader6 publisher0.0.6 exited0 after immediate POM404; all four public files return200 and match local SHA-256. New version only, no overwrite or deployment. PR description updated through structured API body file and exact readback matches; PR OPEN/CLEAN on final7a7f397cff5006cd3373940b8b004ed80560dd29. Required kotlin.build(remote) is completed/success on that head. Final rebase origin/master unchanged; no source/test/POM change or new gate run. Final evidence and cleanup remain.

## Brief audit
| Requested item | State and proof |
|---|---|
| Fresh exact48ad5294 checkout and required full reading | DONE: pinned fresh clone, unchanged origin/master rebase, required briefs/findings and architecture documents read; no root AGENTS.md |
| Failed outgoing-open expected reply cleanup under packet lock | DONE:10-line Session change places IO.put in try/finally, removes unsuccessful open id before unlock, propagates original error; successful-send behavior unchanged |
| Real-transport red3/3 before fix and green3/3 after | DONE:before-byte64 case3/3 intended failures then3/3 passes; partial and flush scenarios each3/3 red and3/3 green,9 new rows total; exact full error and reader-marker diagnostics |
| Product-only reversal | DONE:final formatted test unchanged, exact48ad5294 Session overlay gives9/9 intended count failures again, zero errors/skips/retries |
| Protected lifecycle/rekey/PendingCloseBound unchanged and run once | DONE:all3 files byte-identical across baseline/master/release; named native target27/27, plus new9/9; hashes in jschfix6-protected-files.json |
| Full master once | DONE:534/534 first attempts, zero failures/errors/skips/retry records; native XML retained; final source/test/POM bytes identical to suite product; README-only follow-up disclosed |
| Full release/package once | DONE:242/242 first attempts, zero failures/errors/skips/retry records; package success and285 source/Java8/thin-library/dependency checks |
| Publish reader6 after POM probe | DONE:initial and immediate probe404, publisher0.0.6 exit0; POM/library/sources/Javadoc all200 and exact local SHA-256 matches; jschfix6-publication-verification.json |
| PR update and required remote green | DONE:exact body readback matches, lifecycle-hash line retained; OPEN/CLEAN at7a7f397cff5006cd3373940b8b004ed80560dd29; required kotlin.build(remote) completed/success. No informational wait or suite re-request |
| Checkpoints to PR branch and evidence | DONE:red b695917, correction ae7ffa2, docs-only final7a7f397; release74923e5 and evidence wip/jschfix6-evidence remote; full URLs above and below |
| Explicitly excluded follow-ups | DONE:31-bit channel-id reuse and inherited protected-file formatter complaint recorded without touching either; unexcluded validation performed, changed-file formatting and402 imports pass |
| Owned process and output cleanup | DONE:all tracked commands exited, SDK-selection timeout explicitly stopped before tests; exact ps -eo pid,args -ww snapshot saved locally; process/slot audit finds0/0. Release worktree, generated target/POM, reversal classes and publication staging removed; native evidence and compile caches retained |
| No merge, queue or deployment | DONE:none performed; publication was the explicitly authorized Maven artifact release |

The prior review suggested a terminal-connection policy where write completion is uncertain. The current brief explicitly chooses removing the failed attempt's id and ignoring a subsequent reply; partial-write and complete-write/flush tests encode that supplied policy. This lane does not claim to repair an output stream after its write fails.

Final changed-file review: Session.java adds exceptional cleanup around the existing write. FailedOpenReplyTest.java adds167 lines of real-loopback public-API coverage. Readme.md records the public failed-attempt reply policy. The release POM changes only reader5 to reader6. No generated artifact is tracked on either source branch.

## Final status report — DONE
Original request completed: close the one failed-open bookkeeping finding, publish reader6, update the existing PR and stop at required remote green. The session stages an expected reply while writing an open. A failed put removes that expectation in the same critical section and preserves the original error. A successful put keeps the existing late-reply and single-close behavior.
PR: https://github.com/CodexCoder21Organization/jsch/pull/3 remains OPEN/CLEAN; no merge/enqueue. Final source: https://github.com/CodexCoder21Organization/jsch/commit/7a7f397cff5006cd3373940b8b004ed80560dd29. Release source (published but not merged): https://github.com/CodexCoder21Organization/jsch/commit/74923e5073b553e9512029e01ab19ee311b1abe2 on https://github.com/CodexCoder21Organization/jsch/tree/wip/jschfix6-reader6. Permanent evidence: https://github.com/CodexCoder21Organization/jsch/tree/wip/jschfix6-evidence.
Publication: com.github.mwiede:jsch:0.2.18-reader6. Public POM: https://kotlin.directory/com/github/mwiede/jsch/0.2.18-reader6/jsch-0.2.18-reader6.pom; all4 public file URLs and checksums are in the publication record. Master534/534, release242/242, targeted36/36, both native red controls9/9 intended failures, all zero error/skip/retry rows.
Delegated work at a glance: one gpt-6-luna max invocation completed separate test and source/release review passes; no active delegate or watcher. Both reviews found no concrete blocker. No scheduled loop exists; scheduler tools were unavailable and no handoff operation was used under lane instructions.
Blockers: none. Explicitly excluded follow-ups remain recorded. Landing parallelization sweep: reviews, CI and artifact preparation ran alongside native gates; full test suites were serialized. Next steps: none — the request is complete; the PR remains for orchestrator review.
Lessons: pair registration with cleanup on exceptional exits under the same lock; use real transport ordering markers to test bookkeeping through public behavior; preserve the published source lineage and validate uploaded bytes when making a maintenance release.

Final report timestamp: 2026-10-15T04:41:28.744497+00:00. Process audit at04:40:14 UTC confirms zero owned active processes and zero slots.

2026-10-15T04:41:55.111198+00:00 — OBSERVED: Final live PR state OPEN/CLEAN, mergedAt null, exact final source head and required remote success reverified.
