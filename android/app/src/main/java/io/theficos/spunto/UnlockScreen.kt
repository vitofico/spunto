package io.theficos.spunto

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.theficos.spunto.core.design.SpuntoSpacing

/** Presentation only: Android owns fingerprint and phone-credential authentication. */
@Composable internal fun UnlockScreen(error:String,onUnlock:()->Unit,onScreenLock:()->Unit) {
 val context=LocalContext.current
 val unlock=context.getString(R.string.sys_unlock)
 Surface(Modifier.fillMaxSize()) {
  BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(),contentAlignment=Alignment.TopCenter) {
   val viewport=maxHeight
   Column(
    Modifier.widthIn(max=440.dp).fillMaxWidth().verticalScroll(rememberScrollState())
     .heightIn(min=viewport).padding(horizontal=SpuntoSpacing.s24,vertical=SpuntoSpacing.s32),
    horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.SpaceBetween,
   ) {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
     Text(context.getString(R.string.spunto_wordmark),style=MaterialTheme.typography.displayLarge.copy(fontSize=44.sp,lineHeight=52.sp,letterSpacing=(-1).sp),
      modifier=Modifier.clearAndSetSemantics {heading();contentDescription=context.getString(R.string.sys_lock_title)})
     Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary,CircleShape))
    }
    Column(Modifier.fillMaxWidth().padding(vertical=SpuntoSpacing.s24),horizontalAlignment=Alignment.CenterHorizontally) {
     Column(
      Modifier.widthIn(max=320.dp).clip(RoundedCornerShape(28.dp))
       .clickable(role=Role.Button,onClick=onUnlock).padding(SpuntoSpacing.s16),
      horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s16),
     ) {
      Box(Modifier.size(88.dp).background(MaterialTheme.colorScheme.primaryContainer,CircleShape),contentAlignment=Alignment.Center) {
       Icon(painterResource(R.drawable.ic_fingerprint),contentDescription=null,modifier=Modifier.size(40.dp),tint=MaterialTheme.colorScheme.primary)
      }
      Text(unlock,style=MaterialTheme.typography.titleMedium,textAlign=TextAlign.Center)
     }
     if(error.isNotEmpty()) Text(error,Modifier.widthIn(max=320.dp).padding(top=SpuntoSpacing.s12).semantics {liveRegion=LiveRegionMode.Polite},
      style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.error,textAlign=TextAlign.Center)
    }
    TextButton(onClick=onScreenLock,modifier=Modifier.heightIn(min=48.dp),colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.onSurfaceVariant)) {
     Text(context.getString(R.string.sys_screen_lock),textAlign=TextAlign.Center)
    }
   }
  }
 }
}
