package io.theficos.spunto

import io.theficos.spunto.data.sync.AuthConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.util.Locale

private const val localFixtureFailure="Effectful fixtures require the same local replica authority."

private fun localFixtureRoot(raw:String?):String? {
 if(raw==null) return null
 return runCatching {
 val uri=URI(raw)
 val scheme=uri.scheme?.lowercase(Locale.ROOT)
 val host=uri.host?.lowercase(Locale.ROOT)
 require(scheme in setOf("http","https") && host in setOf("localhost","127.0.0.1","10.0.2.2"))
 require(uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null && uri.rawPath in setOf("","/"))
 require(uri.port==-1 || uri.port in 1..65535)
 val port=if(uri.port==80 && scheme=="http" || uri.port==443 && scheme=="https") -1 else uri.port
 URI(scheme,null,host,port,null,null,null).toASCIIString()
 }.getOrNull()
}

/** Pure guard shared with the table cases. Errors never include URLs or credentials. */
internal fun requireLocalFixtureRoots(authRoot:String?,replicaRoot:String?) {
 val replica=replicaRoot?.takeIf {it.isNotBlank()}
 if(authRoot==null) check(replica==null || localFixtureRoot(replica)!=null) {localFixtureFailure}
 else {
  val local=localFixtureRoot(authRoot)
  check(local!=null && local==localFixtureRoot(replica)) {localFixtureFailure}
 }
}

internal class LocalFixtureAuthority private constructor(
 private val container:ReplicaContainer,private val config:AuthConfig?,private val replicaRoot:String?,private val epoch:String?,
) {
 suspend fun requireCurrent() {
  val auth=container.auth.config.first()
  val state=container.repository.dao.state()
  check(auth==config && container.auth.config.first()==config && state?.epoch==epoch && localFixtureRoot(state?.serverUrl)==localFixtureRoot(replicaRoot)) {localFixtureFailure}
  requireLocalFixtureRoots(auth?.serverUrl,state?.serverUrl)
 }

 /** Retire queued editor writes before disposal, then restore under the repository's editor mutex. */
 fun restoreCaptureDraft(original:String?,closeEditor:()->Unit) {
  val repository=container.repository
  val cleanup=repository.beginEditor(null,"capture")
  try {
   closeEditor()
   runBlocking {
    requireCurrent()
    repository.saveDraft("capture",original.orEmpty())
    if(original==null) repository.clearDraft("capture","")
   }
  } finally {repository.abandonEditor(cleanup)}
 }

 companion object {
  suspend fun require(container:AppContainer):LocalFixtureAuthority=require(container.current)
  suspend fun require(container:ReplicaContainer):LocalFixtureAuthority {
   val auth=container.auth.config.first()
   val state=container.repository.dao.state()
   check(container.auth.config.first()==auth) {localFixtureFailure}
   requireLocalFixtureRoots(auth?.serverUrl,state?.serverUrl)
   return LocalFixtureAuthority(container,auth,state?.serverUrl,state?.epoch)
  }
 }
}
