@file:Suppress("TooManyFunctions", "MatchingDeclarationName")

package com.calimero.mero.sample.explorer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calimero.mero.Mero
import com.calimero.mero.admin.CreateContextRequest
import com.calimero.mero.admin.CreateGroupInNamespaceRequest
import com.calimero.mero.admin.CreateNamespaceRequest
import com.calimero.mero.compose.MeroClient
import com.calimero.mero.sample.ui.Cal
import com.calimero.mero.sample.ui.CalCard
import com.calimero.mero.sample.ui.CalPrimaryButton
import com.calimero.mero.sample.ui.CalSection
import com.calimero.mero.sample.ui.Callout
import com.calimero.mero.sample.ui.CalloutKind
import com.calimero.mero.sample.ui.RowDivider
import com.calimero.mero.sample.ui.SectionLabel
import com.calimero.mero.sample.ui.TopBar
import com.calimero.mero.sample.ui.screenPad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// ---- The plan --------------------------------------------------------------------------------

/**
 * One step of "Run all": an explorer operation, the inputs to run it with, and what to remember
 * from its answer for the steps after it. (== the Swift sample's `RunStep`.)
 */
class RunStep(
    val opId: String,
    /** Inputs, built from what earlier steps found (ids, versions). */
    val inputs: (Map<String, String>) -> Map<String, String> = { emptyMap() },
    /** Ids to remember from the output, as fixture key → JSON key (`$first`: an array's first element). */
    val captures: Map<String, String> = emptyMap(),
    /** An extra check on the output; returns why it failed, or null. */
    val expect: ((String) -> String?)? = null,
    /** Fixture keys this step needs; it is skipped when one is missing. */
    val needs: List<String> = emptyList(),
)

/**
 * What "Run all" does, in order:
 * 1. **Reads**: every operation that takes no input.
 * 2. **Lifecycle**: a throwaway kv-store app from the registry, a namespace, a group, a context,
 *    RPC writes and reads, aliases, a blob and an invite, each step fed the ids the earlier ones
 *    returned; then it deletes what it made, so the node is left as it was.
 * 3. **Skipped**: everything else, with the reason.
 *
 * Only a session signed in to a node directly runs the lifecycle; a Cloud session runs the reads
 * through the relay and skips the rest. (== the Swift sample's `RunAllPlan`.)
 */
object RunAllPlan {
    const val PACKAGE = "com.calimero.kv-store"
    const val REGISTRY = "https://apps.calimero.network"

    private val ns: (Map<String, String>) -> Map<String, String> = { mapOf("namespaceId" to it.f("namespaceId")) }
    private val group: (Map<String, String>) -> Map<String, String> = { mapOf("groupId" to it.f("groupId")) }
    private val ctx: (Map<String, String>) -> Map<String, String> = { mapOf("contextId" to it.f("contextId")) }
    private val blob: (Map<String, String>) -> Map<String, String> = { mapOf("blobId" to it.f("blobId")) }
    private val app: (Map<String, String>) -> Map<String, String> = { mapOf("appId" to it.f("appId")) }
    private val member: (Map<String, String>) -> Map<String, String> = {
        mapOf("groupId" to it.f("groupId"), "identity" to it.f("member"))
    }

    private fun Map<String, String>.f(key: String): String = this[key] ?: ""

