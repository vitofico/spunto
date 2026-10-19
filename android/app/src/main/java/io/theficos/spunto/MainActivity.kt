package io.theficos.spunto
import android.os.Bundle
import androidx.compose.runtime.key
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import io.theficos.spunto.data.sync.ChangeWaiter
class MainActivity:LockedActivity() {
 private var model:SpuntoViewModel?=null
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  val owner=(application as SpuntoApplication).container
  val initialNoteId=intent.getStringExtra("noteId")
  val guideEligible=firstRunGuideEligible(intent.action,intent.data!=null,initialNoteId)
  lifecycleScope.launch {owner.replicas.collectLatest {replica ->
   val selected=ViewModelProvider(this@MainActivity)[replica.key,SpuntoViewModel::class.java]
   val previous=model;model=selected
   setProtectedContent {key(replica.key) {SpuntoApp(selected,initialNoteId,guideEligible)}}
   if(previous!==selected) previous?.retire()
   repeatOnLifecycle(Lifecycle.State.STARTED) {owner.appLock.state.collectLatest {gate ->
    if(gate.unlocked) selected.auth.collectLatest {config ->if(config!=null) ChangeWaiter(selected.container.auth,selected.repository,selected.container.sync).run()}
   }}
  }}
 }
 override fun onUnlocked() {model?.synchronize()}
}
