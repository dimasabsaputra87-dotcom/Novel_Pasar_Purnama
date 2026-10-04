package com.dimas.pasarpurnama

import android.content.Context
import android.net.Uri
import org.json.JSONArray
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
    /** Last chapter index the reader was on, or -1 if the book has not been opened. */
    val progress: Int = -1,
)

/**
 * The library of one account. Everything is stored locally under `filesDir/users/<userId>/` so
 * reading works offline, and [sync] mirrors it to Supabase: the `books` table holds one row per
 * book (progress + reader state such as bookmarks), the private `novels` bucket holds imported
 * book files at `<userId>/<bookId>.json`. Conflicts resolve by last write (`updatedAt`, ms).
 */
class BookRepository(private val context: Context, private val userId: String) {

    private val userDir = userDir(context, userId)
    private val importDir = File(userDir, "books").apply { mkdirs() }
    private val libraryFile = File(userDir, "library.json")

    /** Bundled books plus imported ones; an imported book with the same id replaces the bundled one. */
    fun list(): List<Book> {
        val lib = read { it }
        val byId = LinkedHashMap<String, Book>()
        context.assets.list(ASSET_DIR).orEmpty().filter { it.endsWith(".json") }.forEach { name ->
            runCatching { parse(readAsset(name), bundled = true) }.getOrNull()?.let { byId[it.id] = it }
        }
        importDir.listFiles { f -> f.extension == "json" }.orEmpty().forEach { f ->
            runCatching { parse(f.readText(), bundled = false) }.getOrNull()?.let { byId[it.id] = it }
        }
        return byId.values
            .map { it.copy(progress = lib.books[it.id]?.progress ?: -1) }
            .sortedWith(compareBy({ naturalKey(it.title) }, { naturalKey(it.subtitle) }))
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
        edit { lib ->
            lib.deleted.remove(id)
            lib.books.getOrPut(id) { Entry() }.apply { fileDirty = true; touch() }
        }
        return parse(novel.toString(), bundled = false)
    }

    fun delete(id: String) {
        File(importDir, "$id.json").delete()
        edit { lib ->
            lib.books.remove(id)
            lib.deleted.add(id)
        }
    }

    fun saveProgress(id: String, chapter: Int) = edit { lib ->
        lib.books.getOrPut(id) { Entry() }.apply { progress = chapter; touch() }
    }

    /** Reader settings + bookmarks for one book (the JSON reader.html used to keep in localStorage). */
    fun readerState(id: String): String? = read { it.books[id]?.state }

    fun saveReaderState(id: String, json: String) = edit { lib ->
        lib.books.getOrPut(id) { Entry() }.apply { state = json; touch() }
    }

    /** True for the first account that signed in on this device: it may adopt the old localStorage bookmarks. */
    fun ownsLegacyData(): Boolean = deviceOwner(context) == userId

    /** Two-way sync with Supabase. Blocking; call off the main thread. Throws when offline. */
    fun sync(auth: AuthManager) = synchronized(syncLock) {
        val token = auth.accessToken()

        // 1. Deletions made on this device.
        for (id in read { it.deleted.toList() }) {
            Supabase.call("DELETE", "/rest/v1/books?book_id=eq.$id", token)
            runCatching { Supabase.call("DELETE", "/storage/v1/object/$BUCKET/$userId/$id.json", token) }
            edit { it.deleted.remove(id) }
        }

        // 2. Pull: take newer remote changes, download books this device does not have yet.
        val remote = JSONArray(Supabase.callJson("GET", "/rest/v1/books?select=$COLUMNS", token))
        val remoteIds = HashSet<String>()
        for (i in 0 until remote.length()) {
            val r = remote.getJSONObject(i)
            val id = r.getString("book_id")
            remoteIds += id
            val local = read { it.books[id]?.copy() }
            val file = File(importDir, "$id.json")
            val remoteFileVersion = r.optLong("file_version")
            val needsFile = r.optBoolean("has_file") && local?.fileDirty != true &&
                (!file.exists() || remoteFileVersion > (local?.fileVersion ?: 0))
            if (needsFile) {
                val bytes = Supabase.call("GET", "/storage/v1/object/authenticated/$BUCKET/$userId/$id.json", token)
                File(importDir, "$id.json.part").apply { writeBytes(bytes); renameTo(file) }
            }
            edit { lib ->
                if (id in lib.deleted) return@edit
                val e = lib.books.getOrPut(id) { Entry() }
                if (needsFile) e.fileVersion = remoteFileVersion
                val remoteUpdated = r.optLong("updated_at")
                if (!e.dirty && remoteUpdated > e.updatedAt) {
                    e.progress = r.optInt("progress", -1)
                    e.state = if (r.isNull("reader_state")) null else r.getString("reader_state")
                    e.updatedAt = remoteUpdated
                }
                e.synced = true
            }
        }

        // 3. Books synced before but now gone from the server were deleted on another device.
        edit { lib ->
            val gone = lib.books.filter { (id, e) -> id !in remoteIds && e.synced && !e.dirty && !e.fileDirty }.keys
            gone.forEach { id ->
                lib.books.remove(id)
                File(importDir, "$id.json").delete()
            }
            lib.books.forEach { (id, e) -> if (id !in remoteIds && !e.synced) e.dirty = true }
        }

        // 4. Push local changes.
        val meta = list().associateBy { it.id }
        for ((id, e) in read { lib -> lib.books.filter { it.value.dirty || it.value.fileDirty }.mapValues { it.value.copy() } }) {
            val file = File(importDir, "$id.json")
            var fileVersion = e.fileVersion
            if (e.fileDirty && file.exists()) {
                fileVersion = System.currentTimeMillis()
                Supabase.call(
                    "POST", "/storage/v1/object/$BUCKET/$userId/$id.json", token, file.readBytes(),
                    headers = mapOf("x-upsert" to "true"),
                )
            }
            val book = meta[id]
            val row = JSONObject()
                .put("user_id", userId)
                .put("book_id", id)
                .put("title", book?.title ?: JSONObject.NULL)
                .put("subtitle", book?.subtitle ?: JSONObject.NULL)
                .put("has_file", file.exists())
                .put("progress", e.progress)
                .put("reader_state", e.state ?: JSONObject.NULL)
                .put("updated_at", e.updatedAt)
                .put("file_version", fileVersion)
            Supabase.call(
                "POST", "/rest/v1/books?on_conflict=user_id,book_id", token, row.toString().toByteArray(),
                headers = mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"),
            )
            edit { lib ->
                val cur = lib.books[id] ?: return@edit
                cur.synced = true
                cur.fileVersion = fileVersion
                if (e.fileDirty) cur.fileDirty = false
                // Keep dirty if the reader changed something while we were uploading.
                if (cur.updatedAt == e.updatedAt) cur.dirty = false
            }
        }
    }

