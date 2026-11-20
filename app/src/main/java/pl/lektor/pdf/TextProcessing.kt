package pl.lektor.pdf

import java.text.BreakIterator
import java.util.Locale

/** Czyści tekst wyciągnięty z PDF i dzieli go na krótkie fragmenty do czytania. */
object TextChunker {
    private const val MAX_LEN = 300   // dłuższe zdania tniemy (szybsza reakcja na zmianę tempa)
    private const val MIN_LEN = 25    // bardzo krótkie łączymy z następnym (płynniejsza mowa)

    private val hyphenBreak = Regex("(\\p{L})[-‐­]\\n\\s*(\\p{Ll})")
    private val blankLines = Regex("\\n\\s*\\n")
    private val whitespace = Regex("\\s+")
    private val pageNumber = Regex("^[\\d\\s\\-–—/.]{1,9}$")
    private val hasWord = Regex("[\\p{L}\\p{N}]")
    private val terminal = charArrayOf('.', '!', '?', '…', ':', ';', '"', '»', '”', ')')

    fun buildSegments(pages: List<String>, locale: Locale): List<Segment> {
        val out = ArrayList<Segment>()
        pages.forEachIndexed { pageIdx, raw ->
            for (para in paragraphs(raw)) {
                val sentences = sentences(para, locale)
                sentences.forEachIndexed { i, s ->
                    out += Segment(s, pageIdx, i == sentences.lastIndex)
                }
            }
        }
        return out
    }

    private fun paragraphs(raw: String): List<String> {
        var t = raw.replace("\r\n", "\n").replace('\r', '\n').replace("­\n", "")
        t = hyphenBreak.replace(t, "$1$2")
        val parts = t.split(blankLines)
            .map { it.replace(whitespace, " ").trim() }
            .filter { it.isNotEmpty() }
        // Scal "akapity", które w rzeczywistości są przerwanym zdaniem
        val merged = ArrayList<String>()
        for (p in parts) {
            val last = merged.lastOrNull()
            val lastChar = last?.trimEnd()?.lastOrNull()
            if (last != null && (lastChar == null || lastChar !in terminal)) {
                merged[merged.lastIndex] = "$last $p"
            } else merged += p
        }
        return merged
    }

    private fun sentences(paragraph: String, locale: Locale): List<String> {
        val bi = BreakIterator.getSentenceInstance(locale)
        bi.setText(paragraph)
        val raw = ArrayList<String>()
        var start = bi.first()
        var end = bi.next()
        while (end != BreakIterator.DONE) {
            val s = paragraph.substring(start, end).trim()
            if (s.isNotEmpty()) raw += s
            start = end
            end = bi.next()
        }
        // Tnij zbyt długie
        val cut = ArrayList<String>()
        for (s in raw) cut += hardSplit(s)
        // Łącz zbyt krótkie i usuń śmieci (numery stron, same symbole)
        val result = ArrayList<String>()
        var buf = ""
        for (s in cut) {
            if (!hasWord.containsMatchIn(s) || pageNumber.matches(s)) continue
            buf = if (buf.isEmpty()) s else "$buf $s"
            if (buf.length >= MIN_LEN) {
                result += buf; buf = ""
            }
        }
        if (buf.isNotEmpty()) {
            if (result.isNotEmpty() && result.last().length + buf.length < MAX_LEN) {
                result[result.lastIndex] = result.last() + " " + buf
            } else result += buf
        }
        return result
    }

    private fun hardSplit(sentence: String): List<String> {
        if (sentence.length <= MAX_LEN) return listOf(sentence)
        val parts = ArrayList<String>()
        var s = sentence
        while (s.length > MAX_LEN) {
            val window = s.substring(0, MAX_LEN)
            var idx = window.indexOfLast { it == ',' || it == ';' || it == ':' || it == '—' || it == '–' }
            if (idx < MAX_LEN / 3) idx = window.lastIndexOf(' ')
            if (idx < MAX_LEN / 3) idx = MAX_LEN - 1
            parts += s.substring(0, idx + 1).trim()
            s = s.substring(idx + 1).trim()
        }
        if (s.isNotEmpty()) parts += s
        return parts
    }
}

/** Proste rozpoznawanie języka po charakterystycznych literach. */
object LanguageDetector {
    fun detect(sample: String): Locale {
        var letters = 0
        var cyr = 0
        var ukr = 0
        val scores = HashMap<String, Int>()
        fun add(k: String) { scores[k] = (scores[k] ?: 0) + 1 }
        for (ch in sample) {
            if (!ch.isLetter()) continue
            letters++
            val c = ch.lowercaseChar()
            if (c in 'Ѐ'..'ӿ') {
                cyr++
                if (c == 'і' || c == 'ї' || c == 'є' || c == 'ґ') ukr++
                continue
            }
            when (c) {
                'ą', 'ć', 'ę', 'ł', 'ń', 'ś', 'ź', 'ż' -> add("pl-PL")
                'ř', 'ě', 'ů', 'ť', 'ď', 'ň' -> add("cs-CZ")
                'ä', 'ö', 'ü', 'ß' -> add("de-DE")
                'è', 'ê', 'à', 'ç', 'ù', 'â', 'î', 'œ', 'ë' -> add("fr-FR")
                'ñ', 'í', 'ú', 'á' -> add("es-ES")
            }
        }
        if (letters == 0) return Locale.getDefault()
        if (cyr * 100 / letters > 30) {
            return if (ukr * 1000 / cyr.coerceAtLeast(1) > 5) Locale.forLanguageTag("uk-UA")
            else Locale.forLanguageTag("ru-RU")
        }
        val best = scores.maxByOrNull { it.value }
        if (best != null && best.value * 1000 / letters >= 3) return Locale.forLanguageTag(best.key)
        return Locale.forLanguageTag("en-US")
    }
}
