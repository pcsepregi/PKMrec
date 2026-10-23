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
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.roundToInt

/** Card proportions (63 × 88 mm). */
private const val CARD_ASPECT = 63f / 88f

@Composable
fun CameraScreen(onBack: () -> Unit, onRecognized: (CardReading) -> Unit) {
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

    ScannerContent(onBack = onBack, onRecognized = onRecognized)
}

@Composable
private fun ScannerContent(onBack: () -> Unit, onRecognized: (CardReading) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current

    val stabilizer = remember { ReadingStabilizer() }
    var live by remember { mutableStateOf<CardReading?>(null) }
    var confirmed by remember { mutableStateOf<CardReading?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
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

    // A confirmed reading (camera or manual entry) goes straight to the TCG Collector lookup.
    LaunchedEffect(confirmed) {
        confirmed?.let(onRecognized)
    }

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
            val cam = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            camera = cam
            reduceExposure(cam)
            previewView.post { focusOnBottomStrip(cam, previewView) }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
            executor.shutdown()
            analyzer.close()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Transparent layer above the preview: tap to focus / meter on a spot (e.g. a part without glare).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(camera) {
                    detectTapGestures { offset -> camera?.let { focusAt(it, previewView, offset.x, offset.y) } }
                },
        )

        // Framing guide
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.82f)
                .aspectRatio(CARD_ASPECT)
                .border(3.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(16.dp)),
        )

        // Top: explanation (and the raw text panel when switched on)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.camera_hint), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.camera_glare_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (showRaw) {
                RawTextPanel(lines = rawLines, reading = confirmed ?: live)
            }
        }

        // Bottom: live reading and all controls, within thumb reach
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val result = confirmed
            if (result != null) {
                RecognizedCard(
                    reading = result,
                    onRescan = {
                        stabilizer.reset()
                        live = null
                        confirmed = null
                    },
                )
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = live?.let { stringResource(R.string.camera_reading, it.display) }
                            ?: stringResource(R.string.camera_searching),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
                if (maxZoom >= 2f) {
                    FilledTonalButton(
                        onClick = {
                            zoomed = !zoomed
                            camera?.cameraControl?.setZoomRatio(if (zoomed) 2f else 1f)
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (zoomed) "2x" else "1x") }
                }
                if (camera?.cameraInfo?.hasFlashUnit() == true) {
                    FilledTonalButton(
                        onClick = {
                            torchOn = !torchOn
                            camera?.cameraControl?.enableTorch(torchOn)
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(if (torchOn) R.string.camera_torch_off else R.string.camera_torch_on)) }
                }
                FilledTonalButton(onClick = { showRaw = !showRaw }, modifier = Modifier.weight(1.4f)) {
                    Text(stringResource(R.string.camera_raw_text), maxLines = 1)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text("‹ " + stringResource(R.string.back))
                }
                Button(onClick = { showManual = true }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.camera_manual_entry))
                }
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

/** About -0.7 EV: keeps text under sleeve glare from burning out to white. */
private fun reduceExposure(camera: Camera) {
    val exposure = camera.cameraInfo.exposureState
    if (!exposure.isExposureCompensationSupported) return
    val step = exposure.exposureCompensationStep.toFloat()
    if (step <= 0f) return
    val range = exposure.exposureCompensationRange
    val index = (-0.7f / step).roundToInt().coerceIn(range.lower, range.upper)
    camera.cameraControl.setExposureCompensationIndex(index)
}

/** Focuses and meters on the bottom strip of the framing guide, where the small set code and number are. */
private fun focusOnBottomStrip(camera: Camera, view: PreviewView) {
    if (view.width == 0 || view.height == 0) return
    val guideHeight = view.width * 0.82f / CARD_ASPECT
    val y = (view.height / 2f + guideHeight * 0.42f).coerceAtMost(view.height - 1f)
    focusAt(camera, view, view.width / 2f, y)
}

private fun focusAt(camera: Camera, view: PreviewView, x: Float, y: Float) {
    val point = view.meteringPointFactory.createPoint(x, y)
    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
        .disableAutoCancel()
        .build()
    camera.cameraControl.startFocusAndMetering(action)
}

/** Debug view: what the text recognizer currently sees, so recognition problems can be reported. */
@Composable
private fun RawTextPanel(lines: List<OcrLine>, reading: CardReading?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sorted = lines.sortedBy { it.top }
    val report = buildString {
        appendLine("PKMrec ${BuildConfig.VERSION_NAME} OCR")
        appendLine("reading: " + (reading?.let { "${it.display} | mark=${it.regulationMark} | name=${it.name}" } ?: "-"))
        sorted.forEach { appendLine((if (it.fromStrip) "*" else " ") + "[${it.top}-${it.bottom}] ${it.text}") }
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
                        (if (line.fromStrip) "*" else " ") + "${line.top}: ${line.text}",
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
