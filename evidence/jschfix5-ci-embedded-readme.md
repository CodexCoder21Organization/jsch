# KompileRemoteBuildEmbedded

In-process implementation of the analytics, optimizer, and CI contracts in
[`KompileRemoteBuildApi`](https://github.com/CodexCoder21Organization/KompileRemoteBuildApi).
It pulls raw build runs and per-test results from `url://buildtest/`, persists
them in a local SQLite projection, computes flakey-test statistics and duration
rollups, and implements the optimizer and the
[`KompileRemoteBuild` workstream's CI orchestration path](https://github.com/CodexCoder21Organization/PlanRepository/blob/main/workstreams/KompileRemoteBuild.md#route-ci-through-urlkompile-remote-build).

## Constructor

```kotlin
val embedded = KompileRemoteBuildEmbedded(
    buildTestApi = buildTestProxy,                    // nullable url://buildtest/ proxy
    fileSystem = FileSystem.SYSTEM,                   // okio FileSystem; FakeFileSystem in tests
    dataDir = "/data/kompile-remote-build".toPath(),
    clock = SystemClock(),                            // ManualClock in tests
    terminalBuildRetentionWindowMs = 30L * 24 * 60 * 60 * 1_000,
    retainedTerminalBuildCount = 1_000,
    retentionBatchSize = 25,
    retentionIntervalMs = 5L * 60 * 1_000,
    uploadSessionRetentionWindowMs = 24L * 60 * 60 * 1_000,
)
```

The primary state file is `state.sqlite`. If a legacy `state.json` exists next to it, startup migrates it once to SQLite and renames the JSON file to `state.json.migrated`. `dataDir` must be a real on-disk directory even when an in-memory `fileSystem` is injected: SQLite opens `state.sqlite` through JDBC, and the cross-process `journal-writer.lock` below is an OS-level file lock, so both live on the real filesystem.

Opening a database created before the per-run result cleanup index existed
serves requests while a run-id lookup is filled in transactions of at most 500
entries. Each batch commits its cursor together with its entries, so an interrupted
process resumes from its last committed batch. New results update the lookup in
the same transaction as their source rows. Existing native indexes remain usable;
new empty databases create that index without scanning history. Closing cancels
background migration, and a failed batch reports the database path and cause
without taking the public service down. A later open resumes unfinished work.

Until the lookup is complete, replacement finds an existing run's result rowids
through a separate read connection and stages them in bounded batches. It never
scans unrelated history while holding the writer connection. The final transaction
replaces only that run's rows, so readers see a complete old or new result set.
CI retention uses its own tables and continues while this migration progresses.
See the [migration design and query audit](docs/RUN_ID_LOOKUP_MIGRATION.md).

CI commands use separate files under the same data directory:

- `ci-events.ndjson` is the append-only first-hand record of CI submissions,
  optimizer admission, BuildTest dispatch, observed lifecycle changes,
  per-test and per-build-rule resource samples, rerun candidates and outcomes,
  completion, verdicts, and per-branch test results. It is a write-ahead
  journal in front of `state.sqlite`: startup folds it into the durable
  tables one line at a time and then replaces it with a single
  `journal-compacted` marker line, so recovery never has to hold — or
  re-read — the whole history. The marker is written beside the journal,
  flushed, and moved over it atomically; the parent directory is then flushed
  too so the rename is durable on the host filesystem. The journal on disk is
  therefore either the complete old file or the complete new one. A line left
  half-written by an interrupted append
  can therefore only ever be the last one: startup drops that torn tail,
  records its size in the marker, and recovers everything before it. A
  malformed line anywhere else is real corruption — in production it has been
  produced by two processes appending to the same journal — and is salvaged
  around rather than crashing every subsequent boot: startup imports every
  valid line, moves the invalid bytes verbatim to a sibling
  `ci-events.ndjson.quarantine-<timestampEpochMs>` file, and tosses a
  `DurableJournalCorruptionQuarantinedEffect` (an algebraic effect carrying
  the journal path, quarantine path, and counts; loud on stderr when
  unhandled) before continuing. No byte is silently dropped. A
  well-formed event type that this version does not recognize is reported as
  an unsupported journal record before compaction, leaving the original
  journal available for a newer implementation to recover. During a warm
  lifetime, the same atomic replacement runs after this journal crosses the
  internal 1 MiB bound, so its durable history stays small between restarts.
  If a SQLite projection fails after an append, replay remains synchronous
  under the writer lock before compaction; this preserves the event but can
  briefly block concurrent requests while a large journal is replayed.
- `ci-archives/` retains caller-uploaded archives while a build is active or
  inside terminal retention. Once an old or count-cap-excess terminal build is
  compacted, its archive is removed through the durable deletion outbox.
- `ci-uploads/` holds incomplete upload sessions. Session activity is indexed
  in SQLite; inactive parts expire after the upload retention window. A bounded
  directory cursor enrolls untracked `.part` files left by older versions.
- `capacity-plan-events.ndjson` records each explicit capacity decision,
  shrink, completion outcome, and release, and is compacted on startup the
  same way. Every decision keeps its complete raw input snapshot. Documents at
  most 1 MiB stay inline; larger documents are streamed once into
  `capacity-decision-documents/` under a SHA-256 content-addressed name. Their
  bounded journal records contain the document reference, the decision
  envelope, and the per-key statistics used by the planner, never a replacement
  for the raw public input. If those review fields would themselves exceed the
  journal's 1 MiB line bound, the record keeps only the content address and
  recovery streams the envelope from the decision document. Recovery verifies
  the referenced bytes before it projects or compacts the record and removes
  unreferenced document files.
  That directory is durable state and must be backed up and restored together
  with `state.sqlite`. The
  latest decision for every plan id, including active
  reservations, is served from `state.sqlite` and materialized one decision
  at a time. Recovery reads only the indexed scalar fields and stores each
  decision's original JSON text unchanged, so full-history records remain
  recoverable without rebuilding the whole object in memory. An unknown
  typed record is reported before compaction and leaves the source journal
  unchanged. Invalid mid-file lines follow the same salvage-and-quarantine
  rule as `ci-events.ndjson`. During a warm lifetime, this journal is also
  flushed, atomically replaced with a marker, and followed by a
  parent-directory flush after it crosses the internal 1 MiB bound.
- `journal-writer.lock` is the exclusive cross-process writer claim over the
  data directory, taken with an OS-level `FileChannel.tryLock` before any
  durable file above is opened and held until `close()`. The append-only
  journals have no defense against a second live writer — two processes
  sharing one data directory interleave their buffered appends and corrupt
  the journals mid-file — so a second `KompileRemoteBuildEmbedded` pointed at
  a locked directory fails fast at construction with an error naming the lock
  file, the current holder (recorded inside the lock file), and the remedy.
  The lock is released by the kernel if the holding process dies, and the
  lock file itself is intentionally left behind on close.

Terminal CI retention runs in the background every five minutes by default.
A build is eligible when its completion is older than 30 days or it falls
outside the newest 1,000 unpruned terminal builds, and its capacity reservation
is settled. Each cycle prunes at most 25 builds. When both storage classes have
eligible work, one slot goes to the ordinary class and the other 24 go
to builds with decision documents. Either class can use slots the other does
not need. A configured batch of one alternates between the classes, including
across restarts. Each class visits a finite snapshot in completion-time/build-id
order through a persisted cursor. Later arrivals enter the next pass, even when
their keys sort inside the current pass. The oldest pending snapshot entry is
never displaced by file discovery when the batch has multiple slots: at most
one other slot per class can reclaim an archive found ahead of that cursor.
For a batch of one, each class alternates archive discovery and snapshot
progress, with that turn persisted too. Archives and file-less builds therefore
both receive service; builds holding both kinds of file belong to the document
class. A bounded archive-directory page still provides early file discovery.
Its directory cursor continues between cycles and closes with the service;
opening a new process restarts only that supplementary filesystem traversal.
External file removals and deletion errors mean that not every cycle can
promise to free bytes.

Candidate reads use indexed scalar eligibility and completion order. Their
work is bounded by the configured batch plus the fixed 1,001-row diagnostic
probe; it does not grow with the retained-count setting or historical table
size. Count-window membership is maintained as builds change.
Recording a completion also uses covering scalar indexes and the bounded
decision-document reference lookup. Establishing that a file-less build has no
capacity plan therefore does not read unrelated recorded request envelopes.
Opening an existing database replaces its persisted retention trigger bodies
with these scalar lookups without changing decision rows or document references.
Opening an older store creates empty additive lookups without scanning historical
retention rows. Each retention cycle fills at most 1,024 rows from each source:
completions, decision references, upload sessions, and pending file deletions.
The entries and each snapshot cursor commit together, so a restart resumes
from the last committed batch. Each source snapshot contains only the keys
present when it began. New writes, including writes by the previous release,
maintain the lookups in the same transaction as the source record and cannot
extend historical migration, even with arbitrary caller-selected plan ids.
Build pruning waits until completion and shared-reference coverage is complete;
pending deletions can still drain. Changing the retained count also adjusts at
most 1,024 entries per cycle, with count-based pruning paused until the new window
is exact. Age expiry remains an independent rule. Previous releases can still
open the additive schema. Startup recovery work for live builds and journals is
separate from this bounded historical-retention migration.

An eligible build keeps a small, parseable summary with its id, ref, status,
and timestamps. Run/log references, test details, archive data, and the
immutable capacity request are removed. A decision document remains while any
retained build references it. File deletion uses a durable outbox: a crash
after deleting a file but before acknowledging it causes a harmless missing-file
retry. An I/O failure records its full error and attempt count, schedules its
next attempt no earlier than the configured interval, and allows the rest of
the bounded deletion page to run. Failed entries move behind older unattempted
work. SQLite reuses freed pages without a background full-database vacuum.
All windows, caps, batch size, and interval are constructor parameters shown above.

Every completed cycle, including an empty one, writes the existing summary
line and a detailed retention-outcome line containing selected/pruned builds,
deleted files, bytes freed, failures, and a capped build backlog. A plus suffix
means that backlog exceeds 1,000 builds; failed files remain in the durable
outbox even after their build backlog reaches zero. A separate progress line
reports whether all backfills and count reconciliation are complete, the number
of visited keys per source, and each candidate class's persisted pass number,
visited count and completion flag. During migration a zero candidate backlog
alone does not imply that cleanup is finished.

For a source snapshot containing `N_s` keys and page size `P=1024`, migration
finishes in `M=max_s(floor(N_s/P)+1)` cycles (empty sources need none). For a
candidate snapshot with `N_c` keys in class `c`, each nonempty class gets at
least one cursor advancement per cycle when `B>=2`. When `B=1`, each class
gets a turn every two cycles and its cursor advances at least every four
cycles, because archive discovery alternates with snapshot work. A
conservative bound for visiting every original candidate is
`F*ceil(C/min(B,C))*max_c(N_c)` cycles after migration, where `C=2`,
`F=2` for `B=1` and `F=1` otherwise; arrivals do
not change the original populations or their ranks. With both classes busy
and `B>=2`, the tighter guaranteed document share is `max(1,B-2)` and the
ordinary share is one, allowing one early archive discovery. If no archive
needs an extra slot, the document share is `B-1`.

Thus 7,560 unique, deletable documents at the default batch of 25 require 303
cycles without ordinary competition or early archive discovery, and 315 with
an ordinary slot in use. Allowing an extra archive discovery gives a
conservative 329-cycle document-pass bound. These counts assume complete
migration and no prior deletion backlog; the cycle finishing migration can
also prune, so add `max(0,M-1)` migration cycles. Physical deletion still depends on
shared references and I/O success. Fixed original work progresses regardless
of arrivals; a total backlog cannot shrink if its arrival rate exceeds its
cleanup rate. Defaults remain 25 builds and five minutes, with cycle execution
time additional to the interval.

A decision records the request that produced it. For a request supplied through
`planCapacity(request)` that is the request exactly as it was given. For the
requests CI admission builds for itself, the recorded `request.history` is the
trailing window the planner reads — the latest 100 observations per execution
key within 30 days — rather than every sample the repository has ever produced.
Every field the planner computes is identical either way; embedding the
complete history is what grew a single recorded decision to megabytes and the
plan journal to hundreds of megabytes.

After one-time schema migrations, startup reconciliation is bounded by the
state that is still live, not by the state that has accumulated. The reconciliation that releases reservations left behind by a
build which went terminal without its capacity transition being recorded asks
for the reservations directly — the plan ids whose decision is `ADMITTED`, read
from a covering index — and matches only those against their owning build.
The build match is a primary-key lookup, and terminality comes from the build
table's indexed status field just as it did in the earlier reconciliation.
Reading it from the other end, one decision row per historical build, is not
equivalent: `capacity_plan_decisions` is `WITHOUT ROWID`, so each row carries
its recorded document inline ahead of the columns such a lookup wants, and
every one of those reads walks that document's overflow pages. This runs in the
constructor, so its cost is paid as unavailability — on a 9.1 GB database of
1,079 decisions with none admitted, it read roughly 9 GB before the service
could answer a single call.

A terminal build's durable outcome is repaired rather than left half-written.
A terminal transition writes its state, completion, capacity, and verdict as
separate write-ahead records, so a process can stop between any two of them.
Because a terminal build is never re-observed, a build left without its verdict
row would otherwise answer `getCheckVerdict` with `PENDING` forever and be
reported upstream as a failed check. Restart therefore rebuilds the verdict of
any terminal build that is missing one, from the records the build did leave
behind: the terminal reason on its state record, then its completion cause,
then — only for a build whose completion cause says its tests decided the
outcome — its recorded test results. Where the records that survived identify
the transition, the reconstructed verdict reads exactly as the one the
uninterrupted transition would have written. Where they do not, it is a
truthful summary of what was found rather than a reproduction of the original
text: a build stranded before its completion record cannot be distinguished
from other outcomes and gets the fail-closed summary below, and a build
stranded between the cancellation's state and completion records is reported
as cancelled without attributing the cancellation to its underlying run. When
the records do not establish that the build passed, the rebuilt verdict is RED
and names what was and was not found, because a spurious red check is corrected
by rerunning it while a spurious green check reports a failed build as passing.
The repair runs as a bounded background scan, and on demand for a build that is
read before the scan reaches it; a healthy build costs no extra read, because
whether a verdict row exists is already part of the scan's scalar projection.
Only builds that were already terminal when the process started are repaired —
a build this process makes terminal stays in the ordinary state machine.

The existing SQLite poller remains the broad analytics path for every BuildTest
caller. CI orchestration does not submit through, or depend on, that poller.

```bash
sqlite3 /data/kompile-remote-build/state.sqlite 'select count(*) from runs;'
sqlite3 /data/kompile-remote-build/state.sqlite 'select count(*) from capacity_plans;'
```

## Ingestion

`ingestNow()` performs one cycle:

1. Page through `BuildTestApi.listBuildRunsPaginated` (newest-first) in chunks of 10.
2. For each run with terminal status (`COMPLETED`, `FAILED`, `CANCELED`) not already in local state, fetch `getTestResultsPaginated(runId, offset, limit)` in pages of at most 500 values. The reader validates the echoed offset, stable total, page limit, page size, and forward progress before accepting every value; an incomplete page fails the run's ingest rather than being silently truncated.
3. Continue scanning until the page stream ends or 10 new terminal runs have been ingested this cycle.
4. Write each run directly to SQLite in a transaction.

Non-terminal runs (`PENDING`, `BUILDING`, `TESTING`, etc.) are skipped this cycle and picked up next time. A failure to fetch test results for one runId logs to stderr and continues; the next cycle will retry.

## Aggregation logic

| Method | Formula |
|---|---|
| `listFlakeyTests` | `flakeRate = 1 − max(passes, failures) / total`. Tests with all-pass or all-fail are excluded. |
| `getTestDurationHistogram` | Equal-width buckets over `[min, max]` of **passing-only** durations. Returns `p50`/`p95`/`p99` (linear-interp percentiles, Long-truncated). |
| `listSlowestTests` | Group passing rows by `(repo, test)`, rank by `p95(durations)` descending, take top-N. |
| `getTestPassRateTrend` | Bucket runs by `floor(startedAt / bucketSize)` where `bucketSize` is one day or one week. Each point: `passRate = passes / totalRuns`. |
| `listRepoBuildSuccessStats` | Per repo, count `BuildRun.buildSucceeded`. Sorted ascending so the worst-performing repos surface first. |
| `getDropletSizeDurationStats` | Group passing rows by `BuildRun.dropletSize`, compute p50/p95 per group. Optional `repoName` and `testName` filters. Exact percentile bounds come from indexed rank lookups, so the query does not sort the complete duration history. |

The flakey/duration/slowest aggregations attribute by `TestResult.projectName` (per-test), so combined-downstream-build runs (which span multiple repos in a single buildtest run) are still attributed correctly to each constituent repo.

## Optimizer

`KompileRemoteBuildEmbedded` also implements `KompileRemoteBuildOptimizerService`:

```kotlin
val plan = embedded.planCapacity("foundation.url.resolver", dropletSizeSlug = "s-2vcpu-4gb")
val explicitPlan = embedded.planCapacity(
    DataCapacityPlanningRequest(
        planId = "ci-run-123",
        repoName = "foundation.url.resolver",
        setupHistoryKey = "foundation.url.resolver.build",
        tests = discoveredTests,
        history = trailingExecutionSamples,
        dropletSizeSlug = "s-4vcpu-8gb",
        dropletMemoryBytes = 8L * 1024L * 1024L * 1024L,
        fleetState = currentFleetState,
        submittedAtEpochMs = clock.currentTimeMillis(),
    )
)
val queue = embedded.getQueueStatus()
val accuracy = embedded.getPredictionAccuracy("foundation.url.resolver", sinceDays = 30)
```

Planning uses `BuildTestOptimizerApi.getExecutionResourceStats()` when the connected buildtest server supports it. Against older buildtest servers it degrades to duration-only stats and the conservative 2 GB memory default; if no matching history exists it uses 30 seconds and 2 GB for the unknown test estimate.

The explicit overload plans the caller-supplied suite. It selects p50 duration
and p95 peak memory from the latest 100 observations per key within 30 days.
A new test uses its declared timeout or 30 seconds, and 2 GB for memory. The
planner reserves `max(1 GB, 20% of RAM)` per droplet, stops adding droplets
when the next saves less than 30 seconds, and never selects more droplets than
tests. Admission is serialized with active recorded reservations so concurrent
callers cannot consume the same fleet slot. Provider occupancy includes ad-hoc
BuildTest droplets; when free capacity is below 20% of the limit, only the new
plan is reduced, with a one-droplet floor whenever a slot is available.
`getPredictionAccuracy` combines completed explicit capacity outcomes with
legacy ingested BuildTest plans and returns at most one point per run id.

## CI orchestration

`KompileRemoteBuildEmbedded` also implements `KompileRemoteBuildCiService`.
Callers can submit a complete archive directly or create an upload session with
repository/ref metadata, upload the archive in ordered chunks of at most 2 MiB,
and finalize it. Finalization persists and returns a PENDING CI record before
the background admission cycle plans the build and forwards the caller-supplied
bytes and structured `GitBuildContext` to BuildTest. It never fetches source or
holds GitHub credentials. Once that CI record is durable, repeating
`finalizeUpload` with the same upload-session id returns the same recorded
build, including after a service restart; it does not create another build or
upload the archive again.

The first successful entry into a CI API operation is its shutdown admission
point. Once admitted, all nested upload and orchestration phases of that call
finish even if `close()` starts; calls that have not reached that point are
rejected. A process death is different from orderly shutdown: if it occurs
after an upload archive moves into `ci-archives/` but before the submission
event is appended, restart does not invent a build without durable metadata.
The caller must resubmit, and the unreferenced archive is retained for operator
diagnosis. Automatic recovery or deletion would require a durable upload record
that can distinguish an interrupted finalization from unrelated files; this
service intentionally does neither based on an archive filename alone.

`close()` stops new admission work immediately. If a caller is already inside
a BuildTest dispatch, close returns without waiting for that upstream call;
the caller records the returned BuildTest run id before the SQLite store is
closed, so restart replay does not lose the in-progress dispatch.

Initial dispatch uses `ExplicitCapacityPlanner` with first-hand successful test
and build-rule resource history. Admission is FIFO. A build remains PENDING
with a queue position and estimated start while no fleet slot is available,
then starts automatically when the injected clock runs the next admission
cycle; lack of allocation capacity is not a failed CI result. The queue is
advanced only by that cycle, which the engine starts for itself and which
requests ask to run promptly rather than running themselves. One advancement
runs at a time. Its build-state transitions take the engine lock, while
capacity planning, BuildTest observation, and BuildTest upload calls run
without that engine-wide lock, so an unrelated upload can be finalized and
durably accepted while a dispatch or observation is waiting on external work.
If cancellation targets the build currently being admitted, it waits for that
build's admission step to finish before applying its transition; unrelated
builds do not wait on that step.
Cancellation stops every active underlying BuildTest run with the caller's
reason (`cancelCiBuild(id)` sends a reason naming the CI build). Every run gets
its own attempt; if any run's status read or cancel fails, the call throws an
`IllegalStateException` that lists what happened to each run and does not mark
the CI build canceled. What follows depends on the latest run, which drives the
CI build's status: if that run was canceled, the next refresh marks the CI
build CANCELED and a later `cancelCiBuild` is refused; otherwise the CI build
keeps following that run and `cancelCiBuild` can be called again. An
interruption during cancellation propagates as `InterruptedException` with the
thread's interrupt flag set, without attempting the remaining runs; when earlier
runs already had an outcome, the exception names each of them and carries the
interruption as its cause. While there is no BuildTest connection, cancelling a
CI build that has underlying runs throws an `IllegalStateException` naming the
CI build and its runs; the CI build keeps its status and its runs are untouched,
so the caller can cancel again once the connection is back.
If a saved BuildTest upload session has disappeared, the engine checks for an
accepted run and requires two empty run lookups on separate admission cycles
before replacing the saved session. Initial and rerun dispatch failures share
the same durable five-attempt limit and record the rerun round when applicable;
after the limit the build is FAILED, its capacity is released, and its last
failure and timestamp remain available after restart. A successful dispatch
stops presenting an earlier failure as current, while the journal retains the
history. A PENDING build that has not dispatched after 200 minutes is CANCELED
with an expired-deadline reason, so abandoned client checks do not consume a
fleet slot when the queue recovers. Each admission cycle expires at most 50
such builds before yielding to the next cycle.
CI reads retain their established synchronous observation contract through a
snapshot/fetch/revalidate sequence: a request may observe its build, but every
BuildTest call happens outside the engine-wide lock. A concurrent state or
connection-generation change discards the remote observation and retries or
skips the stale result. Reads also signal the background admission cycle, and
only that cycle creates or uploads rerun sessions. The upstream work a request
pays for is limited to the build or page it asked for and never blocks
unrelated upload acceptance. CI methods expose bounded logs, test and
build-rule result pages, cancellation, a final check verdict, rerun decisions,
per-branch test history, and resource statistics. Resource percentiles use the
latest 100 samples within the latest 30 days.

Only test failures are rerun. Build and compile failures are immediately red.
A failed test is eligible when its error matches a configured transient
signature or recent history contains a green result across `main` and the CI
branch. Reruns are batched into at most three rounds, use the original archive,
and are limited to three total attempts per test and
`max(6, ceil(3% × total tests))` rerun attempts per CI build. A retry changes a
test to green only when BuildTest reports that retry passing.

## Building

```bash
./scripts/build.bash kompile.remotebuild.embedded.buildMaven
```

Built artifact: `kompile.remotebuild.embedded:kompile-remote-build-embedded:0.0.49`.
The embedded service stops accepting new CI operations as soon as `close()` starts, waits for an in-flight BuildTest dispatch to finish, and closes its SQLite store only after that dispatch has recorded its returned run id. A disconnected BuildTest generation clears the upstream API, pauses admission and refresh of durable in-flight builds, and resumes them when a newer generation is installed.

## Running tests

```bash
./scripts/test.bash --test .
```

CI tests use a real in-process `BuildTestEmbeddedService` backed by
`FakeDropletService`, a `ManualClock` for first-hand event timestamps, and
temporary local data directories. No test creates a real DigitalOcean droplet.

## Programmatic usage

```kotlin
import buildtest.api.BuildTestApi
import foundation.url.resolver.UrlResolver
import kompile.remotebuild.embedded.KompileRemoteBuildEmbedded

val buildTest: BuildTestApi = resolver.openSandboxedConnection("url://buildtest/", BuildTestApi::class)
val embedded = KompileRemoteBuildEmbedded(buildTest)

// Run an initial ingest before serving queries.
embedded.ingestNow()

val flakey = embedded.listFlakeyTests("UrlResolver", sinceDays = 30)
flakey.take(5).forEach { println("${it.testName}: flakeRate=${"%.2f".format(it.flakeRate)}") }
```

## Storage choice

SQLite via JDBC. The store keeps runs, per-test results, recorded capacity plans, and prediction-accuracy inputs in indexed tables, so cold start and steady-state heap are independent of total history size. The CI orchestration state — build states, check verdicts, per-branch first-hand test history, execution resource samples and capacity-plan decisions — lives in the same database for the same reason: recovery streams the journals into rows rather than into maps, and only builds that have not reached a terminal status stay resident.

There is one write connection and a bounded pool of read-only connections. Every writer — ingestion, the analytics projection and the CI projection — goes through a single guarded accessor that owns transaction lifecycle. Streaming ingest fetches remote result pages without that guard, commits each bounded page to an unpublished staging table under a short transaction, then publishes the complete run in one final transaction. This keeps partial result sets invisible without making unrelated durable writes wait for a remote page. Each read borrows one `query_only` connection exclusively, so overlapping queries cannot share an older WAL snapshot and miss a write that committed between them. The separate pool also lets request handlers read the last committed snapshot while an ingest is still staging. The two append-only journals are protected by an exclusive `journal-writer.lock` held for the service lifetime; recovery quarantines invalid mid-file bytes in `<journal>.quarantine-<timestamp>` files, adding a numeric suffix when that timestamp already exists.

Opening an existing store also backfills a normalized projection of passing durations with the run start time and droplet size needed by statistics requests, then creates its covering rank index. Inserts, updates, and deletes maintain that projection through SQLite triggers. This is the schema migration for databases created before the projection existed; later opens recognize the installed maintenance trigger, including a legitimately empty projection, and do no source scan or rebuild work.
