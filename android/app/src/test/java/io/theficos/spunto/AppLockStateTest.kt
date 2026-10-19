package io.theficos.spunto

import org.junit.Assert.*
import org.junit.Test

class AppLockStateTest {
 @Test fun defaultOffAndProcessRestartKeepNoPersistedGrant() {
  assertTrue(AppLockState(false).snapshot.unlocked)
  val first=AppLockState(true);val owner=Any();first.started(owner)
  assertFalse(first.snapshot.unlocked);assertTrue(first.complete(first.begin()))
  assertTrue(first.snapshot.unlocked);assertFalse(AppLockState(true).snapshot.unlocked)
 }
 @Test fun backgroundRelocksAndExplicitScreenLockInvalidatesLateSuccess() {
  val state=AppLockState(true);val owner=Any();state.started(owner);assertTrue(state.complete(state.begin()))
  state.stopped(owner,false);assertFalse(state.snapshot.unlocked)
  state.started(owner);val pending=state.begin();state.lock()
  assertFalse(state.complete(pending));assertFalse(state.snapshot.unlocked)
 }
 @Test fun systemCredentialStopCanCompleteOnlyAfterTheHostReturns() {
  val state=AppLockState(true);val owner=Any();state.started(owner);val pending=state.begin()
  state.stopped(owner,false,pending);assertFalse(state.snapshot.unlocked)
  assertFalse(state.complete(pending));state.started(owner);assertTrue(state.complete(pending))
 }
 @Test fun cancelledOrSupersededChallengesNeverUnlock() {
  val state=AppLockState(true);state.started(Any())
  val cancelled=state.begin();state.cancel(cancelled);assertFalse(state.complete(cancelled))
  val old=state.begin();val current=state.begin();state.cancel(old);assertFalse(state.complete(old));assertTrue(state.complete(current))
 }
 @Test fun normalBiometricBackgroundInvalidatesRatherThanPreservingChallenge() {
  val state=AppLockState(true);val owner=Any();state.started(owner);val token=state.begin()
  state.stopped(owner,false);state.started(owner);assertFalse(state.complete(token));assertFalse(state.snapshot.unlocked)
 }
 @Test fun configurationAndInternalActivityTransfersKeepTheCurrentGrant() {
  val state=AppLockState(true);val main=Any();val capture=Any();state.started(main);state.complete(state.begin())
  state.started(capture);state.stopped(main,false);assertTrue(state.snapshot.unlocked)
  state.stopped(capture,true);assertTrue(state.snapshot.unlocked)
  state.started(capture);state.stopped(capture,false);assertFalse(state.snapshot.unlocked)
 }
 @Test fun overlappingConfigurationInstancesAreCountedSeparately() {
  val state=AppLockState(true);val old=Any();val replacement=Any();state.started(old);state.complete(state.begin())
  state.started(replacement);state.stopped(old,true);assertTrue(state.complete(state.begin()))
  state.stopped(replacement,false);assertFalse(state.snapshot.unlocked)
 }
 @Test fun interruptedConfigurationRelocksBeforeALaterStart() {
  val state=AppLockState(true);val old=Any();state.started(old);state.complete(state.begin())
  val stopped=state.stopped(old,true);assertTrue(state.snapshot.unlocked)
  state.settled(stopped);assertFalse(state.snapshot.unlocked)
  state.started(Any());assertFalse(state.snapshot.unlocked)
 }
 @Test fun immediateConfigurationReplacementKeepsTheGrant() {
  val state=AppLockState(true);val old=Any();state.started(old);state.complete(state.begin())
  val stopped=state.stopped(old,true);state.started(Any());state.settled(stopped)
  assertTrue(state.snapshot.unlocked)
 }
 @Test fun settledCredentialTransitionKeepsOnlyItsCurrentChallenge() {
  val state=AppLockState(true);val owner=Any();state.started(owner);val token=state.begin()
  val stopped=state.stopped(owner,true,token);state.settled(stopped,token)
  assertFalse(state.complete(token));state.started(owner);assertTrue(state.complete(token))
  val old=state.stopped(owner,true);state.started(owner);val current=state.begin()
  val credentialStop=state.stopped(owner,false,current)
  state.settled(old);state.settled(credentialStop,current);state.started(owner)
  assertTrue(state.complete(current))
 }
 @Test fun onlyCurrentFrontHostCanCompleteAndScreenOffCancelsCredentialReturn() {
  val state=AppLockState(true);val owner=Any();state.started(owner);val token=state.begin();state.stopped(owner,false)
  state.lock();state.started(owner);assertFalse(state.complete(token));assertFalse(state.snapshot.unlocked)
 }
 @Test fun turningLockOffClearsPendingChallengesAndFutureOnDeviceRestartsStayOff() {
  val state=AppLockState(true);state.started(Any());val old=state.begin();state.configure(false)
  assertFalse(state.complete(old));assertFalse(state.snapshot.enabled);assertTrue(state.snapshot.unlocked)
 }
}
