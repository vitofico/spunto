package io.theficos.spunto.core.design

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Inter and Literata are variable fonts, so each weight (and Literata's optical size) is a variation of one file.
@OptIn(ExperimentalTextApi::class) private fun font(res:Int,weight:Int,vararg axes:FontVariation.Setting)=Font(res,FontWeight(weight),variationSettings=FontVariation.Settings(FontVariation.weight(weight),*axes))
private val interFamily=FontFamily(font(R.font.inter,400),font(R.font.inter,500),font(R.font.inter,600))
private val literataFamily=FontFamily(font(R.font.literata,400),font(R.font.literata,600))
private val displayFamily=FontFamily(font(R.font.literata,400,FontVariation.Setting("opsz",48f)))
private val titleFamily=FontFamily(font(R.font.literata,600,FontVariation.Setting("opsz",30f)))
private val monoFamily=FontFamily(Font(R.font.jetbrains_mono))
fun uiFont():FontFamily=interFamily
fun readingFont():FontFamily=literataFamily
fun monoFont():FontFamily=monoFamily

private val material=Typography()
private fun TextStyle.spec(family:FontFamily,size:Float,line:Float,weight:Int=400,tracking:TextUnit=0.sp,features:String?=null)=copy(fontFamily=family,fontSize=size.sp,lineHeight=line.sp,fontWeight=FontWeight(weight),letterSpacing=tracking,fontFeatureSettings=features)
private val display=material.displaySmall.spec(displayFamily,30f,36f,tracking=(-0.01).em)
private val title=material.titleLarge.spec(titleFamily,22f,28f,600)
/** 03 §11.2, Android column: interface roles in Inter, display and title in Literata. Note text is not here, see [SpuntoType]. */
val spuntoMaterialTypography=Typography(
 displayLarge=display,displayMedium=display,displaySmall=display,headlineLarge=display,
 headlineMedium=title,headlineSmall=title,titleLarge=title,
 titleMedium=material.titleMedium.spec(interFamily,16f,24f,500),titleSmall=material.titleSmall.spec(interFamily,14f,20f,500),
 bodyLarge=material.bodyLarge.spec(interFamily,16f,24f),bodyMedium=material.bodyMedium.spec(interFamily,14f,20f),bodySmall=material.bodySmall.spec(interFamily,12f,16f,features="tnum"),
 labelLarge=material.labelLarge.spec(interFamily,14f,20f,500),labelMedium=material.labelMedium.spec(interFamily,12f,16f,500,features="tnum"),labelSmall=material.labelSmall.spec(interFamily,11f,16f,500,features="tnum"),
)

/** The faces Material roles cannot carry. Read them with `SpuntoType.reading` and so on. */
@Immutable data class SpuntoTypography(
 /** The owner's note text (`body`), in the reading face and size chosen in Appearance. */
 val reading:TextStyle,
 /** Related notes, search snippets, share preview, the Ask question (`body-sm`). */
 val readingSmall:TextStyle,
 /** Headings inside notes and entry titles (`heading`). */
 val heading:TextStyle,
 /** Day headers and section labels (`overline`); uppercase the text where it is used. */
 val overline:TextStyle,
 /** Code, tokens and snippets (`mono`). */
 val mono:TextStyle,
)
/** Reading size S and L step the body to 15 and 18 sp (the web scales by 16/17 and 19/17); the sans face runs at 16.5/17 of the size because Inter is larger. */
fun spuntoType(face:String,size:String):SpuntoTypography {
 val scale=when(normalizedReadingSize(size)) { "S" -> 15f/16; "L" -> 18f/16; else -> 1f }
 val sans=normalizedReadingFace(face)=="sans"
 fun reading(size:Float,line:Float,weight:Int=400)=material.bodyLarge.spec(if(sans) interFamily else literataFamily,size*scale*if(sans) 16.5f/17 else 1f,line*scale,weight)
 return SpuntoTypography(
  reading=reading(16f,25f),readingSmall=reading(15f,22f),heading=reading(18f,25f,600),
  overline=material.labelSmall.spec(interFamily,11f,16f,500,tracking=0.08.em),
  mono=material.bodyMedium.spec(monoFamily,13f,20f),
 )
}
val LocalSpuntoType=staticCompositionLocalOf { spuntoType("serif","M") }
val SpuntoType:SpuntoTypography @Composable @ReadOnlyComposable get()=LocalSpuntoType.current