    @Suppress("LongMethod")
    fun lifecycle(stamp: String): List<RunStep> {
        val alias = "run-all-$stamp"
        return listOf(
            RunStep("pkg.regVersions", { mapOf("registryUrl" to REGISTRY, "packageName" to PACKAGE) }, mapOf("version" to "\$first")),
            RunStep("pkg.semver", { mapOf("a" to "1.2.0", "b" to "1.10.0") }, expect = { output ->
                // "compareSemver(a, b) = n": n < 0 when a sorts first.
                val n =
                    output
                        .substringAfterLast("=")
                        .trim()
                        .toIntOrNull()
                if ((n ?: 0) < 0) null else "1.2.0 should sort before 1.10.0"
            }),
            RunStep(
                "pkg.install",
                { mapOf("packageName" to PACKAGE, "version" to it.f("version")) },
                mapOf("appId" to "applicationId"),
                needs = listOf("version"),
            ),
            RunStep("app.get", app, needs = listOf("appId")),
            RunStep("app.versions", app, needs = listOf("appId")),
            RunStep("app.abi", app, needs = listOf("appId")),
            RunStep("pkg.versions", { mapOf("packageName" to PACKAGE) }),
            RunStep("pkg.latest", { mapOf("packageName" to PACKAGE) }),
            RunStep(
                "ns.create",
                { mapOf("body" to """{"applicationId":"${it.f("appId")}","name":"$alias"}""") },
                mapOf("namespaceId" to "namespaceId"),
                needs = listOf("appId"),
            ),
            RunStep("ns.get", ns, needs = listOf("namespaceId")),
            RunStep("ns.groups", ns, needs = listOf("namespaceId")),
            RunStep("ns.migStatus", ns, needs = listOf("namespaceId")),
            RunStep("ns.cascade", ns, needs = listOf("namespaceId")),
            RunStep("app.nsFor", app, needs = listOf("appId")),
            RunStep(
                "ns.groupCreate",
                { mapOf("namespaceId" to it.f("namespaceId"), "body" to """{"groupName":"run-all","visibility":"open"}""") },
                mapOf("groupId" to "groupId"),
                needs = listOf("namespaceId"),
            ),
            RunStep("grp.info", group, needs = listOf("groupId")),
            RunStep("grp.contexts", group, needs = listOf("groupId")),
            RunStep("grp.defCaps", group, needs = listOf("groupId")),
            RunStep("grp.subVis", group, needs = listOf("groupId")),
            // Swift's `grp.memberDevices`: the Kotlin explorer files it under Account & Devices.
            RunStep("acc.memberDevices", group, needs = listOf("groupId")),
            RunStep("mem.list", group, mapOf("member" to "identity"), needs = listOf("groupId")),
            RunStep("set.grpMetaGet", group, needs = listOf("groupId")),
            RunStep("set.teeGet", group, needs = listOf("groupId")),
            RunStep("up.status", group, needs = listOf("groupId")),
            RunStep("grp.subgroups", { mapOf("groupId" to it.f("namespaceId")) }, needs = listOf("namespaceId")),
            RunStep(
                "ctx.create",
                { mapOf("body" to """{"applicationId":"${it.f("appId")}","groupId":"${it.f("groupId")}","name":"run-all"}""") },
                mapOf("contextId" to "contextId", "identity" to "memberPublicKey"),
                needs = listOf("appId", "groupId"),
            ),
            RunStep("ctx.get", ctx, needs = listOf("contextId")),
            RunStep("ctx.group", ctx, needs = listOf("contextId")),
            RunStep("ctx.storage", ctx, needs = listOf("contextId")),
            RunStep("cid.list", ctx, needs = listOf("contextId")),
            RunStep("cid.owned", ctx, needs = listOf("contextId")),
            RunStep("app.ctxFor", app, needs = listOf("appId")),
            RunStep("app.ctxExec", app, needs = listOf("appId")),
            RunStep("mem.getCaps", member, needs = listOf("groupId", "member")),
            RunStep("mem.getMeta", member, needs = listOf("groupId", "member")),
            RunStep(
                "rpc.execute",
                { mapOf("contextId" to it.f("contextId"), "method" to "set", "args" to """{"key":"run-all","value":"ok"}""") },
                needs = listOf("contextId"),
            ),
            RunStep(
                "rpc.execute",
                { mapOf("contextId" to it.f("contextId"), "method" to "get", "args" to """{"key":"run-all"}""") },
                expect = { if (it.contains("\"ok\"")) null else "get did not return the value set just before" },
                needs = listOf("contextId"),
            ),
            RunStep("al.ctxCreate", { mapOf("body" to """{"alias":"$alias","contextId":"${it.f("contextId")}"}""") }, needs = listOf("contextId")),
            RunStep("al.ctxLookup", { mapOf("name" to alias) }, needs = listOf("contextId")),
            RunStep("al.ctxDelete", { mapOf("name" to alias) }, needs = listOf("contextId")),
            RunStep("al.appCreate", { mapOf("body" to """{"alias":"$alias","applicationId":"${it.f("appId")}"}""") }, needs = listOf("appId")),
            RunStep("al.appLookup", { mapOf("name" to alias) }, needs = listOf("appId")),
            RunStep("al.appDelete", { mapOf("name" to alias) }, needs = listOf("appId")),
            RunStep(
                "blob.upload",
                { mapOf("text" to "run-all $alias", "contextId" to it.f("contextId")) },
                mapOf("blobId" to "blobId"),
                needs = listOf("contextId"),
            ),
            RunStep("blob.info", blob, needs = listOf("blobId")),
            RunStep("blob.get", blob, needs = listOf("blobId")),
            RunStep("blob.delete", blob, needs = listOf("blobId")),
            RunStep("ns.invite", ns, needs = listOf("namespaceId")),
            RunStep("ctx.sync", ctx, needs = listOf("contextId")),
            // Cleanup, newest first.
            RunStep("ctx.delete", ctx, needs = listOf("contextId")),
            RunStep("ns.delete", ns, needs = listOf("namespaceId")),
            RunStep("app.uninstall", app, needs = listOf("appId", "installedByRun")),
        )
    }

