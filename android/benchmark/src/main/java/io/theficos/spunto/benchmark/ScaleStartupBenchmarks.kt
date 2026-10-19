package io.theficos.spunto.benchmark

import android.content.Intent
import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Proposal only. Root must release this benchmark-test APK source before compilation. */
@OptIn(ExperimentalMetricApi::class)
class ScaleStartupBenchmarks {
    @get:Rule val automation = BoundedUiAutomatorRule()
    @get:Rule val benchmark = MacrobenchmarkRule()
    @get:Rule val timeout = Timeout(30, TimeUnit.MINUTES)

    @Test fun coldTypingReady(): Unit = measure("cold", StartupMode.COLD)
    @Test fun warmTypingReady(): Unit = measure("warm", StartupMode.WARM)
    @Test fun warmDirectShareTypingReady(): Unit = measure("share", StartupMode.WARM)

    private fun measure(phase: String, mode: StartupMode) {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("spunto.scale.disposable") == "SPUNTO-SYNTHETIC-SCALE-ONLY")
        val nonce = args.getString("spunto.scale.nonce").orEmpty()
        check(nonce.matches(Regex("[a-f0-9]{32}")))
        val fixtureHash = args.getString("spunto.scale.fixture_report_sha256").orEmpty()
        val apkHash = args.getString("spunto.scale.apk_sha256").orEmpty()
        check(listOf(fixtureHash, apkHash).all { it.matches(Regex("[a-f0-9]{64}")) })
        // These declarations are NOT identity verification. Root binds the
        // actual installed APK, source, retained DB and fixture externally.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(requireNotNull(context.getExternalFilesDir(null)), "scale-startup")
        check(folder.isDirectory || folder.mkdirs())
        val destination = File(folder, "$phase-$nonce.json")
        val reservation = File(folder, "$phase-$nonce.reserved")
        check(!destination.exists() && reservation.createNewFile()) { "Workload nonce already used" }
        val observations = mutableListOf<ScaleStartupObservation>()
        val invocations = mutableListOf<JSONObject>()
        val benchmarkFailure = runCatching {
            prepareReturningUserGuide()
            benchmark.measureRepeated(
                packageName = PACKAGE,
                metrics = listOf(StartupTimingMetric()),
                compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
                startupMode = mode,
                iterations = 20,
                setupBlock = {
                    pressHome()
                    if (mode == StartupMode.WARM) {
                        startActivityAndWait()
                        awaitEmptyScaleComposer()
                        pressHome()
                    }
                },
            ) {
                val callbackOrdinal = invocations.size
                val benchmarkIteration = iteration // public nullable AndroidX measurement identity
                val invocation = JSONObject()
                    .put("callback_ordinal", callbackOrdinal)
                    .put("androidx_iteration", benchmarkIteration ?: JSONObject.NULL)
                    .put("classification", if (benchmarkIteration == null) "unmeasured" else "measured")
                    .put("completed", false)
                invocations += invocation
                val marker = "scaleready${nonce.take(8)}${phase}${callbackOrdinal}"
                val intent = if (phase == "share") Intent(Intent.ACTION_SEND)
                    .setClassName(PACKAGE, "$PACKAGE.CaptureActivity")
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Synthetic disposable scale share $nonce")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    else null
                observations += probeScaleStartup(callbackOrdinal, benchmarkIteration, marker, intent, phase == "share")
                invocation.put("completed", true)
            }
        }.exceptionOrNull()
        val measured = observations.filter { it.benchmarkIteration != null }
        val unmeasured = observations.filter { it.benchmarkIteration == null }
        // Retain every callback. Never trim, sort or renumber AndroidX iterations.
        val callbackIdentityValid = benchmarkFailure == null &&
            observations.map { it.callbackOrdinal } == invocations.indices.toList() &&
            invocations.all { it.getBoolean("completed") } &&
            measured.map { it.benchmarkIteration } == (0 until 20).toList()
        val report = JSONObject()
            .put("contract", "startup-scale-observer-v1")
            .put("status", if (callbackIdentityValid) "uncertified_observer_measurements" else "invalid_callback_diagnostics")
            .put("phase", phase)
            .put("nonce", nonce)
            .put("declared_fixture_report_sha256", fixtureHash)
            .put("declared_apk_sha256", apkHash)
            .put("startup_mode", mode.toString())
            .put("profile_mode", "Require")
            .put("performance_accepted", false)
            .put("root_evidence_gate_required", true)
            .put("clock", "SystemClock.elapsedRealtimeNanos/BOOTTIME")
            .put("callback_identity_source", "MacrobenchmarkScope.iteration")
            .put("callback_identity_valid", callbackIdentityValid)
            .put("benchmark_completed", benchmarkFailure == null)
            .put("callback_count", invocations.size)
            .put("successful_callback_count", observations.size)
            .put("callback_invocations", JSONArray(invocations))
            .put("unmeasured_callback_count", unmeasured.size)
            .put("unmeasured_callbacks", JSONArray(unmeasured.map { it.json() }))
            .put("samples", JSONArray(measured.map { it.json() }))
        val evidenceFailure = runCatching {
            val temporary = File(folder, "$phase-$nonce.json.partial")
            check(temporary.createNewFile())
            FileOutputStream(temporary).use {
                it.write(report.toString().toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.exceptionOrNull()
        if (benchmarkFailure != null) {
            evidenceFailure?.let { benchmarkFailure.addSuppressed(it) }
            throw benchmarkFailure
        }
        evidenceFailure?.let { throw it }
        check(callbackIdentityValid) { "Measured AndroidX iterations must be exactly zero through nineteen with every callback completed" }
    }
}
