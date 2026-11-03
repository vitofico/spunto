package io.theficos.spunto

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.theficos.spunto.core.design.SpuntoSpacing
import io.theficos.spunto.data.sync.*
import kotlinx.serialization.json.*

/** Per-replica preferences retain the name offline, but never across credential replacement. */
internal class AccountIdentityCache(private val preferences:SharedPreferences) {
 private val key="signed-in-account"
 private var generation=0L
 private fun credential(config:AuthConfig)=replicaHash(listOf(config.replicaId,config.token))
 fun read(config:AuthConfig?):SignedInAccount? {
  if(config?.hosted!=true) return null
  return runCatching {
   val cached=Json.parseToJsonElement(preferences.getString(key,null) ?: return null).jsonObject
   if(cached["credential"]?.jsonPrimitive?.contentOrNull!=credential(config)) return null
   Json.decodeFromJsonElement(SignedInAccount.serializer(),cached.getValue("account")).takeIf {it.id==config.userId}
  }.getOrNull()
 }
 fun save(config:AuthConfig,account:SignedInAccount) {
  require(config.hosted && account.id==config.userId)
  val cached=buildJsonObject {put("credential",credential(config));put("account",Json.encodeToJsonElement(SignedInAccount.serializer(),account))}
  preferences.edit().putString(key,cached.toString()).apply()
 }
 /** Recheck the publication generation after both network and credential-store suspension. */
 suspend fun refresh(config:AuthConfig,request:suspend ()->SignedInAccount,current:suspend ()->AuthConfig?):SignedInAccount? {
  val expected=synchronized(this) {generation}
  val account=request()
  val authority=current()
  return synchronized(this) {
   if(expected!=generation || authority!=config || !config.hosted || account.id!=config.userId) null
   else {save(config,account);account}
  }
 }
 @Synchronized fun clear() {generation++;preferences.edit().remove(key).apply()}
}

@Composable internal fun AccountIdentityRow(config:AuthConfig,account:SignedInAccount?,onClick:()->Unit) {
 val resources=LocalContext.current.resources
 val identity=account?.takeIf {config.hosted && it.id==config.userId}
 val name=identity?.label ?: resources.getString(if(config.hosted) R.string.settings_account else R.string.settings_owner)
 SettingsRow(name,if(config.hosted && identity?.label==null) resources.getString(R.string.settings_signed_in) else resources.getString(R.string.settings_signed_in_as,identity?.username?.trim()?.takeIf {it.isNotEmpty()} ?: name),onClick=onClick)
}

@Composable internal fun AccountIdentityDialog(config:AuthConfig,account:SignedInAccount?,onDismiss:()->Unit,onManage:()->Unit) {
 val resources=LocalContext.current.resources
 val identity=account?.takeIf {config.hosted && it.id==config.userId}
 val name=identity?.label ?: resources.getString(if(config.hosted) R.string.settings_account else R.string.settings_owner)
 AlertDialog(onDismissRequest=onDismiss,title={Text(resources.getString(R.string.settings_account))},text={
  Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
   Text(if(config.hosted && identity?.label==null) resources.getString(R.string.settings_signed_in) else resources.getString(R.string.settings_signed_in_as,name))
   identity?.username?.trim()?.takeIf {it.isNotEmpty() && it!=name}?.let {Text(resources.getString(R.string.settings_account_username,it))}
   Text(resources.getString(R.string.settings_account_server,config.serverUrl))
   Text(resources.getString(R.string.settings_this_phone_is,config.name))
  }
 },confirmButton={TextButton(onClick=onManage) {Text(resources.getString(R.string.settings_manage_account))}},dismissButton={TextButton(onClick=onDismiss) {Text(resources.getString(R.string.settings_close))}})
}
