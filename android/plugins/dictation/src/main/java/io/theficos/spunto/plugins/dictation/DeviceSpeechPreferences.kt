package io.theficos.spunto.plugins.dictation
import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
private val Context.speechStore by preferencesDataStore("dictation")
data class SpeechPreferences(val language:String=if(java.util.Locale.getDefault().language=="it") "it-IT" else "en-US",val onlineConsent:Boolean=false)
class DeviceSpeechPreferences(context:Context) {
 private val store=context.applicationContext.speechStore
 private val languageKey=stringPreferencesKey("language")
 private val onlineKey=booleanPreferencesKey("online-consent")
 val settings=store.data.map { SpeechPreferences(it[languageKey] ?: SpeechPreferences().language,it[onlineKey] ?: false) }
 suspend fun update(language:String?=null,online:Boolean?=null) { store.edit { prefs -> language?.let { require(it in setOf("it-IT","en-US","auto")); prefs[languageKey]=it };online?.let { prefs[onlineKey]=it } } }
}
