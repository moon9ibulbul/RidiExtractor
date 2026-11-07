package com.example.ridiextractor

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ridiextractor.ui.theme.RidiExtractorTheme
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RidiExtractorTheme {
                MainInterface()
            }
        }
    }
}

fun getFileName(context: Context, uri: Uri): String {
    var name = ""
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1) {
                name = it.getString(nameIndex)
            }
        }
    }
    if (name.isEmpty()) {
        name = uri.lastPathSegment ?: "unknown_file"
    }
    return name
}

fun copyUriToFile(context: Context, uri: Uri, destFile: File) {
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(destFile).use { output ->
            input.copyTo(output)
        }
    }
}

@Composable
fun MainInterface() {
    val context = LocalContext.current

    var zipUri by remember { mutableStateOf<Uri?>(null) }
    var zipFileName by remember { mutableStateOf("") }
    var prefUri by remember { mutableStateOf<Uri?>(null) }
    var prefFileName by remember { mutableStateOf("") }
    var datUri by remember { mutableStateOf<Uri?>(null) }
    var datFileName by remember { mutableStateOf("") }

    var bookMetadata by remember { mutableStateOf<BookMetadata?>(null) }
    var parsedUuid by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var processStatus by remember { mutableStateOf("") }
    var resultDialogMessage by remember { mutableStateOf<String?>(null) }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            zipUri = uri
            zipFileName = getFileName(context, uri)
            // Inspect zip for metadata & cover asynchronously
            Thread {
                try {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val meta = BookExtractor.inspectZip(stream)
                        Handler(Looper.getMainLooper()).post {
                            bookMetadata = meta
                        }
                    }
                } catch (e: Exception) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "Failed to read ZIP: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
    }

    val prefPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            prefUri = uri
            prefFileName = getFileName(context, uri)
            try {
                val xmlText = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                val uuid = BookExtractor.extractUuidFromPreferences(xmlText)
                parsedUuid = uuid
                if (uuid == null) {
                    Toast.makeText(context, "UUID not found in selected preferences XML", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error reading preferences: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val datPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            datUri = uri
            datFileName = getFileName(context, uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ridi Extractor") },
                backgroundColor = MaterialTheme.colors.primary,
                contentColor = MaterialTheme.colors.onPrimary
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Unggah file-file berikut untuk mendekripsi buku:",
                style = MaterialTheme.typography.subtitle1,
                fontWeight = FontWeight.SemiBold
            )

            // Step 1: Zip file
            FileUploadCard(
                title = "1. ZIP folder extracted",
                description = "File ZIP berisi extracted/ (OEBPS/Text, Images/cover.jpg, content.opf)",
                selectedFileName = zipFileName,
                isCompleted = zipUri != null,
                buttonText = if (zipUri == null) "Pilih ZIP" else "Ganti ZIP",
                onSelectClick = {
                    zipPickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"))
                }
            )

            // Step 2: Preferences XML
            FileUploadCard(
                title = "2. com.initialcoms.ridi_preferences.xml",
                description = if (parsedUuid != null) "UUID ditemukan: $parsedUuid" else "File shared_prefs untuk mengekstrak Device UUID",
                selectedFileName = prefFileName,
                isCompleted = prefUri != null && parsedUuid != null,
                buttonText = if (prefUri == null) "Pilih XML" else "Ganti XML",
                onSelectClick = {
                    prefPickerLauncher.launch(arrayOf("text/xml", "application/xml", "text/plain", "*/*"))
                }
            )

            // Step 3: DAT file
            FileUploadCard(
                title = "3. {bookid}.dat",
                description = "File kunci enkripsi buku dari Ridibooks",
                selectedFileName = datFileName,
                isCompleted = datUri != null,
                buttonText = if (datUri == null) "Pilih DAT" else "Ganti DAT",
                onSelectClick = {
                    datPickerLauncher.launch(arrayOf("*/*"))
                }
            )

            // Preview metadata & cover if available
            if (bookMetadata != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = 3.dp,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val coverBytes = bookMetadata?.coverBytes
                        if (coverBytes != null && coverBytes.isNotEmpty()) {
                            val bitmap = BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size)
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Cover",
                                    modifier = Modifier
                                        .size(70.dp, 100.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = bookMetadata?.title ?: "Unknown Title",
                                style = MaterialTheme.typography.subtitle1,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = bookMetadata?.author ?: "Unknown Author",
                                style = MaterialTheme.typography.body2,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // Progress status
            if (isProcessing) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = processStatus, style = MaterialTheme.typography.body2)
                }
            }

            // Decrypt Action Button
            val canDecrypt = zipUri != null && prefUri != null && datUri != null && parsedUuid != null && !isProcessing
            Button(
                onClick = {
                    val currentZipUri = zipUri ?: return@Button
                    val currentDatUri = datUri ?: return@Button
                    val uuid = parsedUuid ?: return@Button
                    val bookTitle = bookMetadata?.title ?: "decrypted_book"

                    isProcessing = true
                    processStatus = "Memulai dekripsi..."

                    Thread {
                        val cacheDir = context.cacheDir
                        val tempZip = File(cacheDir, "input_${System.currentTimeMillis()}.zip")
                        val tempDat = File(cacheDir, "input_${System.currentTimeMillis()}.dat")
                        val workDir = File(cacheDir, "work_${System.currentTimeMillis()}")

                        try {
                            processStatus = "Menyiapkan file..."
                            copyUriToFile(context, currentZipUri, tempZip)
                            copyUriToFile(context, currentDatUri, tempDat)

                            // Clean book title for filename
                            val safeTitle = bookTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                            val outDir = File(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                                "RidiExtractor"
                            )
                            if (!outDir.exists()) {
                                outDir.mkdirs()
                            }
                            val outputEpub = File(outDir, "$safeTitle.epub")

                            processStatus = "Mendekripsi file XHTML & mengemas ke EPUB..."
                            val finalMeta = BookExtractor.decryptAndPackage(
                                zipFile = tempZip,
                                datFile = tempDat,
                                deviceId = uuid,
                                outputEpubFile = outputEpub,
                                tempWorkDir = workDir
                            )

                            Handler(Looper.getMainLooper()).post {
                                isProcessing = false
                                resultDialogMessage = "Berhasil mendekripsi dan mengemas EPUB:\n\n" +
                                        "Judul: ${finalMeta.title}\n" +
                                        "Penulis: ${finalMeta.author}\n\n" +
                                        "Tersimpan di:\n${outputEpub.absolutePath}"
                            }
                        } catch (e: Exception) {
                            Handler(Looper.getMainLooper()).post {
                                isProcessing = false
                                resultDialogMessage = "Gagal mendekripsi buku: ${e.message}"
                            }
                        } finally {
                            tempZip.delete()
                            tempDat.delete()
                            workDir.deleteRecursively()
                        }
                    }.start()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = canDecrypt,
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Dekripsi & Buat EPUB", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    resultDialogMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { resultDialogMessage = null },
            title = { Text("Informasi Dekripsi") },
            text = { Text(msg) },
            confirmButton = {
                Button(onClick = { resultDialogMessage = null }) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
fun FileUploadCard(
    title: String,
    description: String,
    selectedFileName: String,
    isCompleted: Boolean,
    buttonText: String,
    onSelectClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 2.dp,
        shape = RoundedCornerShape(8.dp),
        border = if (isCompleted) BorderStroke(1.dp, MaterialTheme.colors.primary) else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.subtitle1,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (isCompleted) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Ready",
                        tint = MaterialTheme.colors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
            )
            if (selectedFileName.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "File: $selectedFileName",
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.primary
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = onSelectClick,
                modifier = Modifier.align(Alignment.End),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(buttonText)
            }
        }
    }
}
