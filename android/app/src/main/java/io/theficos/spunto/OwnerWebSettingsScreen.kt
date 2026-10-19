package io.theficos.spunto

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.theficos.spunto.data.sync.SyncStatus

@Composable internal fun OwnerWebSettingsScreen(model:SpuntoViewModel,section:String) {
 val context=LocalContext.current
 val auth by model.auth.collectAsState()
 val replica by model.repository.dao.observeState().collectAsState(initial=null)
 val online by model.online.collectAsState()
 val reauth by model.reauthenticate.collectAsState()
 val status by model.status.collectAsState()
 val gate by model.container.appLock.state.collectAsState()
 val target=ownerWebSettingsTarget(auth,replica?.serverUrl,online,section,reauth || status==SyncStatus.NeedsSignIn)
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
  Text(if(section=="data") "Export all synced notes" else "Owner profile",style=MaterialTheme.typography.titleLarge)
  Text(if(section=="data") "Download your server's library as a ZIP with Markdown, metadata and attachments. Notes saved only on this phone aren't included. Share or save those from their note screen." else "Your private display name is managed on your server. It doesn't change the owner password.",style=MaterialTheme.typography.bodyMedium)
  Text(target.message,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
  TextButton(enabled=gate.unlocked && target.url!=null,onClick={
   if(!model.container.appLock.state.value.unlocked) return@TextButton
   val current=ownerWebSettingsTarget(model.auth.value,replica?.serverUrl,model.online.value,section,model.reauthenticate.value || model.status.value==SyncStatus.NeedsSignIn)
   val url=current.url
   if(url==null || url!=target.url) {model.message.value=current.message;return@TextButton}
   try {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}
   catch(_:ActivityNotFoundException) {model.message.value="No browser is available to open owner settings."}
   catch(_:SecurityException) {model.message.value="Couldn't open owner settings in your browser."}
  }) {Text(if(section=="data") "Open web export · owner sign-in" else "Open web profile · owner sign-in")}
 }
}
