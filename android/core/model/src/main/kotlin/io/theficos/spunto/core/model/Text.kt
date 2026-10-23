package io.theficos.spunto.core.model

import java.text.Normalizer
private fun caseFold(text: String): String = text.codePoints().toArray().joinToString("") { cp -> val char=String(Character.toChars(cp)); caseFoldOverrides[char] ?: char }
private fun compatibilityExpansion(text: String): String = text.codePoints().toArray().joinToString("") { cp -> val char=String(Character.toChars(cp)); nfkdCompatibilityOverrides[char] ?: char }
fun fold(text: String): String = caseFold(Normalizer.normalize(compatibilityExpansion(text), Normalizer.Form.NFKD).codePoints().toArray().filterNot(::combining).joinToString("") { String(Character.toChars(it)) })
fun labelKey(path: String): String {
 val normalized = Normalizer.normalize(path, Normalizer.Form.NFC); val parts = normalized.split('/')
 requireLocal(normalized.isNotEmpty() && normalized.codePointCount(0, normalized.length) <= 128 && parts.size <= 5 && !parts[0].codePoints().allMatch(::unicodeDigit) && parts.all { it.isNotEmpty() && it.codePoints().allMatch { c -> (unicodeLetter(c) || unicodeDigit(c)) || c == '_'.code || c == '-'.code } },LocalOperationProblem.LabelInvalid) { "Labels need 1 to 5 letter, digit, underscore or hyphen segments." }
 return caseFold(normalized)
}
private fun escaped(body: String, at: Int): Boolean { var start = at; while (start > 0 && body[start - 1] == '\\') start--; return (at - start) % 2 == 1 }
fun labels(body: String): List<Label> {
 val mask = BooleanArray(body.length); var fence: Char? = null; var fenceLength = 0; var offset = 0
 for (line in body.splitToSequence(Regex("(?<=\\n)"))) {
  val marker = Regex("^ {0,3}(`{3,}|~{3,})([^\\r\\n]*)").find(line)
  if (fence != null) {
   for (i in offset until offset + line.length) mask[i] = true
   if (marker != null && marker.groupValues[1][0] == fence && marker.groupValues[1].length >= fenceLength && marker.groupValues[2].isBlank()) fence = null
  } else if (marker != null && (marker.groupValues[1][0] == '~' || !marker.groupValues[2].contains('`'))) {
   fence = marker.groupValues[1][0]; fenceLength = marker.groupValues[1].length; for (i in offset until offset + line.length) mask[i] = true
  }; offset += line.length
 }
 val runs = Regex("`+").findAll(body).toList(); var ri = 0
 while (ri < runs.size) { val opener = runs[ri]; if (!mask[opener.range.first] && !escaped(body, opener.range.first)) {
   var close = ri + 1
   while (close < runs.size && !mask[runs[close].range.first]) { if (runs[close].value.length == opener.value.length) { for (i in opener.range.first..runs[close].range.last) mask[i] = true; ri = close; break }; close++ }
  }; ri++ }
 val found = mutableListOf<Label>(); var at = 0
 while (at < body.length) {
  if (body[at] != '#' || mask[at] || (at > 0 && !body[at-1].isWhitespace() && body[at-1] !in "([")) { at++; continue }
  var end = at + 1
  while (end < body.length && !mask[end]) { val cp = body.codePointAt(end); val type = Character.getType(cp); if (!(unicodeLetter(cp) || unicodeDigit(cp)) && cp !in listOf('_'.code, '-'.code, '/'.code) && type !in listOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())) break; end += Character.charCount(cp) }
  val path = Normalizer.normalize(body.substring(at + 1, end), Normalizer.Form.NFC)
  runCatching { labelKey(path) }.getOrNull()?.let { found += Label(it, path, at, end) }; at = end
 }; return found
}
fun rewriteLabels(body: String, old: String, new: String?): String {
 val key = labelKey(old); new?.let(::labelKey); var result = body; val depth = old.split('/').size
 for (label in labels(body).asReversed()) if (label.key == key || label.key.startsWith("$key/")) {
  val replacement = new?.let { listOf(it).plus(label.path.split('/').drop(depth)).joinToString("/") }?.also(::labelKey)?.let { "#$it" } ?: ""
  result = result.replaceRange(label.start, label.end, replacement)
 }; return result
}
private fun titleLinks(line: String): String {
    val result = StringBuilder()
    var start = 0
    var at = 0
    while (true) {
        at = line.indexOf('[', at)
        if (at < 0) break
        val end = line.indexOf(']', at + 1)
        if (end < 0) break
        if (line.getOrNull(end + 1) != '(') {
            // Every opener before this bracket has the same invalid closing bracket.
            at = end + 1
            continue
        }
        val close = line.indexOf(')', end + 2)
        if (close < 0) break
        val marker = if (at > start && line[at - 1] == '!') at - 1 else at
        result.append(line, start, marker).append(line, at + 1, end)
        start = close + 1
        at = start
    }
    return result.append(line, start, line.length).toString()
}

private fun titleTags(line: String): String {
    val result = StringBuilder()
    var start = 0
    while (true) {
        val at = line.indexOf('<', start)
        if (at < 0) break
        val end = line.indexOf('>', at + 1)
        if (end < 0) break
        result.append(line, start, at)
        start = end + 1
    }
    return result.append(line, start, line.length).toString()
}

