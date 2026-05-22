package com.example.ui

import android.app.Activity
import android.content.ContentUris
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
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
    var isCameraMode by remember { mutableStateOf(false) }

    val tempCameraFile = remember { java.io.File(context.cacheDir, "camera_capture.jpg") }
    val cameraUri = remember(tempCameraFile) {
        androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            tempCameraFile
        )
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            if (success) {
                selectedUri = Uri.fromFile(tempCameraFile)
                isCameraMode = true
            } else {
                if (tempCameraFile.exists()) {
                    tempCameraFile.delete()
                }
            }
        }
    )

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                if (tempCameraFile.exists()) {
                    tempCameraFile.delete()
                }
                cameraLauncher.launch(cameraUri)
            } else {
                android.widget.Toast.makeText(context, "Разрешение на камеру отклонено", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            if (tempCameraFile.exists()) {
                tempCameraFile.delete()
            }
        }
    }
    
    // Separately Days, Hours, Minutes, as requested in User query (2)
    var selectedDays by remember { mutableStateOf(0) }
    var selectedHours by remember { mutableStateOf(0) }
    var selectedMinutes by remember { mutableStateOf(1) } // Default 1 minute
    
    val durationMinutes = remember(selectedDays, selectedHours, selectedMinutes) {
        (selectedDays * 24 * 60) + (selectedHours * 60) + selectedMinutes
    }

    val coroutineScope = rememberCoroutineScope()

    var isDeleteInProgress by remember { mutableStateOf(false) }
    var pendingDeleteResult by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }

    val contentResolver = context.contentResolver

    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingDeleteResult?.complete(true)
        } else {
            android.widget.Toast.makeText(context, "Без удаления оригинала функция блокировки недоступна", android.widget.Toast.LENGTH_LONG).show()
            pendingDeleteResult?.complete(false)
        }
        pendingDeleteResult = null
    }

    val onDeleteOriginal: suspend (Uri) -> Boolean = { uriToLock ->
        val deferred = CompletableDeferred<Boolean>()
        pendingDeleteResult = deferred
        
        // Convert photopicker URI to direct MediaStore image/video URI for createDeleteRequest
        val id = uriToLock.lastPathSegment?.toLongOrNull()
        val directUri = if (id != null) {
            val type = contentResolver.getType(uriToLock) ?: ""
            if (type.startsWith("video")) {
                ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            } else {
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            }
        } else {
            uriToLock
        }

        withContext(Dispatchers.Main) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val intentSender = MediaStore.createDeleteRequest(contentResolver, listOf(directUri)).intentSender
                    deleteLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                } else {
                    contentResolver.delete(directUri, null, null)
                    deferred.complete(true)
                }
            } catch (t: Throwable) {
                t.printStackTrace()
                try {
                    contentResolver.delete(directUri, null, null)
                    deferred.complete(true)
                } catch (fallbackEx: Throwable) {
                    fallbackEx.printStackTrace()
                    android.widget.Toast.makeText(context, "Без удаления оригинала функция блокировки недоступна", android.widget.Toast.LENGTH_LONG).show()
                    deferred.complete(false)
                }
            }
        }
        deferred.await()
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
                            Text("Выбрать из галереи")
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        OutlinedButton(
                            onClick = {
                                val hasCameraPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                                    context,
                                    android.Manifest.permission.CAMERA
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                                if (hasCameraPermission) {
                                    if (tempCameraFile.exists()) {
                                        tempCameraFile.delete()
                                    }
                                    cameraLauncher.launch(cameraUri)
                                } else {
                                    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            CameraIcon()
                            Spacer(Modifier.width(8.dp))
                            Text("Сделать снимок")
                        }
                    } else {
                        Text(
                            text = "Выберите время блокировки",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(16.dp))

                        // Timer Clock Picker
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            shape = MaterialTheme.shapes.large,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TimerUnitPicker(
                                        label = "Дни",
                                        value = selectedDays,
                                        onIncrement = { if (selectedDays < 30) selectedDays++ },
                                        onDecrement = { 
                                            if (selectedDays > 0) {
                                                selectedDays--
                                                if (selectedDays == 0 && selectedHours == 0 && selectedMinutes == 0) {
                                                    selectedMinutes = 1
                                                }
                                            }
                                        }
                                    )
                                    
                                    Text(
                                        text = ":",
                                        style = MaterialTheme.typography.displayMedium.copy(
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 24.dp)
                                    )
                                    
                                    TimerUnitPicker(
                                        label = "Часы",
                                        value = selectedHours,
                                        onIncrement = { if (selectedHours < 23) selectedHours++ else selectedHours = 0 },
                                        onDecrement = { 
                                            if (selectedHours > 0) {
                                                selectedHours--
                                            } else {
                                                selectedHours = 23
                                            }
                                            if (selectedDays == 0 && selectedHours == 0 && selectedMinutes == 0) {
                                                selectedMinutes = 1
                                            }
                                        }
                                    )
                                    
                                    Text(
                                        text = ":",
                                        style = MaterialTheme.typography.displayMedium.copy(
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 24.dp)
                                    )
                                    
                                    TimerUnitPicker(
                                        label = "Минуты",
                                        value = selectedMinutes,
                                        onIncrement = { if (selectedMinutes < 59) selectedMinutes++ else selectedMinutes = 0 },
                                        onDecrement = { 
                                            if (selectedMinutes > 0) {
                                                selectedMinutes--
                                            } else {
                                                selectedMinutes = 59
                                            }
                                            if (selectedDays == 0 && selectedHours == 0 && selectedMinutes == 0) {
                                                selectedMinutes = 1
                                            }
                                        }
                                    )
                                }
                                
                                Spacer(Modifier.height(8.dp))
                                
                                val summaryText = buildString {
                                    if (selectedDays > 0) append("$selectedDays дн. ")
                                    if (selectedHours > 0 || selectedDays > 0) append("$selectedHours ч. ")
                                    append("$selectedMinutes мин.")
                                }
                                Text(
                                    text = "Итого: $summaryText ($durationMinutes мин.)",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                isDeleteInProgress = true
                                coroutineScope.launch {
                                    val uriToLock = selectedUri!!
                                    try {
                                        if (isCameraMode) {
                                            viewModel.lockImage(uriToLock, durationMinutes) { _ ->
                                                if (tempCameraFile.exists()) {
                                                    tempCameraFile.delete()
                                                }
                                                true
                                            }
                                        } else {
                                            viewModel.lockImage(uriToLock, durationMinutes, onDeleteOriginal)
                                        }
                                        isCameraMode = false
                                    } catch (t: Throwable) {
                                        android.widget.Toast.makeText(context, "Ошибка при подготовке блокировки", android.widget.Toast.LENGTH_LONG).show()
                                        if (tempCameraFile.exists()) {
                                            tempCameraFile.delete()
                                        }
                                        viewModel.completeAndClean()
                                    } finally {
                                        isDeleteInProgress = false
                                    }
                                }
                            },
                            enabled = !isDeleteInProgress && durationMinutes > 0,
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Заблокировать")
                        }
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = {
                                selectedUri = null
                                if (tempCameraFile.exists()) {
                                    tempCameraFile.delete()
                                }
                                isCameraMode = false
                            }
                        ) {
                            Text("Отмена")
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
                    val days = timeLeftMs / (1000 * 60 * 60 * 24)
                    val hours = (timeLeftMs % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60)
                    val minutes = (timeLeftMs % (1000 * 60 * 60)) / (1000 * 60)
                    val seconds = (timeLeftMs % (1000 * 60)) / 1000
                    
                    val timeString = if (days > 0) {
                        String.format("%d дн. %02d:%02d:%02d", days, hours, minutes, seconds)
                    } else {
                        String.format("%02d:%02d:%02d", hours, minutes, seconds)
                    }
                    Text(
                        text = timeString,
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

@Composable
fun TimerUnitPicker(
    label: String,
    value: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(horizontal = 4.dp)
    ) {
        IconButton(
            onClick = onIncrement,
            modifier = Modifier.size(48.dp)
        ) {
            ArrowUpIcon()
        }
        
        Surface(
            modifier = Modifier
                .width(64.dp)
                .height(64.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 2.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = String.format("%02d", value),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        IconButton(
            onClick = onDecrement,
            modifier = Modifier.size(48.dp)
        ) {
            ArrowDownIcon()
        }
        
        Spacer(Modifier.height(4.dp))
        
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
            ),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun ArrowUpIcon(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    androidx.compose.foundation.Canvas(modifier = modifier.size(24.dp)) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * 0.25f, size.height * 0.65f)
            lineTo(size.width * 0.5f, size.height * 0.35f)
            lineTo(size.width * 0.75f, size.height * 0.65f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = 3.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
        )
    }
}

@Composable
fun ArrowDownIcon(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    androidx.compose.foundation.Canvas(modifier = modifier.size(24.dp)) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * 0.25f, size.height * 0.35f)
            lineTo(size.width * 0.5f, size.height * 0.65f)
            lineTo(size.width * 0.75f, size.height * 0.35f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = 3.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
        )
    }
}

