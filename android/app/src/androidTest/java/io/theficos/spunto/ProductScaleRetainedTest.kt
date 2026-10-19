package io.theficos.spunto

import android.database.Cursor
import android.os.SystemClock
import androidx.paging.PagingSource
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.model.labels
import io.theficos.spunto.data.local.*
import io.theficos.spunto.scale.fixture.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Verifies the retained disposable A replica without invoking a database writer. */
@RunWith(AndroidJUnit4::class)
class ProductScaleRetainedTest {
 @get:Rule val timeout=Timeout(20,TimeUnit.MINUTES)

 @Test fun verifyExistingProductFixtureAWithoutReseed():Unit=runBlocking {
  val started=SystemClock.elapsedRealtimeNanos()
  val arguments=InstrumentationRegistry.getArguments()
  org.junit.Assume.assumeTrue("Scale diagnostics require explicit fixture arguments.",arguments.keySet().any {it.startsWith("spunto.scale.")})
  require(arguments.getString("spunto.scale.disposable")=="SPUNTO-SYNTHETIC-SCALE-ONLY") {"Disposable acknowledgement required."}
  val seed=arguments.getString("spunto.scale.nonce").orEmpty()
  require(seed==KNOWN_SEED_NONCE) {"Known retained seed nonce required."}
  require(arguments.getString("spunto.scale.fixture")=="A" && arguments.getString("spunto.scale.target")=="product") {"Only retained product A is supported."}
  val verification=arguments.getString("spunto.scale.verification_nonce").orEmpty()
  require(Regex("[a-f0-9]{32}").matches(verification) && verification!=seed) {"Fresh verification nonce required."}
  val expectedApk=arguments.getString("spunto.scale.expected_apk_sha256").orEmpty()
  require(Regex("[a-f0-9]{64}").matches(expectedApk)) {"Expected installed APK SHA256 required."}
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  require(context.packageName=="io.theficos.spunto")
  val apk=File(context.applicationInfo.sourceDir).canonicalFile
  require(apk.isFile) {"Installed target APK file required."}
  val actualApk=fileHash(apk)
  require(actualApk==expectedApk) {"Installed target APK differs from root evidence."}
  val marker=File(context.filesDir,"scale-$seed.seed")
  fun requireMarker() {require(Files.isRegularFile(marker.toPath(),LinkOption.NOFOLLOW_LINKS) && marker.length()==0L) {"Existing retained seed marker required."}}
  requireMarker()
  val container=(context.applicationContext as SpuntoApplication).container
  val db=container.database
  val repository=container.repository
  val sql=db.openHelper.readableDatabase
  val databasePath=context.getDatabasePath("spunto.db").canonicalFile
  require(File(requireNotNull(sql.path) {"Expected product database path required."}).canonicalFile==databasePath && sql.version==6) {"Expected shared product schema required."}
  suspend fun requireAuthority() {
   requireMarker()
   require(container.auth.config.first()==null) {"Retained fixture must remain signed out."}
   val state=repository.dao.state()
   require(state!=null && state.epoch=="scale-a" && state.serverUrl.isEmpty()) {"Retained fixture authority differs."}
   require(container.auth.config.first()==null && sql.version==6 && File(requireNotNull(sql.path) {"Expected product database path required."}).canonicalFile==databasePath) {"Retained fixture authority changed."}
  }
  requireAuthority()
  val parent=File(context.cacheDir,"scale-evidence")
  require(parent.isDirectory || parent.mkdirs())
  require(parent.canonicalFile==File(context.cacheDir.canonicalFile,"scale-evidence")) {"Private evidence directory required."}
  val output=File(parent,"A-$seed-retained-$verification")
  Files.createDirectory(output.toPath()) // Atomic reservation; a reused nonce never overwrites evidence.
  val progress=Progress(output,seed,verification,started)
  try {
   progress.write(Stage.FIXTURE,0,ProductScaleFixtures.SIZE)
   val fixture=ProductScaleFixtures.generate("A")
   val expectedPayload=fixture.payloadHash()
   val expectedState=SyncStateEntity(epoch="scale-a",manifest=fixture.manifest.toString(),catalog=fixture.catalog.toString(),serverUrl="")
   val expectedLabels=fixture.notes.sumOf {labels(it.body).map {label ->label.key}.distinct().size}.toLong()
   progress.write(Stage.FIXTURE,fixture.notes.size,fixture.notes.size)
   suspend fun requireStateAndCounts() {
    requireAuthority()
    require(repository.dao.state()==expectedState) {"Retained fixture state differs from frozen A."}
    val counts=TABLES.associateWith {table ->count(sql,table)}
    require(counts.getValue("notes")==10_000L && counts.getValue("notes_fts")==10_000L && counts.getValue("notes_fts_docsize")==10_000L) {"Complete retained 10k indexes required."}
    require(counts.getValue("labels")==expectedLabels && counts.getValue("sync_state")==1L) {"Retained fixture label/state counts differ."}
    require(EMPTY_TABLES.all {counts.getValue(it)==0L}) {"Retained fixture auxiliary tables must be empty."}
   }
   suspend fun actualPayload():String {
    val rows=repository.dao.all()
    val byId=rows.associateBy {it.id}
    require(rows.size==fixture.notes.size && byId.size==fixture.notes.size && byId.keys==fixture.notes.map {it.id}.toSet()) {"Retained fixture IDs differ."}
    val actual=fixture.notes.map {byId.getValue(it.id).note()}
    require(actual==fixture.notes) {"Retained Note payload differs from frozen generator."}
    return fixture.copy(notes=actual).payloadHash().also {require(it==expectedPayload) {"Retained payload digest differs."}}
   }
   requireStateAndCounts()
   progress.write(Stage.PAYLOAD_BEFORE,0,1)
   val beforePayload=actualPayload()
   progress.write(Stage.PAYLOAD_BEFORE,1,1)
   val originalReport=File(parent,"A-$seed.json")
   val originalHash=if(Files.exists(originalReport.toPath(),LinkOption.NOFOLLOW_LINKS)) {
    require(Files.isRegularFile(originalReport.toPath(),LinkOption.NOFOLLOW_LINKS) && originalReport.length() in 1..2_000_000L) {"Original receipt must be a bounded regular file."}
    val receipt=Json.parseToJsonElement(originalReport.readText()).jsonObject
    require(receipt["contract"]?.jsonPrimitive?.intOrNull==1 && receipt["status"]?.jsonPrimitive?.content=="correctness_only") {"Original receipt is not completed correctness evidence."}
    require(receipt["nonce"]?.jsonPrimitive?.content==seed && receipt["fixture"]?.jsonPrimitive?.content=="A" && receipt["target"]?.jsonPrimitive?.content=="product") {"Original receipt identity differs."}
    require(receipt["payload_sha256"]?.jsonPrimitive?.content==expectedPayload && receipt["corpus_source_sha256"]?.jsonPrimitive?.content==ProductScaleFixtures.SOURCE_SHA && receipt["kotlin_runtime"]?.jsonPrimitive?.content==KotlinVersion.CURRENT.toString() && receipt["schema_version"]?.jsonPrimitive?.intOrNull==5) {"Original receipt payload/source differs."}
    require(receipt["checks"]?.jsonObject?.get("pass")?.jsonPrimitive?.booleanOrNull==true && receipt["paging_pass"]?.jsonPrimitive?.booleanOrNull==true) {"Original receipt checks are incomplete."}
    fileHash(originalReport)
   } else null
   progress.write(Stage.SNAPSHOT_BEFORE,0,TABLES.size)
   val before=fingerprints(sql) {done ->progress.write(Stage.SNAPSHOT_BEFORE,done,TABLES.size)}
   requireStateAndCounts()
   progress.write(Stage.ORACLE,0,ProductScaleFixtures.queries.size)
   val checks=ProductScaleCorrectness.verify(repository,fixture) {phase,done,total ->progress.write(Stage.ORACLE,done,total,phase)}
   progress.write(Stage.PAGING,0,fixture.notes.size)
   val pageSource=repository.dao.filteredPaging(timelineQuery(TimelineSpec()))
   val pagedIds=ArrayList<String>(fixture.notes.size)
   val seen=HashSet<String>(fixture.notes.size)
   var next:Int?=null
   var first=true
   var pages=0
   while(first || next!=null) {
    currentCoroutineContext().ensureActive()
    require(pages<250) {"Retained paging did not terminate within 250 pages."}
    val offset=next?:0
    val result=pageSource.load(if(first) PagingSource.LoadParams.Refresh<Int>(null,40,false) else PagingSource.LoadParams.Append(offset,40,false))
    require(result is PagingSource.LoadResult.Page) {"Retained product page failed."}
    require(result.data.size in 1..40) {"Retained product page must make progress."}
    require(result.nextKey==null || result.nextKey!!>offset) {"Retained paging offset failed to advance."}
    for(row in result.data) {require(seen.add(row.id)) {"Retained paging duplicated a note."};pagedIds.add(row.id)}
    require(pagedIds.size<=fixture.notes.size) {"Retained paging exceeded fixture size."}
    next=result.nextKey;first=false;pages++
    if(pages%25==0 || next==null) progress.write(Stage.PAGING,pagedIds.size,fixture.notes.size)
   }
   val allHome=repository.dao.filteredNotes(timelineQuery(TimelineSpec())).map {it.id}
   require(pages==250 && pagedIds.size==10_000 && pagedIds==allHome && seen.size==pagedIds.size) {"Retained full paging differs from product home."}
   progress.write(Stage.PAYLOAD_AFTER,0,1)
   requireStateAndCounts()
   val afterPayload=actualPayload()
   require(beforePayload==afterPayload)
   progress.write(Stage.PAYLOAD_AFTER,1,1)
   val after=fingerprints(sql) {done ->progress.write(Stage.SNAPSHOT_AFTER,done,TABLES.size)}
   require(before==after) {"Logical product tables changed during retained verification."}
   requireStateAndCounts()
   require(fileHash(apk)==expectedApk && File(context.applicationInfo.sourceDir).canonicalFile==apk) {"Installed target APK changed during verification."}
   require(if(originalHash==null) !Files.exists(originalReport.toPath(),LinkOption.NOFOLLOW_LINKS) else Files.isRegularFile(originalReport.toPath(),LinkOption.NOFOLLOW_LINKS) && fileHash(originalReport)==originalHash) {"Original receipt changed during verification."}
   progress.write(Stage.REPORT,0,1)
   val report=buildJsonObject {
    put("contract",1);put("status","current_correctness_only");put("fixture","A");put("target","product");put("nonce",seed);put("verification_nonce",verification)
    put("expected_apk_sha256",expectedApk);put("actual_apk_sha256",actualApk)
    put("corpus_source_sha256",ProductScaleFixtures.SOURCE_SHA);put("kotlin_runtime",KotlinVersion.CURRENT.toString());put("schema_version",sql.version)
    put("expected_payload_sha256",expectedPayload);put("actual_payload_sha256",afterPayload)
    put("prior_setup_receipt_present",originalHash!=null);originalHash?.let {put("prior_setup_receipt_sha256",it)}
    put("prior_setup_correctness",if(originalHash==null) "not_established" else "completed_receipt_present")
    put("before",snapshotJson(before));put("after",snapshotJson(after));put("logical_preservation_pass",true)
    put("checks",checks);put("paging_pass",true);put("paged_home_count",pagedIds.size);put("page_count",pages);put("paged_home_ids_sha256",ProductScaleFixtures.digest(pagedIds))
    put("auth_present",false);put("timings_certified",false);put("performance_accepted",false)
   }
   atomicJson(File(output,"result.json"),report,replace=false)
   progress.write(Stage.COMPLETE,1,1)
  } catch(failure:Throwable) {
   runCatching {progress.write(Stage.FAILED,0,1)}
   throw failure
  }
 }

