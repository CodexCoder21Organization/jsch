package org.example.ci

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

val RETRIGGER_KEYWORDS = listOf("/rerun", "/retry", "/retrigger")
val BYPASS_KEYWORDS = listOf("/bypass")

const val REMOTE_CHECK_RUN_NAME = "kotlin.build (remote)"
const val KOMPILE_REMOTE_BUILD_CHECK_RUN_NAME = "kotlin.build (kompile-remote-build)"
const val KOMPILE_REMOTE_BUILD_DASHBOARD_URL = "https://kompile-build-ci.kotlin.build"

fun shadowRouteNotice(routeDescription: String): String =
    "Informational check: this check exercised the '$routeDescription' route. " +
        "Its result is informational and does not gate branch protection.\n\n" +
        "Dashboard: $KOMPILE_REMOTE_BUILD_DASHBOARD_URL"

fun appendShadowRouteNotice(summary: String, routeDescription: String): String =
    "${shadowRouteNotice(routeDescription)}\n\n$summary"

data class WebhookBuildInfo(
    val headSha: String,
    val owner: String,
    val repo: String,
    val installationId: Long,
    val prNumbers: List<Int>,
    val headBranch: String?,
    // Only populated for a check_run.rerequested event. The ID lets the handler distinguish a
    // real rerequest of an in-flight check from a duplicate delivery for the same commit.
    val rerequestedCheckRunId: Long? = null,
    // Null means the payload did not contain a pull_requests array. A non-null value preserves
    // whether GitHub supplied an empty or non-empty association array, even when its entries were
    // malformed and could not be added to prNumbers.
    val pullRequestAssociationCount: Int? = null,
    val checkpointClassificationDetail: String? = null
)

fun parseCheckSuitePayload(payload: Map<String, Any?>): WebhookBuildInfo? =
    try { parseAuthoritativeCheckSuiteEvent(payload) } catch (_: IllegalArgumentException) { null }

private fun parseAuthoritativeCheckSuiteEvent(payload: Map<String, Any?>): WebhookBuildInfo {
    fun invalid(field: String, expected: String, actual: Any?): Nothing =
        throw IllegalArgumentException("expected $field ($expected), actual $actual")
    fun text(field: String, value: Any?): String =
        (value as? String)?.takeIf { it.isNotBlank() } ?: invalid(field, "non-blank string", value)
    fun positiveInteger(value: Any?, max: Long = Long.MAX_VALUE): Boolean =
        value is Number && value.toString().toBigDecimalOrNull()?.let {
            it > java.math.BigDecimal.ZERO && it <= java.math.BigDecimal.valueOf(max) &&
                it.stripTrailingZeros().scale() <= 0
        } == true
    val suite = payload["check_suite"] as? Map<*, *>
        ?: invalid("check_suite", "object", payload["check_suite"])
    val repository = payload["repository"] as? Map<*, *>
    val owner = text("repository.owner.login", (repository?.get("owner") as? Map<*, *>)?.get("login"))
    val repo = text("repository.name", repository?.get("name"))
    val installation = (payload["installation"] as? Map<*, *>)?.get("id")
    if (!positiveInteger(installation)) invalid("installation.id", "positive integer", installation)
    val sha = text("check_suite.head_sha", suite["head_sha"])
    val branch = text("check_suite.head_branch", suite["head_branch"])
    val associations = suite["pull_requests"]
    if (associations !is List<*> || associations.any {
        it !is Map<*, *> || !positiveInteger(it["number"], Int.MAX_VALUE.toLong())
    }) invalid("check_suite.pull_requests", "array of objects with positive integral number", associations)
    return WebhookBuildInfo(
        sha, owner, repo, (installation as Number).toLong(),
        associations.map { ((it as Map<*, *>)["number"] as Number).toInt() }, branch,
        pullRequestAssociationCount = associations.size
    )
}

fun parseCheckRunPayload(payload: Map<String, Any?>): WebhookBuildInfo? {
    val checkRun = payload["check_run"] as? Map<*, *> ?: return null
    val headSha = checkRun["head_sha"] as? String ?: return null
    val rerequestedCheckRunId = (checkRun["id"] as? Number)?.toLong()

    val repo = payload["repository"] as? Map<*, *> ?: return null
    val owner = (repo["owner"] as? Map<*, *>)?.get("login") as? String ?: return null
    val repoName = repo["name"] as? String ?: return null

    val installation = payload["installation"] as? Map<*, *> ?: return null
    val installationId = (installation["id"] as? Number)?.toLong() ?: return null

    val checkSuite = checkRun["check_suite"] as? Map<*, *>
    val pullRequestAssociationCount = (checkSuite?.get("pull_requests") as? List<*>)?.size
    val prNumbers = mutableListOf<Int>()
    val pullRequests = (checkSuite?.get("pull_requests") as? List<*>) ?: emptyList<Any>()
    for (pr in pullRequests) {
        val prMap = pr as? Map<*, *> ?: continue
        val prNumber = (prMap["number"] as? Number)?.toInt() ?: continue
        prNumbers.add(prNumber)
    }

    val headBranch = checkSuite?.get("head_branch") as? String

    return WebhookBuildInfo(
        headSha = headSha,
        owner = owner,
        repo = repoName,
        installationId = installationId,
        prNumbers = prNumbers,
        headBranch = headBranch,
        rerequestedCheckRunId = rerequestedCheckRunId,
        pullRequestAssociationCount = pullRequestAssociationCount
    )
}

private fun skipCheckpointBranchIfNeeded(
    info: WebhookBuildInfo,
    journal: CiActivityJournal?,
    triggerDescription: String
): Boolean {
    if (info.pullRequestAssociationCount != 0 || info.headBranch?.startsWith("wip/") != true) return false

    println(
        "Ignoring $triggerDescription for non-PR checkpoint branch " +
            "${info.owner}/${info.repo}:${info.headBranch} @ ${info.headSha}"
    )
    journal?.record(
        type = CiActivityType.BUILD_SKIPPED,
        owner = info.owner,
        repo = info.repo,
        prNumbers = info.prNumbers,
        headSha = info.headSha,
        headBranch = info.headBranch,
        outcome = CiActivityOutcome.CHECKPOINT_BRANCH,
        detail = "The non-PR branch '${info.headBranch}' uses the wip/ checkpoint prefix, " +
            "so this $triggerDescription started no build." +
            (info.checkpointClassificationDetail?.let { " $it" } ?: "")
    )
    return true
}

/**
 * Records one authenticated delivery in [journal], naming whatever the payload identifies.
 *
 * Deliberately tolerant: a delivery is recorded even when its payload carries no repository, commit
 * or pull request, because the fact that GitHub sent something is itself the observation an operator
 * comes looking for after a suspected lost trigger. Nothing here may throw — this runs on the
 * request thread that must answer GitHub inside its ten-second budget.
 */
internal fun recordDelivery(
    journal: CiActivityJournal,
    eventType: String?,
    deliveryId: String?,
    payload: Map<String, Any?>
) {
    try {
        val repository = payload["repository"] as? Map<*, *>
        val owner = (repository?.get("owner") as? Map<*, *>)?.get("login") as? String
        val repoName = repository?.get("name") as? String
        val checkSuite = payload["check_suite"] as? Map<*, *>
        val checkRun = payload["check_run"] as? Map<*, *>
        val pullRequest = payload["pull_request"] as? Map<*, *>
        val issue = payload["issue"] as? Map<*, *>
        val headSha = checkSuite?.get("head_sha") as? String
            ?: checkRun?.get("head_sha") as? String
            ?: (pullRequest?.get("head") as? Map<*, *>)?.get("sha") as? String
            ?: payload["after"] as? String
        val headBranch = checkSuite?.get("head_branch") as? String
            ?: (checkRun?.get("check_suite") as? Map<*, *>)?.get("head_branch") as? String
            ?: (payload["ref"] as? String)?.removePrefix("refs/heads/")
        val prNumbers = buildList {
            (pullRequest?.get("number") as? Number)?.toInt()?.let { add(it) }
            (issue?.get("number") as? Number)?.toInt()?.let { add(it) }
            val suitePullRequests = (checkSuite?.get("pull_requests")
                ?: (checkRun?.get("check_suite") as? Map<*, *>)?.get("pull_requests")) as? List<*>
            suitePullRequests?.forEach { entry ->
                (entry as? Map<*, *>)?.get("number")?.let { number ->
                    (number as? Number)?.toInt()?.let { add(it) }
                }
            }
        }.distinct()
        val sender = (payload["sender"] as? Map<*, *>)?.get("login") as? String
        val action = payload["action"] as? String
        journal.record(
            type = CiActivityType.WEBHOOK_RECEIVED,
            owner = owner.orEmpty(),
            repo = repoName.orEmpty(),
            prNumbers = prNumbers,
            headSha = headSha,
            headBranch = headBranch,
            checkRunId = (checkRun?.get("id") as? Number)?.toLong(),
            deliveryId = deliveryId,
            eventType = eventType,
            action = action,
            actor = sender.orEmpty(),
            detail = buildString {
                append("GitHub delivered ")
                append(eventType ?: "an unnamed event")
                action?.let { append(".$it") }
                if (!owner.isNullOrEmpty() && !repoName.isNullOrEmpty()) append(" for $owner/$repoName")
                append(".")
            }
        )
    } catch (error: Exception) {
        // A delivery that cannot be described is still a delivery worth answering. Recording it is a
        // diagnostic nicety; failing the webhook over it would cost the build the delivery triggers.
        println("Could not record webhook delivery '$eventType' in the activity journal: ${error.message}")
    }
}

fun findRetriggerKeyword(commentBody: String): String? {
    val normalized = commentBody.trim().lowercase()
    return RETRIGGER_KEYWORDS.find { keyword -> commentInvokesCommand(normalized, keyword) }
}

fun findBypassKeyword(commentBody: String): String? {
    val normalized = commentBody.trim().lowercase()
    return BYPASS_KEYWORDS.find { keyword -> commentInvokesCommand(normalized, keyword) }
}

/**
 * Returns true when [normalized] (an already trimmed, lowercased comment body) invokes [keyword] as
 * its leading command token. The keyword may be followed by an arbitrary motivation/reason — for
 * example "/retry — droplet was overloaded", "/retry: flaky infra", or "/bypass, docs only" — as
 * long as the keyword is a complete token: either the entire comment, or immediately followed by a
 * non-alphanumeric character. A trailing alphanumeric character means the keyword is merely the
 * prefix of a longer word (e.g. "/retrying", "/rerunning") and is intentionally not treated as a
 * command.
 */
fun commentInvokesCommand(normalized: String, keyword: String): Boolean {
    if (!normalized.startsWith(keyword)) return false
    if (normalized.length == keyword.length) return true
    return !normalized[keyword.length].isLetterOrDigit()
}

/**
 * Handles a single decoded GitHub webhook event. Implementations perform the (potentially slow,
 * network-bound) work of creating check runs and triggering builds. This is a seam so the
 * [WebhookServlet] can hand processing to a background executor — and so the same processing path
 * can be reused by the [MissingCheckRunReconciler] when it back-fills a dropped webhook delivery.
 */
fun interface WebhookHandler {
    fun handle(eventType: String, payload: Map<String, Any?>)
}

/** Delivery identity and durable callbacks owned by the servlet's journal. */
data class RerequestDelivery(
    val id: String,
    val startAction: () -> Boolean,
    val complete: (PullRequestHeadDispatchResult) -> Unit
)

/** One evaluation callback follows an operation through immediate execution or FIFO parking. */
private class HeadDispatchCompletion(
    private val callback: (PullRequestHeadDispatchResult) -> Unit,
    private val onJoined: () -> Unit = { }
) {
    var ownedByCoordinator = false
    private val completed = AtomicBoolean(false)

    fun evaluated(result: PullRequestHeadDispatchResult) {
        if (completed.compareAndSet(false, true)) callback(result)
    }

    fun joined() {
        // This invocation owns no queued work. Its reservation can end, but only the retained
        // operation may evaluate the delivery, start a shadow route, or record completion.
        if (completed.compareAndSet(false, true)) onJoined()
    }
}

interface ResultWebhookHandler : WebhookHandler {
    fun handleDelivery(eventType: String, payload: Map<String, Any?>, delivery: RerequestDelivery?): PullRequestHeadDispatchResult {
        val result = handleWithResult(eventType, payload)
        if (result != PullRequestHeadDispatchResult.UNVERIFIED) delivery?.complete?.invoke(result)
        return result
    }

    fun handleWithResult(eventType: String, payload: Map<String, Any?>): PullRequestHeadDispatchResult

    override fun handle(eventType: String, payload: Map<String, Any?>) {
        handleWithResult(eventType, payload)
    }
}

/**
 * Builds a default daemon thread pool for processing webhook events off the Jetty request thread.
 * The pool is bounded so a burst of webhooks cannot spawn unbounded threads; excess events queue
 * and are processed as workers free up. Because the servlet already returns 200 before processing,
 * queueing does not race GitHub's webhook delivery timeout.
 */
fun newWebhookProcessingExecutor(threads: Int = 16): ExecutorService =
    Executors.newFixedThreadPool(threads) { runnable ->
        Thread(runnable, "webhook-worker").apply { isDaemon = true }
    }

/**
 * Jetty servlet for the GitHub App webhook endpoint (`POST /webhook`).
 *
 * The servlet does only the fast, request-bound work inline — reading the body, verifying the HMAC
 * signature, and decoding the JSON — and then hands the decoded event to [handler] on a background
 * [executor] before immediately returning `200 OK`. This is the durable fix for dropped webhook
 * deliveries: creating a check run, querying bypass status, checking remote build availability, and
 * downloading `build.kts` are all synchronous network calls that previously ran inline and could
 * take 4-9s (sometimes >10s under load). GitHub aborts a webhook delivery that does not get a
 * response within ~10s and does not automatically redeliver it, so any build the timed-out event
 * would have triggered was silently lost. Returning the 200 first means processing latency can never
 * cost us the delivery.
 */