    /** Why an operation is not run automatically. */
    fun skipReason(
        op: SDKOperation,
        signedInToNode: Boolean,
    ): String =
        when {
            op.id in cloudOperationIds -> "Cloud & relay: runs from its form on a Cloud session"
            !signedInToNode -> "Needs a node you sign in to directly"
            op.id in setOf("auth.gen", "auth.refresh", "auth.mock", "auth.revoke", "auth.validate") ->
                "Mints or revokes tokens: would replace or end this session"
            op.id.startsWith("key.") -> "Changes the node's root or client keys"
            op.id in
                setOf(
                    "ctx.join", "ctx.leave", "ns.join", "ns.leave", "ns.admit", "up.join", "up.joinInherit", "grp.leave",
                    "ns.revokeDevice", "mem.add", "mem.remove", "mem.role",
                ) -> "Needs a second node or member"
            op.id.startsWith("acc.") -> "Pairs or re-links devices on the account"
            op.id in setOf("ctx.updateApp", "up.upgrade", "up.retry", "ns.abort", "grp.reparent") -> "Needs a second application version"
            op.id in setOf("rpc.migrate", "rpc.pending") -> "Needs an app with entry migration (kv-store has none)"
            op.id in setOf("tee.attest", "set.teeSet", "ns.nsProof", "grp.proof") -> "Needs a TEE node"
            else -> "Needs input this run does not create; run it from its form"
        }
}

// ---- The runner ------------------------------------------------------------------------------

/** "Run all"'s state: kept above the SDK tab's screens, so a run survives looking at a result. */
class RunAllModel {
    sealed interface Status {
        data object Pending : Status

        data object Running : Status

        data object Passed : Status

        data class Failed(
            val why: String,
        ) : Status

        data class Skipped(
            val why: String,
        ) : Status
    }

    data class Result(
        val id: Int,
        /** The explorer operation, or null for a contract test case. */
        val op: SDKOperation?,
        val title: String,
        val subtitle: String,
        val summary: String,
        val phase: String,
        /** The contract case, for a "Contract" row. */
        val contractCase: ContractSpec.Case? = null,
        /** Index into the lifecycle steps, for a lifecycle row. */
        val step: Int? = null,
        val inputs: Map<String, String> = emptyMap(),
        val status: Status = Status.Pending,
        val output: String = "",
        val millis: Long = 0,
    ) {
        companion object {
            fun of(
                id: Int,
                op: SDKOperation,
                phase: String,
                step: Int? = null,
                status: Status = Status.Pending,
            ) = Result(id, op, op.name, op.category, op.summary, phase, step = step, status = status)

            fun of(
                id: Int,
                contractCase: ContractSpec.Case,
            ) = Result(
                id,
                null,
                contractCase.name,
                contractCase.group,
                "scaffolding-e2e · ${contractCase.id}",
                PHASE_CONTRACT,
                contractCase = contractCase,
            )
        }
    }

