package io.theficos.spunto.data.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.core.DataStore
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable data class AuthConfig(val serverUrl: String, val token: String, val deviceId: String, val name: String, val httpConsent: Boolean = false, val userId:String?=null, val workspaceId:String?=null, val workspaceRole:String?=null) {
 init { require((userId==null && workspaceId==null && workspaceRole==null) || (!userId.isNullOrBlank() && !workspaceId.isNullOrBlank() && workspaceRole in setOf("owner","editor","viewer"))) {"The server returned an incomplete account identity."} }
 val hosted get()=userId!=null
 val replicaId:String get()=if(hosted) "account-"+replicaHash(listOf(serverUrl.trimEnd('/'),userId!!,workspaceId!!)) else "legacy"
}
fun replicaHash(parts:List<String>):String=MessageDigest.getInstance("SHA-256").digest(Json.encodeToString(parts).toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
fun AuthConfig.ownsReplica(replicaId:String):Boolean=if(hosted) this.replicaId==replicaId else replicaId in setOf("","legacy")
data class AuthSelection(val config:AuthConfig?,val replicaId:String)
fun AuthConfig.accept(device:DeviceToken):AuthConfig=copy(token=device.token,deviceId=device.id,userId=device.userId,workspaceId=device.workspaceId,workspaceRole=device.workspaceRole)

// Cache by the actual application-private file, including isolated test contexts.
private val authStores=ConcurrentHashMap<String,DataStore<Preferences>>()
private fun authDataStore(context:Context):DataStore<Preferences> {
 val file=context.applicationContext.preferencesDataStoreFile("auth")
 return authStores.computeIfAbsent(file.canonicalPath) {PreferenceDataStoreFactory.create(produceFile={file})}
}
class AuthStore(context: Context) {
 private val store=authDataStore(context)
 private val secretKey=stringPreferencesKey("session")
 private val replicaKey=stringPreferencesKey("replica")
 val selection:Flow<AuthSelection> = store.data.map {prefs -> val config=prefs[secretKey]?.let {decrypt(it)};AuthSelection(config,prefs[replicaKey] ?: config?.replicaId ?: "legacy")}
 val config: Flow<AuthConfig?> = selection.map {it.config}
 private fun key(): SecretKey {
  val keys=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
  (keys.getKey("spunto.device",null) as? SecretKey)?.let { return it }
  return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply { init(KeyGenParameterSpec.Builder("spunto.device",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build()) }.generateKey()
 }
 suspend fun save(config: AuthConfig) {
  val url=config.serverUrl.trimEnd('/'); require(url.startsWith("https://") || (url.startsWith("http://") && config.httpConsent)) { "Confirm the unencrypted connection before signing in." }
  val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
  val encrypted=cipher.doFinal(Json.encodeToString(config.copy(serverUrl=url)).toByteArray())
  val blob=Base64.encodeToString(cipher.iv+encrypted,Base64.NO_WRAP)
  store.edit { it[secretKey]=blob;it[replicaKey]=config.replicaId }
 }
 private fun decrypt(blob: String): AuthConfig? = runCatching {
  val bytes=Base64.decode(blob,Base64.NO_WRAP); require(bytes.size>28)
  val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12))) }
  Json.decodeFromString<AuthConfig>(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
 }.getOrNull()
 suspend fun clear() { store.edit { val old=it[secretKey]?.let(::decrypt);it.remove(secretKey);if(old?.hosted==true || it[replicaKey]?.startsWith("account-")==true) it[replicaKey]="signed-out" } }
}

fun replicaDatabaseName(replicaId:String):String {
 require(replicaId=="legacy" || replicaId=="signed-out" || Regex("account-[a-f0-9]{64}").matches(replicaId)) {"Invalid local account binding."}
 return if(replicaId=="legacy") "spunto.db" else "spunto-$replicaId.db"
}
