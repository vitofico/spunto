@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class,androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.theficos.spunto

import android.content.res.Resources
import androidx.compose.ui.platform.LocalContext

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.design.*

/** "Carta · System mode · Serif text, size M"; a theme chosen only for this phone says so. */
internal fun appearanceSummary(resources:Resources,theme:String,catalog:ThemeCatalog?,phoneOnly:Boolean,mode:String,face:String,size:String):String {
 val card=(catalog?.cards ?: builtInThemeCards).firstOrNull {it.id==theme}
 val name=card?.name?.takeIf {it.isNotBlank()} ?: if(card==null) "Carta" else resources.getString(R.string.settings_unavailable_colour_pack)
 return listOf(name+if(phoneOnly) resources.getString(R.string.settings_on_this_phone_390c9) else "",resources.getString(R.string.settings_mode ,resources.getString(when(mode) {"light" -> R.string.settings_light;"dark" -> R.string.settings_dark;else -> R.string.settings_system})),resources.getString(R.string.settings_text_size ,if(normalizedReadingFace(face)=="sans") resources.getString(R.string.settings_sans) else resources.getString(R.string.settings_serif),normalizedReadingSize(size))).joinToString(" · ")
}
/** Android 12+ draws the starting window in the app's own night mode, so an in-app Light or Dark choice reaches the cold-start window too. */
internal fun applyWindowNightMode(context:Context,mode:String) {
 if(Build.VERSION.SDK_INT<31) return
 context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(when(mode) {"dark" -> UiModeManager.MODE_NIGHT_YES;"light" -> UiModeManager.MODE_NIGHT_NO;else -> UiModeManager.MODE_NIGHT_AUTO})
}

