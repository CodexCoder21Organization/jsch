package org.example.ci

import buildwatchman.BuildtestRunWatchman
import community.kotlin.clocks.simple.SystemClock
import community.kotlin.logging.MessageSeverity
import foundation.url.protocol.Libp2pPeer
import foundation.url.resolver.UrlProtocol2
import foundation.url.resolver.UrlResolver
import java.io.File
import lock.FileBasedProcessLock
import lock.ProcessLockException
import kotlinx.algebraiceffects.Effective
import kotlinx.algebraiceffects.NotificationEffect
import kotlinx.algebraiceffects.toss
import okio.FileSystem
import okio.Path.Companion.toPath
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import webcronapi.HttpWebCronService

class OrphanedTarballCleanedUpEffect(
    val fileName: String,
    val sizeMb: Long
) : NotificationEffect("Cleaned up orphaned bootstrap tarball: $fileName ($sizeMb MB)", MessageSeverity.INFO)

class OrphanedTarballDeletionFailedEffect(
    val fileName: String,
    val sizeMb: Long
) : NotificationEffect("Failed to delete orphaned bootstrap tarball: $fileName ($sizeMb MB)", MessageSeverity.WARNING)

class StaleTarballTempFileCleanedUpEffect(
    val fileName: String,
    val sizeMb: Long
) : NotificationEffect("Cleaned up stale tarball temp file: $fileName ($sizeMb MB)", MessageSeverity.INFO)

class StaleTarballTempFileDeletionFailedEffect(
    val fileName: String,
    val sizeMb: Long
) : NotificationEffect("Failed to delete stale tarball temp file: $fileName ($sizeMb MB)", MessageSeverity.WARNING)

class StaleTarballTempFileSkippedEffect(
    val fileName: String,
    val sizeMb: Long
) : NotificationEffect("Skipped tarball temp file (locked by another process): $fileName ($sizeMb MB)", MessageSeverity.INFO)

