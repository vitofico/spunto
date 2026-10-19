package io.theficos.spunto
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.theficos.spunto.core.model.uuidV7
import io.theficos.spunto.data.local.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class MultiInstanceDatabaseTest {
 @Test(timeout=600_000) fun workerDatabaseWriteInvalidatesForegroundNotes()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();val name="synthetic-multi-${uuidV7()}.db"
  fun open()=Room.databaseBuilder(context,SpuntoDatabase::class.java,name).enableMultiInstanceInvalidation().addMigrations(MIGRATION_1_2,MIGRATION_2_3).build()
  val foreground=open();val worker=open()
  try {
   val initial=CompletableDeferred<Unit>()
   val observed=async(Dispatchers.IO) { NotesRepository(foreground).notes.onEach { if(it.isEmpty()) initial.complete(Unit) }.first { rows -> rows.any { it.body=="Synthetic worker replica #shared" } } }
   withTimeout(10_000){initial.await()}
   withContext(Dispatchers.IO){worker.openHelper.writableDatabase}
   InstrumentationRegistry.getInstrumentation().waitForIdleSync()
   val id=withContext(Dispatchers.IO){NotesRepository(worker).create("Synthetic worker replica #shared")}
   val rows=withTimeout(10_000){observed.await()}
   assertEquals(id,rows.single().id)
  } finally {worker.close();foreground.close();context.deleteDatabase(name)}
 }
}
