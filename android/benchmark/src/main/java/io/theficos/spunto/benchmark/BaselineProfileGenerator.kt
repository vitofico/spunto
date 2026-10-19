package io.theficos.spunto.benchmark
import android.os.SystemClock
import android.util.Log
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
class BaselineProfileGenerator {
 @get:Rule val automation=BoundedUiAutomatorRule()
 @get:Rule val profile=BaselineProfileRule()
 @get:Rule val perTestTimeout=Timeout(20,java.util.concurrent.TimeUnit.MINUTES)
 @Test fun startup() = collectJourney("startup",includeInStartupProfile=true) { pressHome();startActivityAndWait();awaitComposer() }
 @Test fun capture() = collectJourney("capture") { pressHome();startActivityAndWait();saveSyntheticNote() }
 private fun collectJourney(phase:String,includeInStartupProfile:Boolean=false,journey:MacrobenchmarkScope.()->Unit) {
  prepareReturningUserGuide()
  val started=SystemClock.elapsedRealtime()
  var iteration=0
  Log.i("SpuntoProfile","$phase collection begin")
  try {
   // Keep the library's default convergence limits; dependency profiles are merged by AGP.
   profile.collect(PACKAGE,includeInStartupProfile=includeInStartupProfile,filterPredicate={ it.contains("Lio/theficos/spunto/") }) {
    iteration++
    val iterationStarted=SystemClock.elapsedRealtime()
    Log.i("SpuntoProfile","$phase iteration=$iteration begin elapsedMs=${iterationStarted-started}")
    try { journey() } finally { Log.i("SpuntoProfile","$phase iteration=$iteration end elapsedMs=${SystemClock.elapsedRealtime()-iterationStarted}") }
   }
  } finally { Log.i("SpuntoProfile","$phase collection end iterations=$iteration elapsedMs=${SystemClock.elapsedRealtime()-started}") }
 }
}