class WebhookServlet(
    private val handler: WebhookHandler,
    private val executor: Executor = newWebhookProcessingExecutor(),
    private val webhookSecret: String = System.getenv("GITHUB_WEBHOOK_SECRET") ?: "",
    /**
     * Records every authenticated delivery, so the operations interface can show what GitHub sent
     * and when — including deliveries that produced no build, which nothing else in this service
     * retains.
     */
    private val journal: CiActivityJournal? = null
) : HttpServlet() {
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    /**
     * Convenience constructor that builds a [WebhookEventHandler] for [buildRunner] and runs it on
     * the supplied [executor]. Pass a same-thread executor (`Executor { it.run() }`) to process
     * events synchronously — useful in tests that assert handler side effects right after the POST.
     * Production wires the handler form (above) with the default background pool.
     */
    constructor(buildRunner: BuildRunner, executor: Executor) : this(WebhookEventHandler(buildRunner), executor)

    override fun doPost(req: HttpServletRequest, resp: HttpServletResponse) {
        val eventType = req.getHeader("X-GitHub-Event")
        val signature = req.getHeader("X-Hub-Signature-256") ?: ""

        val bodyBytes = ByteArrayOutputStream().use { out ->
            req.inputStream.copyTo(out)
            out.toByteArray()
        }
        val bodyString = String(bodyBytes, Charsets.UTF_8)

        println("Received webhook event: $eventType")

        if (webhookSecret.isNotEmpty() && !verifySignature(signature, bodyString)) {
            println("Invalid webhook signature")
            resp.status = HttpServletResponse.SC_UNAUTHORIZED
            resp.writer.write("invalid signature")
            return
        }

        // Decode inline so a malformed body produces a synchronous error rather than a false 200.
        val payload: Map<String, Any?> = mapper.readValue(bodyString)

        val deliveryId = req.getHeader("X-GitHub-Delivery")

        // Hand the (potentially slow) processing to a background worker and ACK immediately. The
        // event has been authenticated and decoded; everything past this point is network I/O that
        // must never race GitHub's ~10s webhook delivery timeout.
        executor.execute {
            try {
                // Recorded on the worker, not on the request thread: appending to the journal is
                // file I/O, and nothing that touches a disk belongs on the path that has to answer
                // GitHub inside its ~10-second delivery budget.
                var rerequestInfo: WebhookBuildInfo? = null
                var rerequestDeliveryId: String? = null
                journal?.let { activityJournal ->
                    recordDelivery(activityJournal, eventType, deliveryId, payload)
                    if (
                        deliveryId != null &&
                        payload["action"] == "rerequested" &&
                        (eventType == "check_suite" || eventType == "check_run")
                    ) {
                        val info = if (eventType == "check_suite") {
                            parseCheckSuitePayload(payload)
                        } else {
                            parseCheckRunPayload(payload)
                        }
                        if (info != null) {
                            when (activityJournal.claimRerequestDelivery(
                                deliveryId, eventType, info.owner, info.repo, info.headSha
                            )) {
                                CiActivityJournal.RerequestDeliveryClaimStatus.COMPLETED -> {
                                    activityJournal.record(
                                        type = CiActivityType.BUILD_SKIPPED,
                                        owner = info.owner,
                                        repo = info.repo,
                                        prNumbers = info.prNumbers,
                                        headSha = info.headSha,
                                        headBranch = info.headBranch,
                                        deliveryId = deliveryId,
                                        eventType = eventType,
                                        action = "rerequested",
                                        outcome = CiActivityOutcome.DUPLICATE_DELIVERY,
                                        detail = "GitHub rerequest delivery '$deliveryId' was already completed, " +
                                            "so it created no second replacement check."
                                    )
                                    return@execute
                                }
                                CiActivityJournal.RerequestDeliveryClaimStatus.ACTION_STARTED -> {
                                    println("GitHub rerequest delivery '$deliveryId' already crossed the durable creation boundary; no second replacement is allowed.")
                                    return@execute
                                }
                                CiActivityJournal.RerequestDeliveryClaimStatus.REPROCESS_PENDING ->
                                    println("Reprocessing unfinished earlier GitHub rerequest delivery '$deliveryId'.")
                                CiActivityJournal.RerequestDeliveryClaimStatus.NEW -> Unit
                            }
                            rerequestInfo = info
                            rerequestDeliveryId = deliveryId
                        }
                    }
                }
                val completedInfo = rerequestInfo
                val completedDeliveryId = rerequestDeliveryId
                val delivery = if (completedInfo != null && completedDeliveryId != null) {
                    RerequestDelivery(
                        completedDeliveryId,
                        { journal!!.startRerequestAction(completedDeliveryId) },
                        { result -> journal!!.completeRerequestDelivery(
                            completedDeliveryId, eventType, completedInfo.owner, completedInfo.repo,
                            completedInfo.headSha, result
                        ) }
                    )
                } else null
                if (handler is ResultWebhookHandler) {
                    handler.handleDelivery(eventType, payload, delivery)
                } else {
                    handler.handle(eventType, payload)
                }
            } catch (e: Throwable) {
                println("Error processing webhook event '$eventType': ${e.message}")
                e.printStackTrace()
            }
        }

        resp.status = HttpServletResponse.SC_OK
        resp.writer.write("ok")
    }

    private fun verifySignature(signature: String, body: String): Boolean {
        if (!signature.startsWith("sha256=")) return false
        val sigBytes = signature.removePrefix("sha256=").hexToBytes()

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(webhookSecret.toByteArray(), "HmacSHA256"))
        val expected = mac.doFinal(body.toByteArray())

        if (expected.size != sigBytes.size) return false
        var diff = 0
        for (i in expected.indices) {
            diff = diff or (expected[i].toInt() xor sigBytes[i].toInt())
        }
        return diff == 0
    }

    private fun String.hexToBytes(): ByteArray {
        val clean = this.trim()
        val len = clean.length
        require(len % 2 == 0)
        return ByteArray(len / 2) {
            clean.substring(2 * it, 2 * it + 2).toInt(16).toByte()
        }
    }
}

/**
 * The production [WebhookHandler]: turns a decoded GitHub webhook event into check-run creation and
 * build submission. Extracted from the servlet so it runs on the background executor and so its
 * [processBuildRequest] entry point can be reused by the [MissingCheckRunReconciler] to back-fill a
 * build whose triggering webhook delivery was dropped.
 */