/** Returns whether one persisted shadow build owns the complete reconciliation identity. */
fun trackedShadowBuildMatches(
    build: TrackedBuild,
    request: ReconcileBackfillRequest
): Boolean = build.owner == request.owner && build.repo == request.repo &&
    build.installationId == request.installationId && build.headSha == request.headSha

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val webCronUrl = System.getenv("WEBCRON_URL")
    val callbackUrl = System.getenv("CI_CALLBACK_URL")
    val buildTimeoutMinutes = System.getenv("BUILD_TIMEOUT_MINUTES")?.toLongOrNull() ?: 45L
    val dataDir = System.getenv("DATA_DIR") ?: "/data"
    // Keep the current direct BuildTest route unless the operator explicitly opts into
    // KompileRemoteBuild. The latter is intentionally not the production default while the
    // hosted CI surface is still being rolled out.
    val buildServiceRoute = parseBuildServiceRoute(System.getenv("CI_BUILD_ROUTE"))
    val shadowBuildServiceRoute = parseShadowBuildServiceRoute(
        System.getenv("CI_SHADOW_ROUTE"),
        buildServiceRoute
    )
    val shadowRouteDescription = shadowBuildServiceRoute?.let { route ->
        when (route.target) {
            BuildServiceRouteTarget.DIRECT_BUILDTEST -> "direct-buildtest"
            BuildServiceRouteTarget.KOMPILE_REMOTE_BUILD -> "kompile-remote-build"
        }
    }
    // Parse and validate before Jetty starts so invalid configuration cannot leave a healthy-looking
    // HTTP server running without its redelivery scheduler.
    val redeliveryConfiguration = parseGitHubWebhookRedeliveryConfiguration(
        listOf(
            "REDELIVERY_SWEEP_MINUTES",
            "REDELIVERY_LOOKBACK_MINUTES",
            "REDELIVERY_MAX_PER_SWEEP"
        ).mapNotNull { name -> System.getenv(name)?.let { value -> name to value } }.toMap()
    )

    val alignmentSweepConfiguration = parseGitHubAlignmentSweepConfiguration(
        System.getenv().filterKeys { it.startsWith("ALIGNMENT_SWEEP_") }
    )

    println("Starting kotlin.build CI server on port $port")

    val persistPath = "$dataDir/in-progress-builds.json".toPath()
    val eventLogPath = "$dataDir/build-events.ndjson".toPath()
    val eventLog = BuildEventLog(FileSystem.SYSTEM, eventLogPath)
    // The operator-facing record of what this service did, and the admission rules an operator sets
    // through it. Both are constructed before anything can trigger a build so no trigger is missed
    // from the record and none escapes a declared emergency stop.
    val activityJournal = CiActivityJournal(FileSystem.SYSTEM, "$dataDir/ci-activity.ndjson".toPath())
    val admissionControl = CiAdmissionControl(
        fileSystem = FileSystem.SYSTEM,
        path = "$dataDir/ci-admission.json".toPath(),
        journal = activityJournal
    )
    admissionControl.getEmergencyStop().let { stop ->
        if (stop.active) {
            println(
                "EMERGENCY STOP IS DECLARED (declared by ${stop.activatedBy.ifEmpty { "an unnamed operator" }}, " +
                    "blocking new builds: ${stop.blockNewBuilds}). Status message: " +
                    stop.statusMessage.ifEmpty { "(none given)" }
            )
        }
    }
    val configuredDownstreamBuildsEnabled = System.getenv("DOWNSTREAM_BUILDS_ENABLED")?.lowercase() != "false"
    val tracker = InProgressBuildTracker(
        FileSystem.SYSTEM, persistPath, eventLog, activityJournal,
        configuredDownstreamBuildsEnabled = configuredDownstreamBuildsEnabled
    )
    val watchmanClock = SystemClock()
    val watchmanLedger = WatchmanRemediationLedger(
        fileSystem = FileSystem.SYSTEM,
        path = "$dataDir/build-watchman-remediations.ndjson".toPath(),
        clock = watchmanClock
    )
    val shadowEventLog = shadowBuildServiceRoute?.let {
        BuildEventLog(FileSystem.SYSTEM, "$dataDir/build-events-shadow.ndjson".toPath())
    }
    val shadowTracker = shadowBuildServiceRoute?.let {
        InProgressBuildTracker(
            FileSystem.SYSTEM,
            "$dataDir/in-progress-builds-shadow.json".toPath(),
            requireNotNull(shadowEventLog),
            configuredDownstreamBuildsEnabled = configuredDownstreamBuildsEnabled
        )
    }
    validateBuildServiceRouteForTrackedBuilds(
        tracker.getAllBuilds(),
        buildServiceRoute.target,
        buildServiceRoute.kompileRemoteBuildServiceUrl
    )
    if (shadowBuildServiceRoute != null) {
        validateBuildServiceRouteForTrackedBuilds(
            requireNotNull(shadowTracker).getAllBuilds(),
            shadowBuildServiceRoute.target,
            shadowBuildServiceRoute.kompileRemoteBuildServiceUrl,
            settingName = "CI_SHADOW_ROUTE"
        )
    }

    // Complete durable output recovery before Jetty binds. A service that cannot load this state
    // must fail startup instead of advertising a healthy endpoint while its BuildRunner is absent.
    preparePostCompletionOutputQueue(FileSystem.SYSTEM, dataDir)
    if (shadowBuildServiceRoute != null) {
        preparePostCompletionOutputQueue(FileSystem.SYSTEM, dataDir, completionLockNamespace = "shadow")
    }

    val watchdog: BuildWatchdogScheduler? = if (webCronUrl != null && callbackUrl != null) {
        val webCronService = HttpWebCronService(webCronUrl)
        BuildWatchdogScheduler(webCronService, callbackUrl).also {
            println("Build watchdog enabled: WebCron=$webCronUrl, callback=$callbackUrl")
        }
    } else {
        println("WEBCRON_URL or CI_CALLBACK_URL not set, build watchdog disabled")
        null
    }

    // Wire the remote build service. As of foundation.url:resolver 0.0.560
    // (UrlResolver PR #644) openSandboxedConnection is durable and self-healing:
    // it builds the underlying connection lazily on first use and rebuilds it on
    // failure, so creating the BuildTestApi proxy neither blocks nor throws at
    // startup even when url://buildtest/ is momentarily cold (e.g. a simultaneous
    // ContainerNursery restart). The resolver re-establishes transparently, so
    // kotlin-build-ci no longer needs a background retry loop, a holder, or
    // null-baking — that machinery is exactly what froze a null BuildTestApi and
    // caused the 2026-06-21 org-wide CI outage when buildtest was briefly cold at
    // startup. The first build's RPCs lazily establish the connection; if buildtest
    // is unavailable then, that build fails and the next one self-heals.
    val peerId = "12D3KooWLMyXNfwhcX1YsiNx3hnjk3GGSfsU1fydRa8bzrE6scMT"
    val multiaddr = "/ip4/198.199.106.165/tcp/35000/p2p/$peerId"
    val primaryAdvertisedService = when (buildServiceRoute.target) {
        BuildServiceRouteTarget.DIRECT_BUILDTEST -> "buildtest"
        BuildServiceRouteTarget.KOMPILE_REMOTE_BUILD -> "kompile-remote-build"
    }
    val advertisedServices = if (shadowBuildServiceRoute == null) {
        listOf(primaryAdvertisedService)
    } else {
        buildList {
            add(primaryAdvertisedService)
            add(
                when (shadowBuildServiceRoute.target) {
                    BuildServiceRouteTarget.DIRECT_BUILDTEST -> "buildtest"
                    BuildServiceRouteTarget.KOMPILE_REMOTE_BUILD -> "kompile-remote-build"
                }
            )
        }.distinct()
    }
    val bootstrapPeer = Libp2pPeer.remote(
        peerId = peerId,
        multiaddresses = listOf(multiaddr),
        advertisedServices = advertisedServices
    )
    val urlProtocol = UrlProtocol2(bootstrapPeers = listOf(bootstrapPeer))
    // Give the shadow route its own resolver and protocol lifecycle. A shadow connection outage
    // must not poison the primary route's connection pool or sandbox proxy lifecycle.
    val shadowUrlProtocol = shadowBuildServiceRoute?.let {
        UrlProtocol2(bootstrapPeers = listOf(bootstrapPeer))
    }
    if (shadowUrlProtocol == null) {
        Runtime.getRuntime().addShutdownHook(Thread {
            println("Shutting down UrlProtocol...")
            try { urlProtocol.close() } catch (_: Exception) {}
        })
    } else {
        Runtime.getRuntime().addShutdownHook(Thread {
            println("Shutting down UrlProtocol...")
            try { urlProtocol.close() } catch (_: Exception) {}
            try { shadowUrlProtocol.close() } catch (_: Exception) {}
        })
    }
    val resolver = UrlResolver(urlProtocol)
    val shadowResolver = shadowUrlProtocol?.let { UrlResolver(it) }
    val buildTestApiFactory = { createConfiguredBuildTestApi(resolver, buildServiceRoute) }
    val buildTestApi = buildTestApiFactory()
    val shadowBuildTestApi = shadowBuildServiceRoute?.let { route ->
        createConfiguredBuildTestApi(requireNotNull(shadowResolver), route)
    }
    println(
        "Remote build service wired via " + when (buildServiceRoute.target) {
            BuildServiceRouteTarget.DIRECT_BUILDTEST -> "url://buildtest/ (durable self-healing proxy)"
            BuildServiceRouteTarget.KOMPILE_REMOTE_BUILD ->
                "${buildServiceRoute.kompileRemoteBuildServiceUrl} (opt-in CI orchestration proxy)"
        }
    )

    // Clean up stale combined-downstream temp files left by previous crashes
    if (shadowTracker == null) {
        cleanupStaleCombinedDownstreamFiles(File(dataDir), tracker)
    } else {
        cleanupStaleCombinedDownstreamFiles(File(dataDir), tracker, listOf(shadowTracker))
    }

    // Clean up orphaned bootstrap workspace tarballs from previous crashes.
    // These files persist on disk when the process dies mid-bootstrap (e.g., OOM/GC death spiral).
    // Each file is ~100+ MB, so orphans accumulate and contribute to memory pressure.
    Effective {
        handler { e: OrphanedTarballCleanedUpEffect ->
            println("Cleaning up orphaned bootstrap tarball: ${e.fileName} (${e.sizeMb} MB)")
        }
        handler { e: OrphanedTarballDeletionFailedEffect ->
            System.err.println("WARNING: Failed to delete orphaned bootstrap tarball: ${e.fileName} (${e.sizeMb} MB)")
        }
        if (shadowTracker == null) {
            cleanupOrphanedBootstrapTarballs(dataDir, tracker)
        } else {
            cleanupOrphanedBootstrapTarballs(dataDir, tracker, listOf(shadowTracker))
        }
    }

    // Clean up stale tarball-*.tar.gz temp files from /tmp left by previous crashes.
    // downloadGitHubTarball() creates these files and normally cleans them up via finally
    // blocks, but crashes (OOM, SIGKILL) prevent the finally blocks from running. Each
    // build downloads up to 40+ repo tarballs, so a single crash can leave hundreds of MB.
    Effective {
        handler { e: StaleTarballTempFileCleanedUpEffect ->
            println("Cleaning up stale tarball temp file: ${e.fileName} (${e.sizeMb} MB)")
        }
        handler { e: StaleTarballTempFileDeletionFailedEffect ->
            System.err.println("WARNING: Failed to delete stale tarball temp file: ${e.fileName} (${e.sizeMb} MB)")
        }
        handler { e: StaleTarballTempFileSkippedEffect ->
            println("Skipped tarball temp file (locked by another process): ${e.fileName} (${e.sizeMb} MB)")
        }
        cleanupStaleTarballTempFiles()
    }

    // Start the HTTP server so ContainerNursery's health check passes promptly.
    // The build service proxy above is already wired (lazily); no init thread to
    // wait on.
    val server = Server(port)
    val context = ServletContextHandler(ServletContextHandler.NO_SESSIONS)
    context.contextPath = "/"
    server.handler = context

    // Health endpoint is available immediately
    context.addServlet(ServletHolder(HomepageServlet()), "/")
    context.addServlet(ServletHolder(LogoServlet()), "/logo.png")
    context.addServlet(ServletHolder(HealthServlet()), "/health")
    context.addServlet(ServletHolder(ConfigurationHealthServlet()), "/health/configuration")

    server.start()
    println("kotlin.build CI server started on port $port")

    // Shadow dispatch and build work have independent bounded executors. They are created only
    // when CI_SHADOW_ROUTE is configured, so the primary-only startup path allocates none of them.
    val shadowBuildExecutor = shadowBuildServiceRoute?.let { newShadowBuildExecutor() }
    val shadowDispatchExecutor = shadowBuildServiceRoute?.let { newShadowProcessingExecutor() }

    val buildRunner = if (shadowTracker == null) {
        BuildRunner(
            tracker,
            watchdog,
            buildTestApi,
            freshBuildTestApiFactory = buildTestApiFactory,
            dataDir = dataDir,
            buildServiceRouteTarget = buildServiceRoute.target,
            buildServiceRouteUrl = buildServiceRoute.kompileRemoteBuildServiceUrl,
            buildCheckRunRole = BuildCheckRunRole.REQUIRED
        )
    } else {
        BuildRunner(
            tracker,
            watchdog,
            buildTestApi,
            freshBuildTestApiFactory = buildTestApiFactory,
            dataDir = dataDir,
            buildServiceRouteTarget = buildServiceRoute.target,
            buildServiceRouteUrl = buildServiceRoute.kompileRemoteBuildServiceUrl,
            buildCheckRunRole = BuildCheckRunRole.REQUIRED,
            hasOtherInProgressBuilds = { !shadowTracker.isEmpty() }
        )
    }
    val shadowBuildRunner = shadowBuildServiceRoute?.let { route ->
        BuildRunner(
            tracker = requireNotNull(shadowTracker),
            watchdog = watchdog,
            buildTestApi = requireNotNull(shadowBuildTestApi),
            freshBuildTestApiFactory = {
                createConfiguredBuildTestApi(requireNotNull(shadowResolver), route)
            },
            dataDir = dataDir,
            buildServiceRouteTarget = route.target,
            buildServiceRouteUrl = route.kompileRemoteBuildServiceUrl,
            buildCheckRunRole = BuildCheckRunRole.INFORMATIONAL,
            // The primary runner owns the container keepalive. A shadow build must not extend
            // the primary service lease or generate duplicate /health traffic after primary work
            // has finished; its own remote lease and durable tracker remain independent.
            selfKeepaliveUrl = null,
            onReadCheckRunForCompletion = { owner, repo, installationId, checkRunId ->
                GitHubChecks.getCheckRun(owner, repo, installationId, checkRunId)
            },
            onCompleteCheckRun = { owner, repo, installationId, checkRunId, conclusion, summary, detailsUrl ->
                GitHubChecks.completeCheckRunVerified(
                    owner = owner,
                    repo = repo,
                    installationId = installationId,
                    checkRunId = checkRunId,
                    conclusion = conclusion,
                    summary = summary,
                    detailsUrl = detailsUrl,
                    checkRunName = KOMPILE_REMOTE_BUILD_CHECK_RUN_NAME
                )
            },
            onUpdateCheckRunOutput = { owner, repo, installationId, checkRunId, summary, detailsUrl ->
                GitHubChecks.updateCheckRunOutput(
                    owner = owner,
                    repo = repo,
                    installationId = installationId,
                    checkRunId = checkRunId,
                    summary = summary,
                    detailsUrl = detailsUrl,
                    checkRunName = KOMPILE_REMOTE_BUILD_CHECK_RUN_NAME
                )
            },
            executor = requireNotNull(shadowBuildExecutor),
            hasOtherInProgressBuilds = { !tracker.isEmpty() },
            includeFullErrorStacktrace = true,
            completionLockNamespace = "shadow"
        )
    }
    if (shadowBuildServiceRoute != null) {
        Runtime.getRuntime().addShutdownHook(Thread {
            shadowBuildRunner?.shutdown()
            shadowDispatchExecutor?.shutdownNow()
        })
    }
    val primaryWebhookEventHandler = WebhookEventHandler(
        buildRunner = buildRunner,
        onListCheckRunNamesForCommit = { owner, repo, headSha, installationId ->
            listCheckRunNamesForRef(owner, repo, installationId, headSha)
        },
        defaultDetailsUrl = buildDetailsUrl(
            runId = null,
            target = buildServiceRoute.target,
            serviceUrl = buildServiceRoute.kompileRemoteBuildServiceUrl
        ),
        journal = activityJournal,
        admissionControl = admissionControl
    )
    val webhookHandler: WebhookHandler
    val processBuildRequest: (WebhookBuildInfo) -> Boolean
    val processBuildRequestIfMissing: (WebhookBuildInfo) -> Boolean
    val processBuildRequestIfMissingWithCompletion: (
        WebhookBuildInfo,
        () -> Boolean,
        () -> LostDispatchVerificationResult,
        (PullRequestHeadDispatchResult) -> Unit
    ) -> Unit
    val shadowProcessBuildRequest: ((WebhookBuildInfo) -> Boolean)?
    var shadowDispatchPending: (ReconcileBackfillRequest) -> Boolean = { false }
    if (shadowBuildServiceRoute != null) {
        val shadowWebhookEventHandler = WebhookEventHandler(
            buildRunner = requireNotNull(shadowBuildRunner),
            checkRunName = KOMPILE_REMOTE_BUILD_CHECK_RUN_NAME,
            onListCheckRunNamesForCommit = { owner, repo, headSha, installationId ->
                listCheckRunNamesForRef(owner, repo, installationId, headSha)
            },
            // The primary handler owns user-facing PR feedback. The shadow still evaluates the
            // same authorization result and includes its notice in the informational check, but
            // must not post a duplicate comment for the same rejected command.
            onCreatePrComment = { _, _, _, _, _ -> },
            defaultDetailsUrl = buildDetailsUrl(
                runId = null,
                target = requireNotNull(shadowBuildServiceRoute).target,
                serviceUrl = requireNotNull(shadowBuildServiceRoute).kompileRemoteBuildServiceUrl
            ),
            onCompleteCheckRun = { owner, repo, installationId, checkRunId, conclusion, summary, detailsUrl ->
                GitHubChecks.completeCheckRun(
                    owner = owner,
                    repo = repo,
                    installationId = installationId,
                    checkRunId = checkRunId,
                    conclusion = conclusion,
                    summary = summary,
                    detailsUrl = detailsUrl,
                    checkRunName = KOMPILE_REMOTE_BUILD_CHECK_RUN_NAME
                )
            },
            reportUnhandledErrorsAsFailure = true,
            routeDescription = requireNotNull(shadowRouteDescription),
            // The shadow route submits every build a second time, to the same fleet. An emergency
            // stop that halted only the primary would halve the bleeding, not stop it, so the shadow
            // is admitted through the same rules.
            //
            // It deliberately gets no journal: both routes see the same trigger, so recording both
            // would count every dispatch twice — and the rate rule counts recorded dispatches, so
            // every repository's effective rate would silently halve. The primary's record already
            // says what the trigger did.
            admissionControl = admissionControl
        )
        val dualWebhookEventHandler = DualRouteWebhookEventHandler(
            primary = primaryWebhookEventHandler,
            shadow = shadowWebhookEventHandler,
            shadowExecutor = requireNotNull(shadowDispatchExecutor)
        )
        webhookHandler = dualWebhookEventHandler
        processBuildRequest = dualWebhookEventHandler::processBuildRequest
        processBuildRequestIfMissing = dualWebhookEventHandler::processBuildRequestIfMissing
        processBuildRequestIfMissingWithCompletion = { info, claimAllowance, verifySuiteBeforeClaim, onEvaluated ->
            dualWebhookEventHandler.processBuildRequestIfMissingWithCompletion(info, claimAllowance, verifySuiteBeforeClaim, onEvaluated)
        }
        shadowProcessBuildRequest = dualWebhookEventHandler::processShadowBuildRequest
        shadowDispatchPending = dualWebhookEventHandler::isShadowDispatchPending
    } else {
        webhookHandler = primaryWebhookEventHandler
        processBuildRequest = primaryWebhookEventHandler::processBuildRequest
        processBuildRequestIfMissing = primaryWebhookEventHandler::processBuildRequestIfMissing
        processBuildRequestIfMissingWithCompletion = { info, claimAllowance, verifySuiteBeforeClaim, onEvaluated ->
            primaryWebhookEventHandler.processBuildRequestIfMissingWithCompletion(info, claimAllowance, verifySuiteBeforeClaim, onEvaluated)
        }
        shadowProcessBuildRequest = null
    }

    // The shared watcher supplies the observations; this service owns the bounded actions. The
    // ledger is written before each callback, so a broken GitHub/buildtest dependency cannot turn
    // the one-minute reconciliation and watchdog callbacks into an endless dispatch/redrive loop.
    val watchmanReconciler = BuildWatchmanReconciler(
        listOpenPullRequests = { listAllOpenPullRequests() },
        createPullRequestWatcher = { pullRequest, emit ->
            createPullRequestWatchman(
                pullRequest = pullRequest,
                installationToken = GitHubApi::getInstallationToken,
                clock = watchmanClock,
                emit = emit
            )
        },
        createBuildtestWatcher = { build, emit ->
            BuildtestRunWatchman(
                runId = build.remoteRunId,
                clock = watchmanClock,
                emit = emit
            )
        },
        rerequestCheckSuite = { finding ->
            rerequestCheckSuiteForWatchman(finding)
        },
        dispatchPullRequestHead = { finding ->
            processBuildRequestIfMissing(
                WebhookBuildInfo(
                    headSha = finding.headSha,
                    owner = finding.owner,
                    repo = finding.repo,
                    installationId = finding.installationId,
                    prNumbers = listOf(finding.prNumber),
                    headBranch = finding.headBranch,
                    pullRequestAssociationCount = 1
                )
            )
        },
        dispatchPullRequestHeadResultWithClaim = { finding, claimAllowance, verifySuiteBeforeClaim, onEvaluated ->
            processBuildRequestIfMissingWithCompletion(
                WebhookBuildInfo(
                    headSha = finding.headSha,
                    owner = finding.owner,
                    repo = finding.repo,
                    installationId = finding.installationId,
                    prNumbers = listOf(finding.prNumber),
                    headBranch = finding.headBranch,
                    pullRequestAssociationCount = 1
                ),
                claimAllowance,
                verifySuiteBeforeClaim,
                onEvaluated
            )
        },
        isLostDispatchStillUnstarted = { finding, expectedProvenance ->
            verifyCheckSuiteStillUndispatchedForWatchman(finding, expectedProvenance)
        },
        readLostDispatchProvenance = { pullRequest, finding ->
            readLostDispatchProvenanceForWatchman(pullRequest, finding)
        },
        forceFailAndRedrive = { finding, reason ->
            forceFailAndRedriveWatchman(
                finding = finding,
                reason = reason,
                tracker = tracker,
                buildRunner = buildRunner,
                startReplacement = { current, findingReason ->
                    buildRunner.redriveStalledBuild(current, findingReason)
                }
            )
        },
        reenqueueMergeQueue = { finding, reason ->
            reenqueueMergeQueueForWatchman(finding, reason)
        },
        ledger = watchmanLedger
    )

    // GitHub is authoritative for which required checks are still waiting; the tracker is
    // authoritative for which builds this process is driving. The alignment sweep compares the two
    // on a bounded watched-commit set (open PR heads, merge-queue refs, newest default-branch
    // commits), including default-branch pushes that no other reconciler watches, and gives each
    // stale waiting check one disposition per durable allowance in the watcher ledger. Every action
    // runs under the primary handler's per-head reservation. Disabled by ALIGNMENT_SWEEP_MINUTES=0.
    val alignmentSweep = if (alignmentSweepConfiguration.intervalMinutes > 0L) {
        GitHubAlignmentSweep(
            configuration = alignmentSweepConfiguration,
            tracker = tracker,
            buildRunner = buildRunner,
            buildTestApi = buildTestApi,
            ledger = watchmanLedger,
            journal = activityJournal,
            listRepositories = { listAllAlignmentRepositories() },
            listOpenPullRequests = { owner, repo, installationId ->
                listOpenPullRequestsForRepo(owner, repo, installationId).map { (number, headSha, headRef) ->
                    OpenPullRequest(owner, repo, number, headSha, installationId, headRef)
                }
            },
            listMergeQueueRefs = { owner, repo, installationId -> listMergeQueueRefs(owner, repo, installationId) },
            listDefaultBranchCommits = { owner, repo, installationId, branch, count ->
                listAlignmentDefaultBranchCommits(owner, repo, installationId, branch, count)
            },
            listCheckRuns = { owner, repo, installationId, headSha ->
                listAlignmentCheckRunsForCommit(owner, repo, installationId, headSha)
            },
            getCheckRun = { owner, repo, installationId, checkRunId ->
                getAlignmentCheckRun(owner, repo, installationId, checkRunId)
            },
            completeUntrackedCheckRun = { owner, repo, installationId, checkRunId, conclusion, summary ->
                GitHubChecks.completeCheckRunVerified(
                    owner = owner,
                    repo = repo,
                    installationId = installationId,
                    checkRunId = checkRunId,
                    conclusion = conclusion,
                    summary = summary,
                    detailsUrl = buildDetailsUrl(
                        runId = null,
                        target = buildServiceRoute.target,
                        serviceUrl = buildServiceRoute.kompileRemoteBuildServiceUrl
                    )
                )
            },
            tryWithHeadReservation = { owner, repo, headSha, installationId, action ->
                primaryWebhookEventHandler.tryWithHeadReservation(owner, repo, headSha, installationId, action)
            },
            appId = GitHubApi.getAppId(),
            admissionControl = admissionControl,
            buildServiceRouteTarget = buildServiceRoute.target,
            clock = watchmanClock
        )
    } else {
        null
    }

    // Add the webhook servlet now that BuildRunner is ready. The servlet ACKs 200 immediately and
    // processes the event on a background executor, so handling latency never races GitHub's ~10s
    // webhook delivery timeout (a timed-out delivery is dropped and never automatically redelivered).
    context.addServlet(
        ServletHolder(WebhookServlet(handler = webhookHandler, journal = activityJournal)),
        "/webhook"
    )

    // The operations control surface: what is running, what happened, and the actions an operator
    // takes during an incident. Reads are open; every write carries CI_CONTROL_TOKEN.
    val controlService = CiControlService(
        tracker = tracker,
        journal = activityJournal,
        admissionControl = admissionControl,
        buildRunner = buildRunner,
        clock = watchmanClock,
        routeDescription = when (buildServiceRoute.target) {
            BuildServiceRouteTarget.DIRECT_BUILDTEST -> "direct-buildtest"
            BuildServiceRouteTarget.KOMPILE_REMOTE_BUILD -> "kompile-remote-build"
        },
        shadowRouteDescription = shadowRouteDescription,
        onDispatchBuild = processBuildRequest,
        alignmentSweepStatus = alignmentSweep?.let { sweep -> { sweep.status() } }
    )
    context.addServlet(ServletHolder(CiControlApiServlet(controlService)), "/api/*")

    // Resume builds persisted from a previous run
    if (!tracker.isEmpty()) {
        println("Found ${tracker.size()} build(s) persisted from previous run")
        if (watchdog != null) {
            watchdog.ensureCronJobExists()
        }
        if (buildRunner.canRunBuilds()) {
            buildRunner.recoverPersistedDeferredBuilds()
            for (build in tracker.getAllBuilds()) {
                if (build.future == null) {
                    println("Resuming build ${build.checkRunId} for ${build.owner}/${build.repo} @ ${build.headSha}")
                    buildRunner.resumeBuild(build)
                }
            }
        }
    }

    if (shadowTracker != null && shadowBuildRunner != null && !shadowTracker.isEmpty()) {
        println("Found ${shadowTracker.size()} shadow build(s) persisted from previous run")
        if (watchdog != null) {
            watchdog.ensureCronJobExists()
        }
        if (shadowBuildRunner.canRunBuilds()) {
            shadowBuildRunner.recoverPersistedDeferredBuilds()
            for (build in shadowTracker.getAllBuilds()) {
                if (build.future == null) {
                    println("Resuming shadow build ${build.checkRunId} for ${build.owner}/${build.repo} @ ${build.headSha}")
                    shadowBuildRunner.resumeBuild(build)
                }
            }
        }
    }

    if (watchdog != null) {
        val checkBuildsServlet = if (shadowTracker == null) {
            CheckBuildsServlet(
                tracker = tracker,
                watchdog = watchdog,
                buildTimeoutMinutes = buildTimeoutMinutes,
                buildRunner = buildRunner,
                clock = watchmanClock
            )
        } else {
            CheckBuildsServlet(
                tracker = tracker,
                watchdog = watchdog,
                buildTimeoutMinutes = buildTimeoutMinutes,
                buildRunner = buildRunner,
                shadowTracker = shadowTracker,
                shadowBuildRunner = shadowBuildRunner,
                clock = watchmanClock
            )
        }
        context.addServlet(ServletHolder(checkBuildsServlet), "/check-builds")
    }

    // Build-watchman run assessment is an always-on service responsibility. It is deliberately
    // separate from /check-builds: WebCron is optional, and a missing callback must not also stop
    // stall detection for runs this process is actively orchestrating. A single scheduler owns
    // the mutable watcher instances so a watchdog callback cannot assess the same watcher at the
    // same time. Set WATCHMAN_RUN_POLL_MINUTES=0 only for an explicitly disabled deployment.
    val watchmanRunPollMinutes = System.getenv("WATCHMAN_RUN_POLL_MINUTES")?.toLongOrNull() ?: 1L
    val watchmanRunScheduler = if (watchmanRunPollMinutes > 0L) {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "build-watchman-run-reconciler").apply { isDaemon = true }
        }.also { scheduler ->
            scheduler.scheduleWithFixedDelay({
                try {
                    val watchableBuilds = tracker.getAllBuilds().mapNotNull { build ->
                        build.activeRemoteRunId()?.let { runId ->
                            WatchmanTrackedBuild(
                                owner = build.owner,
                                repo = build.repo,
                                installationId = build.installationId,
                                checkRunId = build.checkRunId,
                                remoteRunId = runId,
                                prNumber = build.prNumbers.firstOrNull(),
                                headSha = build.headSha
                            )
                        }
                    }
                    watchmanReconciler.assessTrackedBuilds(watchableBuilds)
                } catch (e: Exception) {
                    println("Build-watchman run assessment failed: ${e.message}")
                    e.printStackTrace()
                }
            }, 0L, watchmanRunPollMinutes, java.util.concurrent.TimeUnit.MINUTES)
        }
    } else {
        null
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        watchmanRunScheduler?.shutdownNow()
    })
    if (watchmanRunScheduler != null) {
        println("Build-watchman tracked-run stall watching enabled (every " +
            "$watchmanRunPollMinutes minute(s), independent of WebCron)")
    } else {
        println("Build-watchman tracked-run stall watching disabled (WATCHMAN_RUN_POLL_MINUTES=0)")
    }

    if (alignmentSweep != null) {
        val alignmentScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "github-alignment-sweep").apply { isDaemon = true }
        }
        // The first pass waits one interval so startup resume and the startup reconciler run first.
        alignmentScheduler.scheduleWithFixedDelay({
            try {
                alignmentSweep.runPass()
            } catch (e: Exception) {
                println("GitHub-alignment sweep pass failed: ${e.message}")
                e.printStackTrace()
            }
        }, alignmentSweepConfiguration.intervalMinutes, alignmentSweepConfiguration.intervalMinutes,
            java.util.concurrent.TimeUnit.MINUTES)
        Runtime.getRuntime().addShutdownHook(Thread { alignmentScheduler.shutdownNow() })
        println(
            "GitHub-alignment sweep enabled (every ${alignmentSweepConfiguration.intervalMinutes} minute(s), " +
                "age gate ${alignmentSweepConfiguration.ageGateMinutes} minute(s), " +
                "${alignmentSweepConfiguration.maxRepositoriesPerPass} repositories and " +
                "${alignmentSweepConfiguration.maxPerRepository} checks per repository per pass, " +
                "newest ${alignmentSweepConfiguration.defaultBranchCommits} default-branch commits)"
        )
    } else {
        println("GitHub-alignment sweep disabled (ALIGNMENT_SWEEP_MINUTES=0)")
    }

    println("kotlin.build CI server fully initialized")

    // Reconcile local tracker against GitHub (authoritative state) in a background thread.
    // Uses the event log to identify builds that were started but never completed ("dirty"),
    // then checks only those specific check runs on GitHub (typically 0-3 API calls).
    // Runs after server.start() so the health endpoint is immediately available.
    Thread {
        try {
            val reconciler = CheckRunReconciler(
                tracker = tracker,
                buildTimeoutMinutes = buildTimeoutMinutes,
                eventLog = eventLog,
                getCheckRun = { owner, repo, installationId, checkRunId ->
                    GitHubChecks.getCheckRun(owner, repo, installationId, checkRunId)
                },
                failCheckRun = { owner, repo, installationId, checkRunId, summary ->
                    GitHubChecks.completeCheckRunVerified(
                        owner = owner,
                        repo = repo,
                        installationId = installationId,
                        checkRunId = checkRunId,
                        conclusion = "failure",
                        summary = summary,
                        detailsUrl = buildDetailsUrl(
                            runId = null,
                            target = buildServiceRoute.target,
                            serviceUrl = buildServiceRoute.kompileRemoteBuildServiceUrl
                        )
                    )
                },
                repoTimeoutOverrides = buildRunner.repoTimeoutOverrides
            )

            println("Running check run reconciliation...")
            val reconciled = reconciler.reconcile()
            if (reconciled > 0) {
                println("Reconciliation: failed $reconciled orphaned check run(s)")
            }
        } catch (e: Exception) {
            println("Warning: Check run reconciliation failed: ${e.message}")
            e.printStackTrace()
        }
    }.apply {
        isDaemon = true
        name = "check-run-reconciler"
        start()
    }

    // Shadow state has its own event log and tracker, so a restart or a lost GitHub completion
    // cannot be repaired by the primary reconciler. Reconcile it independently and report through
    // the shadow check name; failures in this daemon are logged without affecting the primary one.
    if (shadowTracker != null && shadowEventLog != null && shadowBuildRunner != null) {
        Thread {
            try {
                val shadowReconciler = CheckRunReconciler(
                    tracker = shadowTracker,
                    buildTimeoutMinutes = buildTimeoutMinutes,
                    eventLog = shadowEventLog,
                    getCheckRun = { owner, repo, installationId, checkRunId ->
                        GitHubChecks.getCheckRun(owner, repo, installationId, checkRunId)
                    },
                    failCheckRun = { owner, repo, installationId, checkRunId, summary ->
                        GitHubChecks.completeCheckRunVerified(
                            owner = owner,
                            repo = repo,
                            installationId = installationId,
                            checkRunId = checkRunId,
                            conclusion = "failure",
                            summary = appendShadowRouteNotice(summary, requireNotNull(shadowRouteDescription)),
                            detailsUrl = buildDetailsUrl(
                                runId = null,
                                target = requireNotNull(shadowBuildServiceRoute).target,
                                serviceUrl = requireNotNull(shadowBuildServiceRoute).kompileRemoteBuildServiceUrl
                            ),
                            checkRunName = KOMPILE_REMOTE_BUILD_CHECK_RUN_NAME
                        )
                    },
                    repoTimeoutOverrides = shadowBuildRunner.repoTimeoutOverrides
                )

                println("Running shadow check run reconciliation...")
                val reconciled = shadowReconciler.reconcile()
                if (reconciled > 0) {
                    println("Shadow reconciliation: failed $reconciled orphaned check run(s)")
                }
            } catch (e: Exception) {
                println("Warning: shadow check run reconciliation failed: ${e.message}")
                e.printStackTrace()
            }
        }.apply {
            isDaemon = true
            name = "shadow-check-run-reconciler"
            start()
        }
    }

    // Periodically self-heal dropped webhook deliveries. GitHub does not redeliver a webhook whose
    // delivery timed out (>10s), and this app is triggered by push/check_suite events — not
    // pull_request — so a single dropped trigger delivery leaves a PR permanently without a
    // "kotlin.build (remote)" check (exactly what happened to ContainerNursery PR #474 on
    // 2026-06-29). The primary route now uses the shared watcher to reassess open PRs and queue refs;
    // it directly dispatches a queued zero-run open-PR head after the watcher's 60-second grace,
    // while a synthetic queue-ref suite retains its suite-rerequest path. The default cadence is
    // one minute and the first pass runs immediately. Disabled by RECONCILE_POLL_MINUTES=0.
    val reconcilePollMinutes = System.getenv("RECONCILE_POLL_MINUTES")?.toLongOrNull() ?: 1L
    // The legacy shadow-route reconciler still creates real backfill builds, so its per-pass cap
    // remains configured separately below. The primary watcher uses durable per-key allowances.
    val reconcileMaxPerPass = parseReconcileMaxPerPass(System.getenv())
    if (reconcilePollMinutes > 0) {
        val shadowMissingCheckRunReconciler = if (
            shadowBuildServiceRoute != null && shadowProcessBuildRequest != null
        ) {
            MissingCheckRunReconciler(
                listOpenPullRequests = { listAllOpenPullRequests() },
                listMergeQueueRefs = { owner, repo, installationId ->
                    listMergeQueueRefs(owner, repo, installationId)
                },
                listCheckRunNamesForRef = { owner, repo, installationId, headSha ->
                    listCheckRunNamesForRef(owner, repo, installationId, headSha)
                },
                listCheckSuitesForRef = { owner, repo, installationId, headSha ->
                    listCheckSuitesForRef(owner, repo, installationId, headSha)
                },
                isAlreadyHandled = { request ->
                    requireNotNull(shadowTracker).getAllBuilds().any { trackedShadowBuildMatches(it, request) } ||
                        shadowDispatchPending(request)
                },
                backfill = { request ->
                    requireNotNull(shadowProcessBuildRequest)(
                        WebhookBuildInfo(
                            headSha = request.headSha,
                            owner = request.owner,
                            repo = request.repo,
                            installationId = request.installationId,
                            prNumbers = request.prNumbers,
                            headBranch = request.headBranch
                        )
                    )
                },
                checkRunName = KOMPILE_REMOTE_BUILD_CHECK_RUN_NAME,
                requireEmptyCheckSuite = false,
                prerequisiteCheckRunName = REMOTE_CHECK_RUN_NAME,
                maxBackfillsPerPass = reconcileMaxPerPass
            )
        } else {
            null
        }
        val reconcileScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "build-watchman-open-pr-reconciler").apply { isDaemon = true }
        }
        reconcileScheduler.scheduleWithFixedDelay({
            try {
                watchmanReconciler.reconcileOpenPullRequests()
            } catch (e: Exception) {
                println("Build-watchman reconciliation failed: ${e.message}")
                e.printStackTrace()
            }
        }, 0L, reconcilePollMinutes, java.util.concurrent.TimeUnit.MINUTES)
        // A shadow back-fill performs its own synchronous check creation before it submits the
        // build. Keep that work off the primary reconciliation scheduler so a slow or unreachable
        // shadow route cannot delay primary missing-check recovery.
        val shadowReconcileScheduler = shadowMissingCheckRunReconciler?.let {
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "shadow-missing-check-run-reconciler").apply { isDaemon = true }
            }
        }
        shadowReconcileScheduler?.scheduleWithFixedDelay({
            try {
                requireNotNull(shadowMissingCheckRunReconciler).reconcile()
            } catch (e: Exception) {
                println("Shadow missing-check-run reconciliation failed: ${e.message}")
                e.printStackTrace()
            }
        }, 0L, reconcilePollMinutes, java.util.concurrent.TimeUnit.MINUTES)
        Runtime.getRuntime().addShutdownHook(Thread {
            reconcileScheduler.shutdownNow()
            shadowReconcileScheduler?.shutdownNow()
        })
        println("Build-watchman open-PR and merge-queue reconciliation enabled (every " +
            "$reconcilePollMinutes minute(s), one durable remediation allowance per PR head, " +
            "queue suite, run, or queue ref)")
    } else {
        println("Missing-check-run reconciliation poll disabled (RECONCILE_POLL_MINUTES=0)")
    }

    // Recover delivery types that the state-based reconciler cannot reconstruct, including
    // default-branch pushes, issue-comment commands, and pull-request closure events.
    val redeliverySweepMinutes = redeliveryConfiguration.sweepMinutes
    val redeliveryLookbackMinutes = redeliveryConfiguration.lookbackMinutes
    val redeliveryMaxPerSweep = redeliveryConfiguration.maxPerSweep
    if (redeliverySweepMinutes > 0) {
        val appClient = GitHubApi.getAppClient()
        val redeliverySweep = GitHubWebhookRedeliverySweep(
            client = appClient.client,
            createAppJwt = { GitHubApi.getAppClient().token },
            lookbackMinutes = redeliveryLookbackMinutes,
            maxPerSweep = redeliveryMaxPerSweep
        )
        val redeliverySchedulerStarted = GitHubWebhookRedeliveryScheduler(
            sweep = redeliverySweep,
            intervalMinutes = redeliverySweepMinutes,
            leaderLockFile = File(dataDir, "webhook-redelivery-sweep.lock")
        ).start()
        if (redeliverySchedulerStarted) {
            println(
                "GitHub webhook redelivery sweep enabled (every $redeliverySweepMinutes minute(s), " +
                    "lookback=$redeliveryLookbackMinutes minute(s), max=$redeliveryMaxPerSweep per sweep)"
            )
        } else {
            println("GitHub webhook redelivery sweep is owned by another service instance sharing DATA_DIR=$dataDir")
        }
    } else {
        println("GitHub webhook redelivery sweep disabled (REDELIVERY_SWEEP_MINUTES=$redeliverySweepMinutes)")
    }

    server.join()
}

