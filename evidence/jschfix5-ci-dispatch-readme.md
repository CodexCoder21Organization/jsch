# kotlin-build-ci

A GitHub App that provides CI checks for repositories in the organization. This app runs as a service within ContainerNursery and creates check runs named "kotlin.build (remote)" for all repositories where it's installed.

## How It Works

1. When a push or pull request is made to a repository, GitHub sends a `check_suite.requested` webhook event
2. This service receives the webhook, **acknowledges it with `200 OK` immediately**, and processes it on a background worker — creating a check run via the GitHub Checks API
3. If the repository contains a `build.kts` file, the service downloads the source as a tarball from GitHub and submits it to the configured remote build route via P2P RPC. The default route is `url://buildtest/`; the opt-in `url://kompile-remote-build/` route is selected with `CI_BUILD_ROUTE=kompile-remote-build`. The remote service provisions a server, runs the build and tests, and returns results. The check run status is based on the test results:
   - **Green (success)**: All tests pass — status message shows "All tests passing (X/X)"
   - **Red (failure)**: One or more tests fail — status message shows "X passed, Y failed, Z total"
   - **Red (failure)**: Build itself fails with test counts — status message shows "Build failed: X passed, Y failed, Z total"
   - **Red (failure)**: Build itself fails without test counts — status message shows the build error

When one or more tests pass only after retry, the final detail-enriched check summary adds exactly
one line: `WARNING: N test(s) passed only after retry; see per-test attempt history.` The count comes
from buildtest's structured canonical-attempt records. Runs with no such tests retain the existing
summary byte for byte.

The terminal build record is authoritative for the check conclusion when it contains a decisive verdict. A successful scalar-only record is decisive only when its passed and failed counts account for the declared total; records whose remaining tests may be disabled, filtered out, canceled, pending, or missing require detailed results. The detailed verdict accepts passed, failed, disabled, filtered-out, and canceled rows when they account for the declared total, while a PENDING or RUNNING row or a genuinely absent result keeps the tally inconsistent. A terminal failure remains decisive from its failure fields. A decisive failed primary, or a decisive successful primary for a lifecycle without downstream work, completes the required check immediately from the record's status, verdict, counts, and build error. This also applies to single-phase resume recovery after the execution budget is exhausted: a decisive terminal failure is published before optional details, while a would-be-green record with incomplete or inconsistent scalar counts remains behind the gate until details establish the result. A resumed direct-BuildTest build uses a small scalar terminal probe before any full materialization, so recovery work on the shared materialization gate cannot delay a conclusion that is already decidable. The KompileRemoteBuild compatibility adapter derives status by paging detailed results, so that route keeps its resumed status read inside the memory-bounding gate. Missing, non-terminal, and inconsistent success records keep their existing gated polling and detail behavior. Optional test-result and build-log enrichment then reads the shared detail API and updates the already-completed check with the detail result. Output-only updates are written to a durable queue before their first attempt and survive process restart. The queue retains at most 256 pending updates and retries each update five times with bounded backoff; after the fifth failure it keeps a bounded terminal audit record and stops dispatching that update. A retry can change only the check summary and details URL, never its status or conclusion. If an enrichment read fails or remains unavailable, the completed check stays completed and the failure is reported in the service log and in a post-completion warning update; the terminal conclusion is never replaced by the optional detail result. On startup, legacy queue snapshots are migrated only after complete validation, including exact root and record schemas; malformed snapshots stop startup with the file intact. Recovery also removes incomplete record-write temporary files, lets terminal records suppress stale pending copies by filename, and restores the 64-record terminal audit bound. An intact legacy snapshot identifies files created by its own interrupted migration, so restart can finish migration across every record-write ordering. After terminal-covered pending copies are removed without reading their payloads, an unrelated pending directory above 256 records is still refused before its payloads are read.
4. If a successful GitHub git-tree response confirms that the repository does not contain a `build.kts` file, the check is immediately marked as successful. If the git-tree request fails, the check is marked as failed with the API path and response status instead of being treated as a repository without `build.kts`.
5. The check appears in the PR/commit status as "kotlin.build (remote)"

### Checkpoint branches

A non-PR branch whose name starts with `wip/` is a durability checkpoint and does not receive a
`kotlin.build (remote)` run. Push checkpoint commits to a branch such as `wip/my-investigation`
without opening a pull request for that branch. If that same commit later becomes the current head of
an open pull request, it receives the required check even when GitHub retains the checkpoint branch
and empty pull-request list on the commit's existing check suite. Every suite or check-run rerequest retaining a `wip/` branch consults the
repository's current open pull requests under operation ownership before applying the checkpoint skip,
even when the retained payload contains PR associations. A parked rerequest retains its original
checkpoint provenance and reads current PR membership when it executes, before cancellation or
check creation: a closed or moved PR remains skipped, while failed or contradictory authority remains
unverified. Missing-check
reconciliation dispatches the current PR head directly from that same authoritative state. Earlier
checkpoint skips and suite re-request ledger entries therefore cannot suppress the PR-head dispatch.
An authorized contributor can still comment `/retry` to create the required check immediately. A
commit that is not the current head of an open pull request remains skipped when its retained branch
starts with `wip/`. Checkpoint rerequest reclassification requires exactly one current open PR
matching the repository and commit, with identical duplicate rows and a non-blank current head ref.
When several current PRs share a commit, the retained checkpoint rerequest remains unverified;
direct reconciliation supplies an expected PR number and current branch to resolve that work.

Several open PRs may legitimately share one head commit, and its single required check covers all
of them. Direct reconciliation normalizes identical rows and requires exactly one distinct complete
row for its expected PR number, matching the repository, installation, SHA and ref. Disagreement
within one PR number is contradictory. The complete repository response is normalized and checked
for same-number disagreement before any SHA filtering, including checkpoint reclassification;
diagnostics retain every observed number, SHA and ref. Other current PRs sharing that head are included in bypass
evaluation, build metadata and the journal, which explains when one check covers shared heads.
A failed lookup, mismatched result, incomplete row or contradictory rows prevents creation.

Requested and rerequested check-suite events share one strict authority parser. Both require a
non-blank string head SHA and branch, a valid repository and installation identity, and a present
pull-request array whose every row has a positive integral PR number. Only a validated empty array
can select a non-PR checkpoint skip. Check-run rerequests also require a non-blank suite branch and
a positive integral check-run ID. Current open-PR responses must be arrays whose every row contains
a numeric PR number and non-blank string head SHA and ref. Missing, malformed, and contradictory rerequest
authority produces an unverified evaluation with expected and actual values. An unverified primary
evaluation never completes the delivery.

Rerequest delivery IDs are retained in `ci-activity.ndjson.rerequest-deliveries.json` independently of
the bounded operator journal. IDs are retained for 14 days from their latest state transition to cover
GitHub's manual webhook redelivery horizon. Trimming operator history cannot allow an old delivery
to create another replacement during that period. Pending work can be reprocessed after restart;
completed work is suppressed. Immediately before check creation the service persists an action-start
record. If the process stops across that ambiguous creation boundary, redelivery is suppressed because
GitHub may already have created the check; the service does not invent a completed evaluation.

A parked delivery remains pending until the stripe owner evaluates it. The FIFO retains each distinct
delivery ID and completes that delivery with its own result. A repeated pending delivery ID joins
the retained operation without creating another queue entry, shadow dispatch or durable completion.
That joined invocation releases its own pending marker immediately; the retained operation keeps its
marker until both routes finish. Ordinary same-head duplicates return
immediately and release only their own pending marker; they never schedule shadow work. The retained
primary operation's evaluated callback alone owns that dispatch. Ordinary work for a different head
sharing the stripe is queued and drained. No delivery
waits for another head's work, and one operation's result cannot become another delivery's result.
With two routes, shadow dispatch waits for the primary operation to be evaluated, including when
that operation was parked. An unverified primary evaluation dispatches no shadow work. PR-head
reconciliation repeats current-PR verification independently on the shadow route. A delivery
completes once, after both routes have been evaluated or the shadow was skipped for a known failure.
The delivery's durable action-start record covers both routes: a stop while shadow work is queued
or creating its check cannot make redelivery create another replacement.

Shadow-only backfills remain pending until their own evaluated completion, including while parked
behind another head. Each overlapping operation owns its own pending marker. When queued work
executes, it re-reads the shadow check names before creation; direct PR-head backfills also revalidate
current PR authority. Synthetic queue refs retain their queue authority policy.
Tracked builds and pending head operations suppress shadow backfills only for the same complete
owner, repository, installation and head identity; issue-comment markers are scoped by owner,
repository and pull-request number. Reconciliation considers the comment markers of every current
PR sharing the head, while keeping the single expected PR identity used to validate a direct backfill.

### GitHub-runner-only repositories

`BuildTestEmbedded`, `kompile-core`, `kompile-cli`, `kotlin-build-ci`, `BuildTestApi`,
`BuildTestServerService`, and `BuildTestWui` are gated only by their `bld-*` GitHub Actions
checks. The service does not create either kotlin.build check for those repositories from
pull-request check suites, synthetic merge-queue check suites, pushes, reopened pull requests,
comment or UI re-runs, operator re-triggers, or missing-check reconciliation. Pull-request close
events are still processed so an older build that was already in flight can be cancelled. Refused
operator re-triggers are recorded in the activity journal, and both reconcilers log each repository
they skip together with this runner-only policy reason.

### Structured git context for buildtest

Every source submission to `url://buildtest/` includes structured git identity when the remote service supports it: `owner/repo`, the head commit, and the head branch. Pull request and merge-queue builds also include the base branch and merge-base commit. For normal PRs the base branch is the repository default branch; for `gh-readonly-queue/<target>/...` refs it is the queue target branch.

For PR and merge-queue builds, kotlin-build-ci asks GitHub's compare API for the merge base, downloads that merge-base source tarball the same way it downloads the head source tarball, and uploads it to the same buildtest upload session. Buildtest uses this to run performance tests as a co-run of merge-base versus PR head. This metadata path is fail-open: if compare, merge-base download, structured upload-session support, or merge-base chunk upload is unavailable, the CI check still dispatches using the existing head archive behavior.

### Archive upload recovery and failure attribution

After a restart, an active submission prepares its source archives before repeating the saved upload-session request. `resumeBuild` queues this work and returns before source downloads, uploads, or remote calls. A crash-restored primary request without a run ID retains its own submission deadline: failed recovery remains pending before that deadline, and expiry records the infrastructure result durably before publishing it. A due deadline is retained until the active lifecycle worker releases ownership. Primary, downstream, and bootstrap uploads keep the original request identity and parameters, so an accepted session is reused only when its first source chunk can be sent. Cancellation and terminal-result recovery still resolve the saved request before cleaning up its remote run. Cancellation and cleanup reasons sent to either build route
are limited to 1,024 UTF-16 characters to match the coordinator's cancellation limit. Longer reasons
keep the cause prefix and the first part of the original failure, followed by
`... [truncated: N characters total]`, where N is the original length. Truncation preserves whole
characters. Durable cleanup records, dispatcher logs and the activity journal retain the full original
reason. Only the copy sent to the coordinator is bounded, including retries after restart, so an
intent previously refused for its length can finish without losing its original diagnostic.

Recovery of an unplaced primary request runs asynchronously and can replay its saved request while
local build workers are occupied. A failed replay stays retryable before the submission deadline;
a failed replay at or after that deadline reports one infrastructure result and retains the uncertain
request for cleanup. An already-accepted run is adopted even at the deadline, preserving its real outcome.
A saved replacement request takes precedence over the older unfinished session in every phase.
Replacement changes only the upload request identity; its label, rule, notes and git metadata remain
as originally recorded. Its merge-base archive is downloaded using the saved commit.
Bootstrap workspace archives become reusable only after the complete write is atomically published;
an interrupted write from an earlier process is rebuilt before any saved upload is replayed.

Each source submission belongs to one named upload request, sent as a `kotlin-build-ci-upload-request=<id>` marker line appended to the run notes — and it is the only such line the notes carry, because any marker line already present in the caller's notes is dropped first, so two unrelated builds can never claim one upload request. Every attempt at `createUploadSession` repeats that name, so a create the service applied but whose response was lost is answered with the session it already minted instead of registering a second run that nothing uploads into. When the service reports that an upload session is unknown — it expired, or the service restarted and lost it — the upload does not keep resending chunks to it: it starts a new upload request, publishes the new run id so the GitHub check-run link follows the run that is receiving the archive, and re-sends the archive from the first chunk, all inside the existing attempt budget. Restarting is spent out of that budget like any other attempt, so a service that keeps losing sessions produces one reported failure rather than a stream of new sessions.

The merge-base archive upload fails open on its own failures, but an unknown session is not one of them: it means the whole session is gone, so it is raised immediately and the upload restarts, rather than spending the merge-base retry budget on a session that cannot accept anything.

When an upload does not complete, the failure names the step that stalled, the chunk position reached, the upload session, how many sessions were created, the create and upload attempt counts, the last step that completed with its timestamp, and the exact error text last seen. That summary is reported once per upload and reaches the GitHub check output, so a build whose archive never arrived can be attributed without reading service logs.