private fun titleCode(line: String): String {
    val runs = Regex("`+").findAll(line).map { it.range }.toList()
    val following = IntArray(runs.size)
    var longest = 0
    var nextStart = line.length
    for (index in runs.indices.reversed()) {
        // lineSequence retains these separators, which the previous regex dot
        // could not cross. Each gap between runs is examined at most once.
        for (at in runs[index].last + 1 until nextStart) {
            if (line[at] in "\u0085\u2028\u2029") {
                longest = 0
                break
            }
        }
        following[index] = longest
        longest = maxOf(runs[index].last + 1 - runs[index].first, longest)
        nextStart = runs[index].first
    }
    val result = StringBuilder()
    var start = 0
    var index = 0
    while (index < runs.size) {
        val opener = maxOf(start, runs[index].first)
        val length = runs[index].last + 1 - opener
        // Preserve the greedy opener and first matching closer without retrying
        // every possible opener length when a long run has no matching closer.
        val width = maxOf(minOf(length, following[index]), length / 2)
        if (width == 0) {
            index++
            continue
        }
        var close = opener + width
        if (width * 2 > length) {
            var closer = index + 1
            while (runs[closer].last + 1 - runs[closer].first < width) closer++
            close = runs[closer].first
        }
        result.append(line, start, opener).append(line, opener + width, close)
        start = close + width
        while (index < runs.size && runs[index].last < start) index++
    }
    return result.append(line, start, line.length).toString()
}

fun titleOf(body: String, limit: Int = 120): String {
 var line = body.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty).orEmpty()
 line = line.replace(Regex("^ {0,3}(?:#{1,6}\\s+|>\\s*|[-+*]\\s+|\\d+[.)]\\s+)"), "").replace(Regex("^\\[[ xX]\\]\\s*"), "")
 line = titleCode(titleTags(titleLinks(line)))
 line = line.replace(Regex("(?<!\\\\)\\*{1,3}([^*]+)\\*{1,3}"), "$1").replace(Regex("(?<![\\w\\\\])_{1,3}([^_]+)_{1,3}(?!\\w)"), "$1").replace(Regex("(?<!\\\\)~~(.*?)~~"), "$1").replace(Regex("\\\\([\\\\`*_{}\\[\\]()#+.!>~-])"), "$1")
 line = line.replace(Regex("&([A-Za-z][A-Za-z0-9]*|#x[0-9A-Fa-f]+|#[0-9]+);")) { match ->
  val key=match.groupValues[1]; val cp=if(key.startsWith("#x")) key.drop(2).toIntOrNull(16) else if(key.startsWith("#")) key.drop(1).toIntOrNull() else null
  if(cp!=null && Character.isValidCodePoint(cp) && cp !in 0xd800..0xdfff) String(Character.toChars(cp)) else htmlEntities[key]?:match.value
 }.trim()
 require(limit>=0)
 val points=line.codePointCount(0,line.length)
 if (points <= limit) return line
 val end=line.offsetByCodePoints(0,limit);var cut=line.substring(0,end)
 if (!Character.isWhitespace(line.codePointAt(end))) { val boundary = Regex("\\s+").findAll(cut).lastOrNull()?.range?.first; if (boundary != null) cut = cut.take(boundary) }; return cut.trimEnd()
}
fun matchExpression(query: String): String? {
 val text = fold(query); val terms = Regex("\"([^\"]*)\"|[^\\s\"]+").findAll(text).mapNotNull { match -> Regex("[\\p{L}\\p{N}]+").findAll(match.groups[1]?.value ?: match.value).map { it.value }.joinToString(" ").ifBlank { null } }.toList()
 return terms.mapIndexed { index, term -> "\"$term" + (if (index == terms.lastIndex && !text.trimEnd().endsWith('"')) "*" else "") + "\"" }.joinToString(" ").ifBlank { null }
}
data class SearchQuery(val expression:String?,val labelKeys:List<String>)
fun searchQuery(query:String):SearchQuery {
 val tokens=labels(query);var text=query
 for(token in tokens.asReversed()) text=text.replaceRange(token.start,token.end," ".repeat(token.end-token.start))
 return SearchQuery(matchExpression(text),tokens.map { it.key }.distinct())
}
fun setLabels(body:String,add:List<String>,remove:List<String> = emptyList()):String {
 (add+remove).forEach(::labelKey);var result=body
 for(path in remove) result=rewriteLabels(result,path,null)
 for(path in add) {
  val tokens=labels(result);if(tokens.any { it.key==labelKey(path) }) continue
  val end=result.trimEnd().length;val line=result.lastIndexOf('\n',end-1)+1
  val trailingLabel=tokens.any { it.end==end && it.start>=line }
  result=when {result.isEmpty()->"#$path";trailingLabel->result.substring(0,end)+" #$path"+result.substring(end);else->result+(if(result.endsWith('\n')) "" else "\n")+"#$path"}
 }
 requireLocal(add.all { label -> labels(result).any { it.key==labelKey(label) } },LocalOperationProblem.UnfinishedCodeBlock) { "Close the unfinished code block before adding labels." };return result
}
