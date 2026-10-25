package io.theficos.spunto

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.theficos.spunto.core.design.SpuntoWave
import io.theficos.spunto.core.design.SpuntoWordmark

/** The identity belongs to the background; only navigation and controls determine header height. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TimelineHeader(
 selector:@Composable (Modifier)->Unit,
 actions:@Composable RowScope.()->Unit,
 hasAdditionalActions:Boolean=false,
 windowInsets:WindowInsets=WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal),
) {
 val surface=MaterialTheme.colorScheme.background
 val accent=MaterialTheme.colorScheme.primary
 val waveAlpha=remember(surface,accent) {headerWaveOpacity(surface,accent)}
 val density=LocalDensity.current
 val direction=LocalLayoutDirection.current
 val top=windowInsets.getTop(density)
 val wordmark=MaterialTheme.typography.titleLarge.copy(fontSize=20.sp,lineHeight=30.sp,platformStyle=PlatformTextStyle(includeFontPadding=false),textDirection=TextDirection.Ltr)
 BoxWithConstraints(Modifier.fillMaxWidth().background(surface).testTag("timeline-header")) {
  // Text scaling and extra page actions need space, rather than smaller touch targets.
  val horizontalInsets=with(density) {(windowInsets.getLeft(this,direction)+windowInsets.getRight(this,direction)).toDp()}
  val twoRows=maxWidth-horizontalInsets<360.dp || density.fontScale>1f || hasAdditionalActions
  Box(Modifier.matchParentSize().clipToBounds()) {
   SpuntoWave(Modifier.align(Alignment.TopEnd).offset(x=72.dp,y=(-54).dp).wrapContentSize(Alignment.TopEnd,unbounded=true).requiredSize(width=360.dp,height=180.dp).graphicsLayer {alpha=waveAlpha;compositingStrategy=CompositingStrategy.Offscreen})
   // A custom accent may only just pass 4.5:1. Keep every glyph on its original plain surface.
   val fadeEnd=top+with(density) {4.dp.toPx()}
   Box(Modifier.matchParentSize().background(Brush.verticalGradient(
    0f to surface.copy(alpha=HEADER_SURFACE_FADE_START),.65f to surface.copy(alpha=.55f),1f to surface,endY=fadeEnd,
   )))
  }
  if(twoRows) {
   Column(Modifier.fillMaxWidth().windowInsetsPadding(windowInsets)) {
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
     SpuntoWordmark(style=wordmark)
     Spacer(Modifier.width(8.dp))
     selector(Modifier.weight(1f).heightIn(min=48.dp))
    }
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=4.dp),horizontalArrangement=Arrangement.spacedBy(4.dp,Alignment.End),verticalAlignment=Alignment.CenterVertically,content=actions)
   }
  } else {
   TopAppBar(
    title={Box(Modifier.fillMaxWidth().height(64.dp)) {
     SpuntoWordmark(Modifier.align(Alignment.TopStart).padding(start=12.dp),style=wordmark)
     selector(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end=8.dp).heightIn(min=48.dp))
    }},
    actions=actions,windowInsets=windowInsets,
    colors=TopAppBarDefaults.topAppBarColors(containerColor=androidx.compose.ui.graphics.Color.Transparent),
   )
  }
 }
}
