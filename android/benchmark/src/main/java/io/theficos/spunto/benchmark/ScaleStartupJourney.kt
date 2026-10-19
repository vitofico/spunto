package io.theficos.spunto.benchmark

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import org.junit.Assert.assertTrue
import org.json.JSONObject

private const val EDITOR = "android.widget.EditText"
private fun MacrobenchmarkScope.scaleComposer(): UiObject2? =
    device.findObject(By.clazz(EDITOR).desc("Note text"))
        ?: device.findObject(By.clazz(EDITOR).hasDescendant(By.desc("Note text")))

private fun awaitScaleCondition(message: String, condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + 10_000
    while (SystemClock.elapsedRealtime() < deadline) {
        if (runCatching(condition).getOrDefault(false)) return
        SystemClock.sleep(25)
    }
    assertTrue(message, runCatching(condition).getOrDefault(false))
}

internal fun MacrobenchmarkScope.awaitEmptyScaleComposer() {
    awaitScaleCondition("Disposable fixture composer is not ready") {
        scaleComposer()?.isEnabled == true
    }
    check(requireNotNull(scaleComposer()).text.isNullOrEmpty()) {
        "Refusing to replace a retained draft; restore a pristine disposable fixture"
    }
}

/** BOOTTIME timestamps are observer measurements, not AndroidX startup metrics. */
internal data class ScaleStartupObservation(
    val callbackOrdinal: Int,
    val benchmarkIteration: Int?,
    val launchCallStartedNs: Long,
    val launchCallReturnedNs: Long,
    val emptyEditorObservedNs: Long,
    val inputStartedNs: Long,
    val inputVerifiedNs: Long,
    val keyCount: Int,
) {
    fun json() = JSONObject()
        .put("ordinal", benchmarkIteration ?: JSONObject.NULL)
        .put("callback_ordinal", callbackOrdinal)
        .put("androidx_iteration", benchmarkIteration ?: JSONObject.NULL)
        .put("callback_classification", if (benchmarkIteration == null) "unmeasured" else "measured")
        .put("launch_call_started_ns", launchCallStartedNs)
        .put("launch_call_returned_ns", launchCallReturnedNs)
        .put("empty_editor_observed_ns", emptyEditorObservedNs)
        .put("input_started_ns", inputStartedNs)
        .put("input_verified_ns", inputVerifiedNs)
        .put("key_count", keyCount)
        .put("observer_launch_to_verified_input_ms", (inputVerifiedNs - launchCallStartedNs) / 1_000_000.0)
        .put("observer_input_ms", (inputVerifiedNs - inputStartedNs) / 1_000_000.0)
}

/**
 * Require real injected key events and exact readback, never accessibility setText
 * as evidence of typing readiness. Only this explicit disposable fixture may run.
 */
internal fun MacrobenchmarkScope.probeScaleStartup(
    callbackOrdinal: Int,
    benchmarkIteration: Int?,
    marker: String,
    intent: Intent?,
    closeShare: Boolean,
): ScaleStartupObservation {
    check(marker.matches(Regex("[a-z0-9]+")))
    var ownsProbe = false
    try {
        val launchStarted = SystemClock.elapsedRealtimeNanos()
        if (intent == null) startActivityAndWait() else startActivityAndWait(intent)
        val launchReturned = SystemClock.elapsedRealtimeNanos()
        awaitEmptyScaleComposer()
        val editorObserved = SystemClock.elapsedRealtimeNanos()
        val inputStarted = SystemClock.elapsedRealtimeNanos()
        requireNotNull(scaleComposer()).click()
        ownsProbe = true
        marker.forEach { char ->
            val code = if (char in 'a'..'z') KeyEvent.KEYCODE_A + (char - 'a')
                else KeyEvent.KEYCODE_0 + (char - '0')
            check(device.pressKeyCode(code)) { "Keyboard event injection failed" }
        }
        awaitScaleCondition("Unique keyboard text was not accepted by the composer") {
            scaleComposer()?.text == marker
        }
        val verified = SystemClock.elapsedRealtimeNanos()
        return ScaleStartupObservation(
            callbackOrdinal, benchmarkIteration, launchStarted, launchReturned, editorObserved, inputStarted, verified, marker.length,
        )
    } finally {
        if (ownsProbe) {
            // This UI compare is NOT a database CAS. Parent must audit the
            // quiesced disposable draft row/body/timestamp after each workload.
            // Never overwrite a different/partial draft after failure.
            check(scaleComposer()?.text == marker) {
                "Draft changed during the probe; left untouched for diagnosis"
            }
            requireNotNull(scaleComposer()).text = ""
            awaitScaleCondition("Disposable probe text was not removed") {
                scaleComposer()?.let { it.isEnabled && it.text.isNullOrEmpty() } == true
            }
            device.waitForIdle(500)
            SystemClock.sleep(1_000) // outside startup/input endpoints; draft debounce/Room allowance
            if (closeShare) {
                val close = device.findObject(By.text("Close"))
                check(close != null) { "Share close control is absent" }
                close.click()
                device.waitForIdle(500)
            }
        }
    }
}
