package io.theficos.spunto.data.local

import org.junit.Test
import org.junit.Assert.*

class LegacyAttachmentsTest {
 private val id="00000000-0000-4000-8000-000000000042"
 @Test fun onlyExplicitParsedCanonicalImageLinksHydrateLegacyMarkers() {
  val marker="![Synthetic caption](/a/$id)"
  assertEquals(marker,canonicalLegacyImageMarker("Owner words\n\n$marker",id))
  assertEquals(marker,canonicalLegacyImageMarker("$marker\n$marker",id))
  for(body in listOf("No link", "`$marker`", "```\n$marker\n```", "[plain](/a/$id)", "![wrong](/a/$id/other)", "![absolute](https://other.example/a/$id)","$marker\n![Different caption](/a/$id)")) assertNull(body,canonicalLegacyImageMarker(body,id))
  assertNull(canonicalLegacyImageMarker(marker,"invalid"))
 }
}
