package com.example.duplicatesongs

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ---------- Data model ----------

data class Song(
    val id: Long,
    val title: String,
    val path: String,
    val durationSec: Long,
    val sizeBytes: Long,
    val norm: String
) {
    val uri: Uri get() = ContentUris.withAppendedId(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
    )
}

// ---------- Text normalization & similarity ----------

private val NOISE_WORDS = listOf(
    "official", "video", "audio", "lyrics", "lyric", "remix", "remaster",
    "remastered", "version", "radio edit", "clean", "explicit", "ft",
    "feat", "featuring", "hd", "hq", "live", "mv", "full"
)

fun normalizeTitle(raw: String): String {
    var s = raw.substringBeforeLast('.', raw) // drop extension if present
    s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    s = s.lowercase()
    s = s.replace(Regex("\\([^)]*\\)|\\[[^]]*]|\\{[^}]*}"), " ")
    for (w in NOISE_WORDS) {
        s = s.replace(Regex("\\b$w\\b"), " ")
    }
    s = s.replace(Regex("[^a-z0-9\\u0590-\\u05ff]+"), " ").trim().replace(Regex("\\s+"), " ")
    return s
}

fun levenshtein(a: String, b: String): Int {
    val m = a.length; val n = b.length
    if (m == 0) return n
    if (n == 0) return m
    var prev = IntArray(n + 1) { it }
    for (i in 1..m) {
        val cur = IntArray(n + 1)
        cur[0] = i
        for (j in 1..n) {
            cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1]
            else 1 + minOf(prev[j - 1], prev[j], cur[j - 1])
        }
        prev = cur
    }
    return prev[n]
}

fun similarity(a: String, b: String): Double {
    if (a.isEmpty() && b.isEmpty()) return 1.0
    val dist = levenshtein(a, b)
    return 1.0 - dist.toDouble() / max(max(a.length, b.length), 1)
}

// ---------- Union-Find for transitive grouping ----------

class UnionFind(n: Int) {
    val parent = IntArray(n) { it }
    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) r = parent[r]
        var c = x
        while (parent[c] != c) { val next = parent[c]; parent[c] = r; c = next }
        return r
    }
    fun union(a: Int, b: Int) {
        val ra = find(a); val rb = find(b)
        if (ra != rb) parent[ra] = rb
    }
}

// ---------- MediaStore query ----------

fun loadSongs(context: android.content.Context): List<Song> {
    val songs = mutableListOf<Song>()
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.SIZE
    )
    val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
    context.contentResolver.query(collection, projection, selection, null, null)?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
        val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val name = cursor.getString(nameCol) ?: continue
            val path = cursor.getString(dataCol) ?: name
            val durMs = cursor.getLong(durCol)
            val size = cursor.getLong(sizeCol)
            songs.add(Song(id, name, path, durMs / 1000, size, normalizeTitle(name)))
        }
    }
    return songs
}

fun findDuplicateGroups(
    songs: List<Song>,
    simThreshold: Double,
    durationToleranceSec: Long
): List<List<Song>> {
    val uf = UnionFind(songs.size)
    // Compare only songs with near durations: sort by duration and slide a window,
    // so we avoid the full O(n^2) comparison on large libraries.
    val order = songs.indices.sortedBy { songs[it].durationSec }
    for (p in order.indices) {
        val i = order[p]
        for (q in p + 1 until order.size) {
            val j = order[q]
            if (songs[j].durationSec - songs[i].durationSec > durationToleranceSec) break
            if (similarity(songs[i].norm, songs[j].norm) >= simThreshold) uf.union(i, j)
        }
    }
    val groups = LinkedHashMap<Int, MutableList<Song>>()
    songs.forEachIndexed { idx, song ->
        groups.getOrPut(uf.find(idx)) { mutableListOf() }.add(song)
    }
    return groups.values.filter { it.size > 1 }
}

