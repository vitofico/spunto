package io.theficos.spunto

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.design.SpuntoTheme
import io.theficos.spunto.core.design.SpuntoType
import kotlinx.coroutines.*
import androidx.lifecycle.lifecycleScope
import java.io.File
import androidx.room.withTransaction

internal class CaptureLaunchState:androidx.lifecycle.ViewModel() {
 var selectionStarted=false
 var selectionId by mutableStateOf<String?>(null)
 var selectionError by mutableStateOf("")
 var opened by mutableStateOf(false)
}

class CaptureActivity:LockedActivity() {
 private val model:SpuntoViewModel by viewModels()
 private val launchState:CaptureLaunchState by viewModels()
 // Tile, widget and shortcut launches own their task; removing it returns to the app underneath, not to Spunto. A share runs in the sharing app's task and only finishes.
 override fun finish() { if(isTaskRoot) finishAndRemoveTask() else super.finish() }
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  lifecycleScope.launch {(application as SpuntoApplication).container.replicas.collect {if(it.key!=model.container.key) finish()}}
  setProtectedContent { LaunchedEffect(Unit) {initializeCapture()} }
 }
 private fun initializeCapture() {
  if(intent.action==Intent.ACTION_PROCESS_TEXT) {
   val selection=intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
   lifecycleScope.launch {
    if(selection.isBlank()) { finish();return@launch }
    val body=selection.lines().joinToString("\n") { "> $it" }
    if(!launchState.selectionStarted) {launchState.selectionStarted=true;model.task {try {launchState.selectionId=model.repository.create(body,sourceKind="selection",sourceRef=referrer?.host)} catch(error:Exception) {launchState.selectionError="Couldn't save this selection. Your existing notes and drafts are kept.";throw error}}}
    setProtectedContent { SpuntoTheme(namespace=model.container.replicaId.takeUnless {it=="legacy"}.orEmpty()) { Box(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomCenter) { Surface(shape=MaterialTheme.shapes.large) {
     val id=launchState.selectionId
     if(id==null) Column(Modifier.padding(20.dp)) {Text(launchState.selectionError.ifEmpty {"Saving selection…"});if(launchState.selectionError.isNotEmpty()) TextButton(onClick={finish()}) {Text("Close")}}
     else if(!launchState.opened) TextButton(onClick={launchState.opened=true}) {Text("Saved to spunto · Add a thought")}
     else Column(Modifier.padding(20.dp)) {Text("Add a thought");Composer(model,id,body,startNewParagraph=true,onRecordingSaved={finish()},onSave={finish()})}
    } } } }
    while(launchState.selectionId==null && launchState.selectionError.isEmpty()) delay(50)
    if(launchState.selectionError.isNotEmpty()) return@launch
    delay(2500);if(!launchState.opened) finish()
   }
   return
  }
  val shared=intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
  val image=if(intent.type?.startsWith("image/")==true) intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) else null
  val shareKey=if(intent.action==Intent.ACTION_SEND) "share:"+java.security.MessageDigest.getInstance("SHA-256").digest(listOf(shared,image?.toString().orEmpty(),intent.type.orEmpty(),referrer?.toString().orEmpty()).joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) } else "capture"
  val source=shared.takeIf { it.startsWith("https://") || it.startsWith("http://") }
  setProtectedContent {
   val error by model.message.collectAsState()
   SpuntoTheme(namespace=model.container.replicaId.takeUnless {it=="legacy"}.orEmpty()) {
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomCenter) { Surface(Modifier.fillMaxWidth().wrapContentHeight(),shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.background) {
     Column(Modifier.padding(20.dp).imePadding(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
      Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("spunto.",style=MaterialTheme.typography.titleLarge);TextButton(onClick={finish()}) { Text("Close") } }
      if(error.isNotEmpty()) Text(error)
      if(shared.isNotEmpty()) Text(shared.take(500),style=SpuntoType.reading)
      if(image!=null) coil.compose.AsyncImage(model=image,contentDescription="Shared image",modifier=Modifier.fillMaxWidth().heightIn(max=180.dp))
      Composer(model,draftId=shareKey,initial="",autoFocus=shared.isEmpty() && image==null,autoDictate=intent.getBooleanExtra("dictate",false),canSaveEmpty=shared.isNotEmpty() || image!=null,allowVoiceCapture=shared.isEmpty() && image==null,onPlainCaptureSave=if(shared.isEmpty() && image==null) {body,ref ->model.repository.create(body,sourceRef=ref);finish()} else null,onRecordingSaved={finish()},onSave={comment -> saveShare(comment,shared,source,image,shareKey)})

     }
    }
   }
   }
  }
 }
 private suspend fun saveShare(comment:String,shared:String,source:String?,image:Uri?,draftId:String) {
   val body=listOf(comment,if(shared.isNotEmpty()) shared.lines().joinToString("\n") { "> $it" } else "").filter { it.isNotBlank() }.joinToString("\n\n")
   val attachment=if(image!=null) withContext(Dispatchers.IO) {
    val folder=model.container.privateDirectory("pending-attachments")
    val target=File(folder,java.util.UUID.randomUUID().toString())
    try { requireNotNull(contentResolver.openInputStream(image)) { "The shared image is no longer available." }.use { input -> target.outputStream().use { input.copyTo(it) } };target }
    catch(error:Exception) { target.delete();throw error }
   } else null
   try {
    model.container.database.withTransaction {
     val noteId=model.repository.create(body,sourceUrl=source,sourceKind="share",sourceRef=referrer?.host)
     if(attachment!=null) model.repository.queueImage(noteId,attachment.absolutePath,intent.type ?: "image/jpeg")
     model.repository.clearDraft(draftId,comment)
    }
   } catch(error:Exception) { attachment?.delete();throw error }
   finish()
 }
}