    val results = mutableStateListOf<Result>()
    var running by mutableStateOf(false)
        private set
    var finished by mutableStateOf(false)
        private set

    val passed: Int get() = results.count { it.status == Status.Passed }
    val failed: Int get() = results.count { it.status is Status.Failed }
    val skipped: Int get() = results.count { it.status is Status.Skipped }
    val done: Int get() = results.count { it.status != Status.Pending && it.status != Status.Running }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    suspend fun run(
        client: MeroClient,
        signedInToNode: Boolean,
        contractSpec: ContractSpec? = null,
    ) {
        if (running) return
        running = true
        finished = false
        try {
            val byId = sdkOperations.associateBy { it.id }
            val reads = sdkOperations.filter { it.fields.isEmpty() && it.id !in cloudOperationIds }
            val steps = if (signedInToNode) RunAllPlan.lifecycle((System.currentTimeMillis() / MILLIS).toString()) else emptyList()

            val planned = mutableListOf<Result>()
            reads.forEach { planned += Result.of(planned.size, it, PHASE_READS) }
            steps.forEachIndexed { index, step -> byId[step.opId]?.let { planned += Result.of(planned.size, it, PHASE_LIFECYCLE, step = index) } }
            val covered = planned.mapNotNull { it.op?.id }.toSet()
            val spec = if (signedInToNode) contractSpec else null
            spec?.cases?.forEach { planned += Result.of(planned.size, it) }
            sdkOperations.filter { it.id !in covered }.forEach {
                planned += Result.of(planned.size, it, PHASE_SKIPPED, status = Status.Skipped(RunAllPlan.skipReason(it, signedInToNode)))
            }
            results.clear()
            results.addAll(planned)

            if (client.mero == null) {
                results.indices.filter { results[it].status == Status.Pending }.forEach {
                    results[it] = results[it].copy(status = Status.Skipped("No admin session yet"))
                }
                return
            }
            val env = OpEnv(client)
            val fixtures = mutableMapOf<String, String>()
            if (signedInToNode) {
                val installed = runCatching { env.admin.listApplications().apps }.getOrNull()
                // Only uninstall at the end what this run installed.
                if (installed != null && installed.none { it.packageName == RunAllPlan.PACKAGE }) fixtures["installedByRun"] = "1"
            }

            for (i in results.indices) {
                if (results[i].status == Status.Pending && results[i].op != null) runOne(i, steps, env, fixtures)
            }
            if (spec != null) runContract(spec, env.mero)
        } finally {
            running = false
            finished = true
        }
    }

    /** Run result [i]: skip it when a fixture it needs is missing, else run it and remember what it captures. */
    private suspend fun runOne(
        i: Int,
        steps: List<RunStep>,
        env: OpEnv,
        fixtures: MutableMap<String, String>,
    ) {
        val step = results[i].step?.let { steps[it] }
        val missing = step?.needs?.firstOrNull { it !in fixtures }
        if (missing != null) {
            val why =
                if (missing == "installedByRun") {
                    "${RunAllPlan.PACKAGE} was already installed; left in place"
                } else {
                    "An earlier step did not produce $missing"
                }
            results[i] = results[i].copy(status = Status.Skipped(why))
            return
        }
        val inputs = step?.inputs?.invoke(fixtures) ?: emptyMap()
        results[i] = results[i].copy(inputs = inputs, status = Status.Running)
        val started = System.currentTimeMillis()
        try {
            val output = results[i].op!!.run(env, inputs)
            val millis = System.currentTimeMillis() - started
            val reason = step?.expect?.invoke(output)
            results[i] =
                results[i].copy(output = output, millis = millis, status = reason?.let { Status.Failed(it) } ?: Status.Passed)
            if (reason == null) capture(step, output, fixtures)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e.toString()
            results[i] = results[i].copy(output = message, millis = System.currentTimeMillis() - started, status = Status.Failed(message))
        }
    }

    private fun capture(
        step: RunStep?,
        output: String,
        fixtures: MutableMap<String, String>,
    ) {
        step?.captures?.forEach { (key, jsonKey) -> find(jsonKey, output)?.let { fixtures[key] = it } }
    }

