package io.theficos.spunto.core.design

import kotlin.math.pow

val themeColorRoles = setOf("paper","raised","sunken","ink","ink-2","ink-3","rule","rule-strong","field","accent","accent-strong","accent-soft","on-accent","highlight","danger","warn")
fun contrastRatio(first:String,second:String):Double {
 fun luminance(hex:String):Double = listOf(1,3,5).map { offset -> hex.substring(offset,offset+2).toInt(16)/255.0 }.map { channel -> if(channel<=0.04045) channel/12.92 else ((channel+0.055)/1.055).pow(2.4) }.zip(listOf(0.2126,0.7152,0.0722)).sumOf { (channel,weight) -> channel*weight }
 val a=luminance(first);val b=luminance(second)
 return (maxOf(a,b)+0.05)/(minOf(a,b)+0.05)
}
fun paletteErrors(values:Map<String,String>):List<String> {
 if(themeColorRoles.any { !Regex("#[0-9a-fA-F]{6}").matches(values[it].orEmpty()) }) return listOf("Missing or invalid colour roles")
 val pairs=listOf("ink","ink-2","ink-3","accent","accent-strong","danger").flatMap { ink -> listOf("paper","sunken","raised").map { paper -> Triple(ink,paper,4.5) } }+listOf("ink","ink-2","ink-3","accent","accent-strong").map { Triple(it,"accent-soft",4.5) }+listOf(Triple("ink","highlight",4.5),Triple("on-accent","accent",4.5),Triple("on-accent","accent-strong",4.5))+listOf("paper","raised","sunken").map { Triple("field",it,3.0) }
 return pairs.mapNotNull { (foreground,background,minimum) -> val ratio=contrastRatio(values.getValue(foreground),values.getValue(background));if(ratio<minimum) "$foreground on $background requires $minimum:1" else null }
}
fun resolveThemePalette(theme:String,dark:Boolean,pack:Map<String,String>?=null):Map<String,String> {
 val mode=if(dark) "dark" else "light"
 return pack?.takeIf { paletteErrors(it).isEmpty() }?.filterKeys { it in themeColorRoles } ?: builtInTokens[theme]?.get(mode) ?: builtInTokens.getValue("carta").getValue(mode)
}

data class ThemeAccent(val id:String,val name:String,val light:Map<String,String>,val dark:Map<String,String>)
data class AccentChoice(val id:String?,val name:String)
data class ThemeCard(
 val id:String,val name:String,val status:String="available",val reason:String?=null,val tokens:Int=1,
 val light:Map<String,String> = emptyMap(),val dark:Map<String,String> = emptyMap(),
 val accents:List<ThemeAccent> = emptyList(),val description:String=""
) {
 val available get()=status=="available"
}
fun themeAccentChoices(card:ThemeCard?):List<AccentChoice> = if(card?.available==true && card.accents.isNotEmpty()) listOf(AccentChoice(null,"Default"))+card.accents.map { AccentChoice(it.id,it.name) } else emptyList()
data class ThemeCatalog(val cards:List<ThemeCard>,val authoritative:Boolean=true)
data class ThemePalette(val id:String,val accent:String?=null,val tokens:Int=1,val light:Map<String,String>,val dark:Map<String,String>) {
 fun mode(darkMode:Boolean)=if(darkMode) dark else light
}
private val themeIdPattern=Regex("[a-z][a-z0-9-]{1,23}")
fun boundedThemeText(value:String,limit:Int=256)=value.filter { !it.isISOControl() }.take(limit)
val builtInThemeCards:List<ThemeCard> by lazy {
 builtInThemes.map { choice -> ThemeCard(choice.id,choice.name,light=builtInTokens.getValue(choice.id).getValue("light"),dark=builtInTokens.getValue(choice.id).getValue("dark"),description=choice.description) }
}
private fun modeError(light:Map<String,String>,dark:Map<String,String>):String? =
 listOf("light" to light,"dark" to dark).firstNotNullOfOrNull { (mode,values) -> paletteErrors(values).firstOrNull()?.let { "$mode: $it" } }
