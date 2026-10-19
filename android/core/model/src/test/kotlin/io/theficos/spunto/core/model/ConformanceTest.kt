package io.theficos.spunto.core.model
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
class ConformanceTest {
 @get:org.junit.Rule val timeout = org.junit.rules.Timeout(10, java.util.concurrent.TimeUnit.MINUTES)
 private fun vectors(name: String) = Json.parseToJsonElement(File(System.getProperty("spunto.fixtures"),"$name.json").readText()).jsonArray
 @Test fun sharedFold() { vectors("fold").forEach { val v=it.jsonObject; assertEquals(v.getValue("folded").jsonPrimitive.content,fold(v.getValue("text").jsonPrimitive.content)) } }
 @Test fun sharedTitles() { vectors("titles").forEach { val v=it.jsonObject; assertEquals(v.getValue("title").jsonPrimitive.content,titleOf(v.getValue("body").jsonPrimitive.content,v["limit"]?.jsonPrimitive?.int?:120)) } }
 @Test fun sharedLabels() { vectors("labels").forEach { val v=it.jsonObject; val actual=labels(v.getValue("body").jsonPrimitive.content); assertEquals(v.getValue("paths").jsonArray.map { it.jsonPrimitive.content },actual.map { it.path }); assertEquals(v.getValue("keys").jsonArray.map { it.jsonPrimitive.content },actual.map { it.key }) } }
 @Test fun superscriptAndSubscriptDigitsFollowServerGrammar() {assertEquals(listOf("a²","x₂"),labels("#a² #² #x₂").map { it.path });assertFalse(runCatching {labelKey("²")}.isSuccess)}
 @Test fun unicode16CaseFoldIsIndependentOfAndroidVersion() { val upper=String(Character.toChars(0x10d50));val lower=String(Character.toChars(0x10d70));assertEquals(lower,fold(upper));assertEquals(lower,labelKey(upper));assertEquals(lower,labels("#$upper").single().key) }
 @Test fun newerCompatibilityNormalizationIsIndependentOfAndroidVersion() { assertEquals("a",fold(String(Character.toChars(0x1ccd6))));assertEquals("а",fold(String(Character.toChars(0x1e030)))) }
 @Test fun unpublishedPrivateNoteIsPrivateInItsCreateSnapshot() {val create=OutboxOperation(type="note.create",noteId="n",body="private capture");val private=OutboxOperation(type="note.flags",noteId="n",privateNote=true,at=123);val compacted=compactOutbox(listOf(create,private)).single();assertTrue(compacted.privateNote!!);assertEquals(123L,compacted.privateAt)}
 @Test fun titleBudgetCountsUnicodeCodePoints() { val text="😀".repeat(121); assertEquals("😀".repeat(120),titleOf(text));assertEquals("😀",titleOf("😀x",1));assertFalse(runCatching { labelKey("𝟙𝟚") }.isSuccess) }
 @Test fun sharedRewrites() { vectors("rewrites").forEach { val v=it.jsonObject; assertEquals(v.getValue("expected").jsonPrimitive.content,rewriteLabels(v.getValue("body").jsonPrimitive.content,v.getValue("old").jsonPrimitive.content,v["new"]?.jsonPrimitive?.contentOrNull)) } }
 @Test fun flagCompactionPreservesIndependentActionTimes() { val pin=OutboxOperation(type="note.flags",noteId="n",at=1,pinned=true); val archive=OutboxOperation(type="note.flags",noteId="n",at=2,archived=true); assertEquals(listOf(pin,archive),compactOutbox(listOf(pin,archive))) }
 @Test fun uuidHasTimestampVersionVariant() { val uuid=UUID.fromString(uuidV7(123456)); assertEquals(7,uuid.version()); assertEquals(2,uuid.variant()); assertEquals(123456,uuid.mostSignificantBits ushr 16) }
 @Test fun compactionPreservesEarliestBaseAndNewestBody() { val a=OutboxOperation(type="note.update",noteId="n",baseVersion=3,body="first"); val b=a.copy(opId=uuidV7(),baseVersion=4,body="latest"); assertEquals(listOf(a.copy(body="latest")),compactOutbox(listOf(a,b))); val create=a.copy(type="note.create",baseVersion=null); assertEquals(listOf(create.copy(body="latest")),compactOutbox(listOf(create,b))) }
}
