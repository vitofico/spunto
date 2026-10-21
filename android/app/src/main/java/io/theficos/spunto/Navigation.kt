package io.theficos.spunto

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment

/** The app's back stack of string routes ("timeline", "note:<id>", "settings"…), saved across rotation and process death. */
@Stable class Navigator internal constructor(private val entries:SnapshotStateList<String>,private val forget:(String)->Unit) {
 val route:String get()=entries.last()
 val canGoBack:Boolean get()=entries.size>1
 /** Unique per stack position, so a page opened twice keeps two separate saved states. */
 internal val stateKey:String get()=stateKey(entries.lastIndex)
 private fun stateKey(index:Int)="$index:${entries[index]}"
 /** Pushes a full-screen page (note, search, settings…); Back returns here. */
 fun open(route:String) { if(route!=this.route) entries.add(route) }
 fun back() { if(canGoBack) replace(entries.dropLast(1)) }
 /** Switches the timeline filter (All notes, Archive, Trash, Review, a label or view); Back from a filter returns to All notes. */
 fun filter(route:String)=replace(if(route=="timeline") listOf("timeline") else listOf("timeline",route))
 /** Returns to All notes and drops every page above it. */
 fun home()=replace(listOf("timeline"))
 private fun replace(next:List<String>) {
  val kept=entries.zip(next).takeWhile { (current,wanted) -> current==wanted }.size
  for(index in entries.lastIndex downTo kept) { forget(stateKey(index));entries.removeAt(index) }
  entries.addAll(next.drop(kept))
 }
}
@Composable internal fun rememberNavigator(initialNoteId:String?,states:SaveableStateHolder):Navigator {
 val entries=rememberSaveable(initialNoteId,saver=listSaver<SnapshotStateList<String>,String>({ it.toList() },{ it.toMutableStateList() })) { mutableStateListOf("timeline").apply { initialNoteId?.let { add("note:$it") } } }
 return remember(entries,states) { Navigator(entries,states::removeState) }
}
val LocalNavigator=staticCompositionLocalOf<Navigator> { error("Navigator is provided by SpuntoApp.") }

/** Top bar title of a page; null for pages that show none (a note, search, a plugin page, which heads itself) and for the filter routes, whose title is the labels switcher. */
fun routeTitle(route:String):String?=when(route) {
 "settings" -> "Settings"; "appearance" -> "Appearance"; "plugins" -> "Plugins"; "ask" -> "Ask your notes"
 else -> null
}

/** Where page content puts its top bar icons (a note's pin and ⋮) without owning the scaffold. */
@Stable class TopBarSlot { internal var actions by mutableStateOf<(@Composable RowScope.()->Unit)?>(null) }
internal val LocalTopBar=staticCompositionLocalOf<TopBarSlot?> { null }
/** Adds icons to the top bar's end while the calling page is shown; outside the app shell (tests) they render in place. */
@Composable fun TopBarActions(content:@Composable RowScope.()->Unit) {
 val slot=LocalTopBar.current
 if(slot==null) { Row(verticalAlignment=Alignment.CenterVertically,content=content);return }
 val latest by rememberUpdatedState(content)
 DisposableEffect(slot) {
  val mine:@Composable RowScope.()->Unit={ latest(this) }
  slot.actions=mine
  onDispose { if(slot.actions===mine) slot.actions=null }
 }
}
