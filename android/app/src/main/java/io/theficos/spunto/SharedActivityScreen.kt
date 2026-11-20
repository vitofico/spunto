package io.theficos.spunto

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.theficos.spunto.core.design.SpuntoSpacing
import io.theficos.spunto.data.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

@Composable internal fun SharedActivityScreen(model: SpuntoViewModel, openNote: (String) -> Unit) {
    val config by model.auth.collectAsState()
    val context = LocalContext.current
    val account = config?.takeIf { it.hosted } ?: return
    key(account) {
        val alerts = remember { SharedActivityAlerts(context, account) }
        val scope = rememberCoroutineScope()
        val feedback = LocalFeedback.current
        var feed by remember { mutableStateOf<SharedFeed?>(null) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var enabled by remember { mutableStateOf(alerts.enabled && alerts.allowed) }
        fun run(action: suspend () -> Unit) {
            if (busy) return
            busy = true
            scope.launch {
                try {
                    error = null
                    action()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    feed = null
                    error = if (failure is ApiException) apiErrorMessage(context.resources, failure) else context.getString(R.string.sys_server_unreachable)
                } finally { busy = false }
            }
        }
        fun refresh() = run { feed = alerts.api.sharedActivity() }
        fun configure(value: Boolean) = run {
            alerts.configure(value)
            enabled = alerts.enabled && alerts.allowed
        }
        var permissionPending by remember { mutableStateOf(false) }
        var denied by remember { mutableStateOf(false) }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionPending = false
            denied = !granted || !alerts.allowed
            if (granted && alerts.allowed) configure(true)
        }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    enabled = alerts.enabled && alerts.allowed
                    if (alerts.allowed) denied = false
                    if (!permissionPending) refresh()
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = SpuntoSpacing.s24)) {
            item {
                SettingsSwitchRow(stringResource(R.string.shared_activity_phone), stringResource(R.string.shared_activity_scope), enabled, enabled = !busy && (enabled || feed != null)) { checked ->
                    when {
                        !checked || alerts.allowed -> configure(checked)
                        Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED -> {
                            permissionPending = true
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        else -> denied = true
                    }
                }
                if (denied) SettingsRow(stringResource(R.string.shared_activity_permission), opensPage = true, onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                })
                val current = feed
                if (current?.followable == true) SettingsSwitchRow(stringResource(R.string.shared_activity_follow), null, current.following, enabled = !busy) { follow ->
                    run { alerts.api.followSharedSpace(follow); feed = alerts.api.sharedActivity() }
                }
                SettingsRow(stringResource(R.string.shared_activity_all), opensPage = true, onClick = { model.pluginWeb("/notifications") })
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { SettingsRow(stringResource(R.string.core_ask_retry), it, onClick = ::refresh) }
                if (current != null && current.notifications.isEmpty()) SettingsRow(stringResource(R.string.shared_activity_empty))
            }
            items(feed?.notifications.orEmpty(), key = { it.id }) { event ->
                val title = when (event.kind) {
                    "space_joined" -> R.string.shared_activity_joined
                    "note_shared" -> R.string.shared_activity_shared
                    "note_created" -> R.string.shared_activity_created
                    else -> R.string.shared_activity_title
                }
                val status = stringResource(if (event.read) R.string.shared_activity_read else R.string.shared_activity_unread)
                SettingsRow(stringResource(title), listOf(event.workspaceName, noteTime(event.createdAt), status).filter(String::isNotBlank).joinToString(" · "), enabled = !busy, onClick = {
                    run {
                        alerts.api.readSharedEvent(event)
                        feed = alerts.api.sharedActivity()
                        event.noteId?.let { id ->
                            if (feed?.notifications?.any { it.id == event.id } == true) {
                                if (model.repository.observe(id).first() == null) model.synchronize().join()
                                if (model.repository.observe(id).first() != null) openNote(id)
                                else feedback.show(context.getString(R.string.settings_this_note_is_no_longer_available))
                            }
                        }
                    }
                })
            }
        }
    }
}
