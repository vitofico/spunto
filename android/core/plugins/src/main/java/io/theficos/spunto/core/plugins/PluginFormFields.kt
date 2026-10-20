package io.theficos.spunto.core.plugins

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun FormChoiceField(title:String,choices:JsonArray,value:JsonElement,enabled:Boolean,language:Boolean=false,onChoose:(JsonElement)->Unit) {
 fun label(choice:JsonElement)=if(language) when(choice.jsonPrimitive.content) {"it" -> "Italian";"en" -> "English";else -> choice.jsonPrimitive.content} else choice.jsonPrimitive.content
 if(choices.size<=5 && choices.all {it.jsonPrimitive.content.length<=32}) {
  Column {Text(title);FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
   choices.forEach {choice -> FilterChip(selected=value==choice,onClick={onChoose(choice)},enabled=enabled,label={Text(label(choice))})}
  }}
 } else {
  var expanded by remember(choices) {mutableStateOf(false)}
  var query by remember(choices) {mutableStateOf("")}
  OutlinedCard(onClick={expanded=true},enabled=enabled,modifier=Modifier.fillMaxWidth().semantics {contentDescription="Choose $title"}) {
   Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {Text(title,style=MaterialTheme.typography.labelLarge);Text(if(value in choices) label(value) else "Choose…")}
  }
  if(expanded) AlertDialog(onDismissRequest={expanded=false},title={Text(title)},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
   OutlinedTextField(value=query,onValueChange={query=it},label={Text("Find an option")},singleLine=true,modifier=Modifier.fillMaxWidth())
   val visible=choices.filter {label(it).contains(query,true)}
   if(visible.isEmpty()) Text("No matches. Try another search.")
   LazyColumn(Modifier.heightIn(max=320.dp)) {items(visible) {choice ->
    Row(Modifier.fillMaxWidth().clickable {onChoose(choice);expanded=false}.padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
     RadioButton(selected=value==choice,onClick={onChoose(choice);expanded=false})
     Text(label(choice),modifier=Modifier.weight(1f).padding(top=12.dp))
    }
   }}
  }},confirmButton={TextButton(onClick={expanded=false}) {Text("Cancel")}})
 }
}