 private data class TableFingerprint(val rows:Long,val sha256:String)
 private suspend fun fingerprints(sql:SupportSQLiteDatabase,onTable:(Int)->Unit):Map<String,TableFingerprint> {
  val context=currentCoroutineContext()
  return TABLES.mapIndexed {index,table ->
   context.ensureActive()
   val projection=if(table=="notes_fts") "rowid AS scale_fts_rowid,*" else "*"
   val columns=sql.query("SELECT $projection FROM $table LIMIT 0").use {it.columnNames.toList()}
   val order=columns.joinToString(",") {"\""+it+"\""}
   val digest=MessageDigest.getInstance("SHA-256")
   var rows=0L
   DataOutputStream(DigestOutputStream(object:OutputStream() {override fun write(value:Int) {};override fun write(bytes:ByteArray,offset:Int,length:Int) {}},digest)).use {output ->
    output.writeInt(columns.size);columns.forEach {output.writeUTF(it)}
    sql.query("SELECT $projection FROM $table ORDER BY $order").use {cursor ->
     while(cursor.moveToNext()) {
      context.ensureActive();rows++
      for(column in columns.indices) {
       val type=cursor.getType(column);output.writeByte(type)
       when(type) {
        Cursor.FIELD_TYPE_NULL -> Unit
        Cursor.FIELD_TYPE_INTEGER ->output.writeLong(cursor.getLong(column))
        Cursor.FIELD_TYPE_FLOAT ->output.writeDouble(cursor.getDouble(column))
        Cursor.FIELD_TYPE_STRING -> {val bytes=cursor.getString(column).toByteArray(Charsets.UTF_8);output.writeInt(bytes.size);output.write(bytes)}
        Cursor.FIELD_TYPE_BLOB -> {val bytes=cursor.getBlob(column);output.writeInt(bytes.size);output.write(bytes)}
        else ->error("Unknown SQLite field type.")
       }
      }
     }
    }
   }
   val result=table to TableFingerprint(rows,hex(digest.digest()))
   onTable(index+1)
   result
  }.toMap()
 }
 private fun snapshotJson(snapshot:Map<String,TableFingerprint>)=JsonObject(snapshot.mapValues {(_,value) ->buildJsonObject {put("rows",value.rows);put("sha256",value.sha256)}})
 private fun count(sql:SupportSQLiteDatabase,table:String):Long=sql.query("SELECT COUNT(*) FROM $table").use {require(it.moveToFirst());it.getLong(0)}
 private suspend fun fileHash(file:File):String {
  val digest=MessageDigest.getInstance("SHA-256")
  val context=currentCoroutineContext()
  file.inputStream().buffered().use {stream ->val buffer=ByteArray(64*1024);while(true) {context.ensureActive();val size=stream.read(buffer);if(size<0) break;digest.update(buffer,0,size)}}
  return hex(digest.digest())
 }
 private fun hex(bytes:ByteArray)=bytes.joinToString("") {"%02x".format(it)}
 private enum class Stage { FIXTURE, PAYLOAD_BEFORE, SNAPSHOT_BEFORE, ORACLE, PAGING, PAYLOAD_AFTER, SNAPSHOT_AFTER, REPORT, COMPLETE, FAILED }
 private class Progress(private val directory:File,private val seed:String,private val verification:String,private val started:Long) {
  private var sequence=0
  fun write(stage:Stage,completed:Int,total:Int,phase:ScaleCorrectnessPhase?=null) {
   require(completed in 0..total)
   val elapsed=SystemClock.elapsedRealtimeNanos()
   val value=buildJsonObject {
    put("contract",1);put("fixture","A");put("target","product");put("nonce",seed);put("verification_nonce",verification)
    put("sequence",++sequence);put("stage",stage.name);phase?.let {put("oracle_phase",it.name)}
    put("completed",completed);put("total",total);put("elapsed_realtime_ns",elapsed);put("elapsed_since_test_start_ns",elapsed-started)
    put("status",when(stage) {Stage.COMPLETE ->"complete";Stage.FAILED ->"failed";else ->"running"})
   }
   atomicJson(File(directory,"progress.json"),value,replace=true)
  }
 }
 companion object {
  private const val KNOWN_SEED_NONCE="61a39766246942efa47a50e886188281"
  private val TABLES=listOf("notes","notes_fts","notes_fts_docsize","labels","sync_state","outbox","drafts","attachments","recording_drafts","plugin_output","view_memberships","tombstones","revisions","server_revisions","history_cache","refresh_notes","sync_log")
  private val EMPTY_TABLES=TABLES.filter {it !in setOf("notes","notes_fts","notes_fts_docsize","labels","sync_state")}
  private fun atomicJson(destination:File,value:JsonObject,replace:Boolean) {
   if(!replace) require(!Files.exists(destination.toPath(),LinkOption.NOFOLLOW_LINKS)) {"Fresh evidence output required."}
   val temporary=File(destination.parentFile,destination.name+".partial")
   require(temporary.createNewFile()) {"Evidence partial already exists."}
   FileOutputStream(temporary).use {it.write(value.toString().toByteArray(Charsets.UTF_8));it.fd.sync()}
   if(replace) Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
   else Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE)
  }
 }
}