/** 05 §8.4 (R24): compact theme cards and "Only on this phone"; mode, pure black and wallpaper colours; the reading face and size. */
@Composable fun AppearanceScreen(theme:String,mode:String,black:Boolean,wallpaper:Boolean,onTheme:(String)->Unit,onMode:(String)->Unit,onBlack:(Boolean)->Unit,onWallpaper:(Boolean)->Unit,themeOverride:Boolean=true,onUseInstance:(()->Unit)?=null,catalog:ThemeCatalog=ThemeCatalog(builtInThemeCards,false),accent:String?=null,onAccent:((String?)->Unit)?=null,instanceName:String="Carta",readingFace:String="serif",readingSize:String="M",onReadingFace:(String)->Unit={},onReadingSize:(String)->Unit={}) {
 val resources=LocalContext.current.resources
 val dark=mode=="dark" || mode=="system" && isSystemInDarkTheme()
 val selectedCard=catalog.cards.firstOrNull { it.id==theme && it.available }
 val selectedAccent=selectedCard?.accents?.firstOrNull { it.id==accent }?.id
 val accentChoices=themeAccentChoices(selectedCard)
 val instance=boundedThemeText(instanceName,80).ifBlank {resources.getString(R.string.settings_unavailable_colour_pack)}
 LazyColumn(contentPadding=PaddingValues(bottom=SpuntoSpacing.s24)) {
  item {
   SettingsSection(resources.getString(R.string.settings_theme))
   BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal=SpuntoSpacing.s20,vertical=SpuntoSpacing.s8)) {
    // A hair under the exact share, so rounding never pushes the last card of a row onto the next.
    val gap=SpuntoSpacing.s12;val columns=if(maxWidth<300.dp) 2 else 3;val width=(maxWidth-gap*(columns-1))/columns-1.dp
    FlowRow(Modifier.selectableGroup(),horizontalArrangement=Arrangement.spacedBy(gap),verticalArrangement=Arrangement.spacedBy(gap),maxItemsInEachRow=columns) {
     catalog.cards.forEach { card -> ThemeChoiceCard(card,theme==card.id && card.available,if(card.available) resolveThemeSelection(card.id,if(theme==card.id) accent else null,catalog).mode(dark) else null,Modifier.width(width)) { onTheme(card.id) } }
    }
   }
   if(onUseInstance!=null) SettingsSwitchRow(resources.getString(R.string.settings_only_on_this_phone),if(themeOverride) resources.getString(R.string.settings_other_devices_use ,instance) else resources.getString(R.string.settings_every_device_uses_change_it_for_all ,instance),themeOverride) { if(it) onTheme(theme) else onUseInstance() }
   else if(themeOverride) Text(resources.getString(R.string.settings_other_devices_use ,instance),Modifier.padding(horizontal=SpuntoSpacing.s20,vertical=SpuntoSpacing.s8),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
   if(accentChoices.isNotEmpty() && onAccent!=null) {
    Text(resources.getString(R.string.settings_accent),Modifier.padding(start=SpuntoSpacing.s20,end=SpuntoSpacing.s20,top=SpuntoSpacing.s8),style=MaterialTheme.typography.bodyLarge)
    FlowRow(Modifier.padding(horizontal=SpuntoSpacing.s20),horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) { accentChoices.forEach { choice -> FilterChip(selected=selectedAccent==choice.id,onClick={onAccent(choice.id)},label={Text(if(choice.id==null) resources.getString(R.string.settings_default_accent) else choice.name)}) } }
   }
  }
  item {
   SettingsSection(resources.getString(R.string.settings_mode_a7b93))
   Segmented(listOf("system" to resources.getString(R.string.settings_system),"light" to resources.getString(R.string.settings_light),"dark" to resources.getString(R.string.settings_dark)),mode,onMode)
   SettingsSwitchRow(resources.getString(R.string.settings_pure_black),if(dark) resources.getString(R.string.settings_a_black_background_in_dark_mode) else resources.getString(R.string.settings_only_in_dark_mode),black,enabled=dark,onChange=onBlack)
   if(Build.VERSION.SDK_INT>=31) SettingsSwitchRow(resources.getString(R.string.settings_wallpaper_colours),resources.getString(R.string.settings_take_the_accent_from_your_wallpaper),wallpaper,onChange=onWallpaper)
  }
  item {
   SettingsSection(resources.getString(R.string.settings_reading))
   Text(resources.getString(R.string.settings_face),Modifier.padding(start=SpuntoSpacing.s20,end=SpuntoSpacing.s20,top=SpuntoSpacing.s8),style=MaterialTheme.typography.bodyLarge)
   Segmented(listOf("serif" to resources.getString(R.string.settings_serif),"sans" to resources.getString(R.string.settings_sans)),normalizedReadingFace(readingFace),onReadingFace)
   Text(resources.getString(R.string.settings_text_size_3cc6e),Modifier.padding(start=SpuntoSpacing.s20,end=SpuntoSpacing.s20,top=SpuntoSpacing.s8),style=MaterialTheme.typography.bodyLarge)
   Segmented(listOf("S" to "S","M" to "M","L" to "L"),normalizedReadingSize(readingSize),onReadingSize)
   Text(resources.getString(R.string.settings_a_thought_worth_keeping_in_your_own),Modifier.padding(horizontal=SpuntoSpacing.s20,vertical=SpuntoSpacing.s16),style=spuntoType(readingFace,readingSize).reading)
  }
 }
}
@Composable private fun Segmented(choices:List<Pair<String,String>>,selected:String,onSelect:(String)->Unit) {
 val resources=LocalContext.current.resources
 SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal=SpuntoSpacing.s20,vertical=SpuntoSpacing.s8)) {
  choices.forEachIndexed { index,(value,label) -> SegmentedButton(selected=selected==value,onClick={onSelect(value)},shape=SegmentedButtonDefaults.itemShape(index,choices.size)) { Text(label) } }
 }
}
/** A small page in the theme's own colours: a time, a line of reading text, a label, a dashed proposal and five swatches; a failed pack shows its reason and cannot be chosen. */
@Composable private fun ThemeChoiceCard(card:ThemeCard,chosen:Boolean,palette:Map<String,String>?,modifier:Modifier,onClick:()->Unit) {
 val resources=LocalContext.current.resources
 val shape=RoundedCornerShape(SpuntoRadius.r12)
 val choice=if(card.available) Modifier.selectable(chosen,role=Role.RadioButton,onClick=onClick) else Modifier.semantics(mergeDescendants=true) { disabled() }
 Column(modifier.then(choice),verticalArrangement=Arrangement.spacedBy(6.dp)) {
  if(palette!=null) {
   fun c(role:String)=tokenColor(palette.getValue(role))
   val dashed=c("ink-2")
   Column(Modifier.fillMaxWidth().clip(shape).background(c("paper")).border(if(chosen) 2.dp else 1.dp,if(chosen) MaterialTheme.colorScheme.primary else c("rule"),shape).padding(10.dp).clearAndSetSemantics {},verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s4)) {
    Text("14:32",style=MaterialTheme.typography.labelSmall,color=c("ink-3"))
    Text(resources.getString(R.string.settings_a_loose_idea),style=SpuntoType.readingSmall,color=c("ink"),maxLines=1,overflow=TextOverflow.Ellipsis)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s4)) {
     Text(resources.getString(R.string.settings_idea),style=SpuntoType.readingSmall,color=c("accent"))
     Text(resources.getString(R.string.settings_later),Modifier.drawBehind { drawRoundRect(dashed,cornerRadius=CornerRadius(SpuntoRadius.r4.toPx()),style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),2.dp.toPx())))) }.padding(horizontal=SpuntoSpacing.s4),style=SpuntoType.readingSmall,color=dashed)
    }
    Row(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s4)) { listOf("paper","ink","accent","accent-soft","highlight").forEach { role -> Box(Modifier.size(10.dp).background(c(role),CircleShape).border(1.dp,c("rule"),CircleShape)) } }
   }
  } else Box(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)) {
   Text(resources.getString(R.string.settings_unavailable ,boundedThemeText(if(card.localProblem!=null) resources.getString(R.string.settings_invalid_colour_pack) else card.reason ?: resources.getString(R.string.settings_colour_pack_is_unavailable))),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=4,overflow=TextOverflow.Ellipsis)
  }
  Text(card.name.ifBlank {resources.getString(R.string.settings_unavailable_colour_pack)}+if(chosen) " ✓" else "",style=MaterialTheme.typography.bodyMedium,color=if(card.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
 }
}
