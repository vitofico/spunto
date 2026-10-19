package io.theficos.spunto

import kotlinx.serialization.json.JsonElement

internal suspend fun completeAskRequest(request:suspend ()->JsonElement?,onComplete:()->Unit,onResult:(JsonElement)->Unit) {
 try {
  request()?.let(onResult)
 } finally {
  onComplete()
 }
}
