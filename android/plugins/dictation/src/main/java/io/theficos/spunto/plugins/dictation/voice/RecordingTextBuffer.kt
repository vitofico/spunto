package io.theficos.spunto.plugins.dictation.voice

import io.theficos.spunto.plugins.dictation.TranscriptBuffer

data class RecordingTextSnapshot(val text:String,val revision:Long)

/** Owner edits always win; recognition may replace only its still-unedited live span. */
internal class RecordingTextBuffer(initial:String,selectionStart:Int,selectionEnd:Int) {
 private var buffer=TranscriptBuffer(initial,selectionStart,selectionEnd)
 private var revision=0L
 private var suppressSegment=false
 private var current=initial
 val partialSpan:IntRange? get()=if(suppressSegment) null else buffer.partialSpan
 fun snapshot()=RecordingTextSnapshot(current,revision)
 private fun publish(value:String):RecordingTextSnapshot {if(value!=current) {current=value;revision++};return snapshot()}
 fun typed(text:String,selectionStart:Int,selectionEnd:Int):RecordingTextSnapshot {
  val span=buffer.partialSpan
  if(text==current) {
   if(span==null) buffer=TranscriptBuffer(text,selectionStart,selectionEnd)
   return snapshot()
  }
  if(span!=null && !suppressSegment) {
   val mapped=unchangedSpan(current,text,span)
   if(mapped!=null) {
    val partial=current.substring(span.first,span.last+1)
    val basis=text.removeRange(mapped.first,mapped.last+1)
    buffer=TranscriptBuffer(basis,mapped.first,mapped.first);buffer.partial(partial)
   } else {buffer=TranscriptBuffer(text,selectionStart,selectionEnd);suppressSegment=true}
  } else buffer=TranscriptBuffer(text,selectionStart,selectionEnd)
  return publish(text)
 }
 fun partial(text:String):RecordingTextSnapshot=if(suppressSegment) snapshot() else publish(buffer.partial(text))
 fun final(text:String):RecordingTextSnapshot {
  if(suppressSegment) {suppressSegment=false;return snapshot()}
  return publish(buffer.final(text))
 }
 fun finish():RecordingTextSnapshot=if(suppressSegment) snapshot() else publish(buffer.commitPartial())
}

/** Bounded shortest-edit alignment in both directions rejects ambiguous moved/repeated spans. */
private fun unchangedSpan(before:String,after:String,span:IntRange):IntRange? {
 val forward=alignSpan(before,after,span)?:return null
 val reversed=alignSpan(before.reversed(),after.reversed(),(before.lastIndex-span.last)..(before.lastIndex-span.first))?:return null
 val backward=(after.lastIndex-reversed.last)..(after.lastIndex-reversed.first)
 // An unchanged owner context crossing the live span proves relocation, even if the longest edit alignment favors the longer speech fragment.
 fun crossed(context:String,expectedBefore:Boolean):Boolean {
  if(context.isBlank() || before.indexOf(context)!=before.lastIndexOf(context)) return false
  val position=after.indexOf(context)
  if(position<0 || position!=after.lastIndexOf(context)) return false
  return if(expectedBefore) position>=forward.last+1 else position+context.length<=forward.first
 }
 if(crossed(before.substring(0,span.first),true) || crossed(before.substring(span.last+1),false)) return null
 return forward.takeIf {it==backward && after.substring(it.first,it.last+1)==before.substring(span.first,span.last+1)}
}
private fun alignSpan(before:String,after:String,span:IntRange):IntRange? {
 val maximum=256;val offset=maximum+1;val frontier=IntArray(maximum*2+3) {-1};frontier[offset+1]=0
 val trace=ArrayList<IntArray>();var comparisons=0
 for(distance in 0..maximum) {
  trace+=frontier.copyOf()
  for(diagonal in -distance..distance step 2) {
   var x=if(diagonal==-distance || diagonal!=distance && frontier[offset+diagonal-1]<frontier[offset+diagonal+1]) frontier[offset+diagonal+1] else frontier[offset+diagonal-1]+1
   var y=x-diagonal
   while(x in before.indices && y in after.indices && before[x]==after[y]) {
    if(++comparisons>2_000_000) return null
    x++;y++
   }
   frontier[offset+diagonal]=x
   if(x>=before.length && y>=after.length) {
    val mapped=IntArray(span.last-span.first+1) {-1};var old=before.length;var fresh=after.length
    for(step in distance downTo 0) {
     val previous=trace[step];val k=old-fresh
     val previousK=if(k==-step || k!=step && previous[offset+k-1]<previous[offset+k+1]) k+1 else k-1
     val previousX=previous[offset+previousK];val previousY=previousX-previousK
     while(old>previousX && fresh>previousY) {
      old--;fresh--;if(old in span) mapped[old-span.first]=fresh
     }
     old=previousX;fresh=previousY
    }
    if(mapped.any {it<0} || mapped.indices.drop(1).any {mapped[it]!=mapped[it-1]+1}) return null
    return mapped.first()..mapped.last()
   }
  }
 }
 return null
}