/**
 * Deletes orphaned `bootstrap-workspace-*.tar.gz` files from [dataDir].
 *
 * These files are created during bootstrap builds and are normally cleaned up
 * in a `finally` block. However, if the process crashes (e.g., OOM / GC death
 * spiral), the files persist and accumulate on disk. Each file is 100+ MB, so
 * orphans contribute significantly to memory pressure on the next run.
 *
 * A file is considered orphaned if there is no active build in the tracker
 * whose checkRunId matches the filename pattern `bootstrap-workspace-<checkRunId>.tar.gz`.
 *
 * @return the number of files successfully deleted
 */
fun cleanupOrphanedBootstrapTarballs(
    dataDir: String,
    tracker: InProgressBuildTracker,
    additionalTrackers: Collection<InProgressBuildTracker> = emptyList()
): Int {
    val dir = File(dataDir)
    if (!dir.isDirectory) return 0

    val activeCheckRunIds = (listOf(tracker) + additionalTrackers)
        .flatMap { it.getAllBuilds() }
        .map { it.checkRunId }
        .toSet()
    val bootstrapFiles = dir.listFiles { file ->
        file.name.startsWith("bootstrap-workspace-") && file.name.endsWith(".tar.gz")
    } ?: return 0

    var deletedCount = 0
    for (file in bootstrapFiles) {
        val checkRunIdStr = file.name
            .removePrefix("bootstrap-workspace-")
            .removeSuffix(".tar.gz")
            .removeSuffix(".complete")
        val checkRunId = checkRunIdStr.toLongOrNull()

        if (checkRunId == null || checkRunId !in activeCheckRunIds) {
            val sizeMb = file.length() / (1024 * 1024)
            if (file.delete()) {
                toss(OrphanedTarballCleanedUpEffect(file.name, sizeMb))
                deletedCount++
            } else {
                toss(OrphanedTarballDeletionFailedEffect(file.name, sizeMb))
            }
        }
    }
    return deletedCount
}

