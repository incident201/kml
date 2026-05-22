package com.example.ui

import android.app.Activity
import android.content.ContentValues
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeepMeLockedScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val timeLeftMs by viewModel.timeLeftMs.collectAsState()
    val unlockedBitmap by viewModel.unlockedBitmap.collectAsState()

    val context = LocalContext.current
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var durationMinutes by remember { mutableStateOf(1) } // Default 1 minute
    val coroutineScope = rememberCoroutineScope()

    var pendingDeleteUri by remember { mutableStateOf<Uri?>(null) }
    var isDeleteInProgress by remember { mutableStateOf(false) }

    val contentResolver = context.contentResolver

    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && pendingDeleteUri != null) {
            coroutineScope.launch {
                viewModel.lockImage(pendingDeleteUri!!, durationMinutes) { true }
                pendingDeleteUri = null
                isDeleteInProgress = false
            }
        } else {
            // Delete failed or cancelled
            android.widget.Toast.makeText(context, "Без удаления оригинала функция блокировки недоступна", android.widget.Toast.LENGTH_LONG).show()
            coroutineScope.launch {
                viewModel.completeAndClean()
            }
            pendingDeleteUri = null
            isDeleteInProgress = false
        }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            selectedUri = uri
        }
    }

    // Set Secure flag for lock screen
    val activity = context as? Activity
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                viewModel.cleanupBitmap()
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                viewModel.loadDecryptedBitmap()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(uiState) {
        if (uiState == LockScreenState.LOCKED) {
            activity?.window?.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KeepMeLocked") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (uiState) {
                LockScreenState.IDLE -> {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Lock a photo securely",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(modifier = Modifier.height(32.dp))

                    if (selectedUri == null) {
                        Button(
                            onClick = {
                                pickMedia.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Select Image")
                        }
                    } else {
                        Text("Ready to lock image.")
                        Spacer(Modifier.height(16.dp))
                        Text("Duration: $durationMinutes minutes")
                        Slider(
                            value = durationMinutes.toFloat(),
                            onValueChange = { durationMinutes = it.toInt() },
                            valueRange = 1f..1440f, // up to 24 hours
                            steps = 100
                        )
                        Spacer(Modifier.height(32.dp))
                        Button(
                            onClick = {
                                isDeleteInProgress = true
                                coroutineScope.launch {
                                    val uriToLock = selectedUri!!
                                    try {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                            try {
                                                val intentSender = MediaStore.createDeleteRequest(contentResolver, listOf(uriToLock)).intentSender
                                                pendingDeleteUri = uriToLock
                                                deleteLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                                            } catch (t: Throwable) {
                                                // If createDeleteRequest or launch fails due to photo picker uri limitations,
                                                // fallback to trying contentResolver delete, or show alert.
                                                try {
                                                    contentResolver.delete(uriToLock, null, null)
                                                    viewModel.lockImage(uriToLock, durationMinutes) { true }
                                                    isDeleteInProgress = false
                                                } catch (fallbackEx: Throwable) {
                                                    android.widget.Toast.makeText(context, "Без удаления оригинала функция блокировки недоступна", android.widget.Toast.LENGTH_LONG).show()
                                                    viewModel.completeAndClean()
                                                    isDeleteInProgress = false
                                                }
                                            }
                                        } else {
                                            try {
                                                contentResolver.delete(uriToLock, null, null)
                                                viewModel.lockImage(uriToLock, durationMinutes) { true }
                                                isDeleteInProgress = false
                                            } catch (t: Throwable) {
                                                android.widget.Toast.makeText(context, "Без удаления оригинала функция блокировки недоступна", android.widget.Toast.LENGTH_LONG).show()
                                                viewModel.completeAndClean()
                                                isDeleteInProgress = false
                                            }
                                        }
                                    } catch (t: Throwable) {
                                        android.widget.Toast.makeText(context, "Ошибка при подготовке блокировки", android.widget.Toast.LENGTH_LONG).show()
                                        viewModel.completeAndClean()
                                        isDeleteInProgress = false
                                    }
                                }
                            },
                            enabled = !isDeleteInProgress,
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Lock Now")
                        }
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = { selectedUri = null }) {
                            Text("Cancel")
                        }
                    }
                }
                LockScreenState.LOCKING -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Locking image...")
                }
                LockScreenState.LOCKED -> {
                    Text(
                        text = "LOCKED",
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(32.dp))
                    val hours = timeLeftMs / (1000 * 60 * 60)
                    val minutes = (timeLeftMs % (1000 * 60 * 60)) / (1000 * 60)
                    val seconds = (timeLeftMs % (1000 * 60)) / 1000
                    Text(
                        text = String.format("%02d:%02d:%02d", hours, minutes, seconds),
                        style = MaterialTheme.typography.displayMedium
                    )
                    Spacer(Modifier.height(32.dp))
                    Text(
                        text = "Do not clear app data or your image will be lost permanently.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
                LockScreenState.UNLOCKED -> {
                    if (unlockedBitmap != null) {
                        Image(
                            bitmap = unlockedBitmap!!.asImageBitmap(),
                            contentDescription = "Unlocked Image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                        )
                        Spacer(Modifier.height(32.dp))
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    try {
                                        val bytes = withContext(Dispatchers.IO) { viewModel.getFileBytesToSave() }
                                        if (bytes != null) {
                                            val savedSuccessfully = withContext(Dispatchers.IO) {
                                                try {
                                                    val values = ContentValues().apply {
                                                        put(MediaStore.MediaColumns.DISPLAY_NAME, "Unlocked_${System.currentTimeMillis()}.jpg")
                                                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                            put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES)
                                                        }
                                                    }
                                                    val saveUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                                                    if (saveUri != null) {
                                                        contentResolver.openOutputStream(saveUri)?.use { it.write(bytes) }
                                                        true
                                                    } else {
                                                        false
                                                    }
                                                } catch (e: Exception) {
                                                    e.printStackTrace()
                                                    false
                                                }
                                            }
                                            if (savedSuccessfully) {
                                                android.widget.Toast.makeText(context, "Сохранено в галерею", android.widget.Toast.LENGTH_SHORT).show()
                                                viewModel.completeAndClean()
                                                selectedUri = null
                                            } else {
                                                android.widget.Toast.makeText(context, "Не удалось сохранить файл", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    } catch (t: Throwable) {
                                        t.printStackTrace()
                                        android.widget.Toast.makeText(context, "Ошибка сохранения", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Text("Save to Gallery")
                        }
                    } else {
                        Text("Image could not be loaded or is corrupted.")
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { 
                            viewModel.completeAndClean()
                            selectedUri = null
                        }) {
                            Text("Return Home")
                        }
                    }
                }
                LockScreenState.MISSING_FILE -> {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Lock data missing or corrupted. Original file is unrecoverable.",
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(32.dp))
                    Button(onClick = { 
                        viewModel.completeAndClean()
                        selectedUri = null
                    }) {
                        Text("Reset")
                    }
                }
            }
        }
    }
}
