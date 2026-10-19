package io.theficos.spunto.data.local
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.max
/** FTS4 supplies counts, while Kotlin computes the same title/body weights as the server. */
internal fun bm25(info:ByteArray):Double {
 val values=ByteBuffer.wrap(info).order(ByteOrder.nativeOrder()).asIntBuffer()
 val phrases=values[0]; val columns=values[1]; val rows=values[2]; var score=0.0
 for(p in 0 until phrases) for(c in 0 until columns) {
  val offset=3+2*columns+3*(p*columns+c);val hits=values[offset];if(hits==0)continue
  val matched=values[offset+2];val idf=ln(1+(rows-matched+0.5)/(matched+0.5))
  val ratio=values[3+columns+c].toDouble()/max(values[3+c],1)
  val weight=if(c==0)2.0 else 1.0
  score+=weight*idf*hits*2.2/(hits+1.2*(0.25+0.75*ratio))
 };return score
}

private data class ScoredSearchRow(val note:NoteEntity,val score:Double)

/** Retain the existing comparator and stable input ties; each candidate is scored once. */
internal fun rankSearchRows(rows:List<RankedNote>,checkActive:()->Unit,score:(ByteArray)->Double=::bm25):List<NoteEntity> {
 checkActive()
 val scored=ArrayList<ScoredSearchRow>(rows.size)
 for(row in rows) {checkActive();scored+=ScoredSearchRow(row.note,score(row.info))}
 val order=compareByDescending<ScoredSearchRow> {it.score}.thenByDescending {it.note.updatedAt}
 scored.sortWith(Comparator {left,right ->checkActive();order.compare(left,right)})
 val result=scored.map {row ->checkActive();row.note}
 checkActive()
 return result
}
