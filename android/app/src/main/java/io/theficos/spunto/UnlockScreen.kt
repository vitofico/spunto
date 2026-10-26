package io.theficos.spunto

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.theficos.spunto.core.design.*

/** Presentation only: Android owns fingerprint and phone-credential authentication. */
@Composable internal fun UnlockScreen(error:String,onUnlock:()->Unit,onScreenLock:()->Unit) {
 val context=LocalContext.current
 Surface(Modifier.fillMaxSize()) {
  BoxWithConstraints(Modifier.fillMaxSize().spuntoBackground().safeDrawingPadding(),contentAlignment=Alignment.TopCenter) {
   val viewport=maxHeight
   Column(
    Modifier.widthIn(max=560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
     .heightIn(min=viewport).padding(horizontal=SpuntoSpacing.s24,vertical=SpuntoSpacing.s24),
   ) {
    SpuntoWordmark(style=MaterialTheme.typography.displayLarge.copy(fontSize=44.sp,lineHeight=56.sp,letterSpacing=(-1).sp),
     modifier=Modifier.clearAndSetSemantics {heading();contentDescription=context.getString(R.string.sys_lock_title)})
    SpuntoWave(Modifier.fillMaxWidth().height((viewport*.5f).coerceIn(120.dp,420.dp)).padding(vertical=SpuntoSpacing.s16))
    Spacer(Modifier.weight(1f))
    Text(context.getString(R.string.sys_welcome_back),style=MaterialTheme.typography.headlineLarge.copy(fontFamily=uiFont()),
     modifier=Modifier.padding(top=SpuntoSpacing.s16,bottom=SpuntoSpacing.s24))
    if(error.isNotEmpty()) Text(error,Modifier.fillMaxWidth().padding(bottom=SpuntoSpacing.s16).semantics {liveRegion=LiveRegionMode.Polite},
     style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.error)
    Button(onClick=onUnlock,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),contentPadding=PaddingValues(horizontal=SpuntoSpacing.s24,vertical=SpuntoSpacing.s16)) {
     Text(context.getString(R.string.sys_unlock),textAlign=TextAlign.Center)
    }
    TextButton(onClick=onScreenLock,modifier=Modifier.align(Alignment.CenterHorizontally).padding(top=SpuntoSpacing.s8).heightIn(min=48.dp)) {
     Text(context.getString(R.string.sys_screen_lock),textAlign=TextAlign.Center)
    }
   }
  }
 }
}
