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
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
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
@Composable fun AppearanceScreen(theme:String,mode:String,black:Boolean,wallpaper:Boolean,onTheme:(String)->Unit,onMode:(String)->Unit,onBlack:(Boolean)->Unit,onWallpaper:(Boolean)->Unit,themeOverride:Boolean=true,onUseInstance:(()->Unit)?=null,catalog:ThemeCatalog=ThemeCatalog(builtInThemeCards,false),accent:String?=null,onAccent:((String?)->Unit)?=null,instanceName:String="Carta",readingFace:String="serif",readingSize:String="M",onReadingFace:(String)->Unit={},onReadingSize:(String)->Unit={},backgroundStyle:String="artwork",onBackgroundStyle:(String)->Unit={}) {
 val resources=LocalContext.current.resources
 val dark=mode=="dark" || mode=="system" && isSystemInDarkTheme()
 val selectedCard=catalog.cards.firstOrNull { it.id==theme && it.available }
 val selectedAccent=selectedCard?.accents?.firstOrNull { it.id==accent }?.id
 val accentChoices=themeAccentChoices(selectedCard)
 val instance=boundedThemeText(instanceName,80).ifBlank {resources.getString(R.string.settings_unavailable_colour_pack)}
 LazyColumn(contentPadding=PaddingValues(bottom=SpuntoSpacing.s24)) {
  item {
   SettingsSection(resources.getString(R.string.settings_background_style))
   AppearanceGroup {
    Row(Modifier.fillMaxWidth().padding(SpuntoSpacing.s12).selectableGroup(),horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
     listOf("plain" to resources.getString(R.string.settings_background_plain),"artwork" to resources.getString(R.string.settings_background_artwork)).forEach { (value,label) ->
      val selected=normalizedBackgroundStyle(backgroundStyle)==value
      Column(Modifier.weight(1f).clip(MaterialTheme.shapes.small).selectable(selected,role=Role.RadioButton,onClick={onBackgroundStyle(value)}).border(if(selected) 2.dp else 1.dp,if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,MaterialTheme.shapes.small)) {
       Box(Modifier.fillMaxWidth().height(96.dp).spuntoBackground(style=value).clearAndSetSemantics {})
       Text(label,Modifier.padding(SpuntoSpacing.s12),style=MaterialTheme.typography.labelLarge)
      }
     }
    }
    Text(resources.getString(R.string.settings_background_help),Modifier.padding(horizontal=SpuntoSpacing.s16).padding(bottom=SpuntoSpacing.s12),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }
  item {
   SettingsSection(resources.getString(R.string.settings_theme))
   BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal=SpuntoSpacing.s20,vertical=SpuntoSpacing.s8)) {
    // Three cards across at ordinary phone widths; narrow windows retain readable names.
    val gap=SpuntoSpacing.s8;val columns=if(maxWidth<300.dp) 2 else 3;val width=(maxWidth-gap*(columns-1))/columns-1.dp
    FlowRow(Modifier.selectableGroup(),horizontalArrangement=Arrangement.spacedBy(gap),verticalArrangement=Arrangement.spacedBy(gap),maxItemsInEachRow=columns) {
     catalog.cards.forEach { card -> ThemeChoiceCard(card,theme==card.id && card.available,if(card.available) resolveThemeSelection(card.id,if(theme==card.id) accent else null,catalog).mode(dark) else null,Modifier.width(width)) { onTheme(card.id) } }
    }
   }
   if(onUseInstance!=null || themeOverride || accentChoices.isNotEmpty() && onAccent!=null) AppearanceGroup(Modifier.padding(top=SpuntoSpacing.s8)) {
    if(onUseInstance!=null) SettingsSwitchRow(resources.getString(R.string.settings_only_on_this_phone),if(themeOverride) resources.getString(R.string.settings_other_devices_use ,instance) else resources.getString(R.string.settings_every_device_uses_change_it_for_all ,instance),themeOverride) { if(it) onTheme(theme) else onUseInstance() }
    else if(themeOverride) Text(resources.getString(R.string.settings_other_devices_use ,instance),Modifier.padding(horizontal=SpuntoSpacing.s16,vertical=SpuntoSpacing.s12),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(accentChoices.isNotEmpty() && onAccent!=null) {
     AppearanceDivider()
     Text(resources.getString(R.string.settings_accent),Modifier.padding(start=SpuntoSpacing.s16,end=SpuntoSpacing.s16,top=SpuntoSpacing.s12),style=MaterialTheme.typography.bodyLarge)
     FlowRow(Modifier.padding(horizontal=SpuntoSpacing.s12).padding(bottom=SpuntoSpacing.s8),horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) { accentChoices.forEach { choice -> FilterChip(selected=selectedAccent==choice.id,onClick={onAccent(choice.id)},label={Text(if(choice.id==null) resources.getString(R.string.settings_default_accent) else choice.name)}) } }
    }
   }
  }
  item {
   SettingsSection(resources.getString(R.string.settings_mode_a7b93))
   AppearanceGroup {
    Segmented(listOf("system" to resources.getString(R.string.settings_system),"light" to resources.getString(R.string.settings_light),"dark" to resources.getString(R.string.settings_dark)),mode,onMode)
    AppearanceDivider()
    SettingsSwitchRow(resources.getString(R.string.settings_pure_black),if(dark) resources.getString(R.string.settings_a_black_background_in_dark_mode) else resources.getString(R.string.settings_only_in_dark_mode),black,enabled=dark,onChange=onBlack)
    if(Build.VERSION.SDK_INT>=31) {
     AppearanceDivider()
     SettingsSwitchRow(resources.getString(R.string.settings_wallpaper_colours),resources.getString(R.string.settings_take_the_accent_from_your_wallpaper),wallpaper,onChange=onWallpaper)
    }
   }
  }
  item {
   SettingsSection(resources.getString(R.string.settings_reading))
   AppearanceGroup {
    Text(resources.getString(R.string.settings_face),Modifier.padding(start=SpuntoSpacing.s16,end=SpuntoSpacing.s16,top=SpuntoSpacing.s12),style=MaterialTheme.typography.bodyLarge)
    Segmented(listOf("serif" to resources.getString(R.string.settings_serif),"sans" to resources.getString(R.string.settings_sans)),normalizedReadingFace(readingFace),onReadingFace)
    AppearanceDivider()
    Text(resources.getString(R.string.settings_text_size_3cc6e),Modifier.padding(start=SpuntoSpacing.s16,end=SpuntoSpacing.s16,top=SpuntoSpacing.s12),style=MaterialTheme.typography.bodyLarge)
    Segmented(listOf("S" to "S","M" to "M","L" to "L"),normalizedReadingSize(readingSize),onReadingSize)
   }
   Text(resources.getString(R.string.settings_a_thought_worth_keeping_in_your_own),Modifier.padding(horizontal=SpuntoSpacing.s20,vertical=SpuntoSpacing.s16),style=spuntoType(readingFace,readingSize).reading)
  }
 }
}

@Composable private fun AppearanceGroup(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
 val shape=RoundedCornerShape(SpuntoRadius.r14)
 Column(modifier.fillMaxWidth().padding(horizontal=SpuntoSpacing.s20).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLow).border(1.dp,MaterialTheme.colorScheme.outlineVariant,shape),content=content)
}
@Composable private fun AppearanceDivider() {
 HorizontalDivider(Modifier.padding(horizontal=SpuntoSpacing.s16),color=MaterialTheme.colorScheme.outlineVariant)
}
@Composable private fun Segmented(choices:List<Pair<String,String>>,selected:String,onSelect:(String)->Unit) {
 SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal=SpuntoSpacing.s12,vertical=SpuntoSpacing.s8)) {
  choices.forEachIndexed { index,(value,label) -> SegmentedButton(selected=selected==value,onClick={onSelect(value)},shape=SegmentedButtonDefaults.itemShape(index,choices.size),icon={},colors=SegmentedButtonDefaults.colors(activeContainerColor=MaterialTheme.colorScheme.primary,activeContentColor=MaterialTheme.colorScheme.onPrimary)) { Text(label) } }
 }
}
/** Each miniature uses its own paper, ink and accent; the active accent frames the selected choice. */
@Composable private fun ThemeChoiceCard(card:ThemeCard,chosen:Boolean,palette:Map<String,String>?,modifier:Modifier,onClick:()->Unit) {
 val resources=LocalContext.current.resources
 val shape=RoundedCornerShape(SpuntoRadius.r12)
 val paper=palette?.let {tokenColor(it.getValue("paper"))} ?: MaterialTheme.colorScheme.surfaceVariant
 val ink=palette?.let {tokenColor(it.getValue("ink"))} ?: MaterialTheme.colorScheme.onSurfaceVariant
 val rule=palette?.let {tokenColor(it.getValue("rule"))} ?: MaterialTheme.colorScheme.outlineVariant
 val choice=if(card.available) Modifier.selectable(chosen,role=Role.RadioButton,onClick=onClick) else Modifier.semantics(mergeDescendants=true) { disabled() }
 Column(modifier.clip(shape).background(paper).then(choice).border(if(chosen) 2.dp else 1.dp,if(chosen) MaterialTheme.colorScheme.primary else rule,shape)) {
  if(palette!=null) {
   val accentColor=tokenColor(palette.getValue("accent"))
   Box(Modifier.fillMaxWidth().height(76.dp).spuntoBackground(style="artwork",paper=paper,accent=accentColor).clearAndSetSemantics {}) {
    Text(resources.getString(R.string.settings_a_loose_idea),Modifier.align(Alignment.BottomStart).padding(start=SpuntoSpacing.s12,end=SpuntoSpacing.s8,bottom=SpuntoSpacing.s4),style=SpuntoType.readingSmall,color=ink,maxLines=1,overflow=TextOverflow.Ellipsis)
    if(chosen) Box(Modifier.align(Alignment.TopEnd).padding(SpuntoSpacing.s8).size(22.dp).background(MaterialTheme.colorScheme.primary,CircleShape),contentAlignment=Alignment.Center) {
     Text("✓",color=MaterialTheme.colorScheme.onPrimary,style=MaterialTheme.typography.labelMedium)
    }
   }
  } else Box(Modifier.fillMaxWidth().heightIn(min=76.dp).padding(SpuntoSpacing.s12)) {
   Text(resources.getString(R.string.settings_unavailable ,boundedThemeText(if(card.localProblem!=null) resources.getString(R.string.settings_invalid_colour_pack) else card.reason ?: resources.getString(R.string.settings_colour_pack_is_unavailable))),style=MaterialTheme.typography.bodySmall,color=ink,maxLines=4,overflow=TextOverflow.Ellipsis)
  }
  Text(card.name.ifBlank {resources.getString(R.string.settings_unavailable_colour_pack)},Modifier.fillMaxWidth().padding(horizontal=SpuntoSpacing.s12,vertical=SpuntoSpacing.s8),style=MaterialTheme.typography.labelLarge,color=ink,maxLines=1,overflow=TextOverflow.Ellipsis)
 }
}
