// Generated from web/tokens.json by android/core/design/generate-tokens.py.
package io.theficos.spunto.core.design
import androidx.compose.ui.unit.dp
data class ThemeChoice(val id:String,val name:String,val description:String)
val builtInThemes = listOf(
 ThemeChoice("carta","Carta","Warm paper, a rust accent."),
 ThemeChoice("ardesia","Ardesia","Cool slate, a blue accent."),
 ThemeChoice("salvia","Salvia","Sage paper, a green accent."),
 ThemeChoice("corallo","Corallo","Coral and rose, with deep wine ink."),
 ThemeChoice("elettrica","Elettrica","Electric blue and violet, with lavender light."),
)
val builtInTokens: Map<String, Map<String, Map<String, String>>> = mapOf(
 "carta" to mapOf(
  "light" to mapOf("paper" to "#F8F5F0","raised" to "#FEFDFA","sunken" to "#F0EDE6","ink" to "#221D18","ink-2" to "#5B544C","ink-3" to "#6E6860","rule" to "#E3DFD8","rule-strong" to "#CCC7BE","field" to "#8A857E","accent" to "#9F4C2A","accent-strong" to "#883A1D","accent-soft" to "#FAE9DE","on-accent" to "#FEF9F4","highlight" to "#F5E4B0","danger" to "#AF2934","warn" to "#CD9130","sh" to "34 29 24","chrome" to "#E4DFD7","chrome-pill" to "#F8F5F0"),
  "dark" to mapOf("paper" to "#171412","raised" to "#211D1A","sunken" to "#100E0C","ink" to "#EAE5DD","ink-2" to "#BDB6AE","ink-3" to "#9D978F","rule" to "#2D2925","rule-strong" to "#413C37","field" to "#736E67","accent" to "#E7986F","accent-strong" to "#F6B38B","accent-soft" to "#3F271D","on-accent" to "#24140C","highlight" to "#5F4A1A","danger" to "#ED7473","warn" to "#E1AD57","sh" to "0 0 0","chrome" to "#0C0B0A","chrome-pill" to "#1C1916"),
 ),
 "ardesia" to mapOf(
  "light" to mapOf("paper" to "#F4F6F8","raised" to "#FCFDFE","sunken" to "#EBEEF2","ink" to "#161B22","ink-2" to "#4A5361","ink-3" to "#5E6775","rule" to "#DEE2E8","rule-strong" to "#C5CBD4","field" to "#7D8592","accent" to "#2F5E9E","accent-strong" to "#234B82","accent-soft" to "#E3EBF7","on-accent" to "#F7FAFE","highlight" to "#F2E5A8","danger" to "#B4232F","warn" to "#C98A1E","sh" to "22 27 34","chrome" to "#DFE3E9","chrome-pill" to "#F4F6F8"),
  "dark" to mapOf("paper" to "#13161A","raised" to "#1C2026","sunken" to "#0D0F12","ink" to "#E4E8EE","ink-2" to "#B3BAC5","ink-3" to "#929AA6","rule" to "#272C33","rule-strong" to "#3A4049","field" to "#6C7480","accent" to "#8DB2EE","accent-strong" to "#B0CBF5","accent-soft" to "#1E2B40","on-accent" to "#0F1A2B","highlight" to "#57481A","danger" to "#F07A7A","warn" to "#E0AE55","sh" to "0 0 0","chrome" to "#0A0C0E","chrome-pill" to "#1A1D22"),
 ),
 "salvia" to mapOf(
  "light" to mapOf("paper" to "#F3F5F0","raised" to "#FBFCF9","sunken" to "#E9EDE5","ink" to "#1A1F1B","ink-2" to "#4D564F","ink-3" to "#616A63","rule" to "#DCE1D8","rule-strong" to "#C3CABF","field" to "#7E877F","accent" to "#2D6A4F","accent-strong" to "#22553F","accent-soft" to "#DDEEE3","on-accent" to "#F6FBF7","highlight" to "#EFE3A8","danger" to "#B0282F","warn" to "#C08A2A","sh" to "26 31 27","chrome" to "#DEE3DA","chrome-pill" to "#F3F5F0"),
  "dark" to mapOf("paper" to "#121614","raised" to "#1B201D","sunken" to "#0C0F0D","ink" to "#E2E8E2","ink-2" to "#B2BCB4","ink-3" to "#919C94","rule" to "#262C28","rule-strong" to "#39413B","field" to "#6B756D","accent" to "#7FC4A0","accent-strong" to "#A6DABD","accent-soft" to "#1C3327","on-accent" to "#0D1F15","highlight" to "#4F4A1C","danger" to "#EE7C78","warn" to "#DDB05A","sh" to "0 0 0","chrome" to "#090B0A","chrome-pill" to "#191D1A"),
 ),
 "corallo" to mapOf(
  "light" to mapOf("paper" to "#FFF0EB","raised" to "#FFF8F4","sunken" to "#FADDD5","ink" to "#321018","ink-2" to "#6F3443","ink-3" to "#773545","rule" to "#EDC4C0","rule-strong" to "#C98F96","field" to "#A85063","accent" to "#BC2446","accent-strong" to "#9C1536","accent-soft" to "#FFD6DF","on-accent" to "#FFFFFF","highlight" to "#FFD58D","danger" to "#B21927","warn" to "#87510A","sh" to "50 16 24","chrome" to "#FFC0CC","chrome-pill" to "#FFF0EB"),
  "dark" to mapOf("paper" to "#290F1B","raised" to "#391725","sunken" to "#190811","ink" to "#FFF0EB","ink-2" to "#EEC0C4","ink-3" to "#D39FA9","rule" to "#653044","rule-strong" to "#8F4E63","field" to "#B77387","accent" to "#FF8C9E","accent-strong" to "#FFB0BB","accent-soft" to "#601E36","on-accent" to "#3A0C1C","highlight" to "#664022","danger" to "#FFA39C","warn" to "#FFC17A","sh" to "0 0 0","chrome" to "#1B0813","chrome-pill" to "#451A2D"),
 ),
 "elettrica" to mapOf(
  "light" to mapOf("paper" to "#EFEEFF","raised" to "#FAF8FF","sunken" to "#DDD7FF","ink" to "#16103A","ink-2" to "#423777","ink-3" to "#5B4C92","rule" to "#CFC5F5","rule-strong" to "#A798D0","field" to "#7661B0","accent" to "#5232DD","accent-strong" to "#3D1FBC","accent-soft" to "#DAD2FF","on-accent" to "#FFFFFF","highlight" to "#C6EEFF","danger" to "#AC1840","warn" to "#86520D","sh" to "22 16 58","chrome" to "#C7BDFF","chrome-pill" to "#F1EDFF"),
  "dark" to mapOf("paper" to "#13102C","raised" to "#211A44","sunken" to "#090719","ink" to "#F3EEFF","ink-2" to "#C7B8EF","ink-3" to "#AE9CD8","rule" to "#493966","rule-strong" to "#6B568D","field" to "#8C76C7","accent" to "#B6A1FF","accent-strong" to "#D0BFFF","accent-soft" to "#352366","on-accent" to "#180C3B","highlight" to "#143E64","danger" to "#FF92B2","warn" to "#F4C070","sh" to "0 0 0","chrome" to "#0B0820","chrome-pill" to "#2B2151"),
 ),
)
object SpuntoSpacing { val s2=2.dp; val s4=4.dp; val s8=8.dp; val s12=12.dp; val s16=16.dp; val s20=20.dp; val s24=24.dp; val s32=32.dp; val s48=48.dp; val s64=64.dp; val s96=96.dp }
object SpuntoRadius { val r4=4.dp; val r8=8.dp; val r12=12.dp; val r14=14.dp; val r20=20.dp; val r28=28.dp; val full=999.dp }
/** Durations in milliseconds; easing on Android comes from MaterialTheme.motionScheme. */
object SpuntoMotion { const val dur0=0; const val dur1=120; const val dur2=180; const val dur3=240; const val durFlash=600 }
