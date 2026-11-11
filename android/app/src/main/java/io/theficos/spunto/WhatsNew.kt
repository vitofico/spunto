package io.theficos.spunto

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.design.SpuntoSpacing
import io.theficos.spunto.core.design.SpuntoType

internal const val WhatsNewRoute = "whats-new"
internal const val WhatsNewSeenKey = "latest-seen"
// Advance this ID when adding the next group of updates.
private const val latestUpdate = "2026-11-prugna"

internal fun whatsNewPreferences(context: Context) =
    context.applicationContext.getSharedPreferences(WhatsNewRoute, Context.MODE_PRIVATE)

@Composable
internal fun WhatsNewSettingsRow(onClick: () -> Unit) {
    val context = LocalContext.current
    val unread = remember(context) {
        whatsNewPreferences(context).getString(WhatsNewSeenKey, null) != latestUpdate
    }
    SettingsRow(
        stringResource(R.string.whats_new_title),
        onClick = onClick,
        description = if (unread) stringResource(R.string.whats_new_unread) else null,
    ) {
        if (unread) Badge(containerColor = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun WhatsNewScreen(onAppearance: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(context) {
        whatsNewPreferences(context).edit().putString(WhatsNewSeenKey, latestUpdate).apply()
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 560.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(SpuntoSpacing.s20),
            verticalArrangement = Arrangement.spacedBy(SpuntoSpacing.s24),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(SpuntoSpacing.s8)) {
                UpdateHeading(
                    stringResource(R.string.whats_new_latest),
                    stringResource(R.string.whats_new_prugna_title),
                    stringResource(R.string.whats_new_prugna_body),
                )
                TextButton(onClick = onAppearance, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.whats_new_open_appearance))
                }
            }
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(SpuntoSpacing.s8)) {
                UpdateHeading(
                    stringResource(R.string.whats_new_earlier),
                    stringResource(R.string.whats_new_plain_title),
                    stringResource(R.string.whats_new_plain_body),
                )
            }
        }
    }
}

@Composable
private fun UpdateHeading(group: String, title: String, summary: String) {
    Text(
        group,
        Modifier.semantics { heading() },
        style = SpuntoType.overline,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
    Text(summary, style = MaterialTheme.typography.bodyMedium)
}
