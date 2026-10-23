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
import androidx.compose.ui.text.style.TextOverflow
import io.theficos.spunto.core.design.SpuntoSpacing
import io.theficos.spunto.core.design.SpuntoColors
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
    if(!launchState.selectionStarted) {launchState.selectionStarted=true;model.task {try {launchState.selectionId=model.repository.create(body,sourceKind="selection",sourceRef=referrer?.host)} catch(error:Exception) {launchState.selectionError=getString(R.string.core_selection_save_failed);throw error}}}
    setProtectedContent { SpuntoTheme(namespace=model.container.replicaId.takeUnless {it=="legacy"}.orEmpty()) { Box(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomCenter) { Surface(shape=MaterialTheme.shapes.large) {
     val id=launchState.selectionId
     if(id==null) Column(Modifier.padding(SpuntoSpacing.s20)) {Text(launchState.selectionError.ifEmpty {getString(R.string.core_saving_selection)});if(launchState.selectionError.isNotEmpty()) TextButton(onClick={finish()}) {Text(getString(R.string.core_close))}}
     else if(!launchState.opened) TextButton(onClick={launchState.opened=true}) {Text(getString(R.string.core_saved_add_thought))}
     else Column(Modifier.padding(SpuntoSpacing.s20)) {Text(getString(R.string.core_add_thought));Composer(model,id,body,startNewParagraph=true,onRecordingSaved={finish()},onSave={finish()})}
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
     Column(Modifier.padding(SpuntoSpacing.s20).imePadding(),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
      val sharing=shared.isNotEmpty() || image!=null
      // A share (03 A3): what came in, sunken and quoted on save; an optional thought; up to three recent labels.
      // A3 puts Save in the header row: the composer's bar docks there instead of under the field.
      val header=remember { BottomBarSlot() }
      if(sharing) Row(verticalAlignment=Alignment.CenterVertically) { Text(getString(R.string.core_save_spunto),style=MaterialTheme.typography.titleMedium);Box(Modifier.weight(1f)) { header.content?.invoke() } }
      else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("spunto.",style=MaterialTheme.typography.titleLarge);TextButton(onClick={finish()}) { Text(getString(R.string.core_close)) } }
      if(error.isNotEmpty()) Text(error)
      if(shared.isNotEmpty()) Surface(color=MaterialTheme.colorScheme.surfaceVariant,contentColor=MaterialTheme.colorScheme.onSurface,shape=MaterialTheme.shapes.medium) {
       Column(Modifier.fillMaxWidth().padding(SpuntoSpacing.s12),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s4)) {
        val title=intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf {source!=null && it.isNotBlank()}
        Text(title ?: shared,style=SpuntoType.reading,maxLines=if(title!=null) 2 else 4,overflow=TextOverflow.Ellipsis)
        (source?.let {Uri.parse(it).host} ?: referrer?.host)?.let {Text(it,style=MaterialTheme.typography.labelSmall,color=SpuntoColors.ink3)}
       }
      }
      if(image!=null) coil.compose.AsyncImage(model=image,contentDescription=getString(R.string.core_shared_image),modifier=Modifier.fillMaxWidth().heightIn(max=180.dp))
      val labels by model.labels.collectAsState()
      var chosen by remember { mutableStateOf(emptySet<String>()) }
      CompositionLocalProvider(LocalBottomBar provides header.takeIf { sharing }) { Composer(model,draftId=shareKey,initial="",autoFocus=!sharing,autoDictate=intent.getBooleanExtra("dictate",false),canSaveEmpty=sharing,allowVoiceCapture=!sharing,tools=!sharing,placeholder=if(sharing) getString(R.string.core_thought_optional) else getString(R.string.core_placeholder),onPlainCaptureSave=if(!sharing) {body,ref ->model.repository.create(body,sourceRef=ref);finish();null} else null,onRecordingSaved={finish()},onSave={comment -> saveShare(comment,shared,source,image,shareKey,chosen.joinToString(" ") {"#$it"})}) }
      if(sharing) Row(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
       labels.filter {'/' !in it.key}.sortedByDescending {it.count}.take(3).forEach {label -> FilterChip(selected=label.path in chosen,onClick={chosen=if(label.path in chosen) chosen-label.path else chosen+label.path},label={Text("#${label.path}")})}
      }

     }
    }
   }
   }
  }
 }
 /** The thought first, then the shared text as a quote, then any chosen labels, so the title is never a label. */
 private suspend fun saveShare(comment:String,shared:String,source:String?,image:Uri?,draftId:String,labels:String="") {
   val body=listOf(comment,if(shared.isNotEmpty()) shared.lines().joinToString("\n") { "> $it" } else "",labels).filter { it.isNotBlank() }.joinToString("\n\n")
   val attachment=if(image!=null) withContext(Dispatchers.IO) {
    val folder=model.container.privateDirectory("pending-attachments")
    val target=File(folder,java.util.UUID.randomUUID().toString())
    try { requireNotNull(contentResolver.openInputStream(image)) { getString(R.string.core_shared_image_missing) }.use { input -> target.outputStream().use { input.copyTo(it) } };target }
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
