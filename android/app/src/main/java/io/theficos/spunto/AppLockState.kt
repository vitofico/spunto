package io.theficos.spunto

/** Process-local grants are never saved in an Activity Bundle or on disk. */
internal class AppLockState(initiallyEnabled: Boolean) {
 data class Snapshot(val enabled: Boolean, val unlocked: Boolean, val revision: Long)
 private var enabled = initiallyEnabled
 private var unlocked = !initiallyEnabled
 private var revision = 0L
 private var lifecycleRevision = 0L
 private var challenge: Long? = null
 private val started = mutableSetOf<Any>()
 val snapshot get() = Snapshot(enabled, !enabled || unlocked, revision)
 fun started(owner: Any) { started.add(owner); lifecycleRevision++ }
 fun stopped(owner: Any, changingConfiguration: Boolean, credentialChallenge: Long? = null): Long {
  started.remove(owner)
  lifecycleRevision++
  if(started.isEmpty() && !changingConfiguration) lock(preserveChallenge = credentialChallenge != null && challenge == credentialChallenge)
  return lifecycleRevision
 }
 fun settled(expectedLifecycle: Long, credentialChallenge: Long? = null) {
  // A configuration transaction may replace its host before this main-loop
  // check. If it never does, a later start must not inherit the old grant.
  if(lifecycleRevision == expectedLifecycle && started.isEmpty()) {
   lock(preserveChallenge = credentialChallenge != null && challenge == credentialChallenge)
  }
 }
 fun begin(): Long { revision++; challenge = revision; return revision }
 fun complete(expected: Long): Boolean {
  if(challenge != expected || started.isEmpty()) return false
  challenge = null; unlocked = true; revision++; return true
 }
 fun cancel(expected: Long) { if(challenge == expected) { challenge = null; revision++ } }
 fun configure(value: Boolean) { enabled = value; unlocked = true; challenge = null; revision++ }
 fun lock(preserveChallenge: Boolean = false) {
  unlocked = !enabled
  if(!preserveChallenge) { challenge = null; revision++ }
 }
}
