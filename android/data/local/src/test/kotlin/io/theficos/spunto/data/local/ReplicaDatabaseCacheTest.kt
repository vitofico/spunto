package io.theficos.spunto.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class ReplicaDatabaseCacheTest {
 @Test fun repeatedAccountAndLogoutCyclesReuseHandlesAndPreserveLateDraftsAndOutboxes()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>()
  val cache=ReplicaDatabaseCache(context)
  val prefix="replica-lifecycle-${UUID.randomUUID()}"
  val names=listOf("$prefix-a.db","$prefix-b.db","$prefix-signed-out.db","$prefix-legacy.db")
  val databases=names.map(cache::get)
  var retiredEditor:Job?=null
  try {
   assertEquals(4,databases.toSet().size)
   val repositories=databases.mapIndexed {index,db ->NotesRepository(db,"replica-$index")}
   repositories.forEachIndexed {index,repo ->repo.create("Pending note $index");repo.saveDraft(body="Private draft $index")}
   val pending=repositories.map {it.dao.outbox().single()}
   val finish=CompletableDeferred<Unit>()
   retiredEditor=launch(Dispatchers.IO) {finish.await();repositories[0].saveDraft(body="Alice final unsent words")}
   repeat(100) {
    listOf(0,1,2,0,3,0).forEach {index ->
     val db=cache.get(names[index]);assertSame(databases[index],db)
     assertEquals("Pending note $index",db.dao().all().single().body)
     assertEquals(pending[index],db.dao().outbox().single())
    }
   }
   finish.complete(Unit);retiredEditor.join()
   assertEquals("Alice final unsent words",cache.get(names[0]).dao().draftSnapshot("capture")!!.body)
   (1..3).forEach {index ->assertEquals("Private draft $index",cache.get(names[index]).dao().draftSnapshot("capture")!!.body)}
  } finally {retiredEditor?.cancelAndJoin();databases.forEach {it.close()};names.forEach {context.deleteDatabase(it)}}
 }

 @Test fun concurrentReplicaSelectionCannotOpenDuplicateHandles()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>()
  val cache=ReplicaDatabaseCache(context);val name="replica-concurrent-${UUID.randomUUID()}.db"
  val selected=(1..32).map {async(Dispatchers.Default) {cache.get(name)}}.awaitAll()
  try {selected.forEach {assertSame(selected.first(),it)}}
  finally {selected.toSet().forEach {it.close()};context.deleteDatabase(name)}
 }
}
