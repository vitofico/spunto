package io.theficos.spunto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Run alone before tests with an Activity rule; emits no URL or credential. */
@RunWith(AndroidJUnit4::class)
class LocalFixturePreflightTest {
 @Test fun currentReplicaIsLocalBeforeLaunchingAnActivity():Unit=runBlocking {
  val application=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpuntoApplication
  val authority=LocalFixtureAuthority.require(application.container)
  authority.requireCurrent()
 }
}