fun validateThemeCard(card:ThemeCard):ThemeCard {
 val reason=when {
  !themeIdPattern.matches(card.id) -> "Theme requires a valid ID."
  card.name.isBlank() || card.name.length>80 -> "Theme requires a name of at most 80 characters."
  card.tokens!=1 -> "Unsupported colour token version."
  card.status=="failed" -> card.reason?.takeIf { it.isNotBlank() } ?: "Colour pack is unavailable."
  card.status!="available" -> "Unknown colour pack status."
  card.accents.size>20 -> "Theme has more than 20 accents."
  card.accents.map { it.id }.distinct().size!=card.accents.size -> "Theme has duplicate accent IDs."
  else -> modeError(card.light,card.dark) ?: card.accents.firstNotNullOfOrNull { accent ->
   if(!themeIdPattern.matches(accent.id) || accent.name.isBlank() || accent.name.length>80) "Accent requires a valid ID and name."
   else modeError(accent.light,accent.dark)?.let { "Accent ${accent.id}: $it" }
  }
 }
 if(reason!=null) return card.copy(name=boundedThemeText(card.name,80).ifBlank { "Unavailable colour pack" },status="failed",reason=boundedThemeText(reason),light=emptyMap(),dark=emptyMap(),accents=emptyList())
 return card.copy(name=boundedThemeText(card.name,80),reason=null,light=card.light.filterKeys { it in themeColorRoles },dark=card.dark.filterKeys { it in themeColorRoles },accents=card.accents.map { it.copy(name=boundedThemeText(it.name,80),light=it.light.filterKeys { key -> key in themeColorRoles },dark=it.dark.filterKeys { key -> key in themeColorRoles }) })
}
fun validatedThemeCatalog(cards:List<ThemeCard>,authoritative:Boolean=true):ThemeCatalog {
 val result=builtInThemeCards.toMutableList()
 val groups=cards.groupBy { it.id }
 val seen=mutableSetOf<String>()
 cards.forEachIndexed { index,card ->
  if(!seen.add(card.id)) return@forEachIndexed
  val duplicate=groups.getValue(card.id).size>1
  if(card.id in builtInTokens && !duplicate) return@forEachIndexed
  val candidate=if(duplicate) card.copy(status="failed",reason="Theme ID conflicts with another catalog entry.") else card
  val checked=validateThemeCard(candidate)
  // Failure cards receive a unique display key; ambiguous custom IDs stay failed so they cannot resolve.
  val id=if(card.id in builtInTokens || !themeIdPattern.matches(card.id)) generateSequence("invalid-theme-$index") { "$it-x" }.first { key -> result.none { it.id==key } && key !in groups } else card.id
  result.add(checked.copy(id=id))
 }
 return ThemeCatalog(result,authoritative)
}
fun validateThemePalette(palette:ThemePalette):ThemePalette? = palette.takeIf {
 it.tokens==1 && themeIdPattern.matches(it.id) && (it.accent==null || themeIdPattern.matches(it.accent)) && modeError(it.light,it.dark)==null
}?.copy(light=palette.light.filterKeys { it in themeColorRoles },dark=palette.dark.filterKeys { it in themeColorRoles })
fun resolveThemeSelection(theme:String,accent:String?,catalog:ThemeCatalog?=null,snapshot:ThemePalette?=null):ThemePalette {
 val builtin=builtInThemeCards.firstOrNull { it.id==theme }
 val card=builtin ?: catalog?.cards?.firstOrNull { it.id==theme }?.let(::validateThemeCard)?.takeIf { it.available }
 if(card!=null) {
  val choice=card.accents.firstOrNull { it.id==accent }
  return ThemePalette(card.id,choice?.id,light=(choice?.light ?: card.light).filterKeys { it in themeColorRoles },dark=(choice?.dark ?: card.dark).filterKeys { it in themeColorRoles })
 }
 if(catalog?.authoritative!=true) validateThemePalette(snapshot ?: return resolveThemeSelection("carta",null))?.takeIf { it.id==theme && it.accent==accent }?.let { return it }
 return resolveThemeSelection("carta",null)
}
fun normalizedReadingFace(value:String)=if(value=="sans") "sans" else "serif"
fun normalizedReadingSize(value:String)=if(value in setOf("S","L")) value else "M"
fun readingTextSize(value:String)=when(normalizedReadingSize(value)) { "S" -> 16; "L" -> 19; else -> 17 }
fun readingLineHeight(value:String)=when(normalizedReadingSize(value)) { "S" -> 25; "L" -> 30; else -> 27 }