    // ---------- local library state (library.json) ----------

    /** Sync bookkeeping + reading state for one book. */
    private data class Entry(
        var progress: Int = -1,
        var state: String? = null,
        var updatedAt: Long = 0,
        var fileVersion: Long = 0,
        /** Progress/state changed locally and not pushed yet. */
        var dirty: Boolean = false,
        /** Imported book file not uploaded yet. */
        var fileDirty: Boolean = false,
        /** The server has (had) a row for this book. */
        var synced: Boolean = false,
    ) {
        fun touch() {
            updatedAt = System.currentTimeMillis()
            dirty = true
        }
    }

    private class Library(val books: MutableMap<String, Entry> = LinkedHashMap(), val deleted: MutableSet<String> = LinkedHashSet())

    private fun <T> read(block: (Library) -> T): T = synchronized(fileLock) { block(load()) }

    private fun edit(block: (Library) -> Unit) = synchronized(fileLock) {
        val lib = load()
        block(lib)
        save(lib)
    }

    private fun load(): Library {
        val o = runCatching { JSONObject(libraryFile.readText()) }.getOrNull() ?: return Library()
        val lib = Library()
        o.optJSONObject("books")?.let { books ->
            books.keys().forEach { id ->
                val b = books.getJSONObject(id)
                lib.books[id] = Entry(
                    progress = b.optInt("progress", -1),
                    state = if (b.isNull("state")) null else b.optString("state"),
                    updatedAt = b.optLong("updatedAt"),
                    fileVersion = b.optLong("fileVersion"),
                    dirty = b.optBoolean("dirty"),
                    fileDirty = b.optBoolean("fileDirty"),
                    synced = b.optBoolean("synced"),
                )
            }
        }
        o.optJSONArray("deleted")?.let { arr -> for (i in 0 until arr.length()) lib.deleted += arr.getString(i) }
        return lib
    }

    private fun save(lib: Library) {
        val books = JSONObject()
        lib.books.forEach { (id, e) ->
            books.put(id, JSONObject()
                .put("progress", e.progress)
                .put("state", e.state ?: JSONObject.NULL)
                .put("updatedAt", e.updatedAt)
                .put("fileVersion", e.fileVersion)
                .put("dirty", e.dirty)
                .put("fileDirty", e.fileDirty)
                .put("synced", e.synced))
        }
        val tmp = File(userDir, "library.json.tmp")
        tmp.writeText(JSONObject().put("books", books).put("deleted", JSONArray(lib.deleted.toList())).toString())
        tmp.renameTo(libraryFile)
    }

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
        private const val BUCKET = "novels"
        private const val COLUMNS = "book_id,has_file,progress,reader_state,updated_at,file_version"
        private val fileLock = Any()
        private val syncLock = Any()

        private fun userDir(context: Context, userId: String) = File(context.filesDir, "users/$userId").apply { mkdirs() }

        private fun deviceOwner(context: Context): String? =
            context.getSharedPreferences("device", Context.MODE_PRIVATE).getString("legacy_owner", null)

        /**
         * Before accounts existed, imported books lived in `filesDir/books` and progress in the
         * `progress` prefs. The first account that signs in on this device adopts them (they are
         * then uploaded by the next sync); later accounts start with an empty library.
         */
        fun claimLegacyData(context: Context, userId: String) {
            val device = context.getSharedPreferences("device", Context.MODE_PRIVATE)
            if (device.contains("legacy_owner")) return
            val repo = BookRepository(context, userId)
            val legacyDir = File(context.filesDir, "books")
            legacyDir.listFiles { f -> f.extension == "json" }.orEmpty().forEach { f ->
                if (f.renameTo(File(repo.importDir, f.name))) {
                    repo.edit { lib -> lib.books.getOrPut(f.nameWithoutExtension) { Entry() }.apply { fileDirty = true; touch() } }
                }
            }
            val progress = context.getSharedPreferences("progress", Context.MODE_PRIVATE)
            progress.all.forEach { (id, v) -> if (v is Int) repo.saveProgress(id, v) }
            progress.edit().clear().apply()
            device.edit().putString("legacy_owner", userId).apply()
        }

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
