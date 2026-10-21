@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.theficos.spunto

import android.content.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.design.*
import io.theficos.spunto.core.model.*
import io.theficos.spunto.core.plugins.*
import io.theficos.spunto.data.sync.*
import io.theficos.spunto.data.local.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

@Composable fun AppearanceScreen(theme:String,mode:String,black:Boolean,wallpaper:Boolean,onTheme:(String)->Unit,onMode:(String)->Unit,onBlack:(Boolean)->Unit,onWallpaper:(Boolean)->Unit,themeOverride:Boolean=true,onUseInstance:(()->Unit)?=null,catalog:ThemeCatalog=ThemeCatalog(builtInThemeCards,false),accent:String?=null,onAccent:((String?)->Unit)?=null,instanceName:String="Carta",readingFace:String="serif",readingSize:String="M",onReadingFace:(String)->Unit={},onReadingSize:(String)->Unit={}) {
 val dark=mode=="dark" || mode=="system" && isSystemInDarkTheme()
 val selectedCard=catalog.cards.firstOrNull { it.id==theme && it.available }
 val selectedAccent=selectedCard?.accents?.firstOrNull { it.id==accent }?.id
 val accentChoices=themeAccentChoices(selectedCard)
 LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
  item {
   Text(if(themeOverride) "Theme on this phone" else "Using the instance theme",style=MaterialTheme.typography.bodyMedium)
   if(themeOverride) Text("Other devices use ${boundedThemeText(instanceName,80)}.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
   if(onUseInstance!=null) Row(verticalAlignment=Alignment.CenterVertically) {
    Text("Use a different theme on this phone",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
    Switch(checked=themeOverride,onCheckedChange={if(it) onTheme(theme) else onUseInstance()},modifier=Modifier.semantics { contentDescription="Use a different theme on this phone" })
   }
  }
  items(catalog.cards,key={it.id}) { choice ->
   if(choice.available) {
    val chosen=theme==choice.id
    val palette=resolveThemeSelection(choice.id,if(chosen) accent else null,catalog).mode(dark)
    SpuntoTheme(choice.id,mode,pack=palette,readingFace=readingFace,readingSize=readingSize) {
     Surface(Modifier.fillMaxWidth().clickable(role=Role.RadioButton) { onTheme(choice.id) }.semantics { selected=chosen },shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainer,border=if(chosen) BorderStroke(2.dp,MaterialTheme.colorScheme.primary) else null) {
      Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
       Text(choice.name+if(chosen) " ✓" else "",style=MaterialTheme.typography.titleLarge)
       if(choice.description.isNotBlank()) Text(choice.description,style=MaterialTheme.typography.bodyMedium)
       Text("14:32 · Today",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
       Text("A quiet place for loose ideas.",style=SpuntoType.reading)
       FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Surface(shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.primaryContainer) { Text("idea",Modifier.padding(horizontal=8.dp,vertical=4.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onPrimaryContainer) }
        val proposalColour=MaterialTheme.colorScheme.onSurfaceVariant
        Box(Modifier.drawBehind { drawRoundRect(proposalColour,cornerRadius=androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),style=Stroke(width=1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(),3.dp.toPx())))) }.padding(horizontal=8.dp,vertical=4.dp)) { Text("Suggested label",style=MaterialTheme.typography.bodyMedium,color=proposalColour) }
       }
       Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("paper","ink","accent","accent-soft","highlight").forEach { role -> Box(Modifier.size(20.dp).background(tokenColor(palette.getValue(role)),CircleShape).semantics { contentDescription="${choice.name} $role swatch" }) } }
      }
     }
    }
   } else Surface(Modifier.fillMaxWidth().semantics(mergeDescendants=true) { disabled() },shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainer) {
    Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) { Text(choice.name,style=MaterialTheme.typography.titleLarge);Text("Unavailable · ${boundedThemeText(choice.reason ?: "Colour pack is unavailable.")}",style=MaterialTheme.typography.bodyMedium) }
   }
  }
  if(accentChoices.isNotEmpty() && onAccent!=null) item {
   Text("Accent",style=MaterialTheme.typography.bodyMedium)
   FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
    accentChoices.forEach { choice -> FilterChip(selected=selectedAccent==choice.id,onClick={onAccent(choice.id)},label={Text(choice.name)}) }
   }
  }
  item {
   Text("Mode",style=MaterialTheme.typography.bodyMedium)
   FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("system","light","dark").forEach { choice -> FilterChip(selected=mode==choice,onClick={onMode(choice)},label={Text(choice.replaceFirstChar { it.uppercase() })}) } }
  }
  item {
   Text("Reading face",style=MaterialTheme.typography.bodyMedium)
   FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("serif" to "Serif","sans" to "Sans").forEach { (value,label) -> FilterChip(selected=normalizedReadingFace(readingFace)==value,onClick={onReadingFace(value)},label={Text(label)}) } }
   Text("Text size",style=MaterialTheme.typography.bodyMedium)
   FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("S","M","L").forEach { value -> FilterChip(selected=normalizedReadingSize(readingSize)==value,onClick={onReadingSize(value)},label={Text(value)}) } }
   Text("A thought worth keeping, in your own words.",Modifier.padding(vertical=12.dp),style=spuntoType(readingFace,readingSize).reading)
  }
  item { Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) { Text("Pure black",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium);Switch(black,onBlack,modifier=Modifier.semantics {contentDescription="Pure black"}) };Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) { Text("Wallpaper colours",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium);Switch(wallpaper,onWallpaper,modifier=Modifier.semantics {contentDescription="Wallpaper colours"}) } }
 }
}
