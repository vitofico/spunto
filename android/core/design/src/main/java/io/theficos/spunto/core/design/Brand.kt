package io.theficos.spunto.core.design

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The period belongs to the wordmark; its colour follows the resolved theme, including wallpaper colours. */
@Composable fun SpuntoWordmark(modifier:Modifier=Modifier,style:TextStyle=MaterialTheme.typography.displayLarge) {
 Text(buildAnnotatedString {
  append("spunto")
  withStyle(SpanStyle(color=MaterialTheme.colorScheme.primary)) {append(".")}
 },modifier=modifier,style=style,maxLines=1)
}

/** Static, decorative open strands. Geometry is cached at the rendered size; no bitmap or animation is needed. */
@Composable fun SpuntoWave(modifier:Modifier=Modifier,color:Color=MaterialTheme.colorScheme.primary,alpha:Float=1f) {
 Box(modifier.clearAndSetSemantics {}.clipToBounds().drawWithCache {
  val paths=(0..35).map { strand ->
   val v=strand/35f*2-1
   Path().apply {
    for(step in 0..96) {
     val u=step/96f
     val x=(u*1.2f-.1f)*size.width
     val y=(.17f+.66f*u+.10f*sin(2*PI*u).toFloat()+v*.22f*cos(PI*u).toFloat()+.045f*sin(2*PI*u+v*1.8f).toFloat())*size.height
     if(step==0) moveTo(x,y) else lineTo(x,y)
    }
   }
  }
  val opacity=alpha.coerceIn(0f,1f)
  val brush=Brush.horizontalGradient(0f to Color.Transparent,.18f to color.copy(alpha=.55f*opacity),.65f to color.copy(alpha=.8f*opacity),1f to Color.Transparent)
  val stroke=Stroke(width=.7.dp.toPx())
  onDrawBehind {paths.forEach {drawPath(it,brush,style=stroke)}}
 })
}
