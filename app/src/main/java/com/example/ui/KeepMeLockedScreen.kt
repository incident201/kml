package com.example.ui

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeepMeLockedScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val timeLeftMs by viewModel.timeLeftMs.collectAsState()
    val emergencyTimeLeftMs by viewModel.emergencyTimeLeftMs.collectAsState()
    val unlockedBitmap by viewModel.unlockedBitmap.collectAsState()
    val canCancelLock by viewModel.canCancelLock.collectAsState()
    val isStatusUnknown by viewModel.isStatusUnknown.collectAsState()
    val cleanupFailed by viewModel.cleanupFailed.collectAsState()
    val isUnlockStateSaved by viewModel.isUnlockStateSaved.collectAsState()
    val lastErrorDetails by viewModel.lastErrorDetails.collectAsState()

    val context = LocalContext.current
    var selectedUriStr by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val selectedUri = remember(selectedUriStr) { selectedUriStr?.let { Uri.parse(it) } }

    var showCameraView by remember { mutableStateOf(false) }

    // Request notification permissions for Android 13+ to post alarm alerts
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* Permission handled */ }
    )

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotificationPermission = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasNotificationPermission) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val selectedBitmap = remember(selectedUri) {
        selectedUri?.path?.let { path ->
            try {
                val options = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = 2
                }
                android.graphics.BitmapFactory.decodeFile(path, options)
            } catch (e: Exception) {
                null
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                showCameraView = true
            } else {
                android.widget.Toast.makeText(context, "Разрешение на камеру отклонено", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    )
    
    var selectedDays by remember { mutableStateOf(0) }
    var selectedHours by remember { mutableStateOf(0) }
    var selectedMinutes by remember { mutableStateOf(1) } // Default 1 minute
    
    val durationMinutes = remember(selectedDays, selectedHours, selectedMinutes) {
        (selectedDays * 24 * 60) + (selectedHours * 60) + selectedMinutes
    }

    val coroutineScope = rememberCoroutineScope()

    var isDeleteInProgress by remember { mutableStateOf(false) }
    val onDeleteOriginal: suspend (Uri) -> Boolean = { uriToDelete ->
        withContext(Dispatchers.IO) {
            val scheme = uriToDelete.scheme
            if (scheme == "file") {
                val path = uriToDelete.path
                if (path != null) {
                    val file = java.io.File(path)
                    if (file.exists() && file.absolutePath.contains("staging")) {
                        // For camera staging files, we defer physical deletion until a successful cold-start verification.
                        // So we return true here but do not actually delete yet.
                        true
                    } else {
                        true
                    }
                } else {
                    true
                }
            } else {
                true
            }
        }
    }

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
        if (uiState == LockScreenState.LOCKED || uiState == LockScreenState.EMERGENCY_RECOVERY) {
            activity?.window?.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    if (showCameraView) {
        CameraCaptureView(
            onImageCaptured = { uri ->
                selectedUriStr = uri.toString()
                showCameraView = false
            },
            onClose = {
                showCameraView = false
            }
        )
    } else {
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
                            if (cleanupFailed) {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer
                                    ),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "Экспорт завершён успешно, но при очистке зашифрованных локальных временных файлов локбокса произошла неизвестная системная ошибка. Пожалуйста, повторите ручную очистку локбокса прямо сейчас, чтобы полностью уничтожить защищенную ковербиту.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Button(
                                            onClick = {
                                                viewModel.completeAndClean()
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error
                                            )
                                        ) {
                                            Text("Повторить ручную очистку локбокса")
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }

                            Button(
                                onClick = {
                                    val hasCameraPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                                        context,
                                        android.Manifest.permission.CAMERA
                                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                                    if (hasCameraPermission) {
                                        showCameraView = true
                                    } else {
                                        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .testTag("make_snapshot_button")
                            ) {
                                CameraIcon()
                                Spacer(Modifier.width(8.dp))
                                Text("Сделать снимок")
                            }
                        } else {
                        if (selectedBitmap != null) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .padding(vertical = 8.dp)
                                    .testTag("selected_image_preview_card"),
                                shape = MaterialTheme.shapes.large,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    Image(
                                        bitmap = selectedBitmap.asImageBitmap(),
                                        contentDescription = "Selected Picture Preview",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f))
                                                )
                                            )
                                    )
                                    Text(
                                        text = "Выбранный снимок для блокировки",
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(12.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        Text(
                            text = "Выберите время блокировки",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(12.dp))

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
                                        viewModel.lockImage(uriToLock, durationMinutes, onDeleteOriginal)
                                    } catch (t: Throwable) {
                                        android.widget.Toast.makeText(context, "Ошибка при подготовке блокировки", android.widget.Toast.LENGTH_LONG).show()
                                        onDeleteOriginal(uriToLock)
                                        viewModel.completeAndClean()
                                    } finally {
                                        isDeleteInProgress = false
                                    }
                                }
                            },
                            enabled = !isDeleteInProgress && durationMinutes > 0,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("lock_confirm_button")
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Заблокировать")
                        }
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = {
                                val currentUriStr = selectedUriStr
                                selectedUriStr = null
                                if (currentUriStr != null) {
                                    coroutineScope.launch {
                                        onDeleteOriginal(Uri.parse(currentUriStr))
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("cancel_lock_button")
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
                LockScreenState.UNLOCKED_PENDING_EXPORT -> {
                    if (unlockedBitmap != null) {
                        Image(
                            bitmap = unlockedBitmap!!.asImageBitmap(),
                            contentDescription = "Unlocked Image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                        )
                    } else {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .padding(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Предпросмотр недоступен",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Вы можете успешно экспортировать оригинальный файл",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(horizontal = 8.dp),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                    
                    if (!isUnlockStateSaved) {
                        Spacer(Modifier.height(16.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Внимание: не удалось записать статус разблокировки в защищенные настройки приложения. Экспорт по-прежнему доступен, но если вы перезапустите приложение, оно может снова показать экран блокировки таймера.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    if (cleanupFailed) {
                        Spacer(Modifier.height(16.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Экспорт завершён успешно, но при очистке зашифрованных локальных временных файлов локбокса произошла неизвестная системная ошибка. Пожалуйста, повторите ручную очистку локбокса прямо сейчас, чтобы полностью уничтожить защищенную ковербиту.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        viewModel.completeAndClean()
                                        selectedUriStr = null
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text("Повторить ручную очистку локбокса")
                                }
                            }
                        }
                    }
                    
                    Spacer(Modifier.height(32.dp))
                    
                    var isSaving by remember { mutableStateOf(false) }
                    
                    Button(
                        onClick = {
                            isSaving = true
                            coroutineScope.launch {
                                try {
                                    val savedSuccessfully = viewModel.restoreAndExport()
                                    if (savedSuccessfully) {
                                        android.widget.Toast.makeText(context, "Успешно сохранено в галерею", android.widget.Toast.LENGTH_SHORT).show()
                                        viewModel.completeAndClean()
                                        selectedUriStr = null
                                    } else {
                                        android.widget.Toast.makeText(context, "Не удалось сохранить файл или проверка целостности не прошла", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                } catch (t: Throwable) {
                                    t.printStackTrace()
                                    android.widget.Toast.makeText(context, "Ошибка сохранения", android.widget.Toast.LENGTH_LONG).show()
                                } finally {
                                    isSaving = false
                                }
                            }
                        },
                        enabled = !isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("save_to_gallery_button")
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                        } else {
                            Text("Сохранить файл в галерею")
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
                    lastErrorDetails?.let { details ->
                        Spacer(Modifier.height(16.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .background(MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium)
                                .padding(12.dp)
                        ) {
                            Text(
                                text = details,
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = androidx.compose.ui.unit.TextUnit.Unspecified,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                ),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.verticalScroll(rememberScrollState())
                            )
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                    Button(onClick = { 
                        viewModel.completeAndClean()
                        selectedUriStr = null
                    }) {
                        Text("Reset")
                    }
                }
                LockScreenState.DELETE_ORIGINAL_PENDING -> {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Требуется удаление оригинала",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Для защиты файла необходимо удалить его оригинал с Вашего устройства. Фотография уже надежно защищена внутри локбокса.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        onClick = {
                            viewModel.retryDeleteAndLock(onDeleteOriginal)
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text("Удалить оригинал")
                    }

                    if (isStatusUnknown) {
                        Spacer(Modifier.height(16.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Не удалось подтвердить удаление оригинала из галереи устройства. Пожалуйста, убедитесь, что оригинал файла удален в Вашей галерее, после чего нажмите кнопку ниже, чтобы проверить его статус еще раз.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        viewModel.checkCurrentState()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text("Проверить снова")
                                }
                            }
                        }
                    }

                    if (canCancelLock) {
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = {
                                viewModel.cancelPendingLock()
                                selectedUriStr = null
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Text("Отменить блокировку (файл цел)")
                        }
                    }
                }
                LockScreenState.PERSISTENCE_ERROR -> {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Ошибка сохранения сессии",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Оригинальный файл уже успешно удален, а защищенная копия надежно сохранена внутри зашифрованного хранилища локбокса. Сведения о таймере не удалось записать в защищенные настройки приложения. Пожалуйста, повторите попытку сохранения.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        onClick = {
                            viewModel.retrySaveLockSessionAndTransition()
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text("Повторить сохранение сессии")
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedButton(
                        onClick = {
                            viewModel.switchToEmergencyRecovery()
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text("Перейти в аварийный режим")
                    }
                }
                LockScreenState.EMERGENCY_RECOVERY -> {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Аварийное восстановление",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Системные настройки повреждены или стерты. Ваш файл зашифрован и находится в безопасности. Дождитесь окончания времени блокировки для экспорта.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(32.dp))

                    val days = emergencyTimeLeftMs / (1000 * 60 * 60 * 24)
                    val hours = (emergencyTimeLeftMs % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60)
                    val minutes = (emergencyTimeLeftMs % (1000 * 60 * 60)) / (1000 * 60)
                    val seconds = (emergencyTimeLeftMs % (1000 * 60)) / 1000
                    
                    val timeString = if (days > 0) {
                        String.format("%d дн. %02d:%02d:%02d", days, hours, minutes, seconds)
                    } else if (emergencyTimeLeftMs == -1L) {
                        "Синхронизация времени..."
                    } else {
                        String.format("%02d:%02d:%02d", hours, minutes, seconds)
                    }

                    Text(
                        text = timeString,
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(32.dp))

                    var isSaving by remember { mutableStateOf(false) }

                    Button(
                        onClick = {
                            isSaving = true
                            coroutineScope.launch {
                                try {
                                    val savedSuccessfully = viewModel.emergencyExport()
                                    if (savedSuccessfully) {
                                        android.widget.Toast.makeText(context, "Файл успешно экспортирован", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        android.widget.Toast.makeText(context, "Не удалось экспортировать файл", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                } catch (t: Throwable) {
                                    t.printStackTrace()
                                    android.widget.Toast.makeText(context, "Ошибка экспорта", android.widget.Toast.LENGTH_LONG).show()
                                } finally {
                                    isSaving = false
                                }
                            }
                        },
                        enabled = !isSaving && emergencyTimeLeftMs == 0L,
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                        } else {
                            Text("Экспортировать из аварийного локбокса")
                        }
                    }
                }
                LockScreenState.TIME_SYNC_REQUIRED -> {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(24.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = MaterialTheme.shapes.large,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Нужна проверка времени",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "После перезагрузки приложению нужно получить сетевое время. Подключись к интернету и нажми «Проверить время». Фото остаётся запертым и не потеряно.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = {
                                    viewModel.retryTimeSync()
                                },
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                            ) {
                                Text("Проверить время")
                            }
                        }
                    }
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

@Composable
fun CameraCaptureView(
    onImageCaptured: (Uri) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val executor = ContextCompat.getMainExecutor(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = CameraPreview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    imageCapture = capture

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            capture
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, executor)
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Top cancel button
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Cancel Capture",
                tint = Color.White
            )
        }

        // Bottom Controls Overlay
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(bottom = 48.dp, top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Secure Snapshot (Private Container Staging)",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Shutter Button
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clickable {
                        val capture = imageCapture ?: return@clickable
                        val stagingDir = java.io.File(context.filesDir, "staging")
                        if (!stagingDir.exists()) {
                            stagingDir.mkdirs()
                        }
                        val photoFile = java.io.File(stagingDir, "camera_capture_${java.util.UUID.randomUUID()}.jpg")
                        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                        capture.takePicture(
                            outputOptions,
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                    onImageCaptured(Uri.fromFile(photoFile))
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    exception.printStackTrace()
                                    android.widget.Toast.makeText(context, "Ошибка съемки. Попробуйте еще раз.", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                    .border(4.dp, Color.White, shape = CircleShape)
                    .padding(6.dp)
                    .background(Color.White, shape = CircleShape)
            )
        }
    }
}
