The incident brief gives the reason for this repair:

> The BuildTest coordinator runs one remote command per dispatch queue write to each build droplet through this library; today about 99 % of those writes fail with the library's "channel is not opened." message and the dispatcher re-issues them in a tight loop (16,153 of 16,300 on one run, fleet-wide).
>
> The predecessor demonstrated the library-side defect: when the peer's open acknowledgement arrives after `Channel.connect(timeout)` has already thrown, the client neither records nor releases the now-allocated remote stream, so streams accumulate on the session until the peer stops granting new ones.

This is part of the startup compaction and suite revalidation work. [BuildTestEmbedded pins reader2](https://github.com/CodexCoder21Organization/BuildTestEmbedded/blob/main/build.kts#L35).

The final open decision, terminal close state and acknowledgement publication now share the stream monitor. A late reply for a removed or closed local stream sends CLOSE to the peer recipient carried by that reply. This also handles a reader that finds the local stream just before removal, without a pending map. Pending disconnect ends the local open call. If no reply ever arrives, there is no peer recipient to address; session termination releases the peer allocation, as documented in README/Javadoc.

A real local Apache MINA SSHD peer holds its actual acknowledgement write behind a latch until public `connect(1000)` has thrown. The test then requires the peer allocation to return to zero and a new command to succeed on the same session. Two controls cover pending disconnect and session close with no reply. Each case has three independent first attempts, with no retry runner or timing sleeps.

Validation:

- Unmodified master and reader2: late cleanup fails 3/3, pending disconnect fails 3/3, no-reply session cleanup passes 3/3; no errors or skipped cases.
- Product-only mutation control: late cleanup fails 3/3 with only the product fix removed; zero errors/skipped, then passes 3/3 with the fix restored.
- Fixed master unit suite: 507 tests pass, zero failures/errors/skipped; all nine lifecycle cases pass their first attempts, with no retry records.
- Reader2-based publication unit suite: 215 tests pass, zero failures/errors/skipped or retry records; all nine lifecycle cases and eight existing receive-flow cases pass their first attempts.
- Dedicated test and source review passes completed; reader2 receive buffers and credit writer unchanged. All 285 checked-in reader2 Java sources matched the shipped reader2 sources before this change.

Existing-test edits: none. No timeout or iteration values changed.

Publication: published `com.github.mwiede:jsch:0.2.18-reader3` with publisher 0.0.6. All four public files match the local SHA-256; the [public POM](https://kotlin.directory/com/github/mwiede/jsch/0.2.18-reader3/jsch-0.2.18-reader3.pom) declares the coordinate. The [reader2-based branch](https://github.com/CodexCoder21Organization/jsch/tree/wip/jschfix2-fix) preserves the reader2 implementation and adds this fix plus the release suffix. This PR contains the same fix on master.

[Native XML and review evidence](https://github.com/CodexCoder21Organization/jsch/tree/wip/jschfix2-evidence).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
