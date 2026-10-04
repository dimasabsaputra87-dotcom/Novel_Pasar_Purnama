package com.dimas.pasarpurnama

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val repo = BookRepository(this)
        setContent {
            MaterialTheme(colorScheme = LibraryColors) {
                LibraryScreen(repo)
            }
        }
    }
}

private val Gold = Color(0xFFC9A55E)
private val LibraryColors = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF241C0E),
    background = Color(0xFF15171B),
    surface = Color(0xFF15171B),
    onBackground = Color(0xFFD7D3C9),
    onSurface = Color(0xFFD7D3C9),
    onSurfaceVariant = Color(0xFF8B877E),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(repo: BookRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var books by remember { mutableStateOf<List<Book>?>(null) }
    var toDelete by remember { mutableStateOf<Book?>(null) }
    var showHelp by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        books = withContext(Dispatchers.IO) { repo.list() }
    }

    // Reload when coming back from the reader so the progress bars update.
    val readerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refresh++
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repo.import(uri) } }
            result.onSuccess {
                Toast.makeText(context, "Ditambahkan: ${it.title} ${it.subtitle}".trim(), Toast.LENGTH_SHORT).show()
                refresh++
            }.onFailure {
                Toast.makeText(context, "Gagal menambah buku: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Perpustakaan", fontFamily = FontFamily.Serif) },
                actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.Filled.Info, contentDescription = "Cara menambah buku")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { importLauncher.launch(arrayOf("text/html", "application/json", "text/plain", "application/octet-stream")) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Tambah buku") },
                containerColor = Gold,
                contentColor = Color(0xFF241C0E),
            )
        },
    ) { padding ->
        val list = books
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 96.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(list, key = { _, b -> b.id }) { index, book ->
                    BookCard(
                        book = book,
                        palette = index,
                        progress = repo.progress(book.id),
                        onOpen = {
                            readerLauncher.launch(
                                Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
                            )
                        },
                        onLongPress = { if (!book.bundled) toDelete = book },
                    )
                }
            }
        }
    }

    toDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Hapus buku?") },
            text = { Text("\"${book.title} ${book.subtitle}\" akan dihapus dari perpustakaan beserta posisi bacanya.") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) { repo.delete(book.id) }
                        refresh++
                    }
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Batal") } },
        )
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("Menambah buku") },
            text = {
                Text(
                    "Tekan \"Tambah buku\" lalu pilih file buku:\n\n" +
                        "• File .html hasil build_novel.py (seperti Pasar Purnama Jilid 1), atau\n" +
                        "• File .json berisi data NOVEL.\n\n" +
                        "Buku dengan judul dan subjudul (mis. \"Jilid 2\") yang sama akan diperbarui, bukan diduplikasi.\n\n" +
                        "Tekan lama sampul buku tambahan untuk menghapusnya."
                )
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Oke") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(book: Book, palette: Int, progress: Int, onOpen: () -> Unit, onLongPress: () -> Unit) {
    Column(Modifier.combinedClickable(onClick = onOpen, onLongClick = onLongPress)) {
        Cover(book, palette)
        Spacer(Modifier.height(10.dp))
        Text(
            book.title, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Serif,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (book.subtitle.isNotEmpty()) {
            Text(book.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        val status = when {
            progress < 0 -> "Belum dibaca · ${book.chapterCount} bab"
            else -> "Bab ${progress + 1} dari ${book.chapterCount}"
        }
        Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (progress >= 0 && book.chapterCount > 0) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { (progress + 1f) / book.chapterCount },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = Gold,
                trackColor = Color.White.copy(alpha = 0.08f),
                drawStopIndicator = {},
            )
        }
    }
}

/** Cover art: the book's own image if it is an embedded data: URI, otherwise a generated cover. */
@Composable
private fun Cover(book: Book, palette: Int) {
    val shape = RoundedCornerShape(6.dp)
    val image by produceState<ImageBitmap?>(null, book.coverImage) {
        value = withContext(Dispatchers.IO) { decodeDataUri(book.coverImage)?.asImageBitmap() }
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(2f / 3f).shadow(8.dp, shape).clip(shape),
    ) {
        val img = image
        if (img != null) {
            Image(img, contentDescription = book.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            GeneratedCover(book, palette)
        }
    }
}

private val coverPalettes = listOf(
    Color(0xFF1F2A44) to Color(0xFF0E1220), // night blue
    Color(0xFF3B2A1E) to Color(0xFF17110C), // market brown
    Color(0xFF213A33) to Color(0xFF0C1714), // deep green
    Color(0xFF3E1F2B) to Color(0xFF180B11), // plum
)

@Composable
private fun GeneratedCover(book: Book, palette: Int) {
    val (top, bottom) = coverPalettes[palette % coverPalettes.size]
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom))).padding(14.dp),
    ) {
        // full moon
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = 18.dp).size(46.dp)
                .clip(RoundedCornerShape(50)).background(Gold.copy(alpha = 0.92f))
        )
        Column(Modifier.align(Alignment.Center).padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                book.title.uppercase(), color = Color(0xFFF3E9D2), fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 21.sp, letterSpacing = 1.5.sp,
                textAlign = TextAlign.Center,
            )
            if (book.subtitle.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(book.subtitle, color = Gold, fontFamily = FontFamily.Serif, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
        if (book.author.isNotEmpty()) {
            Text(
                book.author, color = Color(0xFFCBC7BD), fontFamily = FontFamily.Serif, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomCenter), textAlign = TextAlign.Center,
            )
        }
    }
}

private fun decodeDataUri(uri: String?): Bitmap? {
    if (uri == null || !uri.startsWith("data:image")) return null
    return runCatching {
        val bytes = Base64.decode(uri.substringAfter(","), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}
