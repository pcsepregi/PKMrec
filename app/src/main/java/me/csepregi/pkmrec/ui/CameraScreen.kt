package me.csepregi.pkmrec.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.csepregi.pkmrec.BuildConfig
import me.csepregi.pkmrec.R
import me.csepregi.pkmrec.recognition.CardReading
import me.csepregi.pkmrec.recognition.CardTextAnalyzer
import me.csepregi.pkmrec.recognition.OcrLine
import me.csepregi.pkmrec.recognition.ReadingStabilizer
import java.util.concurrent.Executors

/** Card proportions (63 × 88 mm). */
private const val CARD_ASPECT = 63f / 88f

@Composable
fun CameraScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var askedOnce by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        askedOnce = true
    }

    if (!hasPermission) {
        if (!askedOnce) {
            DisposableEffect(Unit) {
                launcher.launch(Manifest.permission.CAMERA)
                onDispose { }
            }
        }
        PermissionNeeded(
            onRequest = { launcher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)),
                )
            },
            onBack = onBack,
        )
        return
    }

    ScannerContent(onBack = onBack)
}

@Composable
private fun ScannerContent(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current

    val stabilizer = remember { ReadingStabilizer() }
    var live by remember { mutableStateOf<CardReading?>(null) }
    var confirmed by remember { mutableStateOf<CardReading?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }
    var rawLines by remember { mutableStateOf<List<OcrLine>>(emptyList()) }

    val analyzer = remember {
        CardTextAnalyzer { reading, lines ->
            if (confirmed != null) return@CardTextAnalyzer
            rawLines = lines
            val stable = stabilizer.offer(reading)
            live = stabilizer.current
            if (stable != null) {
                confirmed = stable
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }
    analyzer.paused = confirmed != null || showManual

    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            val p = providerFuture.get()
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1920, 1080),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, analyzer) }
            p.unbindAll()
            camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
            executor.shutdown()
            analyzer.close()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Framing guide
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.82f)
                .aspectRatio(CARD_ASPECT)
                .border(3.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(16.dp)),
        )

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(onClick = onBack) { Text("‹ " + stringResource(R.string.back)) }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onClick = { showRaw = !showRaw }) { Text(stringResource(R.string.camera_raw_text)) }
            Spacer(Modifier.width(8.dp))
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                FilledTonalButton(onClick = {
                    torchOn = !torchOn
                    camera?.cameraControl?.enableTorch(torchOn)
                }) {
                    Text(stringResource(if (torchOn) R.string.camera_torch_off else R.string.camera_torch_on))
                }
            }
        }

        if (showRaw) {
            RawTextPanel(
                lines = rawLines,
                reading = confirmed ?: live,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp, start = 12.dp, end = 12.dp),
            )
        }

        // Bottom area: live reading / confirmed result
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val result = confirmed
            if (result == null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.camera_hint), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = live?.let { stringResource(R.string.camera_reading, it.display) }
                                ?: stringResource(R.string.camera_searching),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                OutlinedButton(
                    onClick = { showManual = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.camera_manual_entry), color = Color.White) }
            } else {
                RecognizedCard(
                    reading = result,
                    onRescan = {
                        stabilizer.reset()
                        live = null
                        confirmed = null
                    },
                )
            }
        }
    }

    if (showManual) {
        ManualEntryDialog(
            onDismiss = { showManual = false },
            onConfirm = { reading ->
                showManual = false
                confirmed = reading
            },
        )
    }
}

/** Debug view: what the text recognizer currently sees, so recognition problems can be reported. */
@Composable
private fun RawTextPanel(lines: List<OcrLine>, reading: CardReading?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sorted = lines.sortedBy { it.top }
    val report = buildString {
        appendLine("PKMrec ${BuildConfig.VERSION_NAME} OCR")
        appendLine("reading: " + (reading?.let { "${it.display} | mark=${it.regulationMark} | name=${it.name}" } ?: "-"))
        sorted.forEach { appendLine("[${it.top}-${it.bottom}] ${it.text}") }
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.camera_raw_text_title, sorted.size),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("PKMrec OCR", report))
                    Toast.makeText(context, R.string.camera_raw_text_copied, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.camera_raw_text_copy)) }
            }
            Column(
                modifier = Modifier
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                sorted.forEach { line ->
                    Text(
                        "${line.top}: ${line.text}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecognizedCard(reading: CardReading, onRescan: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.camera_recognized), style = MaterialTheme.typography.labelLarge)
            Text(reading.display, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            reading.name?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            if (reading.setCode == null) {
                Text(
                    stringResource(R.string.camera_no_set_code),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.camera_lookup_next_version),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Button(onClick = onRescan, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.camera_rescan))
            }
        }
    }
}

@Composable
private fun ManualEntryDialog(onDismiss: () -> Unit, onConfirm: (CardReading) -> Unit) {
    var setCode by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    val parsed = parseManual(setCode, number)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.camera_manual_entry)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it.trim() },
                    label = { Text(stringResource(R.string.manual_number)) },
                    placeholder = { Text("015/128") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                OutlinedTextField(
                    value = setCode,
                    onValueChange = { setCode = it.trim().uppercase() },
                    label = { Text(stringResource(R.string.manual_set_code)) },
                    placeholder = { Text("PAL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun parseManual(setCode: String, number: String): CardReading? {
    val parts = number.uppercase().split("/").map { it.trim() }
    val num = parts.getOrNull(0)?.takeIf { it.matches(Regex("[A-Z]{0,3}\\d{1,3}")) } ?: return null
    val total = parts.getOrNull(1)?.takeIf { it.matches(Regex("[A-Z]{0,3}\\d{1,3}")) }
    if (parts.size > 1 && total == null) return null
    val code = setCode.takeIf { it.matches(Regex("[A-Z0-9]{2,5}")) }
    return CardReading(code, num, total)
}

@Composable
private fun PermissionNeeded(onRequest: () -> Unit, onOpenSettings: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.back)) }
        Text(stringResource(R.string.camera_permission_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.camera_permission_text))
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.camera_permission_allow))
        }
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.camera_permission_settings))
        }
    }
}