class WebhookEventHandler(
    private val buildRunner: BuildRunner,
    private val checkRunName: String = REMOTE_CHECK_RUN_NAME,
    private val onCreateCheckRun: (
        owner: String,
        repo: String,
        installationId: Long,
        headSha: String,
        name: String
    ) -> Long = GitHubChecks::createCheckRun,
    private val onCompleteCheckRun: (
        owner: String,
        repo: String,
        installationId: Long,
        checkRunId: Long,
        conclusion: String,
        summary: String,
        detailsUrl: String?
    ) -> Unit = { owner, repo, installationId, checkRunId, conclusion, summary, detailsUrl ->
        GitHubChecks.completeCheckRun(
            owner = owner,
            repo = repo,
            installationId = installationId,
            checkRunId = checkRunId,
            conclusion = conclusion,
            summary = summary,
            detailsUrl = detailsUrl,
            checkRunName = checkRunName
        )
    },
    private val onFetchPrHeadSha: (
        owner: String,
        repo: String,
        prNumber: Int,
        installationId: Long
    ) -> String? = ::fetchPrHeadShaFromGitHub,
    private val onListOpenPullRequestsForCommit: (
        owner: String,
        repo: String,
        headSha: String,
        installationId: Long
    ) -> List<OpenPullRequest> = { owner, repo, _, installationId ->
        // Keep the complete repository response until contradictions have been checked.
        listOpenPullRequestsForRepo(owner, repo, installationId)
            .map { (number, currentHeadSha, headRef) ->
                OpenPullRequest(owner, repo, number, currentHeadSha, installationId, headRef)
            }
    },
    private val onListCheckRunNamesForCommit: (
        owner: String,
        repo: String,
        headSha: String,
        installationId: Long
    ) -> List<String> = { _, _, _, _ -> emptyList() },
    private val onCheckPrBypassStatus: (
        owner: String,
        repo: String,
        prNumber: Int,
        installationId: Long
    ) -> BypassCheckResult = ::checkPrBypassStatus,
    private val onCreatePrComment: (
        owner: String,
        repo: String,
        prNumber: Int,
        installationId: Long,
        body: String
    ) -> Unit = GitHubComments::createPrComment,
    private val onFetchRepositoryPermission: (
        owner: String,
        repo: String,
        username: String,
        installationId: Long
    ) -> String = { owner, repo, username, installationId ->
        fetchRepositoryPermission(owner, repo, username, GitHubApi.getInstallationClient(installationId))
    },
    private val onRepoHasBuildKts: (
        owner: String,
        repo: String,
        headSha: String,
        installationId: Long
    ) -> Boolean = { owner, repo, headSha, installationId ->
        buildRunner.repoHasBuildKts(owner, repo, headSha, installationId)
    },
    private val defaultDetailsUrl: String? = null,
    private val reportUnhandledErrorsAsFailure: Boolean = false,
    private val routeDescription: String = "direct-buildtest",
    /** Records what each trigger did, for the operations interface. Absent in tests that do not read it. */
    private val journal: CiActivityJournal? = null,
    /**
     * The emergency stop and the repository build rates. Absent means nothing is ever refused, which
     * is the behavior every caller had before admission control existed.
     */
    private val admissionControl: CiAdmissionControl? = null,
    /** Queries authoritative PR comments before live message-required feedback is posted. */
    private val onHasBypassFeedbackMarker: (
        owner: String,
        repo: String,
        prNumber: Int,
        installationId: Long,
        marker: String
    ) -> Boolean = ::hasPrCommentWithMarker
) : ResultWebhookHandler {
    private data class PendingHeadOperation(
        val info: WebhookBuildInfo,
        val rerequestedCheckRunId: Long?,
        val rerequestedCheckSuite: Boolean,
        val requireMissingCheckRun: Boolean,
        val verifyCurrentPullRequest: Boolean,
        val claimAllowance: (() -> Boolean)?,
        val verifySuiteBeforeClaim: (() -> LostDispatchVerificationResult)?,
        val checkpointRerequestTrigger: String?,
        val parkedBehindRerequest: Boolean = false,
        val delivery: RerequestDelivery? = null,
        val completion: HeadDispatchCompletion? = null
    ) {
        val isRerequest: Boolean
            get() = rerequestedCheckRunId != null || rerequestedCheckSuite
    }

    private data class ActiveHeadOperation(
        val requestKey: String,
        val operation: PendingHeadOperation
    )

    private val headOperationLocks = Array(256) { ReentrantLock() }
    private val headOperationMonitors = Array(headOperationLocks.size) { Any() }
    private val activeHeadOperations = arrayOfNulls<ActiveHeadOperation>(headOperationLocks.size)
    private val pendingHeadReentries = Array(headOperationLocks.size) { ArrayDeque<PendingHeadOperation>() }
    private val missingCheckLookupUnverified = ThreadLocal.withInitial { false }
    private val activeBuildRequests = ConcurrentHashMap.newKeySet<String>()
    private val bypassMessageFeedbackClaims = ConcurrentHashMap.newKeySet<String>()
    private val bypassMessageRefusalJournalClaims = ConcurrentHashMap.newKeySet<String>()

    private fun createCheckRun(
        owner: String,
        repo: String,
        installationId: Long,
        headSha: String,
        name: String
    ): Long = onCreateCheckRun(owner, repo, installationId, headSha, name)

    private fun completeCheckRun(
        owner: String,
        repo: String,
        installationId: Long,
        checkRunId: Long,
        conclusion: String,
        summary: String,
        detailsUrl: String? = defaultDetailsUrl
    ) = onCompleteCheckRun(
        owner,
        repo,
        installationId,
        checkRunId,
        conclusion,
        appendCheckRunNotice(summary, shadowRouteNotice()),
        detailsUrl
    )

    private fun repoHasBuildKtsOrCompleteFailure(
        owner: String,
        repo: String,
        headSha: String,
        installationId: Long,
        checkRunId: Long,
        checkRunNotice: String? = null
    ): Boolean? = try {
        onRepoHasBuildKts(owner, repo, headSha, installationId)
    } catch (e: Exception) {
        val lookupFailure = if (e is RepoBuildKtsLookupException) {
            e.message ?: "GitHub build.kts lookup failed without an error message."
        } else {
            "GitHub build.kts lookup failed for $owner/$repo at commit $headSha before a usable " +
                "git-tree response was obtained (${e.javaClass.name}: " +
                "${e.message ?: "no error message was provided"})."
        }
        val summary = appendCheckRunNotice(
            "kotlin.build CI infrastructure error: failed to determine whether build.kts is present because $lookupFailure",
            checkRunNotice
        )
        System.err.println(
            "GitHub build.kts lookup failed for $owner/$repo @ $headSha; marking check run " +
                "$checkRunId as failed: $lookupFailure"
        )
        completeCheckRun(
            owner = owner,
            repo = repo,
            installationId = installationId,
            checkRunId = checkRunId,
            conclusion = "failure",
            summary = summary
        )
        null
    }

    private fun shadowRouteNotice(): String? = if (reportUnhandledErrorsAsFailure) {
        shadowRouteNotice(routeDescription)
    } else {
        null
    }

    private fun withRouteNotice(notice: String?): String? {
        val routeNotice = shadowRouteNotice()
        return when {
            routeNotice == null -> notice
            notice.isNullOrBlank() -> routeNotice
            else -> "$routeNotice\n\n$notice"
        }
    }

    private fun unexpectedFailureSummary(operation: String, error: Throwable): String = buildString {
        append("Shadow check for route '$routeDescription' failed while $operation: ")
        append(error.javaClass.name)
        append(": ")
        append(error.message ?: "no error message")
        append("\n\n<details><summary>Full error</summary>\n\n````\n")
        append(error.stackTraceToString())
        append("\n````\n</details>")
    }

    private fun completeUnhandledFailure(
        owner: String,
        repo: String,
        installationId: Long,
        checkRunId: Long?,
        operation: String,
        error: Throwable
    ) {
        if (!reportUnhandledErrorsAsFailure || checkRunId == null) return
        try {
            completeCheckRun(
                owner = owner,
                repo = repo,
                installationId = installationId,
                checkRunId = checkRunId,
                conclusion = "failure",
                summary = unexpectedFailureSummary(operation, error)
            )
        } catch (completionError: Throwable) {
            println(
                "Failed to report shadow check $checkRunId after $operation failed: " +
                    "${completionError.javaClass.name}: ${completionError.message}"
            )
            completionError.printStackTrace()
        }
    }

    /**
     * Applies the emergency stop and the repository's build rate to a build this service is about to
     * start outside the check-suite path — a `/rerun` comment or a default-branch push.
     *
     * Returns whether the build was refused, having already completed the check run with the reason
     * and recorded it. The check-suite path in [processBuildRequestInternal] does the same inline,
     * where it also has the bypass notices to append.
     */
    private fun refuseAdmissionForRetrigger(
        owner: String,
        repo: String,
        installationId: Long,
        checkRunId: Long,
        headSha: String,
        prNumber: Int?,
        matchedKeyword: String?
    ): Boolean {
        val decision = admissionControl?.admit(owner, repo) ?: return false
        if (decision !is CiAdmissionDecision.Refused) return false
        println("Refusing the build for $owner/$repo @ $headSha: ${decision.outcome}")
        journal?.record(
            type = CiActivityType.BUILD_REFUSED,
            owner = owner,
            repo = repo,
            prNumbers = listOfNotNull(prNumber),
            headSha = headSha,
            checkRunId = checkRunId,
            outcome = decision.outcome,
            detail = decision.summary.lineSequence().first()
        )
        completeCheckRun(
            owner = owner,
            repo = repo,
            installationId = installationId,
            checkRunId = checkRunId,
            conclusion = "failure",
            summary = appendCheckRunNotice(
                decision.summary,
                matchedKeyword?.let { "Requested with $it." }
            )
        )
        return true
    }

    private fun reportBypassAuthorizationFailure(
        owner: String,
        repo: String,
        prNumber: Int,
        installationId: Long,
        failure: BypassAuthorizationFailure
    ) {
        if (failure.feedbackAlreadyPosted) return
        try {
            onCreatePrComment(
                owner,
                repo,
                prNumber,
                installationId,
                bypassAuthorizationFeedbackComment(owner, repo, prNumber, failure)
            )
        } catch (e: Exception) {
            System.err.println(
                "Failed to post rejected /bypass authorization feedback on " +
                    "$owner/$repo PR #$prNumber: ${e.message}"
            )
            e.printStackTrace()
        }
    }

    private fun reportBypassMessageFailure(
        owner: String,
        repo: String,
        prNumber: Int,
        installationId: Long,
        failure: BypassMessageFailure,
        feedbackClaimed: Boolean
    ) {
        if (failure.feedbackAlreadyPosted || !feedbackClaimed) return
        val claimKey = bypassMessageFeedbackClaimKey(owner, repo, prNumber, failure)
        try {
            onCreatePrComment(
                owner,
                repo,
                prNumber,
                installationId,
                bypassMessageFeedbackComment(owner, repo, prNumber, failure)
            )
        } catch (e: Exception) {
            // The marker is the durable success signal. A failed post created no marker that this
            // process can rely on, so release the in-memory claim and let the next delivery or
            // history pass try again.
            bypassMessageFeedbackClaims.remove(claimKey)
            System.err.println(
                "Failed to post rejected /bypass message feedback on " +
                    "$owner/$repo PR #$prNumber: ${e.message}"
            )
            e.printStackTrace()
        }
    }

    private fun bypassMessageFeedbackClaimKey(
        owner: String,
        repo: String,
        prNumber: Int,
        failure: BypassMessageFailure
    ): String = "$owner/$repo#$prNumber:${failure.commentId ?: "${failure.authorLogin.orEmpty()}:${failure.createdAt}"}"

    /** Atomically claims one refusal for feedback and its journal record within this process. */
    private fun claimBypassMessageFeedback(
        owner: String,
        repo: String,
        prNumber: Int,
        failure: BypassMessageFailure
    ): Boolean {
        if (failure.feedbackAlreadyPosted) return false
        return bypassMessageFeedbackClaims.add(
            bypassMessageFeedbackClaimKey(owner, repo, prNumber, failure)
        )
    }

    /** Records one refusal per comment while allowing a failed feedback post to be retried. */
    private fun recordBypassMessageRefusal(
        owner: String,
        repo: String,
        prNumber: Int,
        failure: BypassMessageFailure,
        headSha: String? = null,
        headBranch: String? = null,
        checkRunId: Long? = null,
        feedbackClaimed: Boolean
    ) {
        val activityJournal = journal ?: return
        if (!feedbackClaimed) return
        val claimKey = bypassMessageFeedbackClaimKey(owner, repo, prNumber, failure)
        if (!bypassMessageRefusalJournalClaims.add(claimKey)) return
        try {
            activityJournal.record(
                type = CiActivityType.BYPASS_REFUSED,
                owner = owner,
                repo = repo,
                prNumbers = listOf(prNumber),
                headSha = headSha,
                headBranch = headBranch,
                checkRunId = checkRunId,
                actor = failure.authorLogin.orEmpty(),
                outcome = CiActivityOutcome.MESSAGE_REQUIRED,
                detail = bypassMessageRefusalDetail(failure.authorLogin, prNumber)
            )
        } catch (error: Throwable) {
            bypassMessageRefusalJournalClaims.remove(claimKey)
            throw error
        }
    }

    private fun liveBypassMessageFeedbackAlreadyPosted(
        owner: String,
        repo: String,
        prNumber: Int,
        installationId: Long,
        commentId: Long?
    ): Boolean {
        if (commentId == null) return false
        val marker = bypassMessageFeedbackMarker(commentId)
        return try {
            // A marker copied by another commenter can only suppress a harmless duplicate refusal,
            // so the live lookup intentionally accepts it just like the history scan does.
            onHasBypassFeedbackMarker(owner, repo, prNumber, installationId, marker)
        } catch (e: Exception) {
            System.err.println(
                "Could not check for existing rejected /bypass message feedback on " +
                    "$owner/$repo PR #$prNumber: ${e.javaClass.name}: ${e.message}"
            )
            false
        }
    }

    private fun completeBypassOperationFailure(
        checkRunId: Long,
        matchedKeyword: String,
        error: Throwable
    ) {
        val summary =
            "kotlin.build CI could not apply $matchedKeyword because the remote bypass operation " +
                "failed: ${error.javaClass.name}: " +
                bypassMessageForMarkdown(error.message ?: "no error message was provided")
        buildRunner.completePendingCheckRun(
            checkRunId,
            CancellationCompletion("failure", summary)
        )
    }

    override fun handleDelivery(eventType: String, payload: Map<String, Any?>, delivery: RerequestDelivery?): PullRequestHeadDispatchResult =
        handleDeliveryWithCompletion(eventType, payload, delivery) { result ->
            if (result != PullRequestHeadDispatchResult.UNVERIFIED) delivery?.complete?.invoke(result)
        }

    override fun handleWithResult(eventType: String, payload: Map<String, Any?>): PullRequestHeadDispatchResult =
        handleDelivery(eventType, payload, null)

    internal fun handleDeliveryWithCompletion(
        eventType: String,
        payload: Map<String, Any?>,
        delivery: RerequestDelivery?,
        onJoined: () -> Unit = { },
        onEvaluated: (PullRequestHeadDispatchResult) -> Unit
    ): PullRequestHeadDispatchResult {
        val completion = HeadDispatchCompletion(onEvaluated, onJoined)
        return try {
            val result = evaluateEvent(eventType, payload, delivery, completion)
            if (!completion.ownedByCoordinator) completion.evaluated(result)
            result
        } catch (error: Throwable) {
            completion.evaluated(PullRequestHeadDispatchResult.UNVERIFIED)
            throw error
        }
    }

    private fun evaluateEvent(
        eventType: String,
        payload: Map<String, Any?>,
        delivery: RerequestDelivery?,
        completion: HeadDispatchCompletion
    ): PullRequestHeadDispatchResult {
        val previousUnverified = missingCheckLookupUnverified.get()
        missingCheckLookupUnverified.set(false)
        try {
            val created = when (eventType) {
                "check_suite" -> handleCheckSuite(payload, delivery, completion)
                "check_run" -> handleCheckRun(payload, delivery, completion)
                "push" -> { handlePush(payload); false }
                "issue_comment" -> { handleIssueComment(payload); false }
                "pull_request" -> { handlePullRequest(payload, completion); false }
                "ping" -> { println("Received ping event - webhook configured correctly!"); false }
                else -> { println("Ignoring event type: $eventType"); false }
            }
            return when {
                missingCheckLookupUnverified.get() -> PullRequestHeadDispatchResult.UNVERIFIED
                created -> PullRequestHeadDispatchResult.ACTION_TAKEN
                else -> PullRequestHeadDispatchResult.VERIFIED_NO_ACTION
            }
        } finally {
            missingCheckLookupUnverified.set(previousUnverified)
        }
    }

    private fun handleCheckSuite(payload: Map<String, Any?>, delivery: RerequestDelivery?, completion: HeadDispatchCompletion): Boolean {
        val action = payload["action"] as? String ?: return false
        if (action != "requested" && action != "rerequested") {
            println("Ignoring check_suite action: $action")
            return false
        }

        val info = try {
            parseAuthoritativeCheckSuiteEvent(payload)
        } catch (error: IllegalArgumentException) {
            missingCheckLookupUnverified.set(true)
            val repository = payload["repository"] as? Map<*, *>
            val suite = payload["check_suite"] as? Map<*, *>
            val detail = "The check_suite $action was not started: ${error.message}; malformed authority cannot select an action."
            journal?.record(
                type = CiActivityType.BUILD_SKIPPED,
                owner = ((repository?.get("owner") as? Map<*, *>)?.get("login") as? String).orEmpty(),
                repo = (repository?.get("name") as? String).orEmpty(),
                headSha = suite?.get("head_sha") as? String,
                outcome = CiActivityOutcome.CURRENT_PR_LOOKUP_FAILED, detail = detail
            )
            println(detail)
            return false
        }
        if (action != "rerequested" && skipCheckpointBranchIfNeeded(info, journal, "check_suite request")) return false

        return processBuildRequestInternal(
            info,
            rerequestedCheckSuite = action == "rerequested",
            checkpointRerequestTrigger = if (action == "rerequested") "check_suite request" else null,
            requireMissingCheckRun = action != "rerequested",
            delivery = delivery,
            completion = completion
        )
    }

    private fun handleCheckRun(payload: Map<String, Any?>, delivery: RerequestDelivery?, completion: HeadDispatchCompletion): Boolean {
        val action = payload["action"] as? String
        println("Received check_run event with action: $action")

        if (action != "rerequested") {
            println("Ignoring check_run action: $action")
            return false
        }

        if (!validateRerequestAuthority("check_run", payload)) return false
        val parsedInfo = parseCheckRunPayload(payload)
        if (parsedInfo == null) {
            println("Could not parse check_run rerequested payload")
            missingCheckLookupUnverified.set(true)
            return false
        }
        return processBuildRequestInternal(
            parsedInfo, rerequestedCheckRunId = parsedInfo.rerequestedCheckRunId,
            checkpointRerequestTrigger = "check_run rerequest", delivery = delivery, completion = completion
        )
    }

    private fun validateRerequestAuthority(eventType: String, payload: Map<String, Any?>): Boolean {
        val event = payload[eventType] as? Map<*, *>
        val suite = if (eventType == "check_suite") event else event?.get("check_suite") as? Map<*, *>
        val associations = suite?.get("pull_requests")
        val repositoryData = payload["repository"] as? Map<*, *>
        val ownerData = repositoryData?.get("owner") as? Map<*, *>
        val installationData = payload["installation"] as? Map<*, *>
        fun numericId(value: Any?): Boolean = value is Number && value.toLong() > 0 && value.toDouble() == value.toLong().toDouble()
        val fields = linkedMapOf<String, Pair<Any?, Boolean>>()
        fields["repository.name (non-blank string)"] = repositoryData?.get("name") to
            ((repositoryData?.get("name") as? String)?.isNotBlank() == true)
        fields["repository.owner.login (non-blank string)"] = ownerData?.get("login") to
            ((ownerData?.get("login") as? String)?.isNotBlank() == true)
        fields["installation.id (positive integer)"] = installationData?.get("id") to numericId(installationData?.get("id"))
        fields["$eventType.head_sha (non-blank string)"] = event?.get("head_sha") to
            ((event?.get("head_sha") as? String)?.isNotBlank() == true)
        fields["${if (eventType == "check_run") "check_run.check_suite" else eventType}.head_branch (non-blank string)"] =
            suite?.get("head_branch") to ((suite?.get("head_branch") as? String)?.isNotBlank() == true)
        fields["${if (eventType == "check_run") "check_run.check_suite" else eventType}.pull_requests (array of objects with numeric number)"] = associations to
            (associations is List<*> && associations.all { row ->
                row is Map<*, *> && numericId(row["number"]) && (row["number"] as Number).toLong() <= Int.MAX_VALUE
            })
        if (eventType == "check_run") fields["check_run.id (numeric check-run id)"] = event?.get("id") to numericId(event?.get("id"))
        val invalid = fields.entries.firstOrNull { !it.value.second } ?: return true
        missingCheckLookupUnverified.set(true)
        val repository = payload["repository"] as? Map<*, *>
        val owner = (repository?.get("owner") as? Map<*, *>)?.get("login")
        val repo = repository?.get("name")
        val head = event?.get("head_sha")
        val detail = "The $eventType rerequest for $owner/$repo @ $head was not started: expected " +
            "${invalid.key}, actual ${invalid.value.first}; malformed authority cannot select an action."
        println(detail)
        journal?.record(type = CiActivityType.BUILD_SKIPPED, owner = owner?.toString() ?: "",
            repo = repo?.toString() ?: "", headSha = head?.toString(),
            outcome = CiActivityOutcome.CURRENT_PR_LOOKUP_FAILED, detail = detail)
        return false
    }

    /**
     * GitHub can retain the branch and empty PR list from the ref on which a commit's check suite
     * was first created. A rerequest must therefore consult current open-PR state before treating
     * that retained `wip/` branch as a non-PR checkpoint.
     */
    private fun reclassifyCheckpointRerequest(
        info: WebhookBuildInfo,
        triggerDescription: String
    ): WebhookBuildInfo? {
        if (info.headBranch?.startsWith("wip/") != true) {
            return info
        }
        val allCurrentPullRequests = try {
            onListOpenPullRequestsForCommit(
                info.owner,
                info.repo,
                info.headSha,
                info.installationId
            )
        } catch (e: Exception) {
            missingCheckLookupUnverified.set(true)
            val explanation = e.message ?: e::class.java.simpleName
            val detail = "The $triggerDescription for ${info.owner}/${info.repo} @ ${info.headSha} " +
                "was not started because current pull-request membership could not be verified: " +
                explanation + if (explanation.endsWith(".")) "" else "."
            println(detail)
            journal?.record(
                type = CiActivityType.BUILD_SKIPPED,
                owner = info.owner, repo = info.repo, headSha = info.headSha,
                headBranch = info.headBranch,
                outcome = CiActivityOutcome.CURRENT_PR_LOOKUP_FAILED,
                detail = detail
            )
            return null
        }
        val normalized = allCurrentPullRequests.distinct()
        val invalidRepositoryRows = normalized.any {
            it.owner != info.owner || it.repo != info.repo || it.installationId != info.installationId ||
                it.number <= 0 || it.headSha.isBlank() || it.headRef.isNullOrBlank()
        }
        if (invalidRepositoryRows || normalized.groupBy { it.number }.any { it.value.size > 1 }) {
            missingCheckLookupUnverified.set(true)
            val observed = normalized.map {
                "${it.owner}/${it.repo}#${it.number}@${it.headSha}:" +
                    "${it.headRef ?: "missing"}[installation=${it.installationId}]"
            }
            val noMatchingIdentity = normalized.none {
                it.owner == info.owner && it.repo == info.repo && it.installationId == info.installationId &&
                    it.headSha == info.headSha
            }
            val detail = if (invalidRepositoryRows && noMatchingIdentity) {
                "The $triggerDescription for ${info.owner}/${info.repo} @ ${info.headSha} " +
                    "was not started because GitHub returned ${allCurrentPullRequests.size} current " +
                    "pull-request records but none matched the repository, commit, and installation; " +
                    "observed $observed."
            } else {
                "The $triggerDescription for ${info.owner}/${info.repo} @ ${info.headSha} " +
                    "was not started: expected one distinct complete current row per PR number for " +
                    "${info.owner}/${info.repo}, installation ${info.installationId}, before filtering by SHA; " +
                    "actual rows $observed. Current PR authority is unverified."
            }
            journal?.record(type = CiActivityType.BUILD_SKIPPED, owner = info.owner, repo = info.repo,
                headSha = info.headSha, headBranch = info.headBranch,
                outcome = CiActivityOutcome.CURRENT_PR_LOOKUP_CONTRADICTORY, detail = detail)
            println(detail)
            return null
        }
        val currentPullRequests = normalized.filter {
            it.owner == info.owner &&
                it.repo == info.repo &&
                it.headSha == info.headSha &&
                it.installationId == info.installationId
        }
        if (currentPullRequests.isEmpty()) {
            return info.copy(
                prNumbers = emptyList(),
                pullRequestAssociationCount = 0,
                checkpointClassificationDetail =
                    "GitHub authoritatively returned no matching current open pull request for " +
                        "${info.owner}/${info.repo} @ ${info.headSha}."
            )
        }
        val distinctPullRequests = currentPullRequests.distinct()
        if (distinctPullRequests.size != 1 || distinctPullRequests.single().headRef.isNullOrBlank()) {
            missingCheckLookupUnverified.set(true)
            val observed = currentPullRequests.map {
                "${it.owner}/${it.repo}#${it.number}@${it.headSha}:" +
                    "${it.headRef ?: "missing"}[installation=${it.installationId}]"
            }
            val detail = "The $triggerDescription for ${info.owner}/${info.repo} @ ${info.headSha} " +
                "was not started because current pull-request membership was contradictory: expected " +
                "all matching rows to identify one pull request with a non-blank current head ref, " +
                "but observed $observed."
            println(detail)
            journal?.record(
                type = CiActivityType.BUILD_SKIPPED,
                owner = info.owner, repo = info.repo, headSha = info.headSha,
                headBranch = info.headBranch,
                outcome = CiActivityOutcome.CURRENT_PR_LOOKUP_CONTRADICTORY,
                detail = detail
            )
            return null
        }

        val currentNumbers = listOf(distinctPullRequests.single().number)
        val currentHeadBranch = requireNotNull(distinctPullRequests.single().headRef)
        println(
            "Treating $triggerDescription for ${info.owner}/${info.repo} @ ${info.headSha} as " +
                "pull-request work from current GitHub state (PRs ${currentNumbers.joinToString()}) " +
                "instead of retained checkpoint branch '${info.headBranch}'."
        )
        return info.copy(
            prNumbers = currentNumbers,
            headBranch = currentHeadBranch,
            pullRequestAssociationCount = 1
        )
    }

    /**
     * Creates the `kotlin.build (remote)` check run for [info] and triggers (or bypasses, or
     * short-circuits) the build. This is the shared entry point for both inbound webhooks and the
     * [MissingCheckRunReconciler] back-fill, so callers must only invoke it for a head commit that
     * does not already have the check run.
     *
     * @return true once the GitHub check run has been created, including when a later build step
     * fails; false when processing stopped before the check run was created.
     */
    fun processBuildRequest(info: WebhookBuildInfo): Boolean =
        processBuildRequestInternal(info)

    /**
     * Processes reconciliation work only if the named check is still absent. The authoritative
     * read runs while the same per-head reservation used by webhook handling is held, so a webhook
     * cannot create a check between this read and [onCreateCheckRun].
     */
    fun processBuildRequestIfMissing(info: WebhookBuildInfo): Boolean =
        processBuildRequestInternal(info, requireMissingCheckRun = true)

    fun processBuildRequestIfMissingResult(
        info: WebhookBuildInfo,
        claimAllowance: () -> Boolean = { true }
    ): PullRequestHeadDispatchResult =
        processBuildRequestWithCompletion(info, true, true, claimAllowance) { }

    /** Delivers only evaluated outcomes; a parked request returns before invoking [onEvaluated]. */
    fun processBuildRequestIfMissingWithCompletion(
        info: WebhookBuildInfo,
        claimAllowance: () -> Boolean,
        verifySuiteBeforeClaim: (() -> LostDispatchVerificationResult)? = null,
        onEvaluated: (PullRequestHeadDispatchResult) -> Unit
    ) {
        processBuildRequestWithCompletion(info, true, true, claimAllowance,
            verifySuiteBeforeClaim = verifySuiteBeforeClaim, onEvaluated = onEvaluated)
    }

    internal fun processBuildRequestWithCompletion(
        info: WebhookBuildInfo,
        requireMissingCheckRun: Boolean,
        verifyCurrentPullRequest: Boolean,
        claimAllowance: () -> Boolean,
        verifySuiteBeforeClaim: (() -> LostDispatchVerificationResult)? = null,
        onJoined: () -> Unit = { },
        onEvaluated: (PullRequestHeadDispatchResult) -> Unit
    ): PullRequestHeadDispatchResult {
        val previousUnverified = missingCheckLookupUnverified.get()
        missingCheckLookupUnverified.set(false)
        val completion = HeadDispatchCompletion(onEvaluated) {
            onJoined()
            onEvaluated(PullRequestHeadDispatchResult.VERIFIED_NO_ACTION)
        }
        return try {
            val created = processBuildRequestInternal(
                info, requireMissingCheckRun = requireMissingCheckRun, verifyCurrentPullRequest = verifyCurrentPullRequest,
                claimAllowance = claimAllowance, verifySuiteBeforeClaim = verifySuiteBeforeClaim, completion = completion
            )
            when {
                missingCheckLookupUnverified.get() -> PullRequestHeadDispatchResult.UNVERIFIED
                created -> PullRequestHeadDispatchResult.ACTION_TAKEN
                else -> PullRequestHeadDispatchResult.VERIFIED_NO_ACTION
            }
        } catch (error: Throwable) {
            completion.evaluated(PullRequestHeadDispatchResult.UNVERIFIED)
            throw error
        } finally {
            missingCheckLookupUnverified.set(previousUnverified)
        }
    }

    private fun processBuildRequestInternal(
        info: WebhookBuildInfo,
        rerequestedCheckRunId: Long? = null,
        rerequestedCheckSuite: Boolean = false,
        requireMissingCheckRun: Boolean = false,
        verifyCurrentPullRequest: Boolean = false,
        claimAllowance: (() -> Boolean)? = null,
        verifySuiteBeforeClaim: (() -> LostDispatchVerificationResult)? = null,
        delivery: RerequestDelivery? = null,
        completion: HeadDispatchCompletion? = null,
        checkpointRerequestTrigger: String? = null
    ): Boolean {
        completion?.ownedByCoordinator = true
        val requestKey = "${info.owner}/${info.repo}@${info.headSha}:$checkRunName"
        val stripeIndex = headOperationStripe(requestKey)
        val lock = headOperationLocks[stripeIndex]
        val operation = PendingHeadOperation(
            info,
            rerequestedCheckRunId,
            rerequestedCheckSuite,
            requireMissingCheckRun,
            verifyCurrentPullRequest,
            claimAllowance,
            verifySuiteBeforeClaim,
            checkpointRerequestTrigger
        )
        val deliveryOperation = operation.copy(delivery = delivery, completion = completion)
        var duplicate = false
        var joined = false
        var parked = false
        synchronized(headOperationMonitors[stripeIndex]) {
            val active = activeHeadOperations[stripeIndex]
            if (active != null) {
                if (!operation.isRerequest && active.requestKey == requestKey) {
                    duplicate = true
                } else {
                    val queue = pendingHeadReentries[stripeIndex]
                    val id = deliveryOperation.delivery?.id
                    if (id == null || (active.operation.delivery?.id != id && queue.none { it.delivery?.id == id })) {
                        queue.addLast(deliveryOperation.copy(
                            parkedBehindRerequest = id == null && active.requestKey == requestKey && active.operation.isRerequest
                        ))
                    } else {
                        joined = true
                    }
                    parked = true
                }
            } else {
                check(lock.tryLock()) { "Expected idle stripe $stripeIndex to have no lock owner." }
                activeHeadOperations[stripeIndex] = ActiveHeadOperation(requestKey, deliveryOperation)
            }
        }
        if (duplicate) {
            if (!requireMissingCheckRun) recordActiveProcessingSkip(info)
            completion?.joined()
            return false
        }
        if (joined) completion?.joined()
        if (parked) {
            // Parking is not an evaluated no-action result. The owner carries the delivery's
            // completion callback and invokes it only after evaluating this exact operation.
            missingCheckLookupUnverified.set(true)
            return false
        }
        try {
            return drainHeadOperationStripe(stripeIndex, lock, deliveryOperation)
        } finally {
            synchronized(headOperationMonitors[stripeIndex]) {
                if (lock.isHeldByCurrentThread) {
                    activeHeadOperations[stripeIndex] = null
                    (headOperationMonitors[stripeIndex] as Object).notifyAll()
                    lock.unlock()
                }
            }
        }
    }

    private fun headOperationStripe(requestKey: String): Int =
        (requestKey.hashCode() and Int.MAX_VALUE) % headOperationLocks.size

    /**
     * Runs [action] while owning the per-head reservation for `owner/repo@headSha`, waiting for the
     * stripe to become idle first. Used by the `/rerun` comment path, which creates a check and starts
     * its build: holding the reservation for that whole span means the GitHub-alignment sweep, which
     * acts only while it owns the same reservation, either sees the new check and its tracked build or
     * finishes its own action before the comment is evaluated. Work parked on the stripe meanwhile is
     * drained by this caller exactly as [tryWithHeadReservation] does.
     */
    fun <T> withHeadReservationWaiting(
        owner: String,
        repo: String,
        headSha: String,
        installationId: Long,
        action: () -> T
    ): T {
        val requestKey = "$owner/$repo@$headSha:$checkRunName"
        val stripeIndex = headOperationStripe(requestKey)
        val monitor = headOperationMonitors[stripeIndex]
        synchronized(monitor) {
            while (activeHeadOperations[stripeIndex] != null) {
                (monitor as Object).wait()
            }
        }
        var result: Result<T>? = null
        while (result == null) {
            val ran = tryWithHeadReservation(owner, repo, headSha, installationId) {
                result = runCatching(action)
            }
            if (!ran) {
                synchronized(monitor) {
                    while (activeHeadOperations[stripeIndex] != null) {
                        (monitor as Object).wait()
                    }
                }
            }
        }
        return result!!.getOrThrow()
    }

    /**
     * Runs [action] while owning the same per-head reservation webhook creation, rerequests and
     * direct reconciliation use for `owner/repo@headSha`, but only when that reservation's stripe
     * is idle: this never waits for another operation. Returns false without running [action]
     * when the stripe is busy. Every delivery arriving for the stripe meanwhile, including one for
     * the same head, is parked rather than dropped as a duplicate (the reservation is registered
     * under its own key), and this caller drains the parked work after [action] returns, so it is
     * evaluated against whatever [action] left behind.
     */
    fun tryWithHeadReservation(
        owner: String,
        repo: String,
        headSha: String,
        installationId: Long,
        action: () -> Unit
    ): Boolean {
        val requestKey = "$owner/$repo@$headSha:$checkRunName"
        val stripeIndex = headOperationStripe(requestKey)
        val lock = headOperationLocks[stripeIndex]
        val reservation = PendingHeadOperation(
            info = WebhookBuildInfo(
                headSha = headSha,
                owner = owner,
                repo = repo,
                installationId = installationId,
                prNumbers = emptyList(),
                headBranch = null
            ),
            rerequestedCheckRunId = null,
            rerequestedCheckSuite = false,
            requireMissingCheckRun = true,
            verifyCurrentPullRequest = false,
            claimAllowance = null,
            verifySuiteBeforeClaim = null,
            checkpointRerequestTrigger = null
        )
        synchronized(headOperationMonitors[stripeIndex]) {
            if (activeHeadOperations[stripeIndex] != null || !lock.tryLock()) return false
            activeHeadOperations[stripeIndex] = ActiveHeadOperation("$requestKey#reservation", reservation)
        }
        try {
            action()
        } finally {
            val next = synchronized(headOperationMonitors[stripeIndex]) {
                val next = pendingHeadReentries[stripeIndex].removeFirstOrNull()
                if (next == null) {
                    activeHeadOperations[stripeIndex] = null
                    (headOperationMonitors[stripeIndex] as Object).notifyAll()
                    lock.unlock()
                } else {
                    activeHeadOperations[stripeIndex] = ActiveHeadOperation(
                        "${next.info.owner}/${next.info.repo}@${next.info.headSha}:$checkRunName", next
                    )
                }
                next
            }
            if (next != null) {
                try {
                    drainHeadOperationStripe(stripeIndex, lock, next)
                } catch (error: Throwable) {
                    System.err.println(
                        "Work parked behind the head reservation for $requestKey failed during evaluation: " +
                            "${error.javaClass.name}: ${error.message}"
                    )
                } finally {
                    synchronized(headOperationMonitors[stripeIndex]) {
                        if (lock.isHeldByCurrentThread) {
                            activeHeadOperations[stripeIndex] = null
                            (headOperationMonitors[stripeIndex] as Object).notifyAll()
                            lock.unlock()
                        }
                    }
                }
            }
        }
        return true
    }

    private fun drainHeadOperationStripe(
        stripeIndex: Int,
        lock: ReentrantLock,
        initial: PendingHeadOperation
    ): Boolean {
        var firstResult = false
        var firstUnverified = false
        var failure: Throwable? = null
        var operation: PendingHeadOperation? = initial
        while (operation != null) {
            val current = operation
            missingCheckLookupUnverified.set(false)
            try {
                val created = processBuildRequestUnderHeadLock(
                    current.info, current.rerequestedCheckRunId, current.rerequestedCheckSuite,
                    current.requireMissingCheckRun, current.verifyCurrentPullRequest,
                    current.claimAllowance, current.verifySuiteBeforeClaim, current.parkedBehindRerequest, current.delivery,
                    current.checkpointRerequestTrigger
                )
                val unverified = missingCheckLookupUnverified.get()
                if (current === initial) {
                    firstResult = created
                    firstUnverified = unverified
                }
                val result = when {
                    unverified -> PullRequestHeadDispatchResult.UNVERIFIED
                    created -> PullRequestHeadDispatchResult.ACTION_TAKEN
                    else -> PullRequestHeadDispatchResult.VERIFIED_NO_ACTION
                }
                current.completion?.evaluated(result)
            } catch (e: Throwable) {
                current.completion?.evaluated(PullRequestHeadDispatchResult.UNVERIFIED)
                if (current === initial) { failure = e; firstUnverified = true }
                else {
                    System.err.println("Parked delivery '${current.delivery?.id}' failed during evaluation: ${e.message}")
                    e.printStackTrace()
                }
            }
            operation = synchronized(headOperationMonitors[stripeIndex]) {
                val next = pendingHeadReentries[stripeIndex].removeFirstOrNull()
                if (next == null) {
                    activeHeadOperations[stripeIndex] = null
                    (headOperationMonitors[stripeIndex] as Object).notifyAll()
                    lock.unlock()
                } else {
                    activeHeadOperations[stripeIndex] = ActiveHeadOperation(
                        "${next.info.owner}/${next.info.repo}@${next.info.headSha}:$checkRunName", next)
                }
                next
            }
        }
        missingCheckLookupUnverified.set(firstUnverified)
        failure?.let { throw it }
        return firstResult
    }

    private fun recordActiveProcessingSkip(info: WebhookBuildInfo) {
        val detail = "Processing for ${info.owner}/${info.repo} @ ${info.headSha} is already active, " +
            "so this duplicate delivery started no second check."
        println("Ignoring duplicate build delivery for ${info.owner}/${info.repo} @ ${info.headSha}: processing is already active")
        journal?.record(
            type = CiActivityType.BUILD_SKIPPED,
            owner = info.owner,
            repo = info.repo,
            prNumbers = info.prNumbers,
            headSha = info.headSha,
            headBranch = info.headBranch,
            outcome = CiActivityOutcome.DUPLICATE_DELIVERY,
            detail = detail
        )
    }

    private fun currentPullRequestBuildInfo(info: WebhookBuildInfo): WebhookBuildInfo? {
        val expectedNumber = info.prNumbers.singleOrNull()
        val expectedBranch = info.headBranch
        if (expectedNumber == null || expectedBranch.isNullOrBlank()) {
            val detail = "Direct dispatch for ${info.owner}/${info.repo} @ ${info.headSha} was not " +
                "started because its expected current pull request was incomplete: expected one PR " +
                "number and a non-blank head ref, but got PRs ${info.prNumbers} and ref " +
                "'${expectedBranch ?: "missing"}'."
            println(detail)
            journal?.record(
                type = CiActivityType.BUILD_SKIPPED,
                owner = info.owner, repo = info.repo, prNumbers = info.prNumbers,
                headSha = info.headSha, headBranch = info.headBranch,
                outcome = CiActivityOutcome.CURRENT_PR_LOOKUP_CONTRADICTORY,
                detail = detail
            )
            return null
        }
        val current = try {
            onListOpenPullRequestsForCommit(info.owner, info.repo, info.headSha, info.installationId)
        } catch (e: Exception) {
            missingCheckLookupUnverified.set(true)
            val detail = "Direct dispatch for ${info.owner}/${info.repo} PR #$expectedNumber @ " +
                "${info.headSha} could not verify the current open pull request: " +
                "${e.message ?: e::class.java.simpleName}."
            println(detail)
            journal?.record(
                type = CiActivityType.BUILD_SKIPPED,
                owner = info.owner, repo = info.repo, prNumbers = info.prNumbers,
                headSha = info.headSha, headBranch = info.headBranch,
                outcome = CiActivityOutcome.CURRENT_PR_LOOKUP_FAILED,
                detail = detail
            )
            return null
        }
        val distinct = current.distinct()
        val sameNumber = distinct.filter { it.number == expectedNumber }
        val expected = sameNumber.singleOrNull()
        val exact = expected != null && expected.owner == info.owner && expected.repo == info.repo &&
            expected.installationId == info.installationId && expected.headSha == info.headSha &&
            expected.headRef == expectedBranch && expected.number > 0
        val completeRows = distinct.all {
            it.owner == info.owner && it.repo == info.repo && it.installationId == info.installationId &&
                it.headSha.isNotBlank() && it.number > 0 && !it.headRef.isNullOrBlank()
        } && distinct.groupBy { it.number }.all { it.value.size == 1 }
        if (exact && completeRows) {
            val numbers = distinct.filter { it.headSha == info.headSha }.map { it.number }.sorted()
            return info.copy(prNumbers = numbers, pullRequestAssociationCount = numbers.size)
        }
        val observed = distinct.map {
            "${it.owner}/${it.repo}#${it.number}@${it.headSha}:${it.headRef ?: "missing"}[installation=${it.installationId}]"
        }
        val detail = "Direct dispatch for ${info.owner}/${info.repo} PR #$expectedNumber @ " +
            "${info.headSha}:$expectedBranch was not started: expected exactly one distinct complete " +
            "row for PR #$expectedNumber matching ${info.owner}/${info.repo}, installation ${info.installationId}, " +
            "head ${info.headSha} and ref $expectedBranch; actual rows $observed."
        println(detail)
        journal?.record(
            type = CiActivityType.BUILD_SKIPPED,
            owner = info.owner, repo = info.repo, prNumbers = info.prNumbers,
            headSha = info.headSha, headBranch = info.headBranch,
            outcome = CiActivityOutcome.CURRENT_PR_LOOKUP_CONTRADICTORY,
            detail = detail
        )
        return null
    }

    private fun processBuildRequestUnderHeadLock(
        requestedInfo: WebhookBuildInfo,
        rerequestedCheckRunId: Long?,
        rerequestedCheckSuite: Boolean,
        requireMissingCheckRun: Boolean,
        verifyCurrentPullRequest: Boolean,
        claimAllowance: (() -> Boolean)?,
        verifySuiteBeforeClaim: (() -> LostDispatchVerificationResult)?,
        parkedBehindRerequest: Boolean,
        delivery: RerequestDelivery?,
        checkpointRerequestTrigger: String?
    ): Boolean {
        if (!isKotlinBuildDispatchEnabled(requestedInfo.owner, requestedInfo.repo)) {
            println(
                "Ignoring kotlin.build check request for ${requestedInfo.owner}/${requestedInfo.repo}: " +
                    "$KOTLIN_BUILD_DISPATCH_DISABLED_REASON."
            )
            return false
        }
        // Retain the original checkpoint provenance while parked. Membership can change before
        // this operation owns the stripe, so classify here before cancellation or creation.
        val classifiedInfo = if (checkpointRerequestTrigger != null) {
            reclassifyCheckpointRerequest(requestedInfo, checkpointRerequestTrigger) ?: return false
        } else requestedInfo
        if (checkpointRerequestTrigger != null &&
            skipCheckpointBranchIfNeeded(classifiedInfo, journal, checkpointRerequestTrigger)) return false
        val info = if (verifyCurrentPullRequest) currentPullRequestBuildInfo(classifiedInfo) ?: return false else classifiedInfo
        val sharedHeadDetail = if (verifyCurrentPullRequest && info.prNumbers.size > 1) {
            " Current open pull requests ${info.prNumbers} share this head commit; one check covers all of them."
        } else ""
        if (requireMissingCheckRun) {
            val checkRunNames = try {
                onListCheckRunNamesForCommit(
                    info.owner,
                    info.repo,
                    info.headSha,
                    info.installationId
                )
            } catch (e: Exception) {
                missingCheckLookupUnverified.set(true)
                val detail = "The '$checkRunName' check could not be created because the " +
                    "authoritative check-run lookup for ${info.owner}/${info.repo} @ " +
                    "${info.headSha} failed: ${e.message ?: e::class.java.simpleName}. Without " +
                    "that result, the service cannot prove that creation would be unique."
                println(detail)
                journal?.record(
                    type = CiActivityType.BUILD_SKIPPED,
                    owner = info.owner,
                    repo = info.repo,
                    prNumbers = info.prNumbers,
                    headSha = info.headSha,
                    headBranch = info.headBranch,
                    outcome = CiActivityOutcome.CHECK_RUN_LOOKUP_FAILED,
                    detail = detail
                )
                return false
            }
            if (checkRunNames.any { it == checkRunName }) {
                val detail = "The '$checkRunName' check already exists on ${info.owner}/${info.repo} " +
                    "@ ${info.headSha}, so this trigger created no second check."
                println(detail)
                journal?.record(
                    type = CiActivityType.BUILD_SKIPPED,
                    owner = info.owner,
                    repo = info.repo,
                    prNumbers = info.prNumbers,
                    headSha = info.headSha,
                    headBranch = info.headBranch,
                    outcome = CiActivityOutcome.CHECK_RUN_ALREADY_EXISTS,
                    detail = detail
                )
                return false
            }
        }
        val buildIsRerequested = when {
            rerequestedCheckRunId != null -> buildRunner.hasStaleCheckRun(rerequestedCheckRunId)
            rerequestedCheckSuite -> buildRunner.hasStaleBuild(info.owner, info.repo, info.headSha)
            else -> false
        }
        if (parkedBehindRerequest &&
            (rerequestedCheckRunId != null || rerequestedCheckSuite) &&
            !buildIsRerequested
        ) {
            println(
                "Ignoring the duplicate parked rerequest for ${info.owner}/${info.repo} @ " +
                    "${info.headSha}: the earlier rerequest already left no stale build to replace"
            )
            return false
        }
        if (buildRunner.hasInProgressBuild(info.owner, info.repo, info.headSha) && !buildIsRerequested) {
            println("Ignoring duplicate build delivery for ${info.owner}/${info.repo} @ ${info.headSha}: a build is already tracked")
            journal?.record(
                type = CiActivityType.BUILD_SKIPPED,
                owner = info.owner,
                repo = info.repo,
                prNumbers = info.prNumbers,
                headSha = info.headSha,
                headBranch = info.headBranch,
                outcome = CiActivityOutcome.BUILD_ALREADY_TRACKED,
                detail = "A build for this commit is already tracked, so this trigger started no " +
                    "second one."
            )
            return false
        }
        if (buildIsRerequested) {
            val canceledBuildSummary =
                "This check was rerequested; the previous in-flight run was canceled before a replacement run started."
            println(
                "Replacing the tracked build for ${info.owner}/${info.repo} @ ${info.headSha} " +
                    "because check run ${rerequestedCheckRunId ?: "suite"} was rerequested"
            )
            val cancellationCompletion = CancellationCompletion("cancelled", canceledBuildSummary)
            val canceledBuilds = if (rerequestedCheckRunId != null) {
                buildRunner.cancelBuildsForCheckRun(rerequestedCheckRunId, cancellationCompletion)
            } else {
                buildRunner.cancelStaleBuildsForRepo(
                    owner = info.owner,
                    repo = info.repo,
                    headSha = info.headSha,
                    completion = cancellationCompletion
                )
            }
            for (build in canceledBuilds) {
                val completed = buildRunner.completeCanceledBuild(
                    build = build,
                    conclusion = "cancelled",
                    summary = canceledBuildSummary
                )
                if (!completed) {
                    println(
                        "Could not replace the rerequested build for ${info.owner}/${info.repo} @ ${info.headSha}: " +
                            "old check ${build.checkRunId} was not completed; the replacement was not created."
                    )
                    return false
                }
            }
            if (buildRunner.hasInProgressBuild(info.owner, info.repo, info.headSha)) {
                println(
                    "Could not replace the rerequested build for ${info.owner}/${info.repo} @ ${info.headSha}: " +
                        "another in-progress build remains tracked"
                )
                return false
            }
        }
        // Only the stripe owner may authorize the direct repair. The callback retains the suite
        // identity that selected this route, including while this operation was parked.
        if (verifySuiteBeforeClaim != null) {
            val verification = try {
                verifySuiteBeforeClaim()
            } catch (error: Exception) {
                LostDispatchVerificationResult.Unverified(
                    "Expected verified unchanged suite identity and an empty check-run list before " +
                        "creating '$checkRunName' for ${info.owner}/${info.repo} @ ${info.headSha}; " +
                        "actual verification failed: ${error.message}.")
            }
            if (verification != LostDispatchVerificationResult.ActionableZeroRun) {
                missingCheckLookupUnverified.set(true)
                val detail = when (verification) {
                    is LostDispatchVerificationResult.Unverified -> verification.detail
                    is LostDispatchVerificationResult.VerifiedNoAction -> verification.detail
                    LostDispatchVerificationResult.ActionableZeroRun -> error("Expected a failed suite verification")
                }
                println(detail)
                return false
            }
        }
        if (claimAllowance != null && !claimAllowance()) return false
        // GitHub creation has no delivery-id key. Persist the boundary before calling it so a
        // stopped or ambiguous call is never replayed as a second replacement for this delivery.
        if (delivery?.startAction?.invoke() == false) {
            missingCheckLookupUnverified.set(true)
            return false
        }
        println("Creating check run for ${info.owner}/${info.repo} @ ${info.headSha} (installation: ${info.installationId})")

        var checkRunCreated = false
        var createdCheckRunId: Long? = null
        try {
            val checkRunId = createCheckRun(
                owner = info.owner,
                repo = info.repo,
                installationId = info.installationId,
                headSha = info.headSha,
                name = checkRunName
            )
            checkRunCreated = true
            createdCheckRunId = checkRunId

            // Check if any associated PR has an active /bypass
            val prNumbers = info.prNumbers.toMutableList()

            // Merge queue branch name fallback (gh-readonly-queue/*/pr-N-<sha>)
            // GitHub does not populate pull_requests for merge queue check suites,
            // so we extract the PR number from the branch name as a fallback.
            if (prNumbers.isEmpty() && info.headBranch != null) {
                val mqPrNumber = extractPrNumberFromMergeQueueBranch(info.headBranch)
                if (mqPrNumber != null) {
                    println("Detected merge queue branch '${info.headBranch}', associated with PR #$mqPrNumber")
                    prNumbers.add(mqPrNumber)
                }
            }

            val authorizationNotices = linkedSetOf<String>()
            for (prNumber in prNumbers) {
                println("Checking bypass status for associated PR #$prNumber...")
                val bypassResult = onCheckPrBypassStatus(info.owner, info.repo, prNumber, info.installationId)
                bypassResult.authorizationFailure?.let { failure ->
                    authorizationNotices += bypassAuthorizationNotice(failure)
                    reportBypassAuthorizationFailure(
                        info.owner,
                        info.repo,
                        prNumber,
                        info.installationId,
                        failure
                    )
                }
                bypassResult.messageFailure?.let { failure ->
                    val feedbackClaimed = claimBypassMessageFeedback(
                        info.owner,
                        info.repo,
                        prNumber,
                        failure
                    )
                    recordBypassMessageRefusal(
                        owner = info.owner,
                        repo = info.repo,
                        prNumber = prNumber,
                        failure = failure,
                        headSha = info.headSha,
                        headBranch = info.headBranch,
                        checkRunId = checkRunId,
                        feedbackClaimed = feedbackClaimed
                    )
                    reportBypassMessageFailure(
                        info.owner,
                        info.repo,
                        prNumber,
                        info.installationId,
                        failure,
                        feedbackClaimed
                    )
                }
                if (bypassResult.isActive) {
                    val bypassMessage = requireNotNull(bypassResult.message) {
                        "An active /bypass for ${info.owner}/${info.repo} PR #$prNumber did not include its message."
                    }
                    println("PR #$prNumber has active /bypass, marking check run $checkRunId as bypassed...")
                    journal?.record(
                        type = CiActivityType.BYPASS_APPLIED,
                        owner = info.owner,
                        repo = info.repo,
                        prNumbers = if (verifyCurrentPullRequest) prNumbers else listOf(prNumber),
                        headSha = info.headSha,
                        headBranch = info.headBranch,
                        checkRunId = checkRunId,
                        outcome = CiActivityOutcome.BYPASS,
                        detail = "Check marked green by an active /bypass on pull request #$prNumber. " +
                            "Message: ${bypassMessageForMarkdown(bypassMessage)}. No build was run." +
                            sharedHeadDetail
                    )
                    completeCheckRun(
                        owner = info.owner,
                        repo = info.repo,
                        installationId = info.installationId,
                        checkRunId = checkRunId,
                        conclusion = "success",
                        summary = appendCheckRunNotice(
                            "Build bypassed via /bypass comment on PR #$prNumber: " +
                                bypassMessageForMarkdown(bypassMessage),
                            authorizationNotices.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
                        )
                    )
                    println("Check run $checkRunId bypassed successfully!")
                    return true
                }
            }

            val authorizationNotice = authorizationNotices.takeIf { it.isNotEmpty() }?.joinToString("\n\n")

            // The emergency stop and the repository's build rate are evaluated here — after a
            // /bypass has had its say, so the escape hatch still works during an incident, and
            // before any work is submitted, so a refused build costs the build service nothing.
            val admission = admissionControl?.admit(info.owner, info.repo) ?: CiAdmissionDecision.Admitted
            if (admission is CiAdmissionDecision.Refused) {
                println(
                    "Refusing the build for ${info.owner}/${info.repo} @ ${info.headSha}: " +
                        admission.outcome
                )
                journal?.record(
                    type = CiActivityType.BUILD_REFUSED,
                    owner = info.owner,
                    repo = info.repo,
                    prNumbers = prNumbers,
                    headSha = info.headSha,
                    headBranch = info.headBranch,
                    checkRunId = checkRunId,
                    outcome = admission.outcome,
                    detail = admission.summary.lineSequence().first() + sharedHeadDetail
                )
                completeCheckRun(
                    owner = info.owner,
                    repo = info.repo,
                    installationId = info.installationId,
                    checkRunId = checkRunId,
                    conclusion = "failure",
                    summary = appendCheckRunNotice(admission.summary, authorizationNotice)
                )
                return true
            }

            if (!buildRunner.canRunBuilds()) {
                journal?.record(
                    type = CiActivityType.BUILD_REFUSED,
                    owner = info.owner,
                    repo = info.repo,
                    prNumbers = prNumbers,
                    headSha = info.headSha,
                    headBranch = info.headBranch,
                    checkRunId = checkRunId,
                    outcome = CiActivityOutcome.BUILD_SERVICE_UNAVAILABLE,
                    detail = "The remote build service is not available, so no build could be started." + sharedHeadDetail
                )
                System.err.println("CRITICAL: Build service unavailable, marking check run $checkRunId as FAILED for ${info.owner}/${info.repo} @ ${info.headSha}")
                completeCheckRun(
                    owner = info.owner,
                    repo = info.repo,
                    installationId = info.installationId,
                    checkRunId = checkRunId,
                    conclusion = "failure",
                    summary = appendCheckRunNotice(
                        "kotlin.build CI infrastructure error: the remote build service (url://buildtest/) " +
                            "is not available. Builds cannot be executed. This requires immediate investigation.",
                        authorizationNotice
                    )
                )
            } else {
                val hasBuildKts = repoHasBuildKtsOrCompleteFailure(
                    owner = info.owner,
                    repo = info.repo,
                    headSha = info.headSha,
                    installationId = info.installationId,
                    checkRunId = checkRunId,
                    checkRunNotice = authorizationNotice
                ) ?: return true
                if (hasBuildKts) {
                    println("Repository ${info.owner}/${info.repo} has build.kts, triggering async build...")
                    journal?.record(
                        type = CiActivityType.BUILD_DISPATCHED,
                        owner = info.owner,
                        repo = info.repo,
                        prNumbers = prNumbers,
                        headSha = info.headSha,
                        headBranch = info.headBranch,
                        checkRunId = checkRunId,
                        detail = "Submitted to the $routeDescription build service." + sharedHeadDetail
                    )
                    buildRunner.runBuildAsync(
                        owner = info.owner,
                        repo = info.repo,
                        headSha = info.headSha,
                        installationId = info.installationId,
                        checkRunId = checkRunId,
                        prNumbers = prNumbers,
                        headBranch = info.headBranch,
                        triggerReason = withRouteNotice(authorizationNotice),
                        checkRunNotice = withRouteNotice(authorizationNotice)
                    )
                } else {
                    println("Check run created with ID: $checkRunId, marking as successful (no build.kts)...")
                    completeCheckRun(
                        owner = info.owner,
                        repo = info.repo,
                        installationId = info.installationId,
                        checkRunId = checkRunId,
                        conclusion = "success",
                        summary = appendCheckRunNotice(
                            "kotlin.build CI check passed (no build.kts found in repository).",
                            authorizationNotice
                        )
                    )
                    println("Check run $checkRunId completed successfully!")
                }
            }
            return true
        } catch (e: Exception) {
            if (!checkRunCreated) missingCheckLookupUnverified.set(true)
            println("Error processing build request for ${info.owner}/${info.repo}: ${e.message}")
            e.printStackTrace()
            completeUnhandledFailure(
                owner = info.owner,
                repo = info.repo,
                installationId = info.installationId,
                checkRunId = createdCheckRunId,
                operation = "processing the build request",
                error = e
            )
            return checkRunCreated
        }
    }

    private fun handleIssueComment(payload: Map<String, Any?>) {
        val action = payload["action"] as? String
        if (action != "created") {
            println("Ignoring issue_comment action: $action")
            return
        }

        val issue = payload["issue"] as? Map<*, *> ?: return
        val pullRequest = issue["pull_request"] as? Map<*, *>
        if (pullRequest == null) {
            println("Ignoring comment on non-PR issue")
            return
        }

        val comment = payload["comment"] as? Map<*, *> ?: return
        val commentBody = (comment["body"] as? String) ?: return

        val bypassKeyword = findBypassKeyword(commentBody)
        val retriggerKeyword = findRetriggerKeyword(commentBody)

        if (bypassKeyword == null && retriggerKeyword == null) {
            println("Comment does not contain trigger keyword, ignoring")
            return
        }

        val isBypass = bypassKeyword != null
        val matchedKeyword = bypassKeyword ?: retriggerKeyword!!

        val repo = payload["repository"] as? Map<*, *> ?: return
        val owner = (repo["owner"] as? Map<*, *>)?.get("login") as? String ?: return
        val repoName = repo["name"] as? String ?: return
        val prNumber = (issue["number"] as? Number)?.toInt() ?: return

        if (!isKotlinBuildDispatchEnabled(owner, repoName)) {
            println(
                "Ignoring $matchedKeyword for $owner/$repoName: " +
                    "$KOTLIN_BUILD_DISPATCH_DISABLED_REASON."
            )
            return
        }

        val installation = payload["installation"] as? Map<*, *>
            ?: throw IllegalArgumentException(
                "Missing 'installation' field in issue_comment payload for $owner/$repoName PR #$prNumber"
            )
        val installationId = (installation["id"] as? Number)?.toLong()
            ?: throw IllegalArgumentException(
                "Missing or non-numeric installation.id in issue_comment payload for " +
                    "$owner/$repoName PR #$prNumber, got: ${installation["id"]}"
            )

        val authorAssociation = comment["author_association"] as? String
        val authorLogin = (comment["user"] as? Map<*, *>)?.get("login") as? String
        val repositoryPermission = if (authorLogin == null) {
            "unknown (comment payload did not include user.login)"
        } else {
            try {
                onFetchRepositoryPermission(owner, repoName, authorLogin, installationId)
            } catch (e: Exception) {
                "lookup failed (${e.message ?: e.javaClass.simpleName})"
            }
        }
        if (!isAuthorizedCiCommandPermission(repositoryPermission)) {
            println(
                "Ignoring CI command $matchedKeyword because GitHub repository permission must be " +
                    "write, maintain, or admin, but was $repositoryPermission " +
                    "(comment.author_association=${authorAssociation ?: "missing"})."
            )
            journal?.record(
                type = if (isBypass) CiActivityType.BYPASS_REFUSED else CiActivityType.COMMAND_RECEIVED,
                owner = owner,
                repo = repoName,
                prNumbers = listOf(prNumber),
                actor = authorLogin.orEmpty(),
                outcome = CiActivityOutcome.NOT_AUTHORIZED,
                detail = "$matchedKeyword from ${authorLogin ?: "an unidentified account"} was not " +
                    "applied: repository permission was $repositoryPermission, and write, maintain " +
                    "or admin is required."
            )
            if (isBypass) {
                val commentId = (comment["id"] as? Number)?.toLong()
                reportBypassAuthorizationFailure(
                    owner,
                    repoName,
                    prNumber,
                    installationId,
                    BypassAuthorizationFailure(
                        commentId = commentId,
                        authorLogin = authorLogin,
                        authorAssociation = authorAssociation ?: "MISSING",
                        repositoryPermission = repositoryPermission,
                        createdAt = comment["created_at"] as? String ?: ""
                    )
                )
            }
            return
        }

        val bypassMessage = if (isBypass) extractBypassMessage(commentBody) else null
        if (isBypass && bypassMessage == null) {
            println(
                "Ignoring $matchedKeyword because /bypass requires a non-empty message on the same line."
            )
            val messageFailure = BypassMessageFailure(
                commentId = (comment["id"] as? Number)?.toLong(),
                authorLogin = authorLogin,
                createdAt = comment["created_at"] as? String ?: "",
                feedbackAlreadyPosted = liveBypassMessageFeedbackAlreadyPosted(
                    owner,
                    repoName,
                    prNumber,
                    installationId,
                    (comment["id"] as? Number)?.toLong()
                )
            )
            val feedbackClaimed = claimBypassMessageFeedback(
                owner,
                repoName,
                prNumber,
                messageFailure
            )
            recordBypassMessageRefusal(
                owner = owner,
                repo = repoName,
                prNumber = prNumber,
                failure = messageFailure,
                feedbackClaimed = feedbackClaimed
            )
            reportBypassMessageFailure(
                owner,
                repoName,
                prNumber,
                installationId,
                messageFailure,
                feedbackClaimed
            )
            return
        }

        println("Detected CI keyword: $matchedKeyword (bypass=$isBypass)")
        journal?.record(
            type = CiActivityType.COMMAND_RECEIVED,
            owner = owner,
            repo = repoName,
            prNumbers = listOf(prNumber),
            actor = authorLogin.orEmpty(),
            outcome = matchedKeyword,
            detail = "${authorLogin ?: "An authorized account"} commented $matchedKeyword on pull " +
                "request #$prNumber."
        )

        var createdCheckRunId: Long? = null
        try {
            println("Handling $matchedKeyword for $owner/$repoName PR #$prNumber (installation: $installationId)")

            val headSha = onFetchPrHeadSha(owner, repoName, prNumber, installationId)
            if (headSha == null) {
                println("Could not fetch PR head SHA for $owner/$repoName #$prNumber")
                return
            }

            // Set by the /rerun replacement step: anything it could not replace is reported on the
            // new check's summary as well as in the activity journal.
            var rerunNotice: String? = null
            fun createCheckAndDispatch() {
            println("Creating check run for $owner/$repoName @ $headSha")

            val checkRunId = createCheckRun(
                owner = owner,
                repo = repoName,
                installationId = installationId,
                headSha = headSha,
                name = checkRunName
            )
            createdCheckRunId = checkRunId

            if (isBypass) {
                val requiredBypassMessage = requireNotNull(bypassMessage)
                buildRunner.trackPendingCheckRunCompletion(
                    owner = owner,
                    repo = repoName,
                    headSha = headSha,
                    installationId = installationId,
                    checkRunId = checkRunId,
                    completion = CancellationCompletion(
                        "failure",
                        "kotlin.build CI could not apply $matchedKeyword because the service stopped " +
                            "before the remote bypass operation was durably confirmed."
                    )
                )
                val canceledBuildSummary =
                    "Build bypassed via /bypass comment on PR #$prNumber: " +
                        "${bypassMessageForMarkdown(requiredBypassMessage)}; " +
                        "the in-flight build was stopped."
                // Cancel all in-progress builds for this PR before bypassing (including
                // stale builds from force-pushed commits with a different headSha).
                val cancelledBuilds = buildRunner.cancelBuildsForRepo(
                    owner,
                    repoName,
                    // The operator's message is the bypass reason the build service records on
                    // each stopped run.
                    reason = requiredBypassMessage,
                    headSha = headSha,
                    prNumber = prNumber,
                    completion = CancellationCompletion("success", canceledBuildSummary),
                    remoteDisposition = RemoteRunDisposition.BYPASSED,
                )
                for (build in cancelledBuilds) {
                    buildRunner.completeCanceledBuild(
                        build = build,
                        conclusion = "success",
                        summary = canceledBuildSummary
                    )
                }
                if (cancelledBuilds.isNotEmpty()) {
                    println("Cancelled existing build for $owner/$repoName @ $headSha (triggered by $matchedKeyword on PR #$prNumber)")
                }

                println("Bypass requested, marking check run $checkRunId as successful without running build...")
                journal?.record(
                    type = CiActivityType.BYPASS_APPLIED,
                    owner = owner,
                    repo = repoName,
                    prNumbers = listOf(prNumber),
                    headSha = headSha,
                    checkRunId = checkRunId,
                    actor = authorLogin.orEmpty(),
                    outcome = CiActivityOutcome.BYPASS,
                    detail = "${authorLogin ?: "An authorized account"} bypassed the check with " +
                        "$matchedKeyword on pull request #$prNumber. Message: " +
                        bypassMessageForMarkdown(requiredBypassMessage) +
                        (if (cancelledBuilds.isEmpty()) {
                            "; no build was running."
                        } else {
                            "; ${cancelledBuilds.size} in-flight build(s) were cancelled."
                        })
                )
                buildRunner.completePendingCheckRun(
                    checkRunId,
                    CancellationCompletion(
                        "success",
                        "Build bypassed via $matchedKeyword comment: " +
                            bypassMessageForMarkdown(requiredBypassMessage)
                    )
                )
                println("Check run $checkRunId bypassed successfully!")
            } else if (refuseAdmissionForRetrigger(
                    owner = owner,
                    repo = repoName,
                    installationId = installationId,
                    checkRunId = checkRunId,
                    headSha = headSha,
                    prNumber = prNumber,
                    matchedKeyword = matchedKeyword
                )
            ) {
                // The check run has already been completed with the refusal, and the refusal is
                // recorded; there is nothing further to do for this comment.
            } else if (!buildRunner.canRunBuilds()) {
                journal?.record(
                    type = CiActivityType.BUILD_REFUSED,
                    owner = owner,
                    repo = repoName,
                    prNumbers = listOf(prNumber),
                    headSha = headSha,
                    checkRunId = checkRunId,
                    actor = authorLogin.orEmpty(),
                    outcome = CiActivityOutcome.BUILD_SERVICE_UNAVAILABLE,
                    detail = "The remote build service is not available, so the $matchedKeyword " +
                        "started no build."
                )
                System.err.println("CRITICAL: Build service unavailable, marking check run $checkRunId as FAILED for $owner/$repoName @ $headSha (re-triggered via $matchedKeyword)")
                completeCheckRun(
                    owner = owner,
                    repo = repoName,
                    installationId = installationId,
                    checkRunId = checkRunId,
                    conclusion = "failure",
                    summary = "kotlin.build CI infrastructure error: the remote build service (url://buildtest/) " +
                        "is not available. Builds cannot be executed. This requires immediate investigation. " +
                        "(re-triggered via $matchedKeyword comment)"
                )
            } else {
                val hasBuildKts = repoHasBuildKtsOrCompleteFailure(
                    owner = owner,
                    repo = repoName,
                    headSha = headSha,
                    installationId = installationId,
                    checkRunId = checkRunId,
                    checkRunNotice = "Re-triggered via $matchedKeyword comment"
                ) ?: return
                if (hasBuildKts) {
                    println("Repository $owner/$repoName has build.kts, triggering async build (re-triggered via $matchedKeyword)...")
                    journal?.record(
                        type = CiActivityType.BUILD_DISPATCHED,
                        owner = owner,
                        repo = repoName,
                        prNumbers = listOf(prNumber),
                        headSha = headSha,
                        checkRunId = checkRunId,
                        actor = authorLogin.orEmpty(),
                        outcome = matchedKeyword,
                        detail = "Re-triggered by $matchedKeyword on pull request #$prNumber and " +
                            "submitted to the $routeDescription build service."
                    )
                    buildRunner.runBuildAsync(
                        owner = owner,
                        repo = repoName,
                        headSha = headSha,
                        installationId = installationId,
                        checkRunId = checkRunId,
                        prNumbers = listOf(prNumber),
                        triggerReason = withRouteNotice("Re-triggered via $matchedKeyword on PR #$prNumber"),
                        checkRunNotice = listOfNotNull(rerunNotice, shadowRouteNotice()).joinToString("\n\n").ifEmpty { null }
                    )
                } else {
                    println("Check run created with ID: $checkRunId, marking as successful (no build.kts)...")
                    completeCheckRun(
                        owner = owner,
                        repo = repoName,
                        installationId = installationId,
                        checkRunId = checkRunId,
                        conclusion = "success",
                        summary = "kotlin.build CI check passed (no build.kts found in repository). (re-triggered via $matchedKeyword comment)"
                    )
                    println("Check run $checkRunId completed successfully!")
                }
            }
            }
            if (isBypass) {
                createCheckAndDispatch()
            } else {
                // A re-run always results in exactly one live build for the head: it supersedes, never
                // skips. While this comment owns the head's reservation (so the GitHub-alignment sweep
                // cannot rebuild the head in between), every tracked build for the commit is cancelled
                // through the completion gateway, which ends its worker, any pending degraded-status
                // retry and any deferred-admission entry, and concludes its check as cancelled; only
                // then is the new check created and its build started. A build the sweep has just
                // started for the head is superseded the same way.
                withHeadReservationWaiting(owner, repoName, headSha, installationId) {
                    val replacedSummary = "This check was replaced by a newer build requested with " +
                        "$matchedKeyword on pull request #$prNumber; that build reports on a new check run."
                    val supersedeNotes = mutableListOf<String>()
                    // Replace the head's builds one at a time, so a build another path already owns,
                    // or a replacement that fails, never stops the others from being replaced or the
                    // new build from starting.
                    val headBuilds = buildRunner.tracker.getAllBuilds().filter {
                        it.owner == owner && it.repo == repoName && it.headSha == headSha &&
                            it.terminalConclusion == null
                    }.sortedBy { it.checkRunId }
                    for (candidate in headBuilds) {
                        val recordNotReplaced: (String, String) -> Unit = { outcome, detail ->
                            supersedeNotes += detail
                            println(detail)
                            journal?.record(
                                type = CiActivityType.BUILD_CANCELLED,
                                owner = candidate.owner,
                                repo = candidate.repo,
                                prNumbers = candidate.prNumbers.ifEmpty { listOf(prNumber) },
                                headSha = candidate.headSha,
                                headBranch = candidate.headBranch,
                                checkRunId = candidate.checkRunId,
                                remoteRunId = candidate.remoteRunId,
                                actor = authorLogin.orEmpty(),
                                outcome = outcome,
                                detail = detail
                            )
                        }
                        if (candidate.cancellationRequested) {
                            // Another path (an operator, /bypass, a closed pull request, the watchdog)
                            // is already cancelling this build and will conclude its check.
                            recordNotReplaced(
                                CiActivityOutcome.RERUN_LEFT_TO_CANCELLING_PATH,
                                "Check ${candidate.checkRunId} was already being cancelled by another path, so " +
                                    "$matchedKeyword on pull request #$prNumber left it to that path and started " +
                                    "the new build."
                            )
                            continue
                        }
                        val replaced = try {
                            buildRunner.cancelBuildsForRepo(
                                owner = owner,
                                repo = repoName,
                                headSha = headSha,
                                checkRunId = candidate.checkRunId,
                                completion = CancellationCompletion("cancelled", replacedSummary),
                                remoteDisposition = RemoteRunDisposition.SUPERSEDED,
                                reason = "$matchedKeyword on $owner/$repoName pull request #$prNumber " +
                                    "started a replacement build for this commit"
                            )
                        } catch (error: Exception) {
                            recordNotReplaced(
                                CiActivityOutcome.RERUN_REPLACE_FAILED,
                                "Check ${candidate.checkRunId} could not be replaced by $matchedKeyword on pull " +
                                    "request #$prNumber (${error.javaClass.simpleName}: ${error.message}); the new " +
                                    "build was started anyway."
                            )
                            continue
                        }
                        for (build in replaced) {
                            val completed = buildRunner.completeCanceledBuild(
                                build = build,
                                conclusion = "cancelled",
                                summary = replacedSummary
                            )
                            if (!completed) {
                                println(
                                    "The check ${build.checkRunId} replaced by $matchedKeyword for $owner/$repoName @ " +
                                        "$headSha could not be concluded yet; its cancellation stays durable for recovery."
                                )
                            }
                            journal?.record(
                                type = CiActivityType.BUILD_CANCELLED,
                                owner = build.owner,
                                repo = build.repo,
                                prNumbers = build.prNumbers.ifEmpty { listOf(prNumber) },
                                headSha = build.headSha,
                                headBranch = build.headBranch,
                                checkRunId = build.checkRunId,
                                remoteRunId = build.remoteRunId,
                                actor = authorLogin.orEmpty(),
                                outcome = CiActivityOutcome.RERUN_REPLACED,
                                detail = "$matchedKeyword on pull request #$prNumber replaced this build with a new one."
                            )
                        }
                    }
                    rerunNotice = supersedeNotes.takeIf { it.isNotEmpty() }?.joinToString("\n")
                    createCheckAndDispatch()
                }
            }
        } catch (e: Throwable) {
            if (e !is Exception && e !is AssertionError) throw e
            println("Error handling issue_comment: ${e.message}")
            e.printStackTrace()
            // createCheckAndDispatch assigns this from a local function, so read it once.
            val failedCheckRunId = createdCheckRunId
            if (isBypass && failedCheckRunId != null) {
                completeBypassOperationFailure(
                    checkRunId = failedCheckRunId,
                    matchedKeyword = matchedKeyword,
                    error = e
                )
            } else {
                completeUnhandledFailure(
                    owner = owner,
                    repo = repoName,
                    installationId = installationId,
                    checkRunId = failedCheckRunId,
                    operation = "handling the $matchedKeyword issue comment",
                    error = e
                )
            }
        }
    }

    private fun handlePullRequest(payload: Map<String, Any?>, completion: HeadDispatchCompletion) {
        val action = payload["action"] as? String
        if (action != "closed" && action != "reopened" && action != "synchronize") {
            println("Ignoring pull_request action: $action")
            return
        }

        val repo = payload["repository"] as? Map<*, *> ?: return
        val owner = (repo["owner"] as? Map<*, *>)?.get("login") as? String ?: return
        val repoName = repo["name"] as? String ?: return
        val pr = payload["pull_request"] as? Map<*, *> ?: return
        val prNumber = (pr["number"] as? Number)?.toInt() ?: return
        val prHeadSha = (pr["head"] as? Map<*, *>)?.get("sha") as? String

        if (action == "synchronize") {
            supersedeOutdatedPullRequestBuilds(owner, repoName, prNumber, prHeadSha)
            return
        }

        if (action == "reopened") {
            val installation = payload["installation"] as? Map<*, *>
            val installationId = (installation?.get("id") as? Number)?.toLong()
            if (installationId == null || prHeadSha == null) {
                println(
                    "Cannot start a fresh check for reopened $owner/$repoName PR #$prNumber: " +
                        "installation.id=${installation?.get("id") ?: "missing"}, " +
                        "pull_request.head.sha=${prHeadSha ?: "missing"}."
                )
                return
            }
            val headRef = (pr["head"] as? Map<*, *>)?.get("ref") as? String
            processBuildRequestInternal(
                WebhookBuildInfo(
                    headSha = prHeadSha,
                    owner = owner,
                    repo = repoName,
                    installationId = installationId,
                    prNumbers = listOf(prNumber),
                    headBranch = headRef
                ),
                completion = completion
            )
            return
        }

        println("PR #$prNumber closed for $owner/$repoName (headSha=$prHeadSha), checking for in-progress builds to cancel...")
        val canceledBuildSummary =
            "PR #$prNumber was closed, canceling the in-flight build for this pull request."
        val cancellationCompletion = CancellationCompletion("cancelled", canceledBuildSummary)
        val cancelledBuilds = if (prHeadSha != null) {
            // Cancel by prNumber (catches stale builds from force-pushed commits) OR headSha
            // (backward compat for builds loaded from disk without prNumbers).
            buildRunner.cancelBuildsForRepo(
                owner,
                repoName,
                reason = "pull request #$prNumber was closed",
                headSha = prHeadSha,
                prNumber = prNumber,
                completion = cancellationCompletion,
                // Closing the pull request is a person deciding against these builds.
                remoteDisposition = RemoteRunDisposition.CANCELED,
            )
        } else {
            // Fallback: if head SHA is not in the payload, cancel all builds for this repo.
            // This is conservative but avoids silently doing nothing.
            buildRunner.cancelBuildsForRepo(
                owner,
                repoName,
                reason = "pull request #$prNumber was closed",
                completion = cancellationCompletion,
                remoteDisposition = RemoteRunDisposition.CANCELED,
            )
        }
        for (build in cancelledBuilds) {
            buildRunner.completeCanceledBuild(
                build = build,
                conclusion = "cancelled",
                summary = canceledBuildSummary
            )
        }
        if (cancelledBuilds.isNotEmpty()) {
            println("Cancelled in-progress build for $owner/$repoName PR #$prNumber (headSha=$prHeadSha)")
            for (build in cancelledBuilds) {
                journal?.record(
                    type = CiActivityType.BUILD_CANCELLED,
                    owner = build.owner,
                    repo = build.repo,
                    prNumbers = build.prNumbers.ifEmpty { listOf(prNumber) },
                    headSha = build.headSha,
                    headBranch = build.headBranch,
                    checkRunId = build.checkRunId,
                    remoteRunId = build.remoteRunId,
                    outcome = CiActivityOutcome.PULL_REQUEST_CLOSED,
                    detail = "Pull request #$prNumber was closed, so its in-flight build was cancelled."
                )
            }
        } else {
            println("No in-progress builds found for $owner/$repoName PR #$prNumber (headSha=$prHeadSha)")
        }
    }

    /**
     * Stops builds still running for an earlier head of a pull request that has just been pushed
     * to, recording them as superseded.
     *
     * GitHub sends `pull_request.synchronize` on every push to an open pull request, and separately
     * starts a check suite for the new head — so the build for the new commit is dispatched by that
     * other path, not here. All this does is retire the runs the push made pointless: they are
     * testing a commit nobody will merge, their verdict cannot gate anything, and until they finish
     * they occupy build machines the new run needs. Nobody asked for them to stop, which is why
     * they are recorded as superseded rather than canceled.
     *
     * Does nothing when the payload carries no head SHA: without knowing which commit is current
     * there is no way to tell the run that was just started from the ones it replaced, and stopping
     * all of them would kill the new build too.
     */
    private fun supersedeOutdatedPullRequestBuilds(
        owner: String,
        repoName: String,
        prNumber: Int,
        prHeadSha: String?,
    ) {
        if (prHeadSha == null) {
            println(
                "Cannot retire outdated builds for $owner/$repoName PR #$prNumber: the " +
                    "pull_request.synchronize payload carried no head SHA, so the build for the " +
                    "new commit cannot be told apart from the ones it replaced."
            )
            return
        }
        val supersededSummary =
            "A newer commit was pushed to PR #$prNumber, so this build is no longer current and " +
                "was stopped. The check for the new commit reports the result that counts."
        val supersededBuilds = buildRunner.supersedeOutdatedBuildsForPullRequest(
            owner = owner,
            repo = repoName,
            prNumber = prNumber,
            currentHeadSha = prHeadSha,
            completion = CancellationCompletion("cancelled", supersededSummary),
        )
        for (build in supersededBuilds) {
            buildRunner.completeCanceledBuild(
                build = build,
                conclusion = "cancelled",
                summary = supersededSummary,
            )
        }
        if (supersededBuilds.isNotEmpty()) {
            println(
                "Retired ${supersededBuilds.size} outdated build(s) for $owner/$repoName PR " +
                    "#$prNumber after a push to $prHeadSha"
            )
        }
    }

    private fun handlePush(payload: Map<String, Any?>) {
        val ref = payload["ref"] as? String ?: return
        val headCommit = payload["head_commit"] as? Map<*, *> ?: return
        val headSha = headCommit["id"] as? String ?: return

        val repo = payload["repository"] as? Map<*, *> ?: return
        val owner = (repo["owner"] as? Map<*, *>)?.get("login") as? String
            ?: (repo["owner"] as? Map<*, *>)?.get("name") as? String
            ?: return
        val repoName = repo["name"] as? String ?: return
        val defaultBranch = repo["default_branch"] as? String ?: "main"

        if (!isKotlinBuildDispatchEnabled(owner, repoName)) {
            println(
                "Ignoring push for $owner/$repoName: " +
                    "$KOTLIN_BUILD_DISPATCH_DISABLED_REASON."
            )
            return
        }

        // Only trigger builds for pushes to the default branch (main/master)
        if (ref != "refs/heads/$defaultBranch") {
            println("Ignoring push to non-default branch: $ref (default: $defaultBranch)")
            return
        }

        var createdCheckRunId: Long? = null
        var installationIdForFailure: Long? = null
        try {
            val installation = payload["installation"] as? Map<*, *>
                ?: throw IllegalArgumentException("Missing 'installation' field in push payload for $owner/$repoName @ $headSha")
            val installationId = (installation["id"] as? Number)?.toLong()
                ?: throw IllegalArgumentException("Missing or non-numeric installation.id in push payload for $owner/$repoName @ $headSha, got: ${installation["id"]}")
            installationIdForFailure = installationId

            println("Creating check run for push to $owner/$repoName @ $headSha (branch: $ref, installation: $installationId)")
            val checkRunId = createCheckRun(
                owner = owner,
                repo = repoName,
                installationId = installationId,
                headSha = headSha,
                name = checkRunName
            )
            createdCheckRunId = checkRunId

            if (refuseAdmissionForRetrigger(
                    owner = owner,
                    repo = repoName,
                    installationId = installationId,
                    checkRunId = checkRunId,
                    headSha = headSha,
                    prNumber = null,
                    matchedKeyword = null
                )
            ) {
                // Refused by the emergency stop or this repository's build rate; the check run has
                // already been completed with the reason and the refusal recorded.
                return
            }
            if (!buildRunner.canRunBuilds()) {
                journal?.record(
                    type = CiActivityType.BUILD_REFUSED,
                    owner = owner,
                    repo = repoName,
                    headSha = headSha,
                    headBranch = defaultBranch,
                    checkRunId = checkRunId,
                    outcome = CiActivityOutcome.BUILD_SERVICE_UNAVAILABLE,
                    detail = "The remote build service is not available, so the push to " +
                        "$defaultBranch started no build."
                )
                System.err.println("CRITICAL: Build service unavailable, marking check run $checkRunId as FAILED for $owner/$repoName @ $headSha (push to $defaultBranch)")
                completeCheckRun(
                    owner = owner,
                    repo = repoName,
                    installationId = installationId,
                    checkRunId = checkRunId,
                    conclusion = "failure",
                    summary = "kotlin.build CI infrastructure error: the remote build service (url://buildtest/) " +
                        "is not available. Builds cannot be executed. This requires immediate investigation."
                )
            } else {
                val hasBuildKts = repoHasBuildKtsOrCompleteFailure(
                    owner = owner,
                    repo = repoName,
                    headSha = headSha,
                    installationId = installationId,
                    checkRunId = checkRunId
                ) ?: return
                if (hasBuildKts) {
                    println("Repository $owner/$repoName has build.kts, triggering async build...")
                    journal?.record(
                        type = CiActivityType.BUILD_DISPATCHED,
                        owner = owner,
                        repo = repoName,
                        headSha = headSha,
                        headBranch = defaultBranch,
                        checkRunId = checkRunId,
                        detail = "Push to $defaultBranch submitted to the $routeDescription build service."
                    )
                    buildRunner.runBuildAsync(
                        owner = owner,
                        repo = repoName,
                        headSha = headSha,
                        installationId = installationId,
                        checkRunId = checkRunId,
                        headBranch = defaultBranch,
                        triggerReason = withRouteNotice("Push to $defaultBranch"),
                        checkRunNotice = shadowRouteNotice()
                    )
                } else {
                    println("Check run created with ID: $checkRunId, marking as successful (no build.kts)...")
                    completeCheckRun(
                        owner = owner,
                        repo = repoName,
                        installationId = installationId,
                        checkRunId = checkRunId,
                        conclusion = "success",
                        summary = "kotlin.build CI check passed (no build.kts found in repository)."
                    )
                    println("Check run $checkRunId completed successfully!")
                }
            }
        } catch (e: Exception) {
            println("Error handling push: ${e.message}")
            e.printStackTrace()
            installationIdForFailure?.let { knownInstallationId ->
                completeUnhandledFailure(
                    owner = owner,
                    repo = repoName,
                    installationId = knownInstallationId,
                    checkRunId = createdCheckRunId,
                    operation = "handling the push event",
                    error = e
                )
            }
        }
    }
}

