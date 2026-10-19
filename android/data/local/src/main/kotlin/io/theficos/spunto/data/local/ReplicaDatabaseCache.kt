package io.theficos.spunto.data.local

import android.content.Context

/** Process-owned handles stay available to a retired editor's final draft write. */
class ReplicaDatabaseCache(context:Context) {
 private val context=context.applicationContext
 private val databases=mutableMapOf<String,SpuntoDatabase>()
 @Synchronized fun get(databaseName:String):SpuntoDatabase =
  databases.getOrPut(databaseName) {SpuntoDatabase.open(context,databaseName)}
}