For direct BuildTest uploads in the primary, downstream, and self-bootstrap phases, the accepted
run ID and completion of the workspace upload are persisted separately. If the CI process restarts
after recording a session but before acknowledging finalization, recovery first checks for an
authoritative terminal result. A terminal run is polled for its verdict; otherwise primary recovery
downloads the same head commit, while downstream and bootstrap recovery rebuilds that phase's
workspace with the same head commit and saved predecessor run IDs. Recovery sends the archive from
chunk zero to the saved session and finalizes it before polling. A bootstrap test upload keeps its
saved fat-JAR run ID for a replacement session. A lost finalization response is safe because
BuildTest accepts repeated chunks and finalization for the same session. Cancellation and
replacement retain their existing ownership rules. Records written before the upload marker was
introduced retain their existing polling behavior: those records do not distinguish an unfinished
upload from a queued build whose archive was already accepted.

### Opt-in KompileRemoteBuild route

`CI_BUILD_ROUTE` is unset by default, which preserves the direct `url://buildtest/` submission and polling path. Set it to `kompile-remote-build` only in an environment where the CI surface is hosted at `url://kompile-remote-build/`. The route adapter sends the archive and `CiRefContext` through `kompile.remotebuild.api:kompile-remote-build-api:0.0.11`, then maps KRB lifecycle, bounded results/logs, and check verdicts into the existing runner contract. The API also provides the upload-session lookup used to reconcile an ambiguous `finalizeUpload` without creating a duplicate build. The KRB service owns its execution lease, and the head archive plus ref metadata are submitted without the direct route's optional merge-base chunk.

After the archive is uploaded, `finalizeUpload` is idempotent by upload-session id. If its RPC result is ambiguous because the connection ended after the request write began, the adapter retries only finalization with the same session id inside the existing attempt budget. It never creates another session or uploads the archive again for that outcome. Other finalization failures are returned immediately.

Every stop on this route — a cancellation, a supersede, an outpace, or a bypass fallback — is sent as KRB's `cancelCiBuild(id, reason)` with the same non-blank reason the direct route would send, and KRB records that reason on each underlying BuildTest run it stops. A blank reason is refused before any request is sent. The reason-less `cancelCiBuild(id)` is never used. KRB records every such stop as `CANCELED`: it has no outpace, supersede or bypass operation, so the reason is what tells them apart on the build service.

The KRB API cannot produce native `BYPASSED`. A `/bypass <message>` request on this route therefore selects and saves `FALLBACK_CANCEL` before issuing cancellation or inspecting a pre-existing terminal marker. Its remote proof is `CANCELED`, and the cancellation's reason is `CI bypassed via /bypass: <message>`; the local GitHub check still reports the requested bypass message. The direct BuildTest route provides native `BYPASSED` with the exact stored message as described below.

This route requires a hosted `url://kompile-remote-build/` service whose client implements `cancelCiBuild(id, reason)` (KompileRemoteBuildServiceServer 0.1.29 or later). Deploy that service before deploying a kotlin-build-ci that uses this route as its primary or shadow route; against an older service every stop on this route fails and stays durably tracked for retry.

### Durable cancellation and submission contract

The first saved terminal result owns the check's conclusion, summary and details URL. If cancellation saves that result before a worker reaches completion, the worker publishes the saved cancellation even when its own result differs. A cancellation publisher that re-enters while this gateway owner validates its result defers and leaves the owner running; the owner publishes the exact first saved result, including its summary and details URL, without a watchdog sweep or restart. Optional worker detail enrichment runs only when that exact worker candidate was published, so a stale worker cannot replace a saved cancellation or bypass summary even when both conclusions are `success`. A result already saved by the worker before cancellation remains authoritative. Once publication succeeds, later cancellation cannot issue a second completion. Detail enrichment is also skipped when an opaque create failure replaces the worker's candidate with its infrastructure report, and when a retry finds the result already published (for example after an acknowledgement failure); in both cases the published result is not the worker's exact candidate.

A cancellation path's completion result reports whether the terminal result that cancellation saved owns the check's published conclusion, regardless of which path made the GitHub request or whether publication was left to the gateway owner already publishing it. `completeCanceledBuild` returns true when this call published that result, when another completion path published it first, when the gateway owner already publishing the check publishes it, and when GitHub accepted it but clearing the local tracking record failed (recovery reconciles the saved result under the same check identity and then clears the record); `completeCanceledBuildWithOutcome` says which of these happened. The gateway records each accepted publication, as a fixed-size digest in a bounded in-process record, before it clears the tracking record, so a caller never sees the check untracked without that record. So when a worker, the watchdog or a resume publishes a saved cancellation first, an operator cancel still records the cancellation with the check run and its conclusion, and a GitHub Re-run request still creates its replacement build. It returns false only when another saved result owns the check, when publication did not finish and the saved result stays tracked for recovery, or when the check stopped being tracked and this process holds no record of which result was published (another process published it, or the bounded record already evicted the entry).

Each primary, downstream, bootstrap fat-JAR, and bootstrap test submission belongs to its check's durable cancellation owner. Before a direct upload-session create can register a run, the tracker saves the named upload request and its parameters. Before KRB finalization can accept a run, it saves the opaque upload-session ID. A returned run ID replaces that reservation atomically, before result mapping or product callbacks. For a cancellation-owned conclusion, acceptance is then saved per run before the local terminal result can be claimed and posted. Opaque create failures report independently, as described below.

The built-in GitHub completion adapter reads the same check identity before publication and before
any completion retry. When the same check is completed with the saved conclusion, recovery acknowledges that acceptance
locally without posting it again. Recovery and live completion use the same acceptance proof.
Status and conclusion decide verification; summary and details URL representation differences are
reported as diagnostics and do not prevent acknowledgement. A failed verification read retains
the unreported result; it never acknowledges a result before remote acceptance. Custom completion
adapters can supply `onReadCheckRunForCompletion` alongside `onCompleteCheckRun`; an adapter that
provides only a completion callback must still reconcile an ambiguous external call. Once that
callback returns successfully, acceptance is saved durably before the tracker acknowledgement.
An interruption before or during acknowledgement preserves the caller's signal, and recovery
reuses the saved acceptance across process restart and long pending history without publishing
again. Supplied acceptance readers remain authoritative. Ordinary acknowledgement write failures
retain the callback-only adapter's existing replay behavior.

A saved terminal result also owns its journal step independently of publication. Recovery replays a
missing completion event before removing the tracker row, including older already-reported rows.
The result's stable identity makes an append whose acknowledgement was lost idempotent. Pending
append ownership survives journal trimming and restart, and is released after the tracker durably
acknowledges that event. The normal bounded history policy still discards distant acknowledged events.

Submission and cancellation use the same per-check process lock. A concurrent terminal owner waits for ID adoption or submission failure. A synchronous callback requesting cancellation leaves the owner pending until the enclosing submission supplies its ID. Restart recovery resolves KRB reservations by upload-session lookup and idempotent finalization; it resolves direct reservations by repeating the same named create request. An early negative lookup cannot prove an in-flight finalization was rejected. The current route APIs also report the same opaque failure before and after acceptance, so recovery conservatively retains a reservation after such a failure; only an adopted run or a definitive non-acceptance result could discharge it. Failures before the run-creating call leave no reservation. An interrupted submission with no existing cancellation or terminal owner adopts the accepted run and resumes it. The direct primary upload resends the same head archive to its saved session when necessary; a run already terminal keeps its actual verdict. Recovery repeats the named request to resolve acceptance and never creates a separate replacement merely because its response was lost. Replacement retains unresolved old-attempt reservations, and returned old-attempt IDs move into durable detached cleanup on the surviving check. After bounded opaque run-creating failures, the check reports its infrastructure conclusion with full diagnostics while retaining the reservation and its original failure context. If the process exits before recording a create failure, restart recovery repeats the same named request with the existing recovery backoff. When that unresolved primary creation still fails at or after the check's saved submission timeout, the check publishes an infrastructure result and retains the request for later resolution and cleanup. An accepted run is adopted first and keeps its remote outcome; time spent in the remote queue is outside this submission deadline. The saved cause, full stack trace and infrastructure classification produce the same failure report on the original worker or after restart. Successful reporting is recorded separately from cleanup, so later recovery cancels an adopted run with `orphaned after opaque create failure: <cause>` and cannot issue a second completion. An unresolved reservation or cleanup prevents untracking; it does not leave this failure report pending. A replacement attempt retains the old reservation as detached cleanup owned by the old attempt.

| Invariant | Required public behavior |
|---|---|
| C1 — Submission ownership | No accepted or potentially accepted run escapes tracking. The remote ownership sequence is submission reservation → run ID → accepted cleanup → removal. An opaque create failure may post its local terminal result before resolution; its reservation and failure context survive until recovery finishes, and successful reporting is acknowledged independently. |
| C2 — Route before proof | Select and persist the route's operation mode before either reading terminal proof or issuing an operation. KRB bypass expects `CANCELED`; direct native bypass expects `BYPASSED` and the exact message. |
| C3 — Accepted stages | Every remote cancellation or bypass belongs to durable intent and saves acceptance before completion. Upload adapters and callback error handlers never issue independent cleanup. |
| C4 — Consistent intent | A public cancellation's reason, message, mode, completion and redrive intent must agree. Redrive requires `CANCEL` and no bypass message; incompatible tuples are rejected before changing state or sending an operation. |
| C5 — Bounded proof reads | KRB reconciliation and marker proof reads, including lazy result fields, use the same route materialization gate as ordinary terminal reads across different checks. |
| C6 — Local results | `LOCAL_RESULT` cleanup accepts any terminal remote status, including completion or failure observed during a stop request. This covers detached submitted-run cleanup after the check has saved its local result, recovery of a superseded check whose replacement already owns the pull request, and recovery of a closed pull request whose remote run was already recorded terminal before cancellation was accepted. Detached ownership requires saved cancellation intent and no attached, unfinished worker future for that check in this process; a live owner keeps exact remote proof. The saved local conclusion remains authoritative; a completed check is never posted again. The requested `SUPERSEDED` or `CANCELED` disposition stays unchanged when a detached remote run reports `COMPLETED`; diagnostics retain the observed status and the unmet cancellation requirement. Live cancellation and bypass retain their exact proof requirements. |
| C7 — Assertion origin | Only the generated proxy boundary classifies transport assertions. Genuine assertions from adapters, local dependencies and product callbacks retain their ordinary failure behavior. |
| C8 — Diagnostics | Failure diagnostics retain the operation and proof context, including run identity, mode, expected/observed result, and the owning reason and summary where applicable; original failures remain available as causes or suppressed diagnostics. |

### Optional shadow route

`CI_SHADOW_ROUTE` is unset by default. When it is unset, the service keeps the primary-only construction and reporting path: it does not create shadow trackers, remote-service clients, workers, or connections, and it posts only the required `kotlin.build (remote)` check. When set to the other valid route, a CI-triggering event is submitted to the shadow route after the primary has a known evaluation; an unverified primary result skips shadow dispatch. The kompile-remote-build result is reported by a separate `kotlin.build (kompile-remote-build)` check whose output names the route, includes the dashboard link, and says that the result is informational and does not gate branch protection. Shadow state is stored separately so a shadow failure, timeout, or restart cannot change the primary build's tracker or verdict.

An infrastructure-only error already classified by the runner completes the informational check as `neutral` while retaining the full diagnostic summary. A completed compile or test failure remains `failure`; ambiguous or unclassified errors also remain failures.

The migration can proceed in three phases:

1. Run the existing direct route alone (`CI_BUILD_ROUTE` unset or `direct-buildtest`, with no `CI_SHADOW_ROUTE`).
2. Keep direct as primary and set `CI_SHADOW_ROUTE=kompile-remote-build` while comparing the informational shadow results.
3. Swap the values: set `CI_BUILD_ROUTE=kompile-remote-build` and `CI_SHADOW_ROUTE=direct-buildtest` while keeping branch protection on `kotlin.build (remote)`.

The two variables must name different valid routes. An invalid value or a shadow value equal to the effective primary route stops startup with a descriptive configuration error.

### Webhook acknowledgement is non-blocking

GitHub aborts a webhook delivery that does not receive a response within roughly 10 seconds, and it does **not** automatically redeliver a delivery that timed out. Creating a check run, querying `/bypass` status, checking remote build availability, and downloading `build.kts` are all synchronous network calls; under load they could take 4–9 seconds (occasionally more), so processing a webhook inline on the request thread could exceed the 10-second budget and the delivery — and the build it would have triggered — would be silently lost.