/**
 * Dispatches one authenticated event to the required primary check and, when configured, to an
 * informational shadow check. The shadow dispatch follows the primary operation's evaluation,
 * including when its coordinator has parked it, and uses its own bounded executor. A failing
 * shadow service cannot change the primary
 * check result or throw into the primary handler.
 */
class DualRouteWebhookEventHandler(
    private val primary: WebhookEventHandler,
    private val shadow: WebhookEventHandler,
    private val shadowExecutor: Executor = newShadowProcessingExecutor()
) : ResultWebhookHandler {
    private data class ShadowHeadIdentity(
        val owner: String,
        val repo: String,
        val installationId: Long,
        val headSha: String
    )

    private data class ShadowIssueIdentity(val owner: String, val repo: String, val prNumber: Int)

    private val pendingShadowHeads = ConcurrentHashMap<ShadowHeadIdentity, Int>()
    // issue_comment payloads do not contain the PR head SHA. Scope their short-lived markers to
    // the repository and PR so an unrelated repository with the same SHA is never suppressed.
    private val pendingShadowIssueComments = ConcurrentHashMap<ShadowIssueIdentity, Int>()

    /** True while a shadow task for this exact repository/head or one of its PRs is pending. */
    fun isShadowDispatchPending(request: ReconcileBackfillRequest): Boolean =
        pendingShadowHeads.containsKey(request.shadowHeadIdentity()) ||
            request.prNumbers.any { pendingShadowIssueComments.containsKey(
                ShadowIssueIdentity(request.owner, request.repo, it)
            ) }

    fun isShadowDispatchPending(info: WebhookBuildInfo): Boolean =
        pendingShadowHeads.containsKey(info.shadowHeadIdentity()) ||
            info.prNumbers.any { pendingShadowIssueComments.containsKey(
                ShadowIssueIdentity(info.owner, info.repo, it)
            ) }

    override fun handleDelivery(eventType: String, payload: Map<String, Any?>, delivery: RerequestDelivery?): PullRequestHeadDispatchResult {
        return dispatchEvent(eventType, payload, delivery)
    }

    override fun handleWithResult(
        eventType: String,
        payload: Map<String, Any?>
    ): PullRequestHeadDispatchResult = dispatchEvent(eventType, payload, null)

    private fun dispatchEvent(eventType: String, payload: Map<String, Any?>, delivery: RerequestDelivery?): PullRequestHeadDispatchResult {
        val pendingHead = shadowHeadIdentityForEvent(eventType, payload)
        val pendingIssue = shadowIssueIdentityForEvent(eventType, payload)
        pendingHead?.let { pendingShadowHeads.compute(it) { _, count -> (count ?: 0) + 1 } }
        pendingIssue?.let { pendingShadowIssueComments.compute(it) { _, count -> (count ?: 0) + 1 } }
        val cleared = AtomicBoolean(false)
        val clearPending = {
            if (cleared.compareAndSet(false, true)) {
                pendingHead?.let { pendingShadowHeads.computeIfPresent(it) { _, count -> (count - 1).takeIf { it > 0 } } }
                pendingIssue?.let { pendingShadowIssueComments.computeIfPresent(it) { _, count -> (count - 1).takeIf { it > 0 } } }
            }
        }
        val actionStarted = AtomicBoolean(false)
        val routeDelivery = delivery?.let { original ->
            original.copy(startAction = {
                // Both routes belong to this one delivery. Once its boundary is persisted here,
                // the second route can dispatch; a reconstructed delivery cannot cross it again.
                actionStarted.get() || original.startAction().also { if (it) actionStarted.set(true) }
            })
        }
        return try {
            primary.handleDeliveryWithCompletion(eventType, payload, routeDelivery, onJoined = clearPending) { primaryResult ->
                if (primaryResult == PullRequestHeadDispatchResult.UNVERIFIED) {
                    clearPending()
                } else {
                    val finished = AtomicBoolean(false)
                    val finish = {
                        if (finished.compareAndSet(false, true)) {
                            clearPending()
                            delivery?.complete?.invoke(primaryResult)
                        }
                        Unit
                    }
                    val dispatched = dispatchShadow("event '$eventType'") {
                        shadow.handleDeliveryWithCompletion(eventType, payload, routeDelivery, onJoined = { finish() }) { finish() }
                    }
                    if (!dispatched) finish()
                }
            }
        } catch (e: Throwable) {
            clearPending()
            println("Error processing primary CI event '$eventType': ${e.message}")
            e.printStackTrace()
            PullRequestHeadDispatchResult.UNVERIFIED
        }
    }

    /** Processes an explicit back-fill, including synthetic queue refs, through both routes. */
    fun processBuildRequest(info: WebhookBuildInfo): Boolean =
        dispatchReconciled(info, false, false, { true }) { } == PullRequestHeadDispatchResult.ACTION_TAKEN

    /** Processes an explicit back-fill after each route checks whether its check is missing. */
    fun processBuildRequestIfMissing(info: WebhookBuildInfo): Boolean =
        dispatchReconciled(info, true, false, { true }) { } == PullRequestHeadDispatchResult.ACTION_TAKEN

    fun processBuildRequestIfMissingResult(
        info: WebhookBuildInfo,
        claimAllowance: () -> Boolean = { true }
    ): PullRequestHeadDispatchResult = dispatchReconciled(info, true, true, claimAllowance) { }

    /** Reports the primary operation's evaluated outcome, including after FIFO parking. */
    fun processBuildRequestIfMissingWithCompletion(
        info: WebhookBuildInfo,
        claimAllowance: () -> Boolean,
        verifySuiteBeforeClaim: (() -> LostDispatchVerificationResult)? = null,
        onEvaluated: (PullRequestHeadDispatchResult) -> Unit
    ) {
        dispatchReconciled(info, true, true, claimAllowance, verifySuiteBeforeClaim, onEvaluated)
    }

    private fun dispatchReconciled(
        info: WebhookBuildInfo,
        requireMissingCheckRun: Boolean,
        verifyCurrentPullRequest: Boolean,
        claimAllowance: () -> Boolean,
        verifySuiteBeforeClaim: (() -> LostDispatchVerificationResult)? = null,
        onEvaluated: (PullRequestHeadDispatchResult) -> Unit
    ): PullRequestHeadDispatchResult {
        val clearPending = retainShadowHead(pendingShadowHeads, info.shadowHeadIdentity())
        val completion = HeadDispatchCompletion(onEvaluated)
        var joined = false
        return try {
            primary.processBuildRequestWithCompletion(
                info, requireMissingCheckRun, verifyCurrentPullRequest, claimAllowance,
                verifySuiteBeforeClaim = verifySuiteBeforeClaim,
                onJoined = { joined = true; clearPending() }
            ) { primaryResult ->
                try {
                    if (joined || primaryResult == PullRequestHeadDispatchResult.UNVERIFIED) {
                        clearPending()
                    } else {
                        val dispatched = dispatchShadow("reconciled build request") {
                            try {
                                shadow.processBuildRequestWithCompletion(
                                    info, requireMissingCheckRun, verifyCurrentPullRequest, { true }
                                ) { clearPending() }
                            } catch (error: Throwable) {
                                clearPending()
                                throw error
                            }
                        }
                        if (!dispatched) clearPending()
                    }
                } finally {
                    completion.evaluated(primaryResult)
                }
            }
        } catch (e: Throwable) {
            clearPending()
            completion.evaluated(PullRequestHeadDispatchResult.UNVERIFIED)
            println("Error processing primary reconciled build request: ${e.message}")
            e.printStackTrace()
            PullRequestHeadDispatchResult.UNVERIFIED
        }
    }

    /** Processes only the shadow route, retaining pending ownership through queued evaluation. */
    fun processShadowBuildRequest(info: WebhookBuildInfo): Boolean {
        val clearPending = retainShadowHead(pendingShadowHeads, info.shadowHeadIdentity())
        // Synthetic queue refs have their own authority and must not be compared to a PR head.
        val verifyCurrentPr = info.prNumbers.isNotEmpty() &&
            info.headBranch?.startsWith("gh-readonly-queue/") != true
        return try {
            shadow.processBuildRequestWithCompletion(info, true, verifyCurrentPr, { true }) {
                clearPending()
            } == PullRequestHeadDispatchResult.ACTION_TAKEN
        } catch (error: Throwable) {
            clearPending()
            throw error
        }
    }

    private fun dispatchShadow(description: String, action: () -> Unit): Boolean {
        try {
            shadowExecutor.execute {
                try {
                    action()
                } catch (e: Throwable) {
                    println("Error processing shadow $description: ${e.message}")
                    e.printStackTrace()
                }
            }
            return true
        } catch (e: Throwable) {
            println("Could not schedule shadow $description: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    private fun shadowHeadIdentityForEvent(
        eventType: String,
        payload: Map<String, Any?>
    ): ShadowHeadIdentity? {
        val repository = payload["repository"] as? Map<*, *> ?: return null
        val owner = (repository["owner"] as? Map<*, *>)?.get("login") as? String ?: return null
        val repo = repository["name"] as? String ?: return null
        val installationId = ((payload["installation"] as? Map<*, *>)?.get("id") as? Number)?.toLong()
            ?: return null
        val headSha = headShaForEvent(eventType, payload) ?: return null
        return ShadowHeadIdentity(owner, repo, installationId, headSha)
    }

    private fun shadowIssueIdentityForEvent(
        eventType: String,
        payload: Map<String, Any?>
    ): ShadowIssueIdentity? {
        if (eventType != "issue_comment") return null
        val repository = payload["repository"] as? Map<*, *> ?: return null
        val owner = (repository["owner"] as? Map<*, *>)?.get("login") as? String ?: return null
        val repo = repository["name"] as? String ?: return null
        val prNumber = ((payload["issue"] as? Map<*, *>)?.get("number") as? Number)?.toInt() ?: return null
        return ShadowIssueIdentity(owner, repo, prNumber)
    }

    private fun headShaForEvent(eventType: String, payload: Map<String, Any?>): String? = when (eventType) {
        "check_suite" -> parseCheckSuitePayload(payload)?.headSha
        "check_run" -> parseCheckRunPayload(payload)?.headSha
        "push" -> (payload["head_commit"] as? Map<*, *>)?.get("id") as? String
        "pull_request" -> ((payload["pull_request"] as? Map<*, *>)?.get("head") as? Map<*, *>)?.get("sha") as? String
        else -> null
    }

    private fun ReconcileBackfillRequest.shadowHeadIdentity(): ShadowHeadIdentity =
        ShadowHeadIdentity(owner, repo, installationId, headSha)

    private fun WebhookBuildInfo.shadowHeadIdentity(): ShadowHeadIdentity =
        ShadowHeadIdentity(owner, repo, installationId, headSha)
}

fun newShadowProcessingExecutor(): ExecutorService =
    ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(256),
        { runnable -> Thread(runnable, "shadow-webhook-worker").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

/**
 * Runs shadow builds on their own independent pool. Rejection is reported by BuildRunner; a shadow
 * build is never allowed to run on a primary webhook or watchdog thread.
 *
 * The pool is the same elastic, no-waiting-room shape the primary route uses, for the same reason:
 * a shadow check spends nearly all of its lifecycle sleeping in its status poll, so a fixed worker
 * count would cap how many shadow checks can be in any phase at all rather than capping work.
 */
fun newShadowBuildExecutor(): ExecutorService = newBuildLifecycleExecutor("shadow-build-worker")

internal fun fetchPrHeadShaFromGitHub(
    owner: String,
    repo: String,
    prNumber: Int,
    installationId: Long
): String? {
    val gh = GitHubApi.getInstallationClient(installationId)
    val url = "https://api.github.com/repos/$owner/$repo/pulls/$prNumber"

    val request = gh.newRequestBuilder(url)
        .get()
        .build()

    gh.client.newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) {
            val body = resp.body?.string()
            println("Failed to fetch PR details: ${resp.code} ${resp.message} body=$body")
            return null
        }
        val body = resp.body?.string() ?: return null
        val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        val pr: Map<String, Any?> = mapper.readValue(body)
        val head = pr["head"] as? Map<*, *> ?: return null
        return head["sha"] as? String
    }
}

/** Each accepted operation owns one marker, including overlaps on the same head. */
private fun <T : Any> retainShadowHead(pending: ConcurrentHashMap<T, Int>, identity: T): () -> Unit {
    pending.compute(identity) { _, count -> (count ?: 0) + 1 }
    val cleared = AtomicBoolean(false)
    return {
        if (cleared.compareAndSet(false, true)) {
            pending.computeIfPresent(identity) { _, count -> (count - 1).takeIf { it > 0 } }
        }
        Unit
    }
}
