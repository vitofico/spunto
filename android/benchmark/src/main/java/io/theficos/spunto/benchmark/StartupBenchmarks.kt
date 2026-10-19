package io.theficos.spunto.benchmark
import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
@OptIn(ExperimentalMetricApi::class)
class StartupBenchmarks {
 @get:Rule val automation=BoundedUiAutomatorRule()
 @get:Rule val benchmark=MacrobenchmarkRule()
 @get:Rule val perTestTimeout=Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 @Test fun coldStartWithProfile() {
  prepareReturningUserGuide()
  benchmark.measureRepeated(packageName=PACKAGE,metrics=listOf(StartupTimingMetric()),compilationMode=CompilationMode.Partial(baselineProfileMode=BaselineProfileMode.Require),startupMode=StartupMode.COLD,iterations=5,setupBlock={pressHome()}) { startActivityAndWait();awaitComposer() }
 }
 @Test fun localSave() {
  prepareReturningUserGuide()
  benchmark.measureRepeated(packageName=PACKAGE,metrics=listOf(TraceSectionMetric("spunto.local-save",TraceSectionMetric.Mode.Sum)),compilationMode=CompilationMode.Partial(baselineProfileMode=BaselineProfileMode.Require),iterations=5,setupBlock={pressHome();startActivityAndWait();awaitComposer()}) { saveSyntheticNote() }
 }
}
