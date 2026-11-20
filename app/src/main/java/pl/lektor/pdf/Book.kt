package pl.lektor.pdf

import java.util.Locale

/** Jeden fragment do przeczytania (zwykle jedno zdanie). */
data class Segment(
    val text: String,
    /** Numer strony PDF liczony od 0. */
    val page: Int,
    /** Czy po tym fragmencie kończy się akapit (tylko do wyświetlania). */
    val paragraphEnd: Boolean
)

class Book(
    val id: String,
    val title: String,
    val pageCount: Int,
    val segments: List<Segment>,
    /** Język wykryty automatycznie z treści. */
    val detectedLocale: Locale
) {
    /** Dla każdej strony: indeks pierwszego fragmentu na tej stronie lub dalej. */
    private val firstOfPage: IntArray = IntArray(pageCount + 1).also { arr ->
        var s = 0
        for (p in 0..pageCount) {
            while (s < segments.size && segments[s].page < p) s++
            arr[p] = s
        }
    }

    fun firstSegmentOfPage(page: Int): Int =
        firstOfPage[page.coerceIn(0, pageCount)].coerceAtMost((segments.size - 1).coerceAtLeast(0))
}
