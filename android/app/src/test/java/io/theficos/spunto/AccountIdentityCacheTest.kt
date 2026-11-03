package io.theficos.spunto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.theficos.spunto.data.sync.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=android.app.Application::class,sdk=[28])
class AccountIdentityCacheTest {
 @Test fun offlineIdentityIsBoundToAccountReplicaAndExactCredentialThenClearedOnSignOut() {
  val context=ApplicationProvider.getApplicationContext<Context>()
  val preferences=context.getSharedPreferences("identity-${System.nanoTime()}",Context.MODE_PRIVATE)
  val cache=AccountIdentityCache(preferences)
  val alice=AuthConfig("https://synthetic.invalid","synthetic-a","device","Phone",userId="alice",workspaceId="personal",workspaceRole="owner")
  val identity=SignedInAccount("alice","Alice","alice-login")
  try {
   cache.save(alice,identity)
   assertEquals(identity,AccountIdentityCache(preferences).read(alice))
   assertNull(cache.read(alice.copy(userId="bob")))
   assertNull(cache.read(alice.copy(workspaceId="shared")))
   assertNull(cache.read(alice.copy(token="replacement")))
   assertNull(cache.read(alice.copy(serverUrl="https://other.invalid")))
   assertNull(cache.read(AuthConfig(alice.serverUrl,"legacy","device","Phone")))
   assertNull(cache.read(null))
   try {cache.save(alice,identity.copy(id="bob"));fail("Wrong account identity")} catch(_:IllegalArgumentException) {}
   assertEquals(identity,cache.read(alice))
   cache.clear();assertNull(cache.read(alice));assertTrue(preferences.all.isEmpty())
  } finally {preferences.edit().clear().commit()}
 }
 @Test fun signOutDuringSuspendedCredentialReadCannotRestoreTheClearedIdentity()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>()
  val preferences=context.getSharedPreferences("identity-signout-${System.nanoTime()}",Context.MODE_PRIVATE)
  val cache=AccountIdentityCache(preferences)
  val config=AuthConfig("https://synthetic.invalid","synthetic-a","device","Phone",userId="alice",workspaceId="personal",workspaceRole="owner")
  val identity=SignedInAccount("alice","Private retained name","alice-login")
  val readingAuthority=CompletableDeferred<Unit>();val releaseAuthority=CompletableDeferred<Unit>()
  try {
   cache.save(config,identity)
   val delayed=async {cache.refresh(config,{identity},{readingAuthority.complete(Unit);releaseAuthority.await();config})}
   readingAuthority.await()
   // Sign-out clears immediately, while the credential-store read still holds its old snapshot.
   cache.clear();assertNull(cache.read(config))
   releaseAuthority.complete(Unit)
   assertNull(delayed.await());assertNull(cache.read(config));assertTrue(preferences.all.isEmpty())
  } finally {releaseAuthority.complete(Unit);preferences.edit().clear().commit()}
 }

}
