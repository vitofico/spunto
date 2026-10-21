@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.theficos.spunto

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.theficos.spunto.data.local.CreatedRange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import io.theficos.spunto.core.design.SpuntoSpacing

// Material date pickers use UTC midnight; filtering uses each chosen local calendar day.
internal fun pickerDateMillis(date:String):Long?=date.takeIf {it.isNotBlank()}?.let {LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()}
internal fun pickerDate(millis:Long):String=Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
private fun filterDateLabel(raw:String)=raw.takeIf {it.isNotBlank()}?.let {LocalDate.parse(it).format(DateTimeFormatter.ofPattern("d MMM yyyy"))} ?: "Any date"

@Composable internal fun NoteFiltersSheet(from:String,to:String,onDismiss:()->Unit,onApply:(String,String)->Unit,today:LocalDate=LocalDate.now()) {
 var draftFrom by rememberSaveable(from,to) {mutableStateOf(from)}
 var draftTo by rememberSaveable(from,to) {mutableStateOf(to)}
 var picking by remember {mutableStateOf<String?>(null)}
 ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
  NoteFilterContent(draftFrom,draftTo,today,onFrom={draftFrom=it},onTo={draftTo=it},onPick={picking=it},onDismiss=onDismiss,onApply={onApply(draftFrom,draftTo)})
 }
 picking?.let {field ->
  val current=if(field=="from") draftFrom else draftTo
  val state=rememberDatePickerState(initialSelectedDateMillis=pickerDateMillis(current))
  DatePickerDialog(onDismissRequest={picking=null},confirmButton={TextButton(onClick={state.selectedDateMillis?.let {if(field=="from") draftFrom=pickerDate(it) else draftTo=pickerDate(it)};picking=null},enabled=state.selectedDateMillis!=null) {Text("Choose date")}},dismissButton={TextButton(onClick={picking=null}) {Text("Cancel")}}) {
   DatePicker(state,title={Text(if(field=="from") "Created from" else "Created through",Modifier.padding(start=SpuntoSpacing.s24,top=SpuntoSpacing.s16))})
  }
 }
}

@Composable internal fun NoteFilterContent(from:String,to:String,today:LocalDate,onFrom:(String)->Unit,onTo:(String)->Unit,onPick:(String)->Unit,onDismiss:()->Unit,onApply:()->Unit) {
 val valid=remember(from,to) {runCatching {CreatedRange.parse(from,to)}.isSuccess}
 Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal=SpuntoSpacing.s24).padding(bottom=SpuntoSpacing.s20),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s16)) {
  Text("Filter notes",style=MaterialTheme.typography.headlineSmall)
  Text("Date created",style=MaterialTheme.typography.titleSmall)
  Text("Choose a period or pick dates. Your notes stay in their usual order.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
  FlowRow(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
   listOf("Today" to 1L,"Last 7 days" to 7L,"Last 30 days" to 30L).forEach {(label,days) ->
    val start=today.minusDays(days-1).toString();val end=today.toString()
    FilterChip(selected=from==start && to==end,onClick={onFrom(start);onTo(end)},label={Text(label)})
   }
  }
  Row(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
   OutlinedButton(onClick={onPick("from")},modifier=Modifier.weight(1f).semantics {contentDescription="Choose start date"},shape=MaterialTheme.shapes.medium,contentPadding=PaddingValues(SpuntoSpacing.s16)) {Column {Text("From",style=MaterialTheme.typography.labelSmall);Text(filterDateLabel(from))}}
   OutlinedButton(onClick={onPick("to")},modifier=Modifier.weight(1f).semantics {contentDescription="Choose end date"},shape=MaterialTheme.shapes.medium,contentPadding=PaddingValues(SpuntoSpacing.s16)) {Column {Text("Through",style=MaterialTheme.typography.labelSmall);Text(filterDateLabel(to))}}
  }
  if(!valid) Text("Choose an end date on or after the start date.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
  if(from.isNotBlank() || to.isNotBlank()) TextButton(onClick={onFrom("");onTo("")}) {Text("Clear dates")}
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12),verticalAlignment=Alignment.CenterVertically) {
   TextButton(onClick=onDismiss,modifier=Modifier.weight(1f)) {Text("Cancel")}
   Button(onClick=onApply,enabled=valid,modifier=Modifier.weight(1f)) {Text("Apply filters")}
  }
 }
}

/** What an active date filter reads as on its pill: a preset's name, or the dates. */
internal fun dateFilterLabel(from:String,to:String,today:LocalDate=LocalDate.now()):String {
 val presets=listOf(1L to "Today",7L to "Last 7 days",30L to "Last 30 days")
 presets.firstOrNull {(days,_) ->from==today.minusDays(days-1).toString() && to==today.toString()}?.let {return it.second}
 return when {from.isNotBlank() && to.isNotBlank() -> "${filterDateLabel(from)} – ${filterDateLabel(to)}";from.isNotBlank() -> "From ${filterDateLabel(from)}";else -> "Through ${filterDateLabel(to)}"}
}
/** The active date filter, cleared in one tap (03 §3.2). */
@Composable internal fun DateFilterPill(from:String,to:String,onClear:()->Unit) {
 val label=dateFilterLabel(from,to)
 Row(Modifier.padding(horizontal=SpuntoSpacing.s20)) {
  InputChip(selected=true,onClick=onClear,label={Text("$label  ×")},modifier=Modifier.heightIn(min=48.dp).semantics {contentDescription="$label. Clear date filter"})
 }
}
