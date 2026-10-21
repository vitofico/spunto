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
   DatePicker(state,title={Text(if(field=="from") "Created from" else "Created through",Modifier.padding(start=24.dp,top=16.dp))})
  }
 }
}

@Composable internal fun NoteFilterContent(from:String,to:String,today:LocalDate,onFrom:(String)->Unit,onTo:(String)->Unit,onPick:(String)->Unit,onDismiss:()->Unit,onApply:()->Unit) {
 val valid=remember(from,to) {runCatching {CreatedRange.parse(from,to)}.isSuccess}
 Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
  Text("Filter notes",style=MaterialTheme.typography.headlineSmall)
  Text("Date created",style=MaterialTheme.typography.titleSmall)
  Text("Choose a period or pick dates. Your notes stay in their usual order.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
  FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
   listOf("Today" to 1L,"Last 7 days" to 7L,"Last 30 days" to 30L).forEach {(label,days) ->
    val start=today.minusDays(days-1).toString();val end=today.toString()
    FilterChip(selected=from==start && to==end,onClick={onFrom(start);onTo(end)},label={Text(label)})
   }
  }
  Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
   OutlinedButton(onClick={onPick("from")},modifier=Modifier.weight(1f).semantics {contentDescription="Choose start date"},shape=MaterialTheme.shapes.medium,contentPadding=PaddingValues(16.dp)) {Column {Text("From",style=MaterialTheme.typography.labelSmall);Text(filterDateLabel(from))}}
   OutlinedButton(onClick={onPick("to")},modifier=Modifier.weight(1f).semantics {contentDescription="Choose end date"},shape=MaterialTheme.shapes.medium,contentPadding=PaddingValues(16.dp)) {Column {Text("Through",style=MaterialTheme.typography.labelSmall);Text(filterDateLabel(to))}}
  }
  if(!valid) Text("Choose an end date on or after the start date.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
  if(from.isNotBlank() || to.isNotBlank()) TextButton(onClick={onFrom("");onTo("")}) {Text("Clear dates")}
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
   TextButton(onClick=onDismiss,modifier=Modifier.weight(1f)) {Text("Cancel")}
   Button(onClick=onApply,enabled=valid,modifier=Modifier.weight(1f)) {Text("Apply filters")}
  }
 }
}