    /**
     * The scaffolding-e2e contract tests: install the app from the registry, make a namespace,
     * group and context, run every case in order with one shared `${run}` prefix, then delete what
     * was made (the app only when this run installed it).
     */
    @Suppress("LongMethod")
    private suspend fun runContract(
        spec: ContractSpec,
        mero: Mero,
    ) {
        val rows = results.indices.filter { results[it].phase == PHASE_CONTRACT }
        try {
            val installedBefore =
                runCatching {
                    mero.admin
                        .listApplications()
                        .apps
                        .any { it.packageName == spec.packageName }
                }.getOrNull()
            val version = mero.admin.getRegistryVersions(RunAllPlan.REGISTRY, spec.packageName).firstOrNull()
            if (version == null) {
                rows.forEach { results[it] = results[it].copy(status = Status.Skipped("${spec.packageName} is not on the registry")) }
                return
            }
            val app = mero.admin.installFromRegistry(spec.packageName, version)
            val ns = mero.admin.createNamespace(CreateNamespaceRequest(applicationId = app.applicationId, name = "run-all-contract"))
            val group =
                mero.admin.createGroupInNamespace(
                    ns.namespaceId,
                    CreateGroupInNamespaceRequest(groupName = "contract", visibility = CreateGroupInNamespaceRequest.VISIBILITY_OPEN),
                )
            val context =
                mero.admin.createContext(CreateContextRequest(applicationId = app.applicationId, groupId = group.groupId, name = "contract"))

            val runner = ContractCaseRunner(mero, context.contextId, "e2e_" + (System.currentTimeMillis() / MILLIS).toString(BASE36))
            for (i in rows) {
                val testCase = results[i].contractCase ?: continue
                results[i] = results[i].copy(status = Status.Running)
                val started = System.currentTimeMillis()
                val why = runner.run(testCase)
                results[i] =
                    results[i].copy(
                        millis = System.currentTimeMillis() - started,
                        status = why?.let { Status.Failed(it) } ?: Status.Passed,
                        output = why ?: "passed",
                    )
            }

            runCatching { mero.admin.deleteContext(context.contextId) }
            runCatching { mero.admin.deleteNamespace(ns.namespaceId) }
            if (installedBefore == false) runCatching { mero.admin.uninstallApplication(app.applicationId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e.toString()
            rows.filter { results[it].status == Status.Pending }.forEach {
                results[it] = results[it].copy(status = Status.Failed("Setup failed: $message"))
            }
        }
    }

    fun report(): String =
        buildString {
            append("MeroKit Run all — $passed passed, $failed failed, $skipped skipped")
            results.forEach { r ->
                append('\n')
                append(
                    when (val s = r.status) {
                        Status.Passed -> "PASS  ${r.title} (${r.millis} ms)"
                        is Status.Failed -> "FAIL  ${r.title}: ${s.why}"
                        is Status.Skipped -> "SKIP  ${r.title}: ${s.why}"
                        else -> "....  ${r.title}"
                    },
                )
            }
        }

    companion object {
        const val PHASE_READS = "Reads"
        const val PHASE_LIFECYCLE = "Lifecycle"
        const val PHASE_CONTRACT = "Contract"
        const val PHASE_SKIPPED = "Skipped"
        val PHASES = listOf(PHASE_READS, PHASE_LIFECYCLE, PHASE_CONTRACT, PHASE_SKIPPED)
        private const val MILLIS = 1000L
        private const val BASE36 = 36

        /** The first string value under [key] anywhere in a JSON document, or a top-level array's first element for `$first`. */
        fun find(
            key: String,
            json: String,
        ): String? {
            val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return null
            if (key == "\$first") {
                val first = (root as? JsonArray)?.firstOrNull() ?: return null
                return (first as? JsonPrimitive)?.contentOrNull ?: first.toString()
            }

            fun walk(node: JsonElement): String? =
                when (node) {
                    is JsonObject ->
                        (node[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                            ?: node.values.firstNotNullOfOrNull(::walk)
                    is JsonArray -> node.firstNotNullOfOrNull(::walk)
                    else -> null
                }
            return walk(root)
        }
    }
}

// ---- Views -----------------------------------------------------------------------------------

/** The "Run all" entry at the top of the SDK list. */
@Composable
fun RunAllEntry(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Cal.radiusCard))
            .background(Cal.surface)
            .border(1.dp, Cal.border, RoundedCornerShape(Cal.radiusCard))
            .clickable(onClick = onClick)
            .padding(16.dp)
            .testTag("runAllEntry"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Cal.lime), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, null, tint = Cal.onLime, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Run all", color = Cal.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("Test every method against this session and see what passes", color = Cal.textDim, fontSize = 13.sp)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = Cal.textFaint, modifier = Modifier.size(18.dp))
    }
}

private enum class RunFilter(
    val label: String,
) {
    ALL("All"),
    FAILED("Failed"),
    PASSED("Passed"),
    SKIPPED("Skipped"),
}

/** The Run all screen: summary and button, a filter, then the results by phase. Tap a row for its detail. */
@Composable
@Suppress("LongMethod")
fun RunAllScreen(
    client: MeroClient,
    model: RunAllModel,
    signedInToNode: Boolean,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val contractSpec = remember { ContractSpec.load(context) }
    var filter by remember { mutableStateOf(RunFilter.ALL) }
    var copied by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<RunAllModel.Result?>(null) }

