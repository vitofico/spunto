package io.theficos.spunto

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.model.PluginPublicationFence
import io.theficos.spunto.core.plugins.ManifestCache
import io.theficos.spunto.data.local.*
import io.theficos.spunto.data.sync.*
import io.theficos.spunto.scale.fixture.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** PROPOSAL ONLY: root must release/copy this AndroidTest source before compiling/executing. */
@RunWith(AndroidJUnit4::class)
class ProductScaleSetupTest {
 @get:Rule val timeout=org.junit.rules.Timeout(20,java.util.concurrent.TimeUnit.MINUTES)
 @Test fun seedAndVerifyExplicitlyDisposableProductReplica():Unit=runBlocking {
  val arguments=InstrumentationRegistry.getArguments()
  org.junit.Assume.assumeTrue("Scale diagnostics require explicit fixture arguments.",arguments.keySet().any {it.startsWith("spunto.scale.")})
  require(arguments.getString("spunto.scale.disposable")=="SPUNTO-SYNTHETIC-SCALE-ONLY") {"Disposable device acknowledgement required."}
  val nonce=arguments.getString("spunto.scale.nonce").orEmpty();require(Regex("[a-f0-9]{32}").matches(nonce)) {"Provide a fresh disposable-device nonce."}
  val variant=arguments.getString("spunto.scale.fixture").orEmpty();require(variant in setOf("A","B","C"))
  val target=arguments.getString("spunto.scale.target").orEmpty();require(target in setOf("product","isolated"))
  require(variant!="C" || target=="isolated") {"C requires isolated fence correctness; actual product C UI must pair separately."}
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  require(context.packageName=="io.theficos.spunto.debug") {"Synthetic setup requires the isolated debug package."}
  require(AuthStore(context).config.first()==null) {"Refusing an authenticated device."}
  val marker=File(context.filesDir,"scale-$nonce.seed");require(marker.createNewFile()) {"Fixture nonce was already used."}
  val fixture=ProductScaleFixtures.generate(variant)
  val isolatedName="scale-$nonce.db"
  val product=if(target=="product") (context.applicationContext as SpuntoApplication).container else null
  val db=product?.database ?: Room.databaseBuilder(context,SpuntoDatabase::class.java,isolatedName).build()
  val repository=product?.repository ?: NotesRepository(db)
  try {
   val sql=db.openHelper.writableDatabase
   val emptyTables=listOf("notes","outbox","drafts","attachments","recording_drafts","plugin_output","view_memberships","tombstones","revisions","server_revisions","history_cache","refresh_notes","sync_log","sync_state")
   fun count(table:String):Long=sql.query("SELECT COUNT(*) FROM $table").use {it.moveToFirst();it.getLong(0)}
   require(emptyTables.all {count(it)==0L}) {"Refusing a populated replica. No data will be erased."}
   val start=SystemClock.elapsedRealtimeNanos()
   db.withTransaction {repository.dao.putState(SyncStateEntity(epoch="scale-${variant.lowercase()}",manifest=fixture.manifest.toString(),catalog=fixture.catalog.toString(),serverUrl=if(variant=="C") "https://scale.synthetic.invalid" else ""))}
   fixture.notes.chunked(100).forEach {chunk ->db.withTransaction {chunk.forEach {repository.putNote(it)}}}
   db.withTransaction {fixture.outputs.forEach {repository.dao.putOutput(it)}}
   val setupNs=SystemClock.elapsedRealtimeNanos()-start
   require(count("notes")==10_000L && count("notes_fts")==10_000L && count("notes_fts_docsize")==10_000L)
   val literalLabelCount=fixture.notes.sumOf {io.theficos.spunto.core.model.labels(it.body).map {label ->label.key}.distinct().size}
   require(count("labels")==literalLabelCount.toLong())
   require(count("plugin_output")==fixture.outputs.size.toLong())
   require(count("view_memberships")==if(variant=="C") 2L else 0L)
   require(emptyTables.filter {it !in setOf("notes","sync_state","plugin_output","view_memberships")}.all {count(it)==0L})
   val correctness=ProductScaleCorrectness.verify(repository,fixture)
   val pageSource=repository.dao.filteredPaging(timelineQuery(TimelineSpec()))
   val pagedIds=mutableListOf<String>();var next:Int?=null;var first=true
   while(first || next!=null) {
    val result=pageSource.load(if(first) PagingSource.LoadParams.Refresh<Int>(null,40,false) else PagingSource.LoadParams.Append(next!!,40,false))
    require(result is PagingSource.LoadResult.Page) {"Product DAO page failed."}
    require(result.data.size<=40);pagedIds.addAll(result.data.map {it.id});next=result.nextKey;first=false
   }
   val allHome=repository.dao.filteredNotes(timelineQuery(TimelineSpec())).map {it.id}
   require(pagedIds==allHome && pagedIds.distinct().size==pagedIds.size)
   if(variant=="C") {
    val config=AuthConfig("https://scale.synthetic.invalid","synthetic-fixture-token","scale-test","Scale fixture")
    val fence=PluginPublicationFence()
    val committed=readCommittedPluginPublication(repository,config,fence) {config} ?: error("Fixture publication missing.")
    val privatePreferences=object:ContextWrapper(context) {override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=super.getSharedPreferences("scale-$nonce-$name",mode)}
    val cache=ManifestCache(privatePreferences,fence);require(cache.adopt(committed.owner)!=null)
    var slipId:String?=null
    cache.withCaptureSurfacePublication(fixture.notes,repository.dao.outputs().first(),updateManifest={},cacheSlip={id,_,_ ->slipId=id})
    require(slipId==fixture.id(11)) {"Private/trashed/inactive slip became eligible."}
    val members=repository.dao.filteredNotes(timelineQuery(TimelineSpec(kind=TimelineKind.PLUGIN,pluginId="scale-live",filter=buildJsonObject {put("member","focus")})))
    require(members.map {it.id}==listOf(fixture.id(11)))
    fence.invalidate();cache.withCaptureSurfacePublication(fixture.notes,fixture.outputs,updateManifest={},cacheSlip={id,_,_ ->slipId=id})
    require(slipId==null) {"Invalidated publication retained a slip."}
   }
   require(AuthStore(context).config.first()==null && repository.dao.outbox().isEmpty())
   val report=buildJsonObject {
    put("contract",1);put("status","correctness_only");put("fixture",variant);put("target",target);put("nonce",nonce)
    put("corpus_source_sha256",ProductScaleFixtures.SOURCE_SHA);put("kotlin_runtime",KotlinVersion.CURRENT.toString());put("android_sdk",Build.VERSION.SDK_INT);put("device_fingerprint",Build.FINGERPRINT)
    put("sqlite_version",sql.query("SELECT sqlite_version()").use {it.moveToFirst();it.getString(0)})
    put("schema_version",sql.version);put("payload_sha256",fixture.payloadHash());put("notes",count("notes"));put("fts_rows",count("notes_fts"));put("fts_docsize_rows",count("notes_fts_docsize"));put("literal_label_rows",literalLabelCount)
    put("body_utf8_bytes",fixture.notes.sumOf {it.body.toByteArray(Charsets.UTF_8).size.toLong()});put("minimum_body_utf8_bytes",fixture.notes.minOf {it.body.toByteArray(Charsets.UTF_8).size});put("maximum_body_utf8_bytes",fixture.notes.maxOf {it.body.toByteArray(Charsets.UTF_8).size})
    put("pinned",fixture.notes.count {it.pinned});put("archived",fixture.notes.count {it.archived});put("trashed",fixture.notes.count {it.trashed});put("private",fixture.notes.count {it.privateNote})
    put("folded_index_sha256",ProductScaleFixtures.digest(repository.dao.all().sortedBy {it.id}.map {it.id+"\t"+it.foldedTitle+"\t"+it.foldedBody}))
    put("outputs",fixture.outputs.size);put("attachments",count("attachments"));put("outbox",count("outbox"));put("auth_present",false);put("paged_home_count",pagedIds.size);put("paged_home_ids_sha256",ProductScaleFixtures.digest(pagedIds));put("setup_ns_not_interaction_metric",setupNs)
    put("device_plugin_default_boundary","Signed-out product keeps shipped unpaired device defaults; zero server contributions for A/B.")
    put("checks",correctness);put("paging_pass",true);put("isolated_c_eligibility_pass",variant=="C")
   }
   val folder=File(context.cacheDir,"scale-evidence").apply {require(isDirectory || mkdirs())}
   val destination=File(folder,"$variant-$nonce.json");require(!destination.exists())
   val temporary=File(folder,"$variant-$nonce.json.partial");require(temporary.createNewFile())
   FileOutputStream(temporary).use {it.write(report.toString().toByteArray(Charsets.UTF_8));it.fd.sync()}
   Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE)
   // No note text, queries or credentials go to logcat. Root exports this named private file.
  } finally {if(target=="isolated") db.close()}
 }
}
