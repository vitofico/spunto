package io.theficos.spunto

import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/** Explicit first/returning-user setup before an activity starts; restore only this marker. */
internal class GuidePreferenceFixture(private val completed:Boolean?):ExternalResource() {
 private lateinit var preferences:SharedPreferences
 private var present=false
 private var original=false
 override fun before() {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpuntoApplication
  runBlocking {LocalFixtureAuthority.require(app.container).requireCurrent()}
  preferences=firstRunGuidePreferences(app)
  present=preferences.contains(firstRunGuideCompletedKey)
  original=preferences.getBoolean(firstRunGuideCompletedKey,false)
  val editor=preferences.edit()
  if(completed==null) editor.remove(firstRunGuideCompletedKey) else editor.putBoolean(firstRunGuideCompletedKey,completed)
  check(editor.commit())
 }
 override fun after() {
  if(!::preferences.isInitialized) return
  val editor=preferences.edit()
  if(present) editor.putBoolean(firstRunGuideCompletedKey,original) else editor.remove(firstRunGuideCompletedKey)
  check(editor.commit())
 }
}
