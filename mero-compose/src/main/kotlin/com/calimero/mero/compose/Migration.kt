package com.calimero.mero.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calimero.mero.compose.state.rememberMigrationStatus
import com.calimero.mero.compose.state.rememberMyAuthoredMigration
import kotlinx.coroutines.launch

/** The banner's default words: "You have 3 entries to re-sign after a recent upgrade." */
fun migrationPendingMessage(pendingCount: Int): String =
    "You have $pendingCount ${if (pendingCount == 1) "entry" else "entries"} to re-sign after a recent upgrade."

/**
 * Shown only while this identity has entries below the target schema — mero-react
 * `MigrationPendingBanner`. One button runs the `migrate_my_entries` convert; the banner clears
 * itself once nothing is pending, and [onMigrated] fires when a convert leaves none.
 *
 * Test tags: `migrationPendingBanner`, `migrationPendingButton`.
 */
@Composable
fun MigrationPendingBanner(
    contextId: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    onMigrated: () -> Unit = {},
    theme: MeroTheme = LocalMeroTheme.current,
) {
    val migration = rememberMyAuthoredMigration(contextId)
    val count by migration.pendingCount.collectAsStateWithLifecycle()
    val mutation by migration.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    if (count <= 0) return
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(theme.backgroundSecondary, RoundedCornerShape(theme.radius))
                .padding(12.dp)
                .testTag("migrationPendingBanner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message ?: migrationPendingMessage(count), color = theme.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Button(
            onClick = { scope.launch { if (migration.authorize()?.remaining == 0) onMigrated() } },
            enabled = !mutation.loading,
            colors = ButtonDefaults.buttonColors(containerColor = theme.primary, contentColor = theme.primaryText),
            modifier = Modifier.testTag("migrationPendingButton"),
        ) {
            Text(if (mutation.loading) "Migrating…" else "Migrate my data")
        }
    }
}

/**
 * An operator's view of a namespace's migration rollup — mero-react `MigrationAdminPanel`: the
 * cohort counters, members pending signature and one row per member. Read-only; pair it with the
 * upgrade state holders to drive a migration. Live over the event stream, polling as a fallback.
 *
 * Test tags: `migrationAdminPanel`, `migrationStatusError`, `migrationFailedCount`,
 * `membersPendingSignature`, `migrationMemberRow`.
 */
@Suppress("LongMethod")
@Composable
fun MigrationAdminPanel(
    namespaceId: String,
    modifier: Modifier = Modifier,
    pollIntervalMs: Long? = null,
    theme: MeroTheme = LocalMeroTheme.current,
) {
    val holder = rememberMigrationStatus(namespaceId, pollIntervalMs)
    val read by holder.state.collectAsStateWithLifecycle()
    val status = read.data
    val error = read.error
    Column(modifier = modifier.fillMaxWidth().testTag("migrationAdminPanel"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (status == null) {
            // A successful read always yields a rollup, so no status is "not loaded yet", never "empty".
            Text(
                if (error != null) "Failed to load migration status: ${error.message}" else "Loading migration status…",
                color = if (error != null) theme.error else theme.textSecondary,
            )
            return@Column
        }
        if (error != null) {
            // A poll failed after data loaded: keep the last good rollup, flagged as possibly stale.
            Text("Could not refresh migration status: ${error.message}", color = theme.error, modifier = Modifier.testTag("migrationStatusError"))
        }
        val rollup = status.rollup
        Counter("Target version", status.targetVersion.toString(), theme)
        Counter("Migrated", rollup.migrated.toString(), theme)
        Counter("In progress", rollup.inProgress.toString(), theme)
        Counter("Unknown", rollup.unknown.toString(), theme)
        Counter("Failed", rollup.failed.toString(), theme, "migrationFailedCount")
        Counter("Total", rollup.total.toString(), theme)
        Counter("All migrated", if (rollup.allMigrated) "yes" else "no", theme)
        Counter("Members pending signature", rollup.membersPendingSignature.toString(), theme, "membersPendingSignature")
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            listOf("Peer", "State", "Schema", "Pending", "Reason").forEach {
                Text(it, color = theme.textSecondary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
        }
        status.members.forEach { m ->
            Row(modifier = Modifier.fillMaxWidth().testTag("migrationMemberRow")) {
                listOf(
                    m.peer,
                    m.state.name.lowercase(),
                    m.report?.schemaVersion?.toString() ?: "—",
                    m.report?.authoredRemaining?.toString() ?: "—",
                    m.report?.migrationFailed?.toString() ?: "—",
                ).forEach { Text(it, color = theme.text, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Counter(
    label: String,
    value: String,
    theme: MeroTheme,
    tag: String? = null,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = theme.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = theme.text, fontSize = 13.sp, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
    }
}