@Composable
fun CameraIcon(modifier: Modifier = Modifier, color: Color = androidx.compose.material3.LocalContentColor.current) {
    androidx.compose.foundation.Canvas(modifier = modifier.size(24.dp)) {
        val bodyWidth = size.width * 0.8f
        val bodyHeight = size.height * 0.55f
        val bodyLeft = (size.width - bodyWidth) / 2f
        val bodyTop = (size.height - bodyHeight) / 2f + 2.dp.toPx()
        
        val cornerRadius = 3.dp.toPx()
        
        val lensRadius = size.width * 0.18f
        val lensCenterX = size.width / 2f
        val lensCenterY = bodyTop + bodyHeight / 2f
        
        val flashRadius = 2.dp.toPx()
        val flashCenterX = bodyLeft + bodyWidth - 6.dp.toPx()
        val flashCenterY = bodyTop + 6.dp.toPx()

        val mountWidth = size.width * 0.25f
        val mountHeight = 4.dp.toPx()
        val mountLeft = (size.width - mountWidth) / 2f
        val mountTop = bodyTop - mountHeight + 1.dp.toPx()

        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(mountLeft, mountTop),
            size = androidx.compose.ui.geometry.Size(mountWidth, mountHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            style = androidx.compose.ui.graphics.drawscope.Fill
        )

        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(bodyLeft, bodyTop),
            size = androidx.compose.ui.geometry.Size(bodyWidth, bodyHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
        )

        drawCircle(
            color = color,
            radius = lensRadius,
            center = androidx.compose.ui.geometry.Offset(lensCenterX, lensCenterY),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
        )

        drawCircle(
            color = color,
            radius = lensRadius * 0.4f,
            center = androidx.compose.ui.geometry.Offset(lensCenterX, lensCenterY),
            style = androidx.compose.ui.graphics.drawscope.Fill
        )

        drawCircle(
            color = color,
            radius = flashRadius,
            center = androidx.compose.ui.geometry.Offset(flashCenterX, flashCenterY),
            style = androidx.compose.ui.graphics.drawscope.Fill
        )
    }
}