To prevent this, the webhook endpoint verifies the HMAC signature and decodes the JSON inline (fast), then hands the decoded event to a bounded background executor and returns `200 OK` immediately. Processing latency can therefore never cost us the delivery. This is paired with the [Dropped-Webhook Self-Healing](#dropped-webhook-self-healing) poll below, which recovers any delivery that is lost for other reasons (for example, the server being briefly unreachable when the delivery arrived).

The runner uses UrlResolver's caller-scoped sandbox interruption: stopping a worker releases its
in-flight status call without reporting a method deadline or abandoning unrelated calls on the shared
connection. `shutdown()` waits for workers to stop while retaining remote run IDs for restart recovery.

### How many checks run at once

`GET /api/status` keeps `buildsInFlight` as the total number of logical tracked builds.
When a legacy array repeats a check-run ID, the last record supplies its repository and state;
earlier records contribute to none of the counters. Its four
state counters partition that total: `buildsAwaitingCleanup` counts cancellation or saved terminal
results, `buildsWaitingForAWorker` counts deferred work whose lease has not started running,
`buildsWithRemoteRuns` counts the remaining checks with an active remote run ID, and
`buildsAwaitingSubmission` counts the remaining checks. Cleanup takes precedence over the other
states, so a retained cleanup row is never presented as a worker waiting to start.
The tracker portion of `/api/status` reads a fixed number of counters, independent of the number
of tracked builds and the size of their diagnostics. Status uses the last successfully published
tracker view. It does not wait
for a worker holding the tracker monitor or decode record diagnostics. A peer writer becomes
visible in this view when this tracker next refreshes; an unchanged indexed point read reuses
its selected decoded row while its row-version metadata is unchanged.
`buildsDispatchedInLastHour` counts journaled new build dispatches rather than accepted remote
submissions; resumed work does not add another dispatch entry. These figures do not impose an
hourly global limit. Repository limits appear separately in `repositoriesWithABuildRate`.


A check's lifecycle is submit-then-poll: downloading the source archive and uploading it to the build service takes seconds, and everything after that is polling the remote run's status, which is almost entirely sleeping. The build-worker pool is therefore elastic — it keeps four workers alive when the service is idle and grows on demand up to 64 concurrent check lifecycles. Restart recovery may occupy at most 60 lifecycle slots, leaving four available for newly accepted webhook work. Primary-session creation and durable ID persistence have their own process-wide permit. A ready direct upload first obtains the bounded priority reservation described below, before creating a coordinator session whose inactivity clock would otherwise run while it waits. When a direct primary submission occupies the final admission worker, its later tracking continues on a virtual thread so the worker remains available for the next submission. If all available lifecycles are occupied, the next deferred check is held at the zero-capacity executor handoff so the first worker that finishes takes it immediately.

The pool has no waiting room. A submission either starts on a worker immediately or is rejected, and a rejection puts the check on the runner's durable deferred queue, where its wait is persisted (`deferredAtMillis`, plus the worker queue depth at the time) and survives a restart. Newly accepted webhook work is admitted before restart recovery work, while checks remain oldest-first within each class. That queue holds at most 64 checks. A check beyond that limit receives an explicit failure instead of being retained in an unbounded local backlog. The durable queue is the only place a check can wait, so every check that has not started remains visible rather than sitting silently in an executor queue while GitHub shows it as in progress.

Remote test results are requested in windows of at most 32 rows. Each window also uses the
coordinator's existing 1 MiB response limit. The consumer processes every returned row before
requesting the next window; it retains counts and at most 200 failure diagnostics, rather than
all results. Retained diagnostic names and individual error text each have a 4 KiB UTF-8 limit,
and retained error text has a 256 KiB total limit. The per-row callback receives the complete
returned fields. A single row larger than the coordinator's response limit remains subject to
the coordinator's existing response rules.

The route-specific materialization gate admits one memory-heavy caller at a time. A direct upload
reserves priority after its archives are ready and before creating its session. An activated
reservation has strict priority over queued status reads through an active attempt's publication,
chunks and finalization. A failed attempt releases its reservation and lane during retry backoff,
then rejoins FIFO admission when ready; this also applies before replacing an unknown session and
between failed create attempts. Restored uploads
prepare their archives before reserving; status and projection reads continue during that download.
A known restored session is reused and finalized on its original ID.

Upload fairness uses **K=4**: at most four further upload grants precede the next waiting status turn,
in addition to the current holder. Outstanding reservations consume that allowance before more
sessions can be created. When it drains, a status grant resets it; later ready uploads wait before
creation. Within the upload class, independent reservations are FIFO by ready admission. Synchronous
callback submissions share the current owner's grant, including when independent admission is full;
they do not take another grant or reset fairness debt. Their nested work finishes before the callback
returns, while independent reservations keep their order. A single upload with
no earlier allowance owed waits only for the current holder. With sustained uploads it can also owe
the fairness status turn before its session is created; unconditional priority for every new arrival
would starve status reads.

For an activated ready reservation, the scheduling delay before its active upload attempt is at most
**H + U**, where H is the current holder's remaining duration and U is the combined
remaining creation, publication, and transfer duration of up to three earlier activated uploads; queued status reads add zero grants. Total
time from session creation to the first chunk also includes the upload's own response/durable-ID
work R and publication/first-request work P: a conservative conditional bound is R + H + U + P.
Retry backoff withdraws admission; the failed session can see status grants during backoff and
re-admission. None of those durations has a finite scheduler guarantee, so this does not promise the coordinator's
five-minute inactivity limit is met. A restored
session's age also includes process downtime and archive preparation, which have no finite bound in
this scheduler; recovery cannot guarantee its first chunk arrives within five minutes of the old
session's creation. It preserves a still-known session and replaces it only on an unknown-session
response.

The status turns preserve short-burst priority for new work without starving recovery reads. While recovery waits, fresh calls may receive at most four consecutive grants before the next grant is reserved for recovery; after that recovery grant, fresh priority starts again. Within each kind of call, waiting callers are admitted in the order they arrived, so the reserved grant goes to the recovery call that has waited longest rather than to a later call from a recovery that was just served. Thus the oldest recovery status caller waits at most five status turns (twenty further upload grants); a caller behind other recovery readers has the corresponding FIFO-position bound. These bounds assume each current operation finishes. They count independent grants; synchronous
nested callbacks remain part of the current holder. A nested retry cannot release an enclosing RPC's
still-live materialization. Such a retry retains the outer owner for its existing delay budget:
base delay times N(N-1)/2 per N-attempt sequence (20 seconds for the default five attempts at 2000 ms).
Arbitrary callback work still has no total duration bound. The production create RPC has a 120-second
method watchdog and a 120,000-ms transport response wait; cold connection setup and durable-ID
callbacks fall outside that invocation bound.

## Dropped-Webhook Self-Healing

> The reconciler poll below and the [failed-delivery redelivery sweep](#failed-delivery-redelivery-sweep) are complementary recovery layers — neither replaces the other. [docs/WEBHOOK_RECOVERY.md](docs/WEBHOOK_RECOVERY.md) explains the division of labor, with a coverage table of which layer heals which failure mode.

Because GitHub never redelivers a timed-out (or otherwise failed) webhook, and because this app is triggered by `push` / `check_suite` events rather than `pull_request` events, a single lost trigger delivery would otherwise leave a pull request permanently without a `kotlin.build (remote)` check. (This is exactly what happened to ContainerNursery PR #474 on 2026-06-29: both its `push` and `check_suite:requested` deliveries timed out and the PR never got a remote CI run.)

To make the system self-healing, the service embeds the shared [build-watchman](https://github.com/CodexCoder21Organization/build-watchman) library and periodically assesses the authoritative GitHub state against the work it owns:

1. It enumerates the app's installations and, via the search API, the repositories that currently have open pull requests.
2. For each open PR's head commit, it lists the existing check runs.
3. The shared watcher identifies a queued or in-progress check suite with zero dispatched runs after its 60-second grace period. Before choosing an action, the service reads the suite's commit and branch as typed provenance; it never infers queue ownership from an optional watcher log line. Queue-shaped refs are classified first: they must name the same PR, identify a commit different from the PR head, and carry a valid queue ref before queue remediation is allowed. Only a non-queue suite whose commit equals the current PR head can select direct dispatch. Direct dispatch retains that suite branch for final suite verification, while its reservation owner separately rereads the current PR branch before creating a check. This permits a suite first created on a checkpoint branch to recover after the same commit becomes a PR head without accepting stale PR data. If provenance is missing, malformed, or contradictory, the poll records a distinct no-action message and dispatches neither commit.

A head commit that already has the check run (in progress, success, or failure) is not dispatched again. Direct reconciliation and ordinary check-suite webhook handling share a per-head reservation, but a delivery never waits for a reservation another thread holds. Direct reconciliation re-reads the current open PR and the commit's check-run names while holding the reservation, then claims its durable allowance immediately before creation; a failed read or verified no-action therefore consumes no allowance, while a stop in the genuinely ambiguous claim-to-create boundary does. Successful GitHub responses are authoritative only when `check_runs` is an array, every row has a non-blank string name, and a page shorter than 100 proves the end of the listing. When `total_count` is present it must be a nonnegative integral JSON number, remain unchanged across pages, and exactly match the complete accumulated row count; contradictions are unverified rather than accepted as an empty or complete list. A full twentieth page is unverified, with its URL, page and row count in the diagnostic; it never proves a check is absent. Reconciliation records its result only from the operation's evaluated completion together with its allowance claim. A parked operation remains pending without a terminal ledger result until it is evaluated. A head commit with no app-owned check suite is also not acted on: absence of a check run alone also describes old pull requests that predate this CI installation and repositories that do not use this app. The watcher poll covers open pull requests and their merge-queue refs; pushes to default branches are not inferred by this mechanism; a default-branch check that was created but stopped progressing is handled by the [GitHub-alignment sweep](#github-alignment-sweep).

The shared watcher calls a queued zero-run suite a lost dispatch only when the same app demonstrably dispatches on the pull request's base branch: at least one of that app's check suites on the base commit or on one of the five latest base-branch commits has run. When every sampled base suite of the app is also queued with zero runs, the watcher does not report a lost dispatch. If one of those suites is older than 60 minutes, the watcher logs once that the app is installed but not dispatching for the repository. Otherwise the evidence is too fresh or incomplete, and the watcher logs an `INFO: dispatch classification probe ... was deferred` line, retries on the next poll, then retries every fifth poll. In both cases the service takes no repair action.

The service keeps one watcher per open pull request for as long as that pull request's head commit is unchanged, which can be many hours. GitHub App installation tokens expire after one hour, so the watcher never holds a token of its own: it asks the service's installation-token cache for the installation's current token on every GitHub request, and the cache mints a replacement before the previous token expires. A long-lived watcher therefore keeps authenticating across token rotations; a token GitHub still rejects is reported as a `PROBLEM` line on that poll.

The merge-queue-ref, check-run, and open-pull-request authority readers accept any short final page, including an empty page 20 after nineteen full pages, and return every row accumulated before it; only a full page 20 is incomplete.

Immediately before a direct PR-head repair, the verifier compares the suite's current App id, SHA, and branch with the typed provenance that selected that route; a changed or unreadable identity is recorded as unverified and consumes no allowance. The direct operation retains this verification while parked and repeats it after owning the head reservation, immediately before claiming its allowance; a changed identity or nonempty run list completes as UNVERIFIED without a claim.

### Merge-queue coverage

A PR sitting in the merge queue receives its check suite on a **synthetic merge commit** on a `gh-readonly-queue/<target>/pr-<number>-<sha>` ref — not on the PR's head commit, which by that point already has a completed check run from the branch build. Scanning open-PR head commits alone can therefore never detect a lost merge-queue `check_suite` delivery: the PR looks healthy while its queue entry sits `queued` with zero check runs forever (this stranded a kompile-core merge-queue check suite for 6 days in 2026-07).

The watcher also reads the `gh-readonly-queue/...` ref for each open PR and applies the same
dispatch and progress analysis to the synthetic queue commit. A lost queue-ref dispatch is
re-requested. Immediately before POST, the adapter verifies unchanged suite SHA and branch and reads
its check-run list again. A changed identity, an existing check or a failed read prevents POST and
consumes no allowance. Suite reads, request construction, and HTTP call construction before rerequest `execute()` cannot consume an allowance: a failure is UNVERIFIED, releases its provisional claim once, and permits a later verified attempt. Suite readers inspect raw JSON values: the App ID must be a positive integral
JSON number, and SHA and branch must be non-blank JSON strings. Wrong types are unverified, with the
URL, field, expected type, actual value/type and body in the diagnostic. Merge-queue ref listings
must consist entirely of valid `refs/heads/gh-readonly-queue/<target>/pr-<number>-<hex>` rows with
non-blank string SHAs; conflicting duplicate refs and a full final page at the pagination limit
reject the whole response. If a queue ref disappears while the PR is still open, the service
re-enqueues it once; a durable per-PR ledger records the finding, action result, and the
second-eviction cap decision so a persistent queue failure cannot loop forever.

If buildtest rejects a stalled-run cancellation, the exact cancellation and replacement intent
remain in `in-progress-builds.json`. Startup or worker recovery retries that saved operation and
admits the replacement without consuming another remediation-ledger allowance; the original
remote error is still emitted in the watcher diagnostics.

### How the in-progress build tracker is written and read

The durable tracker uses `in-progress-builds.json` together with committed updates in its
`.updates` sibling directory and accepted-completion receipts in its `.publications` sibling
directory. Keep all three when copying or restoring tracker state. Include `.writing` files in
`.publications`: a complete staged receipt remains authoritative if its atomic publication failed
or ownership acquisition was interrupted. These receipts prevent an accepted completion from being
published again while its tracker-row acknowledgement is pending. Startup removes receipts and
staging files whose rows have ended. Updates become visible through an atomic publication, so an
interrupted write preserves the last committed state.
The `.index` file and its record directories are derived data and can be rebuilt from that durable
state. A missing index directory is rebuilt from the base and committed updates. Startup also
accepts older committed updates without lifecycle counters, deriving their counts from every
inserted, replaced, and deleted record. An unsuccessful upgrade preserves the previously published
index and its pointer until a replacement is ready. Replacing the base file invalidates updates
belonging to its previous content.

Tracker-owned memory has fixed limits: at most 16 MiB of cached file bytes, 128 decoded records,
4 MiB of estimated decoded text, and 4 MiB of conservative collection-object overhead.
Many short collection entries count toward the object limit as well as the text limit. Larger
trackers restore through a streaming reader and keep
records on disk. The finite cache keeps publishing canonical JSON for small trackers. Indexed
point reads retain at most one selected record within the same text and collection limits,
and reuse it while its row-version metadata is unchanged; a record change persists that change rather than
rewriting accumulated records. Periodic compaction streams one record at a time.
Before decoding a replacement or switching to the indexed store, the previous finite cache is
released. A failed replacement never restores that cache; subsequent reads use the durable source.
A bounded weak-reference pool may reuse equal immutable collection snapshots still held by callers,
including after a malformed base is repaired. It retains neither decoded records nor diagnostics.
Record text remains complete. Restart uses the same durable read path as normal operation.
Legacy array files remain readable. On indexed startup, missing lifecycle intent and deferred
sequence fields migrate through one committed record update at a time; a restart preserves
completed migrations and finishes any remaining records. Each migration advances through persisted
rows without rescanning its resolved prefix, including across compaction. Indexed startup finds the
highest saved deferred sequence by visiting each persisted record once, then persists that maximum
as a scalar. Each migration cursor is published in HEAD with its record update. Compaction also
saves the reset cursor before publishing the new base, bound to the new base digest, so an
interrupted publication retains migration progress. Discovery work is linear; total work including
full compactions is not. With at most N retained records, M migrating updates and J already journaled transactions
at the start (0 <= J <= 64), at most floor((J + M) / 64) compactions each visit up to N
records to write the base and another N to rebuild its derived projection. Thus total row
visits are bounded by linear discovery plus 2 * N * floor((J + M) / 64), rather than a
promise that doubling N doubles all work. Small trackers retain
canonical JSON
publication within the same fixed cache limits.
`getAllBuilds()` materializes the list requested by its caller; the tracker does not retain that
list. Worker Futures are process-local handles and are never restored from disk.

The indexed path caches the base digest behind its metadata generation: file key (inode), size,
precise modification time and change time. An unchanged base consumes zero file bytes on reads;
a changed generation is hashed once, and SHA-256 remains the identity in HEAD and the index pointer.
Same-size, same-modification-time replacements and in-place rewrites change the inode or change time.
A writer preserving every generation field during an in-place rewrite is outside the writer contract.
Filesystems without native change-time/file-key support conservatively rehash; non-system storage
adapters must change their published metadata generation. The finite compatibility cache still
compares durable bytes with fixed buffers. After successful reconciliation, refresh removes
unpublished index generations left by interrupted rebuilds. Rebuild validates the base again after
replaying the committed journal and before publishing a replacement. A failure before pointer
publication preserves the old pointer and projection. If a pointer move completes before reporting
an error, both the previous projection and the complete replacement remain available until a
successful refresh reconciles them. Returned collection values are immutable
snapshots. Accepted array syntax and record errors match org.json, and unreadable state still
reports the file and the underlying error instead of answering with an empty tracker.
A derived projection write failure instead names the derived path and preserves its original
cause. It does not advise deleting the authoritative base or label that source unreadable.

**Rollback to an array-only reader:** call `InProgressBuildTracker.flushForRollback()` before
switching readers, or stop the runner gracefully with `BuildRunner.shutdown()`. Both publish every
committed update into the legacy JSON array. A successful repeated shutdown is harmless. Copy the
base, `.updates`, and `.publications` directories before any rollback. An abrupt process stop before that flush can
leave up to 64 committed transactions absent from an older reader; a current reader recovers them.
For single-record updates this is at most 64 record updates. A transaction can also change a batch
(for example repository cancellation), so the general record-update bound is 64 times the maximum
number of rows changed by one transaction in that interval, not an unconditional 64 records.
Graceful rollback has no such gap. The explicit flush reports write failures to its caller.
The JVM exit hook uses `shutdownForJvmExit()` and reuses the runner's fixed ten-second shutdown
wait for the entire preparation task, including storage. It returns false when preparation has
not completed and reports that array-only rollback is not ready. The base and committed `.updates`
still recover every logical build with a current reader. Keep both files; JVM exit alone is not
an acknowledgement that an older array-only reader can be used. Ordinary `shutdown()` retains
its synchronous full-flush contract. Repeated JVM-exit requests share the same preparation task. The exit wait stays bounded even
when a scheduled callback is holding tracker storage; its deadline does not depend on that callback
or its scheduler finishing.
Shutdown flushes under the same exact-path ownership as writes, without waiting for another
tracker file's persistence stripe.

The service reads the tracker before it binds its port, and it **refuses to start** if that file
exists but is empty or cannot be parsed. An empty tracker is written as `[]`, never as nothing, so
a file with no text has been truncated, and unparsable text is a corrupt record set; answering
either with "no builds are tracked" would silently abandon every check run the file held, leaving
those checks in progress on GitHub forever. The startup error names the file and says what to do:
once its contents are known to be unrecoverable, delete it and the service starts with an empty
tracker, after which the checks it held have to be rerequested. The shadow tracker
(`in-progress-builds-shadow.json`, used when `CI_SHADOW_ROUTE` is set) is written and read exactly
the same way and refuses to start on the same conditions.

**Before deploying this release onto a host an earlier release has run on, check that
`in-progress-builds.json` — and `in-progress-builds-shadow.json` when `CI_SHADOW_ROUTE` is set — is
not zero bytes.** Releases before this one wrote straight into the live file, so a stop that
interrupted a worker mid-write could leave it empty; those releases then read it as an empty tracker
and carried on, which is how the in-flight checks it held were lost. This release reports that file
instead of reading it, so a host already damaged that way will fail to start until the file is
removed. The records are already gone either way: delete the zero-byte file and rerequest the checks
that were in flight.

The shared library currently follows the organization's standard `main` merge queue target. A
future build-watchman release should expose the queue target as a parameter before this service is
used for repositories whose merge queue targets another branch.

The open-PR/merge-queue poll interval defaults to 1 minute and is configured with
`RECONCILE_POLL_MINUTES` (set to `0` to disable). The first pass runs immediately. Tracked-run
stall watching is a separate always-on scheduler, also running every minute by default, so it does
not depend on WebCron delivering `/check-builds`; configure it with `WATCHMAN_RUN_POLL_MINUTES`
or set that value to `0` to disable it explicitly. The service stores remediation decisions in
`DATA_DIR/build-watchman-remediations.ndjson`; each PR-head direct dispatch, queue-suite rerequest,
commit-level stalled-run redrive, and queue re-enqueue has one durable allowance by key. Every ledger
load drops a malformed final record and truncates the file to the last complete record before any
append. A malformed non-final record stops loading with the file path, line number and actual
content in the error. A complete record without a trailing newline is preserved and separated from
the next append. A torn claim has not consumed an allowance; a torn release has not released it.
Complete claims and releases appended after recovery survive subsequent reloads. Each new decision
has a durable identity, and a release names that exact decision and applies at most once. Releasing
an older decision again cannot free a later decision's allowance, even after restart or from concurrent
callers. A release after any recorded result is rejected under the persistence lock without appending a record, while repeating a valid release remains idempotent. Legacy decisions receive stable identities from their append positions; a legacy release
without an identity can release only the first matching legacy decision. Ambiguous repeated legacy
attempt numbers cannot establish permission to release a later decision. For example, two allowed
legacy decisions with attempt 1 separated by releases still leave the second decision consumed.
Legacy result records also receive every prior matching decision identity they could describe, so a
completed action cannot regain an allowance after restart merely because its old result lacked an ID.
Distinct attempt numbers release independently, denied claims consume no allowance, and allowances
remain separate by key and remediation kind. New decisions and releases can be appended to a legacy
file; reconstructing the ledger preserves the old identities and exact remaining allowance.

### Failed-delivery redelivery sweep

The state-based poll cannot reconstruct every webhook. Default-branch pushes, `/rerun` and `/bypass` issue comments, and pull-request closure events may leave no authoritative state that can be safely inferred after their delivery is lost. A second background sweep therefore uses GitHub's App webhook-delivery API to replay the actual failed deliveries.

Every 10 minutes by default, the sweep lists cursor-paginated deliveries back to a 120-minute lookback boundary. It only requests redelivery when GitHub's textual and numeric outcome fields agree that the original attempt failed, and it skips records GitHub marks as redeliveries so a failed replay cannot feed back into an unbounded replay chain. It also skips a failed attempt if a later successful delivery has the same `guid`. Malformed or contradictory records are skipped conservatively. A process never requests the same delivery ID twice within the lookback window, each pass is capped at 50 attempts, and a failed redelivery request does not stop the remaining candidates. Each API request uses a fresh App JWT, and only one service instance sharing `DATA_DIR` holds the sweep leader lock.

Configure the sweep with `REDELIVERY_SWEEP_MINUTES`, `REDELIVERY_LOOKBACK_MINUTES`, and `REDELIVERY_MAX_PER_SWEEP`. Set `REDELIVERY_SWEEP_MINUTES=0` to disable it. Negative, non-numeric, overflowing, or non-positive maximum values fail startup with a descriptive configuration error. The existing state reconciler and this delivery sweep are complementary: the reconciler safely repairs missing PR and merge-queue checks from current GitHub state, while redelivery preserves event-specific behavior that cannot be reconstructed.

### GitHub-alignment sweep

The two layers above recover triggers that never produced a check. Neither notices a check that
exists and is stuck: GitHub shows `kotlin.build (remote)` in progress while this service tracks
nothing for it, or tracks a build whose remote run BuildTest no longer has. In September 2026 five
required checks, two of them on `main` pushes, stayed in progress for four days after BuildTest
lost their PENDING runs. A third background sweep therefore compares GitHub's view with the
tracker's.

GitHub lists check runs only per commit, so each pass examines a bounded watched set per
repository: the current merge-queue refs, the heads of open pull requests and the newest
`ALIGNMENT_SWEEP_DEFAULT_BRANCH_COMMITS` commits of the default branch. Repositories come from the
App's installations (runner-only repositories and archived repositories are skipped). On each
commit it lists this App's `kotlin.build (remote)` check runs under the same authority rules as the
reconciler: a malformed row, a `total_count` that disagrees with the rows, or a full final page
makes the listing unverified. A check still `in_progress` or `queued` becomes a candidate only once
it has been waiting for `ALIGNMENT_SWEEP_AGE_MINUTES`; an `in_progress` check uses GitHub's
`started_at`, while a `queued` check uses `created_at` because it has not started yet. That gate
cannot be set below 180 minutes, the overall build deadline: the push and `/rerun` handlers create a
check before they start tracking its build, and a legitimately queued, deferred or running build
keeps its check in progress for as long as the coordinator's own deadlines allow, the longest of
which after admission is that 180-minute deadline. A smaller gate would let the sweep compete with
work the coordinator still considers within budget.

Each candidate receives one of these dispositions:

| What the sweep finds | What it does | Journal entry |
|---|---|---|
| The check is tracked and BuildTest reports its run in progress | Nothing | none |
| The check is tracked and BuildTest reports its run finished | Nothing; the build worker or watchdog publishes the result, and the sweep does not read that run again | `BUILD_ALIGNMENT_DEFERRED` (`ALIGNMENT_OWNED_BY_ANOTHER_PATH`), once |
| The check is tracked, BuildTest's status read finds no run, but BuildTest accepts the run's lease renewal | Nothing: the run still exists (BuildTest answers "not found" while it reloads after a restart) | `BUILD_ALIGNMENT_DEFERRED` (`ALIGNMENT_RUN_STATUS_UNAVAILABLE`) |
| The check is tracked, but a cancellation or terminal result already owns it, it is waiting in the deferred queue, or it has no remote run yet | Nothing; the owning path finishes it | `BUILD_ALIGNMENT_DEFERRED` (`ALIGNMENT_OWNED_BY_ANOTHER_PATH`) |
| BuildTest could not be reached in this pass, or its status read or lease renewal for the run failed without saying the run is gone | Nothing, and any earlier "no run found" observation stops counting | `BUILD_ALIGNMENT_DEFERRED` (`ALIGNMENT_BUILD_SERVICE_UNREACHABLE`) |
| A watched-commit or check-run listing, or the re-read of the check, was not authoritative | Nothing | `BUILD_ALIGNMENT_DEFERRED` (`ALIGNMENT_GITHUB_UNVERIFIED`) |
| Another operation holds the head's reservation | Nothing this pass; the sweep never waits | `BUILD_ALIGNMENT_DEFERRED` (`ALIGNMENT_HEAD_RESERVATION_BUSY`) |
| The re-read shows the check completed, deleted, or no longer this App's `kotlin.build (remote)` check on that commit | Nothing | `BUILD_ALIGNMENT_DEFERRED` (`ALIGNMENT_CHECK_NO_LONGER_WAITING`) |
| The check is tracked, and BuildTest answers the run's lease renewal with its explicit "no run found" response for the first time | Records the observation | `BUILD_ALIGNMENT_DEFERRED` (`ALIGNMENT_AWAITING_SECOND_OBSERVATION`) |
| The same response again on the next examination, at least one interval later, with no known or unreachable observation between, and the tracker still waiting on that same run | Concludes the check as `failure` through the same cancellation-ownership path the worker uses when lease renewal reports the run missing, and stops tracking it | `BUILD_ALIGNMENT_CONCLUDED` (`ALIGNMENT_TRACKED_RUN_MISSING`) |
| The check is untracked and a newer `kotlin.build (remote)` check exists on the same commit | Concludes it as `cancelled`, naming the newer check | `BUILD_ALIGNMENT_CONCLUDED` (`ALIGNMENT_SUPERSEDED`) |
| The check is untracked on a current open-PR head or merge-queue ref, and no other build for that head is in flight | Rebuilds it under the same check, subject to the emergency stop and repository build rate | `BUILD_ALIGNMENT_REDRIVEN` (`ALIGNMENT_UNTRACKED_PULL_REQUEST_HEAD` or `ALIGNMENT_UNTRACKED_MERGE_QUEUE_REF`) |
| The check is untracked on a default-branch commit | Concludes it as `failure` with a summary that names the lost build and says to use **Re-run** | `BUILD_ALIGNMENT_CONCLUDED` (`ALIGNMENT_UNTRACKED_DEFAULT_BRANCH`) |
| The action the evidence calls for was already attempted for this check (its allowance is spent) | Nothing, ever again | `BUILD_ALIGNMENT_DEFERRED` (`ALIGNMENT_ALLOWANCE_SPENT`), once per service start |

Every action is taken while holding the same per-head reservation that check-suite handling and
the `/rerun` comment path use, after re-reading the check from GitHub, and never while the
reservation is busy. Before rebuilding or concluding an untracked check, the sweep re-lists the
commit's checks under that reservation, so a check another path created after the pass listed the
commit supersedes the stuck one instead of being built beside it. A check-suite delivery for the
same head waits behind the sweep and then finds the build it started; a `/rerun` comment waits behind
it and then replaces that build (see [Re-triggering CI](#re-triggering-ci)). What this
guarantees is that the sweep never starts a build beside another build for the same head; it does
not change the one path that still creates checks outside the reservation (the push handler), and
the age gate keeps the sweep away from checks those paths are still
setting up. Each action first claims a durable allowance in
`build-watchman-remediations.ndjson`: one rebuild and one conclusion per check. A restart after the
claim does not repeat the action, and a BuildTest that reports a run unknown and then known again
cannot cause a second rebuild. The tracker is read again immediately before a conclusion is
claimed, so a replacement run started meanwhile keeps the allowance for a later conclusion.
Unreachable is not missing: if BuildTest cannot be reached by a call in the same pass, if a
watched-commit or check-run listing is unverified, or if the reservation is busy, the sweep records
`BUILD_ALIGNMENT_DEFERRED` with the reason, takes no action and consumes no allowance.
BuildTest has no read-only call that answers "no run found", so the missing-run probe is a lease
renewal of the run. It is made only after a status read found nothing, at most once per pass per
check, and it is harmless: a run that still exists is one its tracked worker renews anyway, and a run
BuildTest has lost has no lease left to extend. On the
KompileRemoteBuild route, whose API exposes no lease response, a tracked check whose run is not
found is always deferred rather than concluded, and run reads go through the same
memory-bounding gate the runner uses on that route.

A pass examines at most `ALIGNMENT_SWEEP_MAX_REPOSITORIES_PER_PASS` repositories in rotation and, in
each, lists at most `ALIGNMENT_SWEEP_MAX_PER_REPOSITORY` commits and takes at most that many
actions, so a repository with many stale checks is drained over successive passes.

The sweep spends the installation's shared GitHub API budget, which the webhook handlers and the
open-PR watcher also use. Per repository per pass it issues three enumeration requests (merge-queue
refs, open pull requests, default-branch commits; each read once per pass and reused for every
candidate), one check-run listing per examined commit, and for each stale candidate a re-read of
the check plus, for an untracked one, a re-listing of its commit and at most one conclusion. At the
defaults (10 repositories, 20 commits each, every 15 minutes) that is at most 23 requests per
repository plus one repository listing, about 230 per pass or roughly 920 an hour before any stale
check is found, against
GitHub's 5,000-request hourly limit for an installation. Lower `ALIGNMENT_SWEEP_MAX_REPOSITORIES_PER_PASS`
or raise `ALIGNMENT_SWEEP_MINUTES` if that share is too large. A response saying the rate limit is
exhausted ends the pass immediately and is journaled as `BUILD_ALIGNMENT_DEFERRED`
(`ALIGNMENT_GITHUB_RATE_LIMITED`); the next pass starts from where the rotation left off.

`GET /api/status` reports the sweep as `githubAlignmentSweep`: its settings, `lastSweepAt`, the last
pass's repositories, commits and candidates examined, `githubRequests` (GitHub operations issued;
a listing that spans pages issues one request per page), `stoppedEarlyReason`, and its dispositions
by kind, plus totals since the service started. The first pass runs one interval after startup. Set `ALIGNMENT_SWEEP_MINUTES=0` to
disable the sweep; a value that is not an integer within its documented range fails startup with
the variable, the value and the range in the error.

## Live Build Progress

While a build is running, the check run summary shows a live overview of all build phases:

```
✅ Provisioning server
✅ Installing dependencies
✅ Uploading source code
⏳ **Building and testing...**
⬜ Collecting results

[View build details](https://buildtest.kotlin.build/run?id=...)
```

The progress updates automatically whenever the build transitions between phases (provisioning → installing → uploading → building → collecting). Each phase shows its current status: ✅ completed, ⏳ in progress, or ⬜ pending. A link to the full build details page on buildtest.kotlin.build is always included.

If the buildtest status API is temporarily unreachable, the check remains in progress. Each polling attempt uses a bounded transient-failure window, then yields while the remote run stays durably tracked. The always-on build-watchman run scheduler continues assessing the tracked run even when the optional external watchdog is not configured; every successful status response resets the transient-failure window, and the run's actual terminal outcome is reported once observability recovers. Loss of status observability alone is never treated as evidence that the build failed. Each time a backed-off retry is scheduled because a remote run's status could not be observed, the [activity journal](#activity-journal) records one `BUILD_STATUS_DEGRADED` step naming that run, how long its status has been unobservable, that run's retry cycle number and when the retry runs; a retry that could not even start is recorded against every run of the check that is currently unobservable, and a degradation reported while a retry is already pending adds no entry. The unobservable-since time and cycle count are kept per remote run: one run degrading (for example a primary run during a whole status-API outage) never resets another run's count. The first successful status read of a run that is recorded as unobservable, on any status read path (the poll loop, a resumed build's first read, the terminal read after an exhausted execution budget, or the reconciliation of a multi-phase build's saved phases), records `BUILD_STATUS_RECOVERED` for that run only; reading a different run of the same check is not a recovery of the missing one. Once a cancellation or a terminal result has been claimed for the check, no further `BUILD_STATUS_DEGRADED` entries are recorded, and the reads that only confirm that a cancelled or superseded remote run has stopped do not record a recovery; any open degraded record is removed with the check's tracker row. These records live on the durable tracked build, so they continue across coordinator restarts and a recovery observed after a restart is still recorded. The one exception is a rolling deploy in which an older coordinator version that does not know these records rewrites the shared tracker file: its write drops them, and the next degraded cycle starts that run's count again at cycle 1. A run that stays unobservable for hours therefore shows up in `/api/activity` as one entry per retry cycle rather than as silence.

An explicit lease-renewal response saying that BuildTest has no record of the run is different from temporary status loss. The coordinator reports that condition as a build infrastructure failure, stops renewing the missing run, and removes it from durable tracking instead of scheduling it for another recovery attempt.

## Re-running from GitHub UI

You can re-run a CI check directly from the GitHub UI by clicking the "Re-run" button on an individual check run, or "Re-run all checks" on the check suite. Both actions will create a new check run and trigger a fresh build.

## PR Comment Commands

### Re-triggering CI

You can re-trigger the CI check on any pull request by posting a comment with one of the following trigger keywords:

- `/rerun` - Re-run the CI check
- `/retry` - Re-run the CI check
- `/retrigger` - Re-run the CI check

Simply add a new comment on the PR with one of these keywords (the keyword must be at the beginning of the comment). The CI will re-run and create a new check run for the PR's current head commit. A re-run always leaves exactly one live build for that commit: before creating the new check, the service cancels every build still tracked for the commit — whether it is running, retrying a degraded status read, waiting in the deferred-admission queue, or was just started by the [GitHub-alignment sweep](#github-alignment-sweep) — stops its remote run as superseded, concludes its check as `cancelled` ("replaced by a newer build requested with /rerun"), and records `BUILD_CANCELLED` (`RERUN_REPLACED`) before the new build's `BUILD_DISPATCHED`. Each tracked build is replaced on its own. A build that another path (an operator cancellation, `/bypass`, a closed pull request) is already cancelling is left to that path and recorded as `BUILD_CANCELLED` (`RERUN_LEFT_TO_CANCELLING_PATH`). A build whose replacement fails is recorded as `BUILD_CANCELLED` (`RERUN_REPLACE_FAILED`). In both cases the new build still starts and its check summary names the build that was not replaced, so a `/rerun` never ends without a new build or without saying why. This happens while the comment holds the commit's reservation, so the sweep cannot start a build in between. A re-run refused by the emergency stop or a repository build rate still replaces the old build; the refusal on the new check says to comment again once the incident is over.

You can also add a motivation or reason after the keyword, and it will be ignored — the CI still re-runs. Any separator works, not just a space: `/retry — droplet was overloaded`, `/retry: flaky infrastructure`, and `/rerun, downstream build was unrelated` all re-trigger CI. Only a keyword that runs directly into a longer word (e.g. `/retrying`) is not treated as a command.

### Bypassing CI

You can bypass the CI check and mark it as green without running the build by posting a comment with:

- `/bypass <message>` - Mark the check as green without running the build and record why

Only users whose effective repository permission is `write`, `maintain`, or `admin` can use `/bypass` or the re-trigger commands. The service queries GitHub's collaborator-permission API with its installation token instead of trusting the comment payload's visibility-sensitive `author_association` field. The PR's current comment history is the source of truth for bypass state; this lets normal PR checks and synthetic merge-queue checks make the same decision even if an `issue_comment` delivery was lost.

When GitHub's repository-permission API reports that a `/bypass` author is not authorized, the command is not applied and the app posts an actionable explanation containing both the effective permission and payload association. If a build runs, the same explanation is included in the buildtest run's Notes card and remains visible as a warning in the live and final `kotlin.build (remote)` GitHub check summaries. The app-generated PR comment contains a hidden marker tied to the rejected comment ID, so later comment-history checks do not post duplicate feedback.

Every `/bypass` must include a non-empty message on the same line, for example `/bypass the GitHub runner passes and this change must merge to restore production.` Leading spaces or separators such as `,`, `:`, `—`, and `-` are removed; later lines are not part of the message. A bare command, including one followed only by those separators, is not applied. The app posts one feedback comment with a concrete example and records `BYPASS_REFUSED` with the `MESSAGE_REQUIRED` outcome; an unauthorized command uses `NOT_AUTHORIZED`. The hidden comment-ID marker and an atomic delivery claim prevent repeated notices on live redelivery or later history scans. Old message-less `/bypass` comments are also inactive when a later check scans the PR history.

The accepted message is bounded to `1000` UTF-8 bytes. If it is longer, it is truncated at a valid UTF-8 boundary and ends with the Unicode ellipsis marker `…`; that bounded value is the value sent to BuildTest or the old-binary fallback, recorded in the `BYPASS_APPLIED` journal entry, and used in the check summaries. In Markdown-facing check summaries and feedback comments, the message is safely quoted and escaped so its content cannot add Markdown formatting.

Deploy and verify the reason-capable BuildTest service first, then deploy this consumer. Native `BYPASSED` requires a BuildTest service implementing `bypassBuildRun`; while the service rollout is between those steps, the old-binary fallback covers the window by cancelling the run with the reason `CI bypassed via /bypass: <message>`.

If the PR already has in-flight builds, `/bypass` stops them with the `BYPASSED` disposition, whose stop reason is the comment's message: that is the one way a bypass reaches the build service. It first durably claims cancellation ownership for every matching original check run, then selects and saves the remote operation mode before acting on each run: native `bypassBuildRun` by default, or `FALLBACK_CANCEL`, recorded durably before the fallback request, only when the route does not serve `bypassBuildRun` at all. Native routes mark runs `BYPASSED` with the message; an older KompileRemoteBuild route saves its fallback mode before requesting `CANCELED`. Each accepted remote run ID is saved before the local terminal result is claimed, so restart recovery does not repeat a remote operation that already returned successfully. For a response that ended after the remote side applied the request, recovery reads the run and accepts only a matching terminal result: native bypass requires `BYPASSED` with the bounded form of the saved message, while fallback and ordinary cancellation require `CANCELED`; every other error remains a failure for the next recovery pass. Only after every remote phase is accepted does the service persist and publish bypassed-success for each original check with its build details link, before completing the new bypass check. While cancellation intent is pending, a polling worker that sees a terminal remote result defers to the cancellation owner instead of saving a competing result. Once the owner saves its terminal tuple, an active completion gateway can publish that exact winner if the cancellation publisher defers; a worker's candidate cannot replace it. A failed remote request leaves the original check durably tracked with its pending cancellation details for retry; it does not publish a green result while a remote run may still be active. A real result that was already durably claimed before `/bypass` began still wins, and a failed GitHub completion remains durably tracked for watchdog recovery instead of being silently orphaned. Closing a PR follows the same ownership path, but cancels the remote run with its own reason and claims GitHub's `cancelled` conclusion. Reopening the PR creates a fresh required check on its current head commit.

### A run bypassed on the build service passes its check

A remote run can also be bypassed from the build service's side — the **Bypass Build** action on
its run page at [buildtest.kotlin.build](https://buildtest.kotlin.build/runs), which records the
run as `BYPASSED` with the operator's reason. When this app sees that any run belonging to a check
— primary, downstream or self-bootstrap — has been bypassed, it completes the whole
`kotlin.build (remote)` check as **passing**, with the summary
`Build bypassed on buildtest: <reason>` and a link to the run, and starts no further phase for it.
It is the build-service counterpart of `/bypass`, for the person who is looking at the run rather
than at the pull request. A run stopped any other way (`CANCELED`, `OUTPACED`, `SUPERSEDED`) still
fails a check it had not already decided, because it produced no evidence that the change works;
all four stopped states end polling immediately rather than waiting out the execution budget.

### How a stopped remote run is reported

When this app stops a remote build run it also says **why**, and the build service keeps a
separate terminal state for each reason. A cancellation or a bypass means somebody intervened;
the others are this app tidying up work that stopped being useful, which happens constantly and
without anyone's involvement.

| Reported as | When |
| --- | --- |
| `CANCELED` | An operator cancelled the check, a pull request was closed, or the build-watchman stopped a stalled run — whether or not it went on to start a replacement. |
| `BYPASSED` | `/bypass <message>` waved the check through, so the run's result is no longer needed; the run records the comment's message. |
| `OUTPACED` | The check run already reached its verdict, so a run still executing for it has been overtaken — its result cannot change the outcome and would be discarded even if it finished. |
| `SUPERSEDED` | A newer run for the same work replaced this one: a new commit pushed to the pull request, a rerequested check starting a replacement attempt, or a worker's attempt being replaced before its run could be recorded. |

Reporting all of these as cancellations is what made
[the build service's run list](https://buildtest.kotlin.build/runs) read as though people were
calling builds off by hand all day. In a 758-run sample of stopped runs taken from the live
service, not one was a human cancellation.

**A push to an open pull request now retires the builds it replaced.** GitHub sends
`pull_request.synchronize` on every push and separately starts a check for the new head, so this
app stops the runs still executing for the commit the push replaced and reports them as
superseded. Those runs are testing a commit nobody will merge — their verdict cannot gate
anything, and until they finish they occupy build machines the new run needs. The build for the
new head is deliberately excluded: it carries the same pull-request number as the outdated ones,
so without that exclusion a push would stop the very run it just started.

#### Bypass persistence across merge queue builds

When `/bypass` is posted on a PR, the bypass status persists through subsequent builds triggered by the merge queue or other check suite events. The CI server checks the PR's comment history and branch activity to determine if the bypass is still active.

A bypass is automatically deactivated if any of the following occurs:

- The PR is merged or closed (bypass does not carry over to main branch builds or reactivate if the PR is reopened; a new `/bypass` is required)
- A `/rerun`, `/retry`, or `/retrigger` comment is posted on the PR
- New commits are pushed to the PR branch
- The PR branch is force-pushed

This means you can `/bypass` a PR, and it will remain bypassed through the merge queue without needing to comment `/bypass` again. If you want to re-enable CI after a bypass, post a `/rerun` comment.

## Downstream Dependency Builds

When a change is made to any of the 44 repositories in the kompile-cli transitive dependency tree, the CI automatically builds all downstream repos that depend on it (transitively, up to and including kompile-cli). This acts as a smoke test for the entire dependency chain, catching breakage early before it propagates.

For example, if `string-utilities` is changed, the CI will also build `jar-utilities`, `file-utils`, `build-kotlin-jvm`, `kompile-core`, `kompile-cli`, and all other repos that transitively depend on `string-utilities`.

### How it works

1. After the primary build succeeds, the CI computes all downstream repos using a built-in dependency graph
2. All downstream repo tarballs are downloaded and combined into a single workspace tarball (each repo becomes a subdirectory)
3. The combined tarball is submitted as a single build to the remote build service, which builds all projects together
4. Per-project test results are extracted from `TestResult.projectName` and reported individually in the summary
5. The overall check run conclusion is `failure` if any project (primary or downstream) fails

### Check run summary format

The final check run summary includes a table showing the result of each downstream build:

```
## Primary build: string-utilities
All tests passing (3/3)

## Downstream dependency builds (14 of 15 passed, 1 FAILED)

| Repo | Result | Details |
|------|--------|---------|
| jar-utilities | pass: 2/2 passing | [details](...) |
| file-utils | FAIL: Build failed: Compilation error | [details](...) |
| build-kotlin-jvm | pass: 12/12 passing | [details](...) |
| ... | ... | ... |
```

### Configuration

- `DOWNSTREAM_BUILDS_ENABLED` - (Optional) Set to `false` to disable downstream builds (default: enabled). This env var can be used to temporarily disable the feature without a code change.

Primary, downstream, and bootstrap phases share one 180-minute overall deadline anchored at the first buildtest admission in the lifecycle. Time spent PENDING before that first admission does not count. A remote run that is never admitted intentionally has no coordinator deadline: restoring the old request-time 180-minute limit would incorrectly fail builds waiting in the buildtest queue. The coordinator continues renewing its lease and records PENDING observations with elapsed queue wait in `/api/activity`, at the lease-renewal cadence. The existing local deferred-submission deadline still bounds work before its first remote submission. The 180-minute execution and lifecycle limits are unchanged.

Each durable lifecycle resolves `downstreamRequired` once at its first read or rewrite: an explicit saved true/false marker wins, otherwise any saved downstream or bootstrap run ID requires the multi-phase lifecycle, otherwise current repository configuration decides. The resolved marker is persisted before replacement or re-tracking can discard saved IDs, and survives load, migration, resume and redrive. Within a required lifecycle, saved runs are reconciled and their results applied even when today's phase setting is disabled; a new phase is submitted only when its current setting enables it and no decisive earlier failure exists. Across fresh runs, resume, redrive, replacement and degraded recovery, saved later-phase evidence is read and applied before creating a missing predecessor or deferring because an earlier service record is absent. A decisive saved failure completes the lifecycle without submitting predecessors or inventing primary success; explicit single-phase intent and decisive primary or downstream failures retain their existing early completion behavior. Queue-wait diagnostics are measured from the run's submission timestamp, not from a polling or recovery restart. Same-attempt re-tracking, including a saturated worker queue, preserves every saved phase identity and its generation until replacement resolves the old attempt. Check-run ID zero is a valid tracked identity and follows the same admission, status and generation rules as every other ID.

Every decisive completion determines cleanup from the durable phase set when it claims terminal ownership. It requests cancellation of the other nonterminal remote phases before publishing the result and removing tracking, including unused saved work beside an explicit single-phase success. Cleanup ownership survives a restart; a failed result publication replays the saved verdict without repeating completed cleanup. Once a terminal verdict owns the lifecycle, a late submission cannot add a new phase identity; its submitting worker cancels that run. Re-tracking also preserves the frozen identities, owning attempt generation and legacy phase metadata. A callback that resumes the same terminal check on any thread returns to the current completion owner without waiting for admission or completion locks. Status reads and lease renewals reconcile saved evidence for both ordinary dependency exceptions and the generated proxy's documented `AssertionError` transport failures, retaining the original observation error when no saved verdict decides the result. An ordinary resume error still follows its existing recovery path when the saved-phase scan has no further error.

Downstream source downloads establish one cleanup owner before the first download. Normal completion, each download-loop deadline gate, the post-download deadline gate and exceptions all release every acquired source-file lock and remove its downloaded file exactly once. Bootstrap borrows those source files and releases only the additional files it downloads itself. Cancellation during source acquisition stops further acquisition and submission, then releases the files and locks already owned by that phase.

The admission lifecycle follows these rules:

- The first durable admission write wins. It uses buildtest's recorded admission when available, otherwise the first observed non-PENDING state or decisive terminal result. Later observations cannot move it.
- Retry, replacement (including a saturated worker queue), reconciliation, and restart preserve the anchor, derived deadline, per-run admission history, and last recorded remote phases. A replacement gets new active remote IDs without discarding that history.
- Older records without an anchor migrate on load from their earliest recorded admission. A record with no admission stays unanchored until admission is observed; an already-terminal primary retains its verdict and admission when downstream or bootstrap work continues.
- Restarted downstream and bootstrap runs retain their recorded admission even when it predates the restart. Recovery polls existing run IDs before downloading or recombining source artifacts.
- Every overall-deadline message identifies the repository, last-known run, and last durable phase, including worker polling errors, unobservable runs, watchdog checks, multi-phase boundaries, and locally deferred recovery. A durable admission anchor selects the 180-minute admission summary; a replacement without an active run ID uses its most recent recorded admission and phase. Only unadmitted records use the local deferred-submission wording, and saturation text distinguishes delayed replacement from a build that never started.
- Concurrent trackers sharing the durable file adopt the same first-written anchor and deadline.

Primary enrichment, progress publication, and default-branch lookup converge at one deadline gate before any fresh downstream source is downloaded. Later gates run before a fresh downstream submission and immediately before terminal publication, so crossing the deadline during any intervening callback keeps the original deadline-exceeded result instead of allowing a successful completion.

### Covered repos

The dependency graph covers the 44 repositories that are transitive dependencies of kompile-cli. Repos outside this tree are built normally without downstream builds. When repos add or remove dependencies, the graph in `DependencyGraph.kt` should be updated to match.

## Self-Bootstrap Build

After the downstream dependency builds succeed, the CI can optionally perform a **self-bootstrap check** that proves the code can build itself. This is a two-phase process:

1. **Phase 1** (existing downstream build): Builds all downstream repos in a combined workspace, which produces a newly-built kompile-cli fat JAR as a build artifact.
2. **Phase 2** (bootstrap rebuild): Retrieves the newly-built kompile-cli via `getArtifact()`, builds a full 44-repo workspace tarball with the JAR injected at `.kompile-cli-override.jar`, and submits a second build. The remote build service uses the override JAR instead of the published kompile-cli.

If the bootstrap build succeeds, the newly-built kompile-cli is verified to be capable of building the entire workspace from scratch. If it fails, the check run is marked as failed.

### Configuration

- `BOOTSTRAP_BUILD_ENABLED` - (Optional) Set to `true` to enable the self-bootstrap check (default: disabled). The bootstrap check only runs when downstream builds are also enabled and have passed.

### Prerequisites

The self-bootstrap check requires the remote build service (`url://buildtest/`) to support:
1. `getArtifact(runId)` — returning the kompile-cli fat JAR from a completed build
2. Detecting `.kompile-cli-override.jar` in the workspace tarball and using it instead of the published kompile-cli

### Check run summary format

When enabled, the check run summary includes an additional section:

```
## Self-bootstrap build (PASS)
All tests passing (142/142)

Build details: https://buildtest.kotlin.build/run?id=...
```

If the artifact is unavailable (e.g., `getArtifact` is not implemented on the remote service), the bootstrap is skipped non-fatally:

```
## Self-bootstrap build (SKIPPED)
getArtifact returned null for downstream runId=... The remote build service may not have produced a kompile-cli artifact.
```

## Build Watchdog

The CI server includes an optional watchdog mechanism that uses [WebCronApiServer](https://github.com/CodexCoder21Organization/WebCronApiServer) to provide an additional audit of in-progress builds and recover from failures. Degraded buildtest status polling self-heals through the internal backed-off reconciliation described above; the watchdog additionally covers server restarts, thread crashes, and timeout enforcement.

When a build starts, the watchdog creates a recurring cron job that calls back every minute to the `/check-builds` endpoint. The endpoint handles timeout, restart, and worker-thread recovery. A separate always-on scheduler audits all tracked remote runs with the shared build-watchman progress assessment. Only a `NO forward progress` finding — a `TESTING` or `COLLECTING` run with more than 20 minutes of unchanged test counts, complete log content, and `Last-Modified` metadata — can trigger one redrive, with the decision recorded in the durable remediation ledger. Phase-budget advisories remain diagnostic. A redrive cancels the old remote attempt and starts its replacement under the same GitHub check; if no replacement is admitted, the check is concluded only afterward. Missing log metadata is treated as degraded observation and cannot trigger a terminal result. It:

- **Resumes** any build that was interrupted by a server restart. The remote build's `runId`, original start time, and queue-admission time are persisted, so recovery resumes the existing run without resetting its execution budget or its overall deadline. `COMPLETED`, `FAILED`, and `CANCELED` snapshots are terminal; a stale non-terminal status may also conclude when a positive completion timestamp proves the run finished. The same completion-evidence rule applies on the initial resume probe and on every subsequent follow read. Test counts, including a full passing tally, do not prove the build itself is finished. Counts reported by an active snapshot (`PENDING`, `UPLOADING`, `BUILDING`, or `TESTING`) renew the lease and continue through the normal polling and reconciliation path. No local source checkout is retained — the source is uploaded to the remote build service at the start and is not needed afterwards.
- Fails any build whose thread terminated without reporting a result
- Fails any build that has exhausted its execution budget — the configured timeout measured from the moment
  the remote run left the buildtest queue, including execution time elapsed before a restart. Time a run
  spends queued waiting for build capacity is never charged against that budget, because a queued run has
  not been given the chance to build or test. Every admitted build additionally carries a 3-hour overall
  deadline anchored at its first buildtest admission. The persisted admission anchor survives retries and
  service restarts; PENDING time before that admission is not charged to it.

Builds are also eagerly resumed at startup without waiting for the first watchdog tick. When the last in-progress build completes, the cron job is automatically deleted.

To enable the external watchdog, set both `WEBCRON_URL` and `CI_CALLBACK_URL`. If either is missing, startup recovery and internal degraded-status reconciliation still operate, but periodic `/check-builds` auditing is disabled.

## Startup Cleanup

On startup, the CI server cleans up stale temporary files left behind by previous crashes (OOM kills, SIGKILL, container restarts). Three categories of files are cleaned:

1. **`combined-downstream-*.tar.gz`** in `DATA_DIR` — Combined workspace tarballs from downstream builds. Files not associated with an active tracked build are deleted.
2. **`bootstrap-workspace-*.tar.gz`** in `DATA_DIR` — Bootstrap workspace tarballs. Files not associated with an active tracked build are deleted.
3. **`tarball-*.tar.gz`** in the system temp directory — Individual repo tarballs downloaded by `downloadGitHubTarball()`. Each tarball is protected by a companion `.lock` file held via [ProcessLock](https://github.com/CodexCoder21Organization/ProcessLock). The cleanup function attempts to acquire each lock: if the lock is available (owner process is dead), both the tarball and lock file are deleted; if the lock is held (another instance is still using the file), the tarball is skipped. This makes the cleanup safe for multi-instance deployments.

Without this cleanup, each crash can leave 40+ tarball temp files (several MB each) that accumulate and eventually fill the disk.

## Health Endpoint

`GET /health` returns `200 OK` with a plain-text body that identifies the running build:

```
kotlin.build CI is running
build: kotlinbuild.ci:kotlin-build-ci:<version>
```

The version line makes deployed-vs-repository divergence directly observable: compare the version reported by the live service against the artifact version in `build.kts`. The `SERVICE_VERSION` constant in `HealthServlet.kt` must be bumped together with the `build.kts` artifact version on every release. The current release is `0.0.111`; runner-only repositories do not receive kotlin.build checks from webhook, control, or reconciliation paths. `/bypass` requires and records a same-line message, including on an in-flight remote build.

Startup validates and completes durable post-completion queue recovery before the HTTP server binds.
Malformed or unrecoverable queue state therefore makes the process exit before `/health` is served.

## Operations Control API

Everything an operator needs to see and steer this service is served as JSON under `/api`, and
rendered by [KotlinBuildCiWui](https://github.com/CodexCoder21Organization/KotlinBuildCiWui) at
[ci.kotlin.build](https://ci.kotlin.build). The interface holds no state of its own; this service
owns all of it.

Reads are open — what they return (which repositories are building, what was triggered, what was
bypassed) is already visible to anyone who can see the organization's pull requests. **Writes carry a
shared token**: `Authorization: Bearer $CI_CONTROL_TOKEN`. A service started without
`CI_CONTROL_TOKEN` refuses every write with `503` rather than accepting them unauthenticated.

| Method | Path | What it does |
|--------|------|--------------|
| GET | `/api/status` | Overview figures: builds in flight, deliveries and dispatches in the last hour, bypasses in the last day, the emergency stop, and the GitHub-alignment sweep's last pass (`githubAlignmentSweep`, null when disabled) |
| GET | `/api/builds` | Every build in flight, with its repository, commit, pull requests, remote run id and whether it is waiting for a worker |
| GET | `/api/activity` | The recorded steps, newest first. `?limit=`, `?repository=owner/repo`, `?types=A,B` |
| GET | `/api/repositories` | Every repository this service has touched, with its build rate, builds in flight and builds started in the last hour |
| GET | `/api/emergency` | The emergency stop and its recorded steps |
| POST | `/api/emergency` | `{"active": true, "blockNewBuilds": true, "statusMessage": "…", "actor": "…"}` declares or updates it; `{"active": false}` clears it |
| POST | `/api/emergency/dry-run` | Reports what cancelling everything in flight would cancel, without cancelling anything |
| POST | `/api/emergency/cancel-all` | Cancels every build in flight. Requires `{"confirmation": "CANCEL ALL BUILDS"}` |
| POST | `/api/repositories/rate` | `{"owner": "…", "repo": "…", "maxBuildsPerHour": 5, "actor": "…", "note": "…"}`. `0` pauses the repository; omitting `maxBuildsPerHour` removes the limit |
| POST | `/api/builds/cancel` | `{"checkRunId": 123, "actor": "…", "reason": "…"}` cancels one build and completes its check as cancelled |
| POST | `/api/builds/retrigger` | `{"owner": "…", "repo": "…", "prNumber": 7 \| "headSha": "…", "actor": "…"}` runs a check again |

Running a check again asks GitHub to **re-request the existing check run** — exactly what its Re-run
button does — so the replacement follows the same path as any other rerequest, including cancelling
whatever was still running for that commit. Only a commit with no check run at all (the state a lost
delivery leaves behind) gets a fresh build dispatched directly.

## Activity Journal

`DATA_DIR/ci-activity.ndjson` is the operator-facing record of what this service did:

- **`WEBHOOK_RECEIVED`** — every authenticated delivery, with GitHub's `X-GitHub-Delivery` id, the
  event and action, and whatever repository, commit and pull requests the payload named. This is what
  answers "did GitHub actually tell us?" after a suspected lost trigger.
- **`BUILD_DISPATCHED`**, **`BUILD_STARTED`**, **`BUILD_COMPLETED`** — a build's submission, the
  remote service accepting it (with the run id), and its verdict.
- **`BUILD_PENDING`** — a submitted remote run still waiting for buildtest admission, recorded at most
  once per lease-renewal interval while it waits.
- **`BUILD_STATUS_DEGRADED`** / **`BUILD_STATUS_RECOVERED`** — a remote run's status could not be
  observed, so the check was left in progress and a backed-off status retry was scheduled (one entry
  per retry cycle and run, naming the run, how long it has been unobservable, that run's cycle number
  and the retry delay); and the first status read of that same run that observed it again, on any
  status read path (reads that only confirm a cancelled run has stopped are not status reads), with
  the status it saw, how long it had been unobservable and across how many cycles. Both counts are kept per run and survive coordinator restarts. A cycle caused by a cancelled status
  read, which has no measured window, starts at 0ms.
- **`BUILD_SKIPPED`** — a trigger that produced no build, and why (a duplicate delivery, a commit
  already being built, a repository with no `build.kts`, or a non-PR `wip/*` checkpoint branch).
- **`BUILD_REFUSED`** — a build refused before it started, by the emergency stop, a repository's build
  rate, an unavailable build service, or the GitHub-runner-only repository policy.
- **`BUILD_CANCELLED`** — by an operator, by `/bypass`, or by a closed pull request.
- **`BYPASS_APPLIED`** — a check marked green without a build by `/bypass`.
- **`BYPASS_REFUSED`** — a `/bypass` refused because its author lacked the required repository
  permission or omitted the required same-line message.
- **`COMMAND_RECEIVED`**, **`RETRIGGERED`** — `/rerun`, `/retry` and `/bypass` comments, and operator
  re-runs.
- **`EMERGENCY_*`**, **`RATE_POLICY_UPDATED`** — every emergency and rate change, with its operator.
- **`BUILD_ALIGNMENT_CONCLUDED`**, **`BUILD_ALIGNMENT_REDRIVEN`**, **`BUILD_ALIGNMENT_DEFERRED`** — what
  the [GitHub-alignment sweep](#github-alignment-sweep) did with a stuck check, or why it waited,
  with the check run, commit and remote run it concerned.

This is deliberately separate from `build-events.ndjson`, which exists only to find builds that were
in flight when the process stopped and compacts matched started/completed pairs away as soon as they
are no longer needed for that.

The journal is **bounded**: it keeps the most recent `20 000` steps and rewrites the file when it
grows past that. A failed append preserves the previously acknowledged file contents and in-memory
window, including across restart, when its partial line can be truncated away; when that truncation
also fails, the journal re-reads its window from the file, adopting the line if it landed, and still
reports the failure, so a caller that retries can record the step twice. A failed rewrite or
replacement discards its temporary file.
The distant past is lost on purpose — an unbounded operator log on a small container
volume is a disk-full incident waiting to happen — and the recent past is what an operator reads.

Its heap use is bounded separately, in bytes: the service keeps only about 4 MiB of parsed steps
(the newest end of the window) in memory, whatever the steps' count or size. Beside them it keeps a
16-byte index record per retained step (time, kind and repository), whose arrays reach about 512 KB
at the default 22,000-step ceiling and about 1 MB briefly while a trim builds the replacement.
Re-reading the window from the file (at terminal-result reconciliation, or after an append that
could not be undone) briefly holds about 8 MiB of parsed steps: the old and the new resident sets.
Counts and the repository list are answered from that index and never read the file, even when
the file cannot be read. A step is kept as it reads back from its line: an empty optional field
such as a blank delivery id is stored as absent. A query for
older steps (`/api/activity`) reads the file after releasing the journal's lock, so recording never
waits for it, and holds at most the steps it returns (up to 2,000 per request) outside the 4 MiB
budget; it returns exactly what it would if every retained step were in memory. Reading after the
lock relies on POSIX rename semantics: a file already opened keeps its content when a trim renames
a replacement over it.
Programmatic callers can set the budget with `CiActivityJournal(fileSystem, path, clock, maxEntries,
maxResidentBytes)`.

## Emergency Stop and Repository Build Rates

This service's half of the ecosystem's
[Emergency Mode](https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/architecture/EMERGENCY_MODE.md)
pattern: the declared, temporary, recorded brake an operator reaches for while the cause of a CI
incident is still being found. Settings persist in `DATA_DIR/ci-admission.json`, so neither survives
only in memory — an emergency that ended because a container recycled would be no emergency stop at
all.

Two rules are evaluated before any build starts, in this order:

1. **The emergency stop.** While declared with `blockNewBuilds`, no repository starts a build. Declaring
   it *without* `blockNewBuilds` keeps the incident visible while builds run — the state to use while
   winding an incident down. The operator's status message is quoted in every refusal.
2. **The repository's build rate.** `maxBuildsPerHour` of `0` pauses a repository outright; a positive
   limit admits that many builds per rolling hour, counted from the journal. A repository with no
   policy is unlimited.

Both **refuse loudly rather than queueing**. A parked build would sit invisibly while GitHub showed
its check as running, so a refused build fails its check immediately, names the rule that refused it,
quotes the operator's message, and tells the engineer to comment `/rerun` once the incident is over.
All three ways a build starts — a check suite, a `/rerun` comment, and a push to a default branch —
enforce the same rules.

`/bypass` is deliberately **not** blocked by an emergency stop: a documentation-only change should
still be mergeable while builds are stopped, and closing that escape hatch during an incident is
exactly the wrong time to close it.

Clearing everything already running is a separate, two-step action: `/api/emergency/dry-run` reports
what it would cancel, and `/api/emergency/cancel-all` cancels it only when the confirmation phrase is
typed. Declaring the stop first and cancelling second is the order the interface recommends —
cancelling alone stops nothing from starting again a second later.

## Environment Variables

The following environment variables must be set:

- `PORT` - The port to listen on (default: 8080)
- `GITHUB_APP_ID` - The GitHub App ID
- `GITHUB_PRIVATE_KEY_PEM` - The private key PEM content for the GitHub App
- `GITHUB_WEBHOOK_SECRET` - (Optional) The webhook secret for signature verification
- `BUILD_TIMEOUT_MINUTES` - (Optional) Maximum time in minutes a build may spend executing, measured from the moment the remote run leaves the buildtest queue rather than from submission (default: 30)
- `CI_BUILD_ROUTE` - (Optional) `direct-buildtest` (default; current `url://buildtest/` behavior) or `kompile-remote-build` (opt-in `url://kompile-remote-build/` CI orchestration route). Invalid values stop startup with a descriptive configuration error.
- `CI_SHADOW_ROUTE` - (Optional) The other route, `direct-buildtest` or `kompile-remote-build`, to submit in parallel as the informational `kotlin.build (kompile-remote-build)` check. It must differ from the effective `CI_BUILD_ROUTE`; leaving it unset preserves the primary-only behavior.
- `WEBCRON_URL` - (Optional) Base URL of the [WebCronApiServer](https://github.com/CodexCoder21Organization/WebCronApiServer) (e.g., `http://webcron-server:8080`). Required together with `CI_CALLBACK_URL` to enable the build watchdog.
- `CI_CALLBACK_URL` - (Optional) The externally-reachable URL for the `/check-builds` endpoint (in production, `https://githubci.kotlin.build/check-builds`). Required together with `WEBCRON_URL` to enable the build watchdog.
- `CI_CONTROL_TOKEN` - (Optional, but required to change anything) The shared token every write to the [operations control API](#operations-control-api) must present as `Authorization: Bearer <token>`. Without it the reads still work and every write is refused with `503`. Set the same value on [KotlinBuildCiWui](https://github.com/CodexCoder21Organization/KotlinBuildCiWui).
- `DATA_DIR` - (Optional) Directory for persistent state files (default: `/data`). The service reads `in-progress-builds.json` before it binds its port and refuses to start if that file exists but is empty or unparsable, so when upgrading from a release before this one, check it is not zero bytes first and delete it if it is — see [how the tracker is written and read](#how-the-in-progress-build-tracker-is-written-and-read). The file `in-progress-builds.json` is stored here to track primary builds across server restarts; the operator-facing [activity journal](#activity-journal) is `ci-activity.ndjson` and the [emergency stop and repository build rates](#emergency-stop-and-repository-build-rates) are `ci-admission.json`; watcher remediation decisions are stored in `build-watchman-remediations.ndjson`; when `CI_SHADOW_ROUTE` is set, shadow builds use `in-progress-builds-shadow.json` and `build-events-shadow.ndjson` in the same directory.
- `RECONCILE_POLL_MINUTES` - (Optional) Interval in minutes for the [Dropped-Webhook Self-Healing](#dropped-webhook-self-healing) build-watchman open-PR and merge-queue poll (default: `1`). The first pass runs immediately. Set to `0` to disable open-PR and merge-queue watching.
- `WATCHMAN_RUN_POLL_MINUTES` - (Optional) Interval in minutes for build-watchman's always-on tracked-run stall watcher (default: `1`). This scheduler is independent of WebCron and force-fails/redrives a stalled orchestrated run at most once per durable run key. Set to `0` to disable it.
- `RECONCILE_MAX_PER_PASS` - (Optional) Maximum number of candidates the legacy shadow-route reconciliation pass may attempt when `CI_SHADOW_ROUTE` is enabled (default: `10`). The primary route uses durable allowances for PR-head direct dispatch, queue-suite rerequest, stalled-run redrive, and queue-ref re-enqueue in the watcher ledger.
- `REDELIVERY_SWEEP_MINUTES` - (Optional) Interval in minutes for the [failed-delivery redelivery sweep](#failed-delivery-redelivery-sweep) (default: `10`). Set to `0` to disable the sweep.
- `REDELIVERY_LOOKBACK_MINUTES` - (Optional) How far back the redelivery sweep examines GitHub App webhook deliveries (default: `120`).
- `REDELIVERY_MAX_PER_SWEEP` - (Optional) Maximum redelivery attempts requested during one sweep (default: `50`).
- `ALIGNMENT_SWEEP_MINUTES` - (Optional) Interval in minutes for the [GitHub-alignment sweep](#github-alignment-sweep), 0–10080 (default: `15`). Set to `0` to disable the sweep.
- `ALIGNMENT_SWEEP_AGE_MINUTES` - (Optional) Minutes a `kotlin.build (remote)` check must have been waiting on GitHub before the sweep may act on it, 180–43200 (default and minimum: `180`, the overall build deadline; see [GitHub-alignment sweep](#github-alignment-sweep) for why it cannot be lower).
- `ALIGNMENT_SWEEP_DEFAULT_BRANCH_COMMITS` - (Optional) Newest default-branch commits watched per repository, 1–100 (default: `10`).
- `ALIGNMENT_SWEEP_MAX_REPOSITORIES_PER_PASS` - (Optional) Repositories examined per sweep pass, in rotation, 1–10000 (default: `10`).
- `ALIGNMENT_SWEEP_MAX_PER_REPOSITORY` - (Optional) Check-run listings, and separately actions, per repository per pass, 1–1000 (default: `20`).
- `DOWNSTREAM_BUILDS_ENABLED` - (Optional) Set to `false` to disable downstream dependency builds (default: enabled). See [Downstream Dependency Builds](#downstream-dependency-builds).
- `BOOTSTRAP_BUILD_ENABLED` - (Optional) Set to `true` to enable the self-bootstrap build check (default: disabled). See [Self-Bootstrap Build](#self-bootstrap-build).

## Building

### Option 1: Using Coursier (Recommended)

The easiest way to run kotlin-build-ci is via [Coursier](https://get-coursier.io/) using the artifact published to kotlin.directory:

```bash
coursier launch kotlinbuild.ci:kotlin-build-ci:0.0.101 \
  -r https://kotlin.directory \
  -M org.example.ci.MainKt
```

### Option 2: Building from Source

Build the fat JAR using the kompile build system. The source uses Okio 3.10.0; the launcher
scripts and application build select Kotlin 2.0.21 so they can read the dependency metadata:

```bash
./scripts/build.bash kotlinbuild.ci.buildFatJar output/kotlin-build-ci.jar
```

Then run the JAR:

```bash
java -jar output/kotlin-build-ci.jar
```

### Tests

```bash
./scripts/test.bash --local --test <testFunctionName>   # one test
./scripts/test.bash --local --test .                     # everything
```

Five completion diagnostics — `testBuildRunner_admissionAnchoredDeadlineCancelsInstallingRemoteRun`,
`testBuildRunner_deadlineCancellationDoesNotReportSuccessAfterConcurrentFailure`,
`testBuildRunner_deadlineCancellationFailurePreservesOriginalError`,
`testBuildRunner_resumeDeadlineCancelsAllPersistedRemoteRuns` and
`testBuildRunner_shutdownReleasesQueuedResumeOwnership` — assert a completion summary
containing a stack trace with exact `BuildRunner.kt` line numbers, so **any** edit to
`BuildRunner.kt` shifts them and requires re-running all five, whether or not the change went
anywhere near what they test.

## Running Locally

```bash
export GITHUB_APP_ID=your-app-id
export GITHUB_PRIVATE_KEY_PEM="$(cat your-private-key.pem)"
export GITHUB_WEBHOOK_SECRET=your-secret
./scripts/build.bash kotlinbuild.ci.buildFatJar output/kotlin-build-ci.jar
java -jar output/kotlin-build-ci.jar
```

## Running in ContainerNursery

Add a route to your ContainerNursery config:

```json
{
  "domain": "kotlin.build",
  "image": "danger_jarfile:///path/to/kotlin-build-ci.jar",
  "keepWarmSeconds": 3600,
  "port": 2222,
  "type": "http"
}
```

## Setup

The service authenticates as a GitHub App, not as a personal access token. Setup therefore has two parts: register and configure the App under the organization that owns it, then install that App on the repositories it should build. GitHub's [registration guide](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/registering-a-github-app) describes the surrounding GitHub UI.

### 1. Make the webhook endpoint publicly reachable

Deploy or start the service before enabling its webhook. GitHub must be able to make an HTTPS request to:

```text
https://githubci.kotlin.build/webhook
```

`PORT` is the service's internal listening port (`8080` by default, or the port assigned by ContainerNursery); it does not belong in the public webhook URL. For a non-production installation, expose the local server through an HTTPS endpoint and use that endpoint followed by `/webhook`.

Generate a high-entropy webhook secret and retain it for the service configuration:

```bash
openssl rand -hex 32
```

### 2. Register the GitHub App

1. As an organization owner or GitHub App manager, open the organization's **Settings**.
2. Select **Developer settings → GitHub Apps → New GitHub App**.
3. Choose a unique App name and set **Homepage URL** to the service's public URL, such as `https://githubci.kotlin.build`.
4. Under **Webhook**, select **Active**, set **Webhook URL** to `https://githubci.kotlin.build/webhook`, enter the secret generated above, and keep SSL verification enabled.
5. User authorization and OAuth callback URLs are not required; this service operates only as an installed App.
6. For a private organization service, select **Only on this account** under **Where can this GitHub App be installed?**

### 3. Grant permissions

Under **Permissions & events**, grant only the following permissions and leave unrelated permissions at **No access**:

| Permission category | Permission | Access | Why it is required |
|---|---|---|---|
| Repository | Checks | Read & write | Create the `kotlin.build (remote)` check and update its progress and conclusion. |
| Repository | Contents | Read-only | Read repository refs and download source tarballs for builds. |
| Repository | Issues | Read & write | Read PR conversation history and post rejected-`/bypass` feedback. GitHub exposes PR conversation comments through the [issue comments API](https://docs.github.com/en/rest/issues/comments#create-an-issue-comment). |
| Repository | Metadata | Read-only | Read repository metadata and rulesets, and query each comment author's effective repository permission before accepting `/bypass` or re-trigger commands. GitHub normally selects this permission automatically. |
| Repository | Pull requests | Read-only | Read PR state, commits, merge bases, and head commits for normal and merge-queue builds. |

The App does not require the organization-level `Members` permission. Command authorization uses GitHub's repository-permission API, which calculates the comment author's effective repository access across direct, team, organization, and enterprise grants and requires only repository `Metadata: Read-only`. This avoids relying on the visibility-sensitive `author_association` field. If `Issues` lacks write access, the build can still run but the App cannot post the explanatory PR comment.

When permissions are added to an App that is already installed, saving the App registration is not sufficient: an organization owner must also approve the requested permission update for each installation. GitHub documents this approval requirement in [Modifying a GitHub App registration](https://docs.github.com/en/apps/maintaining-github-apps/modifying-a-github-app-registration#changing-the-permissions-of-a-github-app).

### 4. Subscribe to webhook events

Under **Subscribe to events**, select:

- **Check suite** — starts normal and merge-queue checks.
- **Check run** — handles re-runs requested from the GitHub checks UI.
- **Push** — starts checks for pushed commits.
- **Issue comment** — handles `/rerun`, `/retry`, `/retrigger`, and `/bypass` commands.
- **Pull request** — cancels builds when PRs close and creates fresh checks when PRs reopen.

The events GitHub offers depend on the permissions selected above, so configure permissions before event subscriptions. See GitHub's [webhook configuration guide](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/using-webhooks-with-github-apps).

### 5. Create credentials and configure the service

After creating the App:

1. Copy the **App ID** from the App's settings page.
2. Under **Private keys**, select **Generate a private key** and store the downloaded PEM file securely.
3. Configure the service with the App ID, the complete PEM contents (including the `BEGIN`/`END` lines), and the same webhook secret entered in GitHub:

```bash
export GITHUB_APP_ID=your-app-id
export GITHUB_PRIVATE_KEY_PEM="$(< /secure/path/kotlin-build-ci.private-key.pem)"
export GITHUB_WEBHOOK_SECRET=the-generated-webhook-secret
```

Do not commit the private key or webhook secret. Configure the remaining optional service settings under [Environment Variables](#environment-variables), then start the service as described under [Running Locally](#running-locally) or [Running in ContainerNursery](#running-in-containernursery).

### 6. Install the App

From the App's settings page, select **Install App**, choose the organization, and grant access to either all repositories or the selected repositories that should receive CI. Repository selection is installation state and is separate from the permission settings above. GitHub's [installation guide](https://docs.github.com/en/apps/using-github-apps/installing-your-own-github-app) shows the current installation flow.

Whenever repository access or App permissions change, revisit the installation and confirm that no approval is pending.

### 7. Verify the integration

1. Request `https://githubci.kotlin.build/health` and confirm it returns `200 OK` with the expected artifact version.
2. Ask the service to inspect the effective GitHub installation for a repository:

   ```bash
   curl --fail-with-body \
     'https://githubci.kotlin.build/health/configuration?repository=CodexCoder21Organization/kotlin-build-ci&format=json'
   ```

   A correctly approved installation returns HTTP 200 with `"status":"healthy"`. HTTP 503
   returns `"status":"unhealthy"` and a `problems` array naming every missing or insufficient
   effective permission and webhook subscription. Its `remediation` object contains ordered
   `steps` plus direct App-permissions and installation-settings URLs generated from the current
   App owner, App slug, App ID, installation owner, and installation ID returned by GitHub. The
   endpoint therefore remains accurate after an App rename or reinstall instead of relying on
   deployment-specific IDs copied into documentation. It reads both the App registration and
   repository installation, while judging permissions from the effective installation that the
   organization has actually approved.

   For a human-readable audit, open the HTML representation in a browser:

   ```text
   https://githubci.kotlin.build/health/configuration?repository=CodexCoder21Organization%2Fkotlin-build-ci&format=html
   ```

   The HTML page shows the same effective permissions, webhook events, problems, generated
   remediation steps, and live GitHub settings links as the JSON response. It also links to the
   corresponding explicit JSON endpoint in a new browser tab. The `format` parameter accepts
   `html` or `json` case-insensitively. Omitting the parameter continues to return JSON for backward
   compatibility with existing health checks and scripts; supplying an empty or whitespace-only
   value is invalid and returns HTTP 400 rather than selecting a default.
3. On the App's **Advanced** page, inspect **Recent deliveries**. A `ping` delivery to `/webhook` should receive `200 OK`; failed deliveries include their request and response details and can be redelivered after the configuration is corrected.
4. Push a commit or open a PR containing `build.kts` in an installed repository. Confirm that a `kotlin.build (remote)` check appears and links to its build details.
5. If an authorized user's command is rejected because the repository-permission lookup failed, confirm that **Repository permissions → Metadata** is read-only and approved by the installation. If the warning appears in the check/buildtest page but no PR reply is posted, confirm that **Repository permissions → Issues** is read & write and approved by the installation. The configuration audit supplies the exact current settings and approval URLs for either failure.

## Related projects

[KotlinBuildCiWui](https://github.com/CodexCoder21Organization/KotlinBuildCiWui) is this service's operations interface, deployed at [ci.kotlin.build](https://ci.kotlin.build). It renders the [control API](#operations-control-api) above — the trigger log, the builds in flight, the bypasses, the per-repository build rates and the emergency stop — and holds no state of its own.

The build and test results this app records into [`url://buildtest/`](https://github.com/CodexCoder21Organization/BuildTestApi) are the upstream data source for the **kompile-remote-build** build-analytics ecosystem, which derives flakiness, duration, and pass-rate statistics over `kotlin.build` CI history:

- [KompileRemoteBuildApi](https://github.com/CodexCoder21Organization/KompileRemoteBuildApi) — API interfaces and data types for `url://kompile-remote-build/`.
- [KompileRemoteBuildEmbedded](https://github.com/CodexCoder21Organization/KompileRemoteBuildEmbedded) — in-process implementation; ingests build/test runs from `url://buildtest/` and computes the statistics.
- [KompileRemoteBuildServiceServer](https://github.com/CodexCoder21Organization/KompileRemoteBuildServiceServer) — hosts the Embedded implementation behind `url://kompile-remote-build/`.
- [KompileRemoteBuildCli](https://github.com/CodexCoder21Organization/KompileRemoteBuildCli) — command-line client for `url://kompile-remote-build/`.
- [KompileRemoteBuildWui](https://github.com/CodexCoder21Organization/KompileRemoteBuildWui) — server-side-rendered web dashboard for flakey tests, slowest tests, duration histograms, and pass-rate trends.
