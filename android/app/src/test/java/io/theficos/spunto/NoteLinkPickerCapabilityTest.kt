package io.theficos.spunto

import kotlinx.serialization.json.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteLinkPickerCapabilityTest {
 @Test fun onlySupportedLinksManifestVersionsUseThePicker() {
  assertFalse(supportsNotePicker(buildJsonObject {},"links"))
  assertFalse(supportsNotePicker(manifest("1.0.9"),"links"))
  assertFalse(supportsNotePicker(manifest("unknown"),"links"))
  assertTrue(supportsNotePicker(manifest("1.1.0"),"links"))
  assertTrue(supportsNotePicker(manifest("2.0.0"),"links"))
 }

 private fun manifest(version:String)=buildJsonObject {
  put("plugins",JsonArray(listOf(buildJsonObject {put("id","links");put("version",version)})))
 }
}
