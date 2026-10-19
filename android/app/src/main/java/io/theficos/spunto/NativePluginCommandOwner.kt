package io.theficos.spunto

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject

internal data class PluginCommandIdentity(val credentials:Any?,val epoch:String,val selectedId:String?)
/** Results and finally belong to one selection attempt, including asynchronous committed-epoch validation. */
internal class NativePluginCommandOwner(private val identity:()->PluginCommandIdentity,private val validate:(PluginCommandIdentity,(Boolean)->Unit)->Job?={_,done -> done(true);null}) {
 private class Attempt(val identity:PluginCommandIdentity) {var job:Job?=null;val validationJobs=mutableListOf<Job>();var validations=0;var finished=false}
 private var active:Attempt?=null
 val pending=MutableStateFlow(false)
 fun reset() {val previous=active;active=null;pending.value=false;previous?.job?.cancel();previous?.validationJobs?.forEach {it.cancel()}}
 fun start(launch:((JsonObject)->Unit,()->Unit)->Job,onResult:(JsonObject)->Unit):Boolean {
  if(active!=null) return false
  val attempt=Attempt(identity());active=attempt;pending.value=true
  fun owns()=active===attempt && identity()==attempt.identity && attempt.job?.isCancelled!=true
  fun finish() {if(active===attempt && attempt.finished && attempt.validations==0) {active=null;pending.value=false}}
  try {
   attempt.job=launch({result -> if(owns()) {
    attempt.validations++
    try {validate(attempt.identity) {allowed ->
     try {if(allowed && owns()) onResult(result)} finally {attempt.validations--;finish()}
    }?.let {attempt.validationJobs+=it}}
    catch(error:Exception) {attempt.validations--;finish();throw error}
   }},{attempt.finished=true;finish()})
  } catch(error:Exception) {if(active===attempt) {active=null;pending.value=false};throw error}
  return true
 }
}
