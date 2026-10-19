package io.theficos.spunto

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the installed variant, including the shortcut parsed by Android itself. */
@RunWith(AndroidJUnit4::class)
class DistributionIdentityTest {
 @Test fun debugIdentityAndCaptureShortcutStayInTheIsolatedPackage() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  assertEquals("io.theficos.spunto.debug",context.packageName)
  val info=context.packageManager.getPackageInfo(context.packageName,0)
  assertTrue(info.versionName!!.endsWith("-debug"))
  assertEquals("Spunto Debug",context.applicationInfo.loadLabel(context.packageManager).toString())
  assertEquals("io.theficos.spunto.SpuntoApplication",context.applicationInfo.className)
  val activity=ComponentName(context.packageName,"io.theficos.spunto.CaptureActivity")
  assertEquals(context.packageName,context.packageManager.getActivityInfo(activity,0).packageName)
  val shortcut=context.getSystemService(ShortcutManager::class.java).manifestShortcuts.single {it.id=="new-note"}
  assertEquals(activity,shortcut.intent!!.component)
  assertEquals(Intent.ACTION_VIEW,shortcut.intent!!.action)
 }
}
