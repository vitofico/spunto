@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.theficos.spunto

import android.content.Context

import androidx.compose.ui.platform.LocalContext

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
private fun filterDateLabel(context:Context,raw:String)=raw.takeIf {it.isNotBlank()}?.let {LocalDate.parse(it).format(DateTimeFormatter.ofPattern("d MMM yyyy",context.resources.configuration.locales[0]))} ?: context.getString(R.string.core_any_date)

@Composable internal fun NoteFiltersSheet(from:String,to:String,onDismiss:()->Unit,onApply:(String,String)->Unit,today:LocalDate=LocalDate.now()) {
 val context=LocalContext.current
 var draftFrom by rememberSaveable(from,to) {mutableStateOf(from)}
 var draftTo by rememberSaveable(from,to) {mutableStateOf(to)}
 var picking by remember {mutableStateOf<String?>(null)}
 ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
  NoteFilterContent(draftFrom,draftTo,today,onFrom={draftFrom=it},onTo={draftTo=it},onPick={picking=it},onDismiss=onDismiss,onApply={onApply(draftFrom,draftTo)})
 }
 picking?.let {field ->
  val current=if(field=="from") draftFrom else draftTo
  val state=rememberDatePickerState(initialSelectedDateMillis=pickerDateMillis(current))
  DatePickerDialog(onDismissRequest={picking=null},confirmButton={TextButton(onClick={state.selectedDateMillis?.let {if(field=="from") draftFrom=pickerDate(it) else draftTo=pickerDate(it)};picking=null},enabled=state.selectedDateMillis!=null) {Text(context.getString(R.string.core_choose_date))}},dismissButton={TextButton(onClick={picking=null}) {Text(context.getString(R.string.core_cancel))}}) {
   DatePicker(state,title={Text(if(field=="from") context.getString(R.string.core_created_from) else context.getString(R.string.core_created_through),Modifier.padding(start=SpuntoSpacing.s24,top=SpuntoSpacing.s16))})
  }
 }
}

@Composable internal fun NoteFilterContent(from:String,to:String,today:LocalDate,onFrom:(String)->Unit,onTo:(String)->Unit,onPick:(String)->Unit,onDismiss:()->Unit,onApply:()->Unit) {
 val context=LocalContext.current
 val valid=remember(from,to) {runCatching {CreatedRange.parse(from,to)}.isSuccess}
 Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal=SpuntoSpacing.s24).padding(bottom=SpuntoSpacing.s20),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s16)) {
  Text(context.getString(R.string.core_filter_notes),style=MaterialTheme.typography.headlineSmall)
  Text(context.getString(R.string.core_date_created),style=MaterialTheme.typography.titleSmall)
  Text(context.getString(R.string.core_filter_hint),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
  FlowRow(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
   listOf(context.getString(R.string.core_today) to 1L,context.getString(R.string.core_last_week) to 7L,context.getString(R.string.core_last_month) to 30L).forEach {(label,days) ->
    val start=today.minusDays(days-1).toString();val end=today.toString()
    FilterChip(selected=from==start && to==end,onClick={onFrom(start);onTo(end)},label={Text(label)})
   }
  }
  Row(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
   OutlinedButton(onClick={onPick("from")},modifier=Modifier.weight(1f).semantics {contentDescription=context.getString(R.string.core_choose_start)},shape=MaterialTheme.shapes.medium,contentPadding=PaddingValues(SpuntoSpacing.s16)) {Column {Text(context.getString(R.string.core_from),style=MaterialTheme.typography.labelSmall);Text(filterDateLabel(context,from))}}
   OutlinedButton(onClick={onPick("to")},modifier=Modifier.weight(1f).semantics {contentDescription=context.getString(R.string.core_choose_end)},shape=MaterialTheme.shapes.medium,contentPadding=PaddingValues(SpuntoSpacing.s16)) {Column {Text(context.getString(R.string.core_through),style=MaterialTheme.typography.labelSmall);Text(filterDateLabel(context,to))}}
  }
  if(!valid) Text(context.getString(R.string.core_end_before_start),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
  if(from.isNotBlank() || to.isNotBlank()) TextButton(onClick={onFrom("");onTo("")}) {Text(context.getString(R.string.core_clear_dates))}
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12),verticalAlignment=Alignment.CenterVertically) {
   TextButton(onClick=onDismiss,modifier=Modifier.weight(1f)) {Text(context.getString(R.string.core_cancel))}
   Button(onClick=onApply,enabled=valid,modifier=Modifier.weight(1f)) {Text(context.getString(R.string.core_apply_filters))}
  }
 }
}

/** What an active date filter reads as on its pill: a preset's name, or the dates. */
internal fun dateFilterLabel(context:Context,from:String,to:String,today:LocalDate=LocalDate.now()):String {
 val presets=listOf(1L to context.getString(R.string.core_today),7L to context.getString(R.string.core_last_week),30L to context.getString(R.string.core_last_month))
 presets.firstOrNull {(days,_) ->from==today.minusDays(days-1).toString() && to==today.toString()}?.let {return it.second}
 return when {from.isNotBlank() && to.isNotBlank() -> context.getString(R.string.core_date_range,filterDateLabel(context,from),filterDateLabel(context,to));from.isNotBlank() -> context.getString(R.string.core_date_from,filterDateLabel(context,from));else -> context.getString(R.string.core_date_through,filterDateLabel(context,to))}
}
/** The active date filter, cleared in one tap (03 §3.2). */
@Composable internal fun DateFilterPill(from:String,to:String,onClear:()->Unit) {
 val context=LocalContext.current
 val label=dateFilterLabel(context,from,to)
 Row(Modifier.padding(horizontal=SpuntoSpacing.s20)) {
  InputChip(selected=true,onClick=onClear,label={Text("$label  ×")},modifier=Modifier.heightIn(min=48.dp).semantics {contentDescription=context.getString(R.string.core_clear_date_filter,label)})
 }
}