/**
 * Cleans up stale `tarball-*.tar.gz` temp files from the system temp directory.
 *
 * [BuildRunner.downloadGitHubTarball] creates temp files with prefix `tarball-<repoName>-`
 * in the system temp directory, each accompanied by a `.lock` file held via
 * [FileBasedProcessLock]. When the owning process dies, the OS automatically releases the
 * NIO file lock but leaves both files on disk.
 *
 * For each tarball file found, this function attempts to acquire the companion `.lock` file:
 * - **Lock acquired**: the original owner is dead — the tarball is stale. Both the tarball
 *   and the lock file are deleted.
 * - **Lock held** ([ProcessLockException]): another process still owns the tarball — it is
 *   skipped. This makes multi-instance deployments safe: a restarting instance will not
 *   delete temp files owned by a running sibling.
 *
 * Orphaned `.lock` files (no corresponding `.tar.gz`) are also cleaned up.
 *
 * @return the number of tarball files successfully deleted
 */
fun cleanupStaleTarballTempFiles(tmpDir: File = File(System.getProperty("java.io.tmpdir"))): Int {
    val staleFiles = tmpDir.listFiles { _, name ->
        name.startsWith("tarball-") && name.endsWith(".tar.gz")
    } ?: return 0

    if (staleFiles.isEmpty()) {
        // Still clean up orphaned .lock files even when no .tar.gz files remain
        cleanupOrphanedTarballLockFiles(tmpDir)
        return 0
    }

    var deletedCount = 0
    for (file in staleFiles) {
        val sizeMb = file.length() / (1024 * 1024)
        val lockFile = File(file.absolutePath + ".lock")

        try {
            // Try to acquire the lock. If this succeeds, the original owner is dead
            // (the OS released the NIO lock when the process exited).
            FileBasedProcessLock.acquire(lockFile)
        } catch (e: ProcessLockException) {
            // Lock is held by another running process — skip this file
            toss(StaleTarballTempFileSkippedEffect(file.name, sizeMb))
            continue
        }

        // Lock acquired — file is stale. Delete the tarball and release the lock.
        // Use try/finally to guarantee the lock is released even if file.delete()
        // fails or an exception occurs during algebraic effect emission.
        try {
            if (file.delete()) {
                toss(StaleTarballTempFileCleanedUpEffect(file.name, sizeMb))
                deletedCount++
            } else {
                toss(StaleTarballTempFileDeletionFailedEffect(file.name, sizeMb))
            }
        } finally {
            FileBasedProcessLock.release(lockFile)
        }
    }

    // Clean up orphaned .lock files whose .tar.gz was already deleted
    cleanupOrphanedTarballLockFiles(tmpDir)

    return deletedCount
}

/**
 * Cleans up orphaned `.lock` files in the temp directory whose corresponding
 * `tarball-*.tar.gz` file no longer exists. These can accumulate when a tarball
 * is deleted but the lock release fails, or when the process crashes after
 * deleting the tarball but before releasing the lock.
 */
private fun cleanupOrphanedTarballLockFiles(tmpDir: File) {
    val lockFiles = tmpDir.listFiles { _, name ->
        name.startsWith("tarball-") && name.endsWith(".tar.gz.lock")
    } ?: return

    for (lockFile in lockFiles) {
        val tarballPath = lockFile.absolutePath.removeSuffix(".lock")
        val tarballFile = File(tarballPath)
        if (!tarballFile.exists()) {
            // Tarball is gone — try to acquire and release the lock to clean it up
            try {
                FileBasedProcessLock.acquire(lockFile)
                FileBasedProcessLock.release(lockFile)
            } catch (e: ProcessLockException) {
                // Lock is held by another process — leave it alone
            }
        }
    }
}
