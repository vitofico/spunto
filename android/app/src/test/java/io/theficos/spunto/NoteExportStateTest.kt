package io.theficos.spunto

import io.theficos.spunto.core.model.Note
import io.theficos.spunto.data.sync.AuthConfig
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application=android.app.Application::class,sdk=[28])
class NoteExportStateTest {
 private val context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
 private val note=Note("synthetic-export","# Example\n\nUnicode città · text",createdAt=10,updatedAt=20,serverRev=3,privateNote=true)
 private fun stamp(value:Note=note,draft:String?=null,account:Any?="account",server:String?="https://synthetic.invalid",epoch:String?="a")=noteExportStamp(value,draft,account,server,epoch)
 @Test fun pickerOwnsOnlyUnchangedUnlockedNoteAndReplicaWithoutRetainingBody() {
  val session=NoteExportSession();val source=stamp();val request=session.begin(source)
  assertTrue(session.owns(request,source,true));assertFalse(session.owns(request,source,false))
  assertFalse(source.toString().contains(note.body));assertFalse(source.revision.contains("Example"))
  for(changed in listOf(stamp(note.copy(id="other")),stamp(note.copy(body="Changed")),stamp(note.copy(title="Changed title")),stamp(note.copy(updatedAt=21)),stamp(note.copy(serverRev=4)),stamp(note.copy(privateNote=false)),stamp(note.copy(trashed=true)),stamp(draft="new draft"),stamp(account="other"),stamp(server="https://other.invalid"),stamp(epoch="b"))) assertFalse(session.owns(request,changed,true))
 }
 @Test fun disposalAndLaterAttemptNeverAcceptAnOldPickerResult() {
  val session=NoteExportSession();val source=stamp(account=null,server=null,epoch=null)
  val first=session.begin(source);session.cancel();assertFalse(session.owns(first,source,true))
  val next=session.begin(source);assertFalse(session.owns(first,source,true));assertTrue(session.owns(next,source,true))
 }
 @Test fun safeBoundedMarkdownFilenamePreservesUsefulUnicodeWithoutPathsOrControls() {
  assertEquals("Città notes.md",markdownFileName(context,"../Città /notes\n\u0000"))
  assertEquals("note.md",markdownFileName(context,"../\\\n\u0000"))
  assertEquals(83,markdownFileName(context,"x".repeat(200)).length)
 }
 @Test fun markdownWriteIsExactUtf8AndStopsBeforeAnyWriteWithoutAuthority() {
  val bytes=java.io.ByteArrayOutputStream()
  writeNoteMarkdown(bytes,note.body) {true}
  assertArrayEquals(note.body.toByteArray(Charsets.UTF_8),bytes.toByteArray())
  val refused=java.io.ByteArrayOutputStream()
  try {writeNoteMarkdown(refused,note.body) {false};fail("Locked export wrote bytes") } catch(_:IllegalStateException) {}
  assertEquals(0,refused.size())
  val interrupted=java.io.ByteArrayOutputStream()
  try {writeNoteMarkdown(interrupted,"x".repeat(20000)) {interrupted.size()==0};fail("Export continued after authority changed") } catch(_:IllegalStateException) {}
  assertEquals(8192,interrupted.size())
 }
 @Test fun fullLibraryAndProfileUseOnlyValidatedOwnerWebRoutesAndNoCredentials() {
  val config=AuthConfig("https://synthetic.invalid","synthetic-export-token","synthetic-device","Phone")
  for(section in listOf("data","profile")) {
   val target=ownerWebSettingsTarget(context,config,"https://synthetic.invalid",true,section)
   assertEquals("https://synthetic.invalid/settings/$section",target.url);assertFalse(target.url!!.contains(config.token))
   assertNull(ownerWebSettingsTarget(context,config,"https://other.invalid",true,section).url)
   assertNull(ownerWebSettingsTarget(context,config,"https://synthetic.invalid",false,section).url)
   assertNull(ownerWebSettingsTarget(context,config,"https://synthetic.invalid",true,section,true).url)
  }
  assertNull(ownerWebSettingsTarget(context,config,"https://synthetic.invalid",true,"../export").url)
  assertNull(ownerWebSettingsTarget(context,null,null,true,"data").url)
  assertNull(ownerWebSettingsTarget(context,config.copy(serverUrl="https://user:secret@synthetic.invalid"),"https://synthetic.invalid",true,"data").url)
  assertNull(ownerWebSettingsTarget(context,config.copy(serverUrl="http://synthetic.invalid"),"http://synthetic.invalid",true,"data").url)
 }
}
