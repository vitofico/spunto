package io.theficos.spunto

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.theficos.spunto.data.local.NotesRepository
import io.theficos.spunto.data.local.SpuntoDatabase
import io.theficos.spunto.data.sync.AuthConfig
import io.theficos.spunto.data.sync.AuthStore
import io.theficos.spunto.data.sync.replicaDatabaseName
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Private auth file and unique replica names preserve retained device fixtures. */
@RunWith(AndroidJUnit4::class)
class AccountReplicaSwitchTest {
 @Test fun encryptedAccountSelectionAndLogoutKeepSeparatePendingReplicas()=runBlocking {
  val base=ApplicationProvider.getApplicationContext<Context>();val unique=UUID.randomUUID().toString()
  val directory=File(base.cacheDir,"account-selection-$unique").apply {mkdirs()}
  val context=object:ContextWrapper(base) {override fun getApplicationContext():Context=this;override fun getFilesDir():File=directory}
  val retained=AuthStore(base).config.first()
  val auth=AuthStore(context)
  assertNull(auth.config.first())
  val a=AuthConfig("https://synthetic.invalid","synthetic-a","device-a","Phone",userId="alice-$unique",workspaceId="personal",workspaceRole="owner")
  val b=a.copy(token="synthetic-b",userId="bob-$unique",workspaceId="shared")
  val names=listOf(replicaDatabaseName(a.replicaId),replicaDatabaseName(b.replicaId))
  val dbA=SpuntoDatabase.open(context,names[0]);val dbB=SpuntoDatabase.open(context,names[1])
  try {
   val repoA=NotesRepository(dbA,a.replicaId);val repoB=NotesRepository(dbB,b.replicaId)
   auth.save(a);assertEquals(retained,AuthStore(base).config.first());assertEquals(a,auth.config.first());repoA.create("Alice pending note");val pending=repoA.dao.outbox().single()
   auth.save(b);assertEquals(b.replicaId,auth.selection.first().replicaId);assertTrue(repoB.dao.all().isEmpty());repoB.create("Bob pending note")
   auth.clear();assertNull(auth.config.first());assertEquals("signed-out",auth.selection.first().replicaId);assertEquals(retained,AuthStore(base).config.first())
   assertEquals(pending,repoA.dao.outbox().single());assertEquals("Bob pending note",repoB.dao.all().single().body)
   auth.save(a.copy(token="synthetic-refreshed"));assertEquals(a.replicaId,auth.selection.first().replicaId);assertEquals(pending,repoA.dao.outbox().single())
  } finally {auth.clear();dbA.close();dbB.close();names.forEach {context.deleteDatabase(it)}}
 }
}
