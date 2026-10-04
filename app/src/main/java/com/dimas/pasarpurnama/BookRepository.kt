package com.dimas.pasarpurnama

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/**
 * One book in the library. The content is a "NOVEL" JSON object, the same shape that
 * build_novel.py injects into the reader HTML:
 *   { id, title, author, cover:{image, subtitle}, chapters:[{title, html}] }
 */
data class Book(
    val id: String,
    val title: String,
    val subtitle: String,
    val author: String,
    val chapterCount: Int,
    val coverImage: String?,
    /** true = shipped inside the APK (assets/books), false = imported by the user. */
    val bundled: Boolean,
)

class BookRepository(private val context: Context) {

    private val importDir = File(context.filesDir, "books").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)

    /** Bundled books plus imported ones; an imported book with the same id replaces the bundled one. */
    fun list(): List<Book> {
        val byId = LinkedHashMap<String, Book>()
        context.assets.list(ASSET_DIR).orEmpty().filter { it.endsWith(".json") }.forEach { name ->
            runCatching { parse(readAsset(name), bundled = true) }.getOrNull()?.let { byId[it.id] = it }
        }
        importDir.listFiles { f -> f.extension == "json" }.orEmpty().forEach { f ->
            runCatching { parse(f.readText(), bundled = false) }.getOrNull()?.let { byId[it.id] = it }
        }
        return byId.values.sortedWith(compareBy({ naturalKey(it.title) }, { naturalKey(it.subtitle) }))
    }

    /** Full NOVEL JSON for the reader, with `id` set to the library id (keeps bookmarks per book). */
    fun loadNovelJson(id: String): String? {
        val imported = File(importDir, "$id.json")
        val raw = if (imported.exists()) imported.readText() else {
            context.assets.list(ASSET_DIR).orEmpty().asSequence()
                .filter { it.endsWith(".json") }
                .map { readAsset(it) }
                .firstOrNull { runCatching { libraryId(JSONObject(it)) == id }.getOrDefault(false) }
        } ?: return null
        return JSONObject(raw).put("id", id).toString()
    }

    /**
     * Imports a book from a .json file (NOVEL object) or an .html file produced by
     * build_novel.py (the line `const NOVEL = {...};`). Re-importing the same title +
     * subtitle updates the existing book. Returns the imported book.
     */
    fun import(uri: Uri): Book {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("File tidak bisa dibuka")
        val json = extractNovelJson(text)
        val novel = JSONObject(json)
        val chapters = novel.optJSONArray("chapters")
        require(chapters != null && chapters.length() > 0) { "File ini tidak berisi bab novel" }
        val id = libraryId(novel)
        novel.put("id", id)
        File(importDir, "$id.json").writeText(novel.toString())
        return parse(novel.toString(), bundled = false)
    }

    fun delete(id: String) {
        File(importDir, "$id.json").delete()
        prefs.edit().remove(id).apply()
    }

    /** Last chapter index the reader was on, or -1 if the book has not been opened. */
    fun progress(id: String): Int = prefs.getInt(id, -1)

    fun saveProgress(id: String, chapter: Int) = prefs.edit().putInt(id, chapter).apply()

    private fun readAsset(name: String) =
        context.assets.open("$ASSET_DIR/$name").use { it.readBytes().toString(Charsets.UTF_8) }

    private fun parse(json: String, bundled: Boolean): Book {
        val o = JSONObject(json)
        val cover = o.optJSONObject("cover")
        return Book(
            id = libraryId(o),
            title = o.optString("title").ifBlank { "Tanpa Judul" },
            subtitle = cover?.optStringOrNull("subtitle").orEmpty(),
            author = o.optStringOrNull("author").orEmpty(),
            chapterCount = o.optJSONArray("chapters")?.length() ?: 0,
            coverImage = cover?.optStringOrNull("image"),
            bundled = bundled,
        )
    }

    companion object {
        private const val ASSET_DIR = "books"

        /** Library id = slug of title + subtitle, so "Pasar Purnama / Jilid 2" never collides with Jilid 1. */
        fun libraryId(novel: JSONObject): String {
            val sub = novel.optJSONObject("cover")?.optStringOrNull("subtitle").orEmpty()
            val base = listOf(novel.optString("title"), sub).filter { it.isNotBlank() }.joinToString(" ")
            return slug(base).ifEmpty { slug(novel.optString("id")).ifEmpty { "buku" } }
        }

        fun extractNovelJson(text: String): String {
            val trimmed = text.trimStart('﻿', ' ', '\n', '\r', '\t')
            if (trimmed.startsWith("{")) return trimmed
            val marker = Regex("""(?:const|var|let)\s+NOVEL\s*=\s*""").find(text)
                ?: error("Tidak menemukan data novel (const NOVEL = ...) di file ini")
            val start = marker.range.last + 1
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            return text.substring(start, end).trim().removeSuffix(";")
        }

        private fun slug(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "")
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')

        /** Pads digit runs so "Jilid 10" sorts after "Jilid 2". */
        private fun naturalKey(s: String) = s.lowercase().replace(Regex("\\d+")) { it.value.padStart(6, '0') }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    }
}