    val open = detail
    if (open != null) {
        BackHandler { detail = null }
        RunResultDetail(model.results.getOrNull(open.id) ?: open, onBack = { detail = null })
        return
    }
    BackHandler(onBack = onBack)

    val visible =
        model.results.filter { r ->
            when (filter) {
                RunFilter.ALL -> true
                RunFilter.FAILED -> r.status is RunAllModel.Status.Failed
                RunFilter.PASSED -> r.status == RunAllModel.Status.Passed
                RunFilter.SKIPPED -> r.status is RunAllModel.Status.Skipped
            }
        }

    Column(Modifier.fillMaxSize().background(Cal.bg)) {
        TopBar(
            "Run all",
            onBack = onBack,
            actions = {
                if (model.finished) {
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(model.report()))
                        copied = true
                    }) { Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, "Copy report", tint = Cal.accentInk) }
                }
            },
        )
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = screenPad + 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Spacer(Modifier.height(0.dp)) }
            item {
                CalCard(padding = 18.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            if (signedInToNode) {
                                "Runs every read, then a full lifecycle on a throwaway kv-store app (install, namespace, " +
                                    "context, RPC, aliases, blob), then the scaffolding-e2e contract tests, and deletes " +
                                    "what it made."
                            } else {
                                "Runs every read through your Cloud session. The lifecycle needs a node you sign in to " +
                                    "directly (Manually enter node URL)."
                            },
                            color = Cal.textDim,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        )
                        if (model.results.isNotEmpty()) {
                            LinearProgressIndicator(
                                progress = { model.done.toFloat() / model.results.size.coerceAtLeast(1) },
                                color = if (model.failed > 0) Cal.error else Cal.accentInk,
                                trackColor = Cal.bgSubtle,
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("runAllSummary")) {
                                StatPill(model.passed, "passed", Cal.success, Cal.successSoft)
                                StatPill(model.failed, "failed", Cal.error, Cal.errorSoft)
                                StatPill(model.skipped, "skipped", Cal.textDim, Cal.bgSubtle)
                            }
                        }
                        Box(contentAlignment = Alignment.Center) {
                            CalPrimaryButton(
                                when {
                                    model.running -> "Running ${model.done} of ${model.results.size}…"
                                    model.finished -> "Run again"
                                    else -> "Run all"
                                },
                                onClick = {
                                    copied = false
                                    filter = RunFilter.ALL
                                    scope.launch { model.run(client, signedInToNode, contractSpec) }
                                },
                                enabled = !model.running,
                                icon =
                                    when {
                                        model.running -> null
                                        model.finished -> Icons.Filled.Refresh
                                        else -> Icons.Filled.PlayArrow
                                    },
                                modifier = Modifier.fillMaxWidth().testTag("runAllButton"),
                            )
                            if (model.running) {
                                CircularProgressIndicator(
                                    color = Cal.onLime,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 20.dp).size(18.dp),
                                )
                            }
                        }
                        if (model.finished) {
                            // The whole report for UI tests, which cannot read the clipboard.
                            Text(model.report(), fontSize = 1.sp, modifier = Modifier.height(1.dp).alpha(0.01f).testTag("runAllReport"))
                        }
                    }
                }
            }
            if (model.results.isNotEmpty()) {
                item {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        RunFilter.entries.forEachIndexed { index, f ->
                            SegmentedButton(
                                selected = filter == f,
                                onClick = { filter = f },
                                shape = SegmentedButtonDefaults.itemShape(index, RunFilter.entries.size),
                                icon = {},
                                colors =
                                    SegmentedButtonDefaults.colors(
                                        activeContainerColor = Cal.surface,
                                        activeContentColor = Cal.text,
                                        activeBorderColor = Cal.borderStrong,
                                        inactiveContainerColor = Cal.bgSubtle,
                                        inactiveContentColor = Cal.textDim,
                                        inactiveBorderColor = Cal.borderStrong,
                                    ),
                            ) { Text(f.label, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                        }
                    }
                }
                RunAllModel.PHASES.forEach { phase ->
                    val rows = visible.filter { it.phase == phase }
                    if (rows.isNotEmpty()) {
                        item(key = phase) {
                            CalSection("$phase · ${rows.size}") {
                                rows.forEachIndexed { i, r ->
                                    RunResultRow(r, onClick = { detail = r })
                                    if (i < rows.size - 1) RowDivider()
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun StatPill(
    value: Int,
    label: String,
    tone: Color,
    bg: Color,
) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$value", color = tone, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(4.dp))
        Text(label, color = tone, fontSize = 12.sp)
    }
}

@Composable
private fun RunResultRow(
    result: RunAllModel.Result,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            when (result.status) {
                RunAllModel.Status.Pending -> Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = Cal.borderStrong, modifier = Modifier.size(20.dp))
                RunAllModel.Status.Running -> CircularProgressIndicator(color = Cal.accentInk, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                RunAllModel.Status.Passed -> Icon(Icons.Filled.CheckCircle, null, tint = Cal.success, modifier = Modifier.size(20.dp))
                is RunAllModel.Status.Failed -> Icon(Icons.Outlined.Cancel, null, tint = Cal.error, modifier = Modifier.size(20.dp))
                is RunAllModel.Status.Skipped -> Icon(Icons.Outlined.RemoveCircleOutline, null, tint = Cal.textFaint, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(result.title, color = Cal.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val (detail, color) =
                when (val s = result.status) {
                    is RunAllModel.Status.Failed -> s.why to Cal.error
                    is RunAllModel.Status.Skipped -> s.why to Cal.textFaint
                    else -> result.subtitle to Cal.textFaint
                }
            Text(detail, color = color, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (result.status == RunAllModel.Status.Passed) {
            Spacer(Modifier.width(8.dp))
            Text("${result.millis} ms", color = Cal.textFaint, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun RunResultDetail(
    result: RunAllModel.Result,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Cal.bg)) {
        TopBar(result.title, subtitle = result.subtitle, onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = screenPad, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(result.summary, color = Cal.textDim, fontSize = 14.sp)
            if (result.inputs.isNotEmpty()) {
                Block(
                    "Inputs",
                    result.inputs
                        .toSortedMap()
                        .entries
                        .joinToString("\n") { "${it.key}: ${it.value}" },
                )
            }
            when (val s = result.status) {
                is RunAllModel.Status.Skipped -> Callout(s.why, kind = CalloutKind.INFO)
                is RunAllModel.Status.Failed -> {
                    Callout(s.why, kind = CalloutKind.DANGER)
                    if (result.output.isNotEmpty() && result.output != s.why) Block("Output", result.output)
                }
                else -> if (result.output.isNotEmpty()) Block("Response", result.output)
            }
        }
    }
}

@Composable
private fun Block(
    title: String,
    text: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(title)
        Text(
            text,
            color = Cal.text,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Cal.radiusControl))
                    .background(Cal.surfaceSunken)
                    .padding(12.dp),
        )
    }
}
