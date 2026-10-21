package io.theficos.spunto

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Short confirmations ("Archived · Undo"), one at a time; the snackbar host announces them politely to TalkBack. */
@Stable class Feedback internal constructor(private val host:SnackbarHostState,private val scope:CoroutineScope) {
 /** Replaces any message on screen. [onAction] runs in the app's scope, so Undo still works after the page that asked has closed. */
 fun show(message:String,action:String?=null,onAction:()->Unit={}) {
  scope.launch {
   host.currentSnackbarData?.dismiss()
   if(host.showSnackbar(message,action,duration=if(action==null) SnackbarDuration.Short else SnackbarDuration.Long)==SnackbarResult.ActionPerformed) onAction()
  }
 }
}
val LocalFeedback=staticCompositionLocalOf<Feedback> { error("Feedback is provided by SpuntoApp.") }
