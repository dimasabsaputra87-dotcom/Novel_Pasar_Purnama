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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
        val auth = AuthManager(this)
        setContent {
            MaterialTheme(colorScheme = LibraryColors) {
                AppRoot(auth)
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

/** Login screen until there is a session, then the signed-in account's own library. */
@Composable
private fun AppRoot(auth: AuthManager) {
    val context = LocalContext.current
    var user by remember { mutableStateOf(auth.user) }
    val u = user
    if (u == null) {
        LoginScreen(auth, onSignedIn = { user = it })
    } else {
        val repo = remember(u.userId) {
            BookRepository.claimLegacyData(context, u.userId)
            BookRepository(context, u.userId)
        }
        LibraryScreen(repo, auth, u, onSignedOut = { user = null })
    }
}

@Composable
private fun LoginScreen(auth: AuthManager, onSignedIn: (UserSession) -> Unit) {
    val scope = rememberCoroutineScope()
    var register by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    fun submit() {
        val mail = email.trim()
        val problem = when {
            !mail.contains('@') || !mail.contains('.') -> "Masukkan alamat email yang benar."
            password.length < 6 -> "Password minimal 6 karakter."
            register && password != confirm -> "Konfirmasi password tidak sama."
            else -> null
        }
        if (problem != null) {
            message = problem; isError = true
            return
        }
        busy = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { if (register) auth.signUp(mail, password) else auth.signIn(mail, password) }
            }
            busy = false
            result.onSuccess { session ->
                if (session != null) onSignedIn(session) else {
                    register = false
                    confirm = ""
                    isError = false
                    message = "Akun dibuat. Buka email konfirmasi yang dikirim ke $mail, lalu masuk di sini."
                }
            }.onFailure {
                isError = true
                message = Supabase.friendlyError(it)
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).widthIn(max = 420.dp).fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(50)).background(Gold.copy(alpha = 0.92f)))
            Spacer(Modifier.height(20.dp))
            Text("Pasar Purnama", fontFamily = FontFamily.Serif, fontSize = 28.sp, color = Color(0xFFF3E9D2))
            Spacer(Modifier.height(6.dp))
            Text(
                if (register) "Buat akun untuk perpustakaanmu sendiri" else "Masuk ke perpustakaanmu",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(
                value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (register) ImeAction.Next else ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (!busy) submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (register) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = confirm, onValueChange = { confirm = it }, label = { Text("Ulangi password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!busy) submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            message?.let {
                Spacer(Modifier.height(14.dp))
                Text(
                    it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    color = if (isError) Color(0xFFE8897D) else Gold,
                )
            }
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = { submit() }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF241C0E))
                else Text(if (register) "Daftar" else "Masuk")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { register = !register; message = null }, enabled = !busy) {
                Text(if (register) "Sudah punya akun? Masuk" else "Belum punya akun? Daftar")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(repo: BookRepository, auth: AuthManager, user: UserSession, onSignedOut: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var books by remember { mutableStateOf<List<Book>?>(null) }
    var toDelete by remember { mutableStateOf<Book?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var manualSync by remember { mutableStateOf(false) }

    // Show the local copy right away, then sync with the account and show the merged result.
    LaunchedEffect(refresh) {
        books = withContext(Dispatchers.IO) { repo.list() }
        syncing = true
        val result = withContext(Dispatchers.IO) { runCatching { repo.sync(auth) } }
        syncing = false
        if (auth.user == null) {
            Toast.makeText(context, "Sesi berakhir, silakan masuk lagi.", Toast.LENGTH_LONG).show()
            onSignedOut()
            return@LaunchedEffect
        }
        result.onSuccess {
            books = withContext(Dispatchers.IO) { repo.list() }
            if (manualSync) Toast.makeText(context, "Perpustakaan sudah tersinkron", Toast.LENGTH_SHORT).show()
        }.onFailure {
            if (manualSync) Toast.makeText(context, "Belum tersinkron: ${Supabase.friendlyError(it)}", Toast.LENGTH_LONG).show()
        }
        manualSync = false
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
                    IconButton(onClick = { manualSync = true; refresh++ }, enabled = !syncing) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Sinkronkan")
                    }
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.Filled.Info, contentDescription = "Cara menambah buku")
                    }
                    Box {
                        IconButton(onClick = { showAccount = true }) {
                            Icon(Icons.Filled.AccountCircle, contentDescription = "Akun")
                        }
                        DropdownMenu(expanded = showAccount, onDismissRequest = { showAccount = false }) {
                            DropdownMenuItem(text = { Text(user.email) }, onClick = {}, enabled = false)
                            DropdownMenuItem(
                                text = { Text("Keluar") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null) },
                                onClick = { showAccount = false; confirmSignOut = true },
                            )
                        }
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
                        progress = book.progress,
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
        if (syncing) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(top = padding.calculateTopPadding()).height(2.dp),
                color = Gold, trackColor = Color.Transparent,
            )
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

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Keluar dari akun?") },
            text = { Text("Buku dan posisi bacamu tetap tersimpan di akun ${user.email}. Masuk lagi kapan saja untuk melanjutkan.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { repo.sync(auth) }
                            auth.signOut()
                        }
                        onSignedOut()
                    }
                }) { Text("Keluar") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Batal") } },
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
                        "Tekan lama sampul buku tambahan untuk menghapusnya.\n\n" +
                        "Buku, posisi baca, dan markah tersimpan di akunmu, jadi ikut muncul saat masuk di perangkat lain."
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