fun formatDuration(sec: Long): String {
    val m = sec / 60; val s = sec % 60
    return "%d:%02d".format(m, s)
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

/**
 * For each group, marks all songs EXCEPT the "best" one (largest file = usually best
 * quality) for deletion. Returns the set of song ids to delete.
 */
fun autoSelectDuplicates(groups: List<List<Song>>): Set<Long> {
    val toDelete = HashSet<Long>()
    for (group in groups) {
        val keep = group.maxByOrNull { it.sizeBytes } ?: continue
        for (song in group) if (song.id != keep.id) toDelete.add(song.id)
    }
    return toDelete
}

// ---------- Activity ----------

// ---------- Crash log (shows the real error next launch instead of a silent close) ----------

object CrashLog {
    private const val FILE = "last_crash.txt"
    fun install(ctx: android.content.Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val text = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                    "${Build.MANUFACTURER} ${Build.MODEL}\nThread: ${t.name}\n\n" +
                    android.util.Log.getStackTraceString(e)
                File(app.filesDir, FILE).writeText(text)
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }
    fun read(ctx: android.content.Context): String? =
        File(ctx.filesDir, FILE).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
    fun clear(ctx: android.content.Context) { runCatching { File(ctx.filesDir, FILE).delete() } }
}

/** Resource id of the developer photo (exists only in the credited flavor). */
fun devPhotoResId(ctx: android.content.Context): Int =
    ctx.resources.getIdentifier("dev_photo", "drawable", ctx.packageName)

/** Posts a persistent "built by" notification with the photo (credited flavor only). */
fun showCreditNotification(ctx: android.content.Context) {
    try {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java) ?: return
        val channelId = "credit"
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(channelId, "קרדיט", android.app.NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val resId = devPhotoResId(ctx)
        val photo = if (resId != 0) android.graphics.BitmapFactory.decodeResource(ctx.resources, resId) else null
        val builder = androidx.core.app.NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.star_big_on)
            .setContentTitle("נבנה על ידי אורי שרגא יעקבסון האלוף")
            .setContentText("שירים כפולים")
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
        if (photo != null) {
            builder.setLargeIcon(photo)
            builder.setStyle(
                androidx.core.app.NotificationCompat.BigPictureStyle()
                    .bigPicture(photo)
                    .bigLargeIcon(null as android.graphics.Bitmap?)
            )
        }
        nm.notify(1001, builder.build())
    } catch (_: Exception) {}
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(this)
        super.onCreate(savedInstanceState)
        val crash = CrashLog.read(this)
        setContent { AppRoot(lastCrash = crash) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(lastCrash: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember {
        mutableStateOf(hasAudioPermission(context))
    }
    var simThreshold by remember { mutableStateOf(0.8f) }
    var durTolerance by remember { mutableStateOf(3f) }
    var scanning by remember { mutableStateOf(false) }
    var allSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var groups by remember { mutableStateOf<List<List<Song>>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pendingDeleteIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showCrash by remember { mutableStateOf(lastCrash != null) }

    // Credited flavor: request notification permission (Android 13+) and post the credit notice.
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) showCreditNotification(context) }
    LaunchedEffect(Unit) {
        if (BuildConfig.SHOW_CREDIT) {
            if (Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                showCreditNotification(context)
            }
        }
    }

    if (showCrash && lastCrash != null) {
        AlertDialog(
            onDismissRequest = { showCrash = false; CrashLog.clear(context) },
            title = { Text("האפליקציה נסגרה בגלל שגיאה") },
            text = { Text(lastCrash.take(2000)) },
            confirmButton = { TextButton(onClick = { showCrash = false; CrashLog.clear(context) }) { Text("סגור") } }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    fun removeIds(ids: Set<Long>) {
        allSongs = allSongs.filterNot { it.id in ids }
        groups = groups.map { g -> g.filterNot { it.id in ids } }.filter { it.size > 1 }
        selected = selected - ids
    }

    // Handles the "confirm delete" system dialog required on Android 10+ (API 29+).
    val deleteRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) removeIds(pendingDeleteIds)
        pendingDeleteIds = emptySet()
    }

    fun requestPermission() {
        val perm = if (Build.VERSION.SDK_INT >= 33)
            android.Manifest.permission.READ_MEDIA_AUDIO
        else
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        permissionLauncher.launch(perm)
    }

    fun runScan() {
        scanning = true
        errorMsg = null
        scope.launch {
            try {
                val songs = withContext(Dispatchers.IO) { loadSongs(context) }
                val result = withContext(Dispatchers.Default) {
                    findDuplicateGroups(songs, simThreshold.toDouble(), durTolerance.toLong())
                }
                allSongs = songs
                groups = result
                selected = emptySet()
            } catch (e: Throwable) {
                errorMsg = "שגיאה בסריקה: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                scanning = false
            }
        }
    }

    /** Deletes the given song ids. On Android 10+ this shows one system confirmation for all. */
    fun deleteIds(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val uris = ids.map { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    if (Build.VERSION.SDK_INT >= 30) {
                        val pi = MediaStore.createDeleteRequest(context.contentResolver, uris)
                        withContext(Dispatchers.Main) {
                            pendingDeleteIds = ids
                            deleteRequestLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                        }
                    } else {
                        // Older Android: try direct delete per file; may prompt on API 29.
                        var deleted = false
                        for (uri in uris) {
                            try { context.contentResolver.delete(uri, null, null); deleted = true }
                            catch (e: SecurityException) {
                                if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException) {
                                    withContext(Dispatchers.Main) {
                                        pendingDeleteIds = ids
                                        deleteRequestLauncher.launch(
                                            IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                                        )
                                    }
                                    return@withContext
                                }
                            }
                        }
                        if (deleted) withContext(Dispatchers.Main) { removeIds(ids) }
                    }
                } catch (e: Exception) { /* ignore */ }
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("מציאת שירים כפולים") })
    }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            if (BuildConfig.SHOW_CREDIT) {
                val photoId = remember { devPhotoResId(context) }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (photoId != 0) {
                        Image(
                            painter = painterResource(id = photoId),
                            contentDescription = "אורי שרגא יעקבסון",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(160.dp)
                                .clip(CircleShape)
                                .border(4.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(
                        "נבנה על ידי",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "אורי שרגא יעקבסון האלוף",
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (!hasPermission) {
                Text("כדי לסרוק את השירים במכשיר, צריך לאשר גישה לקבצי מדיה.")
                Spacer(Modifier.height(12.dp))
                Button(onClick = { requestPermission() }) { Text("אישור הרשאה") }
                return@Column
            }

            Text("סף דמיון בשם: ${(simThreshold * 100).toInt()}%")
            Slider(value = simThreshold, onValueChange = { simThreshold = it }, valueRange = 0.5f..1f)

            Text("טווח סטייה באורך: ${durTolerance.toInt()} שניות")
            Slider(value = durTolerance, onValueChange = { durTolerance = it }, valueRange = 0f..15f)

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { runScan() }, enabled = !scanning) {
                    Text(if (scanning) "סורק..." else "סרוק את המכשיר")
                }
                Spacer(Modifier.width(12.dp))
                if (allSongs.isNotEmpty()) {
                    Text("${allSongs.size} שירים · ${groups.size} קבוצות", fontSize = 13.sp)
                }
            }

            if (groups.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { selected = autoSelectDuplicates(groups) }) {
                        Text("סמן כפולים אוטומטית")
                    }
                    Spacer(Modifier.width(8.dp))
                    if (selected.isNotEmpty()) {
                        TextButton(onClick = { selected = emptySet() }) { Text("נקה") }
                    }
                }
                Button(
                    onClick = { deleteIds(selected) },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) { Text("מחק מסומנים (${selected.size})") }
            }

            Spacer(Modifier.height(12.dp))

            errorMsg?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 6.dp))
            }

            if (groups.isEmpty() && !scanning && allSongs.isNotEmpty() && errorMsg == null) {
                Text("לא נמצאו כפילויות בקריטריונים הנוכחיים 🎉")
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(groups) { group ->
                    Card(modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("קבוצה של ${group.size} שירים דומים", fontWeight = FontWeight.Bold)
                            val best = group.maxByOrNull { it.sizeBytes }
                            group.forEach { song ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = song.id in selected,
                                        onCheckedChange = { checked ->
                                            selected = if (checked) selected + song.id else selected - song.id
                                        }
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            song.title + if (song.id == best?.id) "  ⭐" else "",
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            "${formatDuration(song.durationSec)} · ${formatSize(song.sizeBytes)}",
                                            fontSize = 11.sp
                                        )
                                        Text(song.path, fontSize = 10.sp, maxLines = 1)
                                    }
                                    TextButton(onClick = { deleteIds(setOf(song.id)) }) { Text("מחק") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun hasAudioPermission(context: android.content.Context): Boolean {
    val perm = if (Build.VERSION.SDK_INT >= 33)
        android.Manifest.permission.READ_MEDIA_AUDIO
    else
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    return androidx.core.content.ContextCompat.checkSelfPermission(context, perm) ==
        PackageManager.PERMISSION_GRANTED
}
