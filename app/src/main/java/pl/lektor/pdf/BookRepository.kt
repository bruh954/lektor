package pl.lektor.pdf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.security.MessageDigest

class NoTextException : Exception("No text in PDF")

/**
 * Wyciąga tekst z PDF i zapisuje go w pamięci podręcznej aplikacji,
 * więc ponowne otwarcie książki jest natychmiastowe (i działa nawet bez dostępu do pliku).
 */
object BookRepository {
    private const val PAGE_SEP = '\u000C'

    fun idFor(uri: Uri): String {
        val md = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    private fun cacheFile(ctx: Context, id: String): File =
        File(File(ctx.filesDir, "books").apply { mkdirs() }, "$id.txt")

    fun displayName(ctx: Context, uri: Uri): String {
        var name: String? = null
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) name = c.getString(0) }
        }
        val n = name ?: uri.lastPathSegment ?: "PDF"
        return n.substringAfterLast('/').removeSuffix(".pdf").removeSuffix(".PDF")
    }

    /** Wczytuje książkę z pamięci podręcznej (null, jeśli jej tam nie ma). */
    fun loadCached(ctx: Context, id: String, title: String): Book? {
        val f = cacheFile(ctx, id)
        if (!f.exists()) return null
        val pages = f.readText().split(PAGE_SEP)
        return runCatching { build(id, title, pages) }.getOrNull()
    }

    /** Otwiera PDF (z cache, a jeśli go nie ma – wyciąga tekst). Wywoływać poza wątkiem UI. */
    fun load(ctx: Context, uri: Uri, title: String, onProgress: (Int) -> Unit): Book {
        val id = idFor(uri)
        loadCached(ctx, id, title)?.let { return it }
        val pages = extract(ctx, uri, onProgress)
        val book = build(id, title, pages)
        cacheFile(ctx, id).writeText(pages.joinToString(PAGE_SEP.toString()))
        return book
    }

    private fun extract(ctx: Context, uri: Uri, onProgress: (Int) -> Unit): List<String> {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        val input = ctx.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open $uri")
        input.use { stream ->
            PDDocument.load(stream, MemoryUsageSetting.setupMixed(32L * 1024 * 1024)).use { doc ->
                if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
                val n = doc.numberOfPages
                val stripper = PDFTextStripper().apply {
                    sortByPosition = true
                    paragraphEnd = "\n\n"
                }
                val pages = ArrayList<String>(n)
                for (p in 1..n) {
                    stripper.startPage = p
                    stripper.endPage = p
                    pages += runCatching { stripper.getText(doc) }.getOrDefault("")
                    onProgress(p * 100 / n.coerceAtLeast(1))
                }
                return pages
            }
        }
    }

    private fun build(id: String, title: String, pages: List<String>): Book {
        val sample = StringBuilder()
        for (p in pages) {
            if (sample.length > 20_000) break
            sample.append(p).append(' ')
        }
        val locale = LanguageDetector.detect(sample.toString())
        val segments = TextChunker.buildSegments(pages, locale)
        if (segments.isEmpty()) throw NoTextException()
        return Book(id, title, pages.size, segments, locale)
    }
}
