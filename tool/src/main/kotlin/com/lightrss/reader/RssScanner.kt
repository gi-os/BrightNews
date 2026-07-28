package com.lightrss.reader

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.pm.PackageManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QR scanning built the same way LightPass builds its camera screens: this tool owns the
 * [LifecycleCameraController], binds it to the lifecycle itself, and shows it through a
 * [PreviewView].
 *
 * The SDK's own scanner composable was refusing to start whenever the LightOS permission call
 * failed, which is what a sideloaded tool gets. Driving CameraX directly means the only thing
 * that can stop the preview is the Android permission itself, and when that happens the reason
 * is printed on screen instead of being swallowed.
 */
@Composable
fun FeedQrScanner(
    onScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val launcher = rememberPermissionRequestLauncher(Manifest.permission.CAMERA)
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    var androidGranted by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var asked by remember { mutableStateOf(false) }

    // Android's own permission prompt, used when LightOS will not put its dialog up.
    val systemRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        androidGranted = granted
        if (!granted) {
            status = "Android refused the camera. Grant it with the command below."
        }
    }

    // Re-checks every time the screen resumes, which covers coming back from the LightOS
    // permission dialog. Same shape as the LightPass permission gate.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            androidGranted = context.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

            if (androidGranted) {
                status = null
                return@repeatOnLifecycle
            }

            // Not granted yet. Prefer the LightOS dialog, but it only exists if the server is
            // willing to talk to us; when it is not, fall back to Android's own prompt.
            val server = checkPermission(Manifest.permission.CAMERA).asKotlinResult
            val serverAnswered = server.isSuccess
            status = server.fold(
                onSuccess = { "LightOS: ${it.permissionResult}" },
                onFailure = { "LightOS did not answer — asking Android directly" },
            )
            if (!asked) {
                asked = true
                if (serverAnswered) launcher?.launch() else systemRequest.launch(Manifest.permission.CAMERA)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (androidGranted) {
            CameraPreview(
                onScanned = onScanned,
                onError = { message -> status = message },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(horizontal = 2f.gridUnitsAsDp()),
                contentAlignment = Alignment.Center,
            ) {
                LightText(
                    text = "Waiting for camera access.\n\nAllow the camera when the phone asks, " +
                        "then this screen picks it up.",
                    variant = LightTextVariant.Paragraph,
                    align = TextAlign.Center,
                    lighten = true,
                )
            }
        }

        val message = status
        if (message != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(LightThemeTokens.colors.background)
                    .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.4f.gridUnitsAsDp()),
            ) {
                LightText(
                    text = message,
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                LightText(
                    text = "adb shell pm grant com.lightrss.reader android.permission.CAMERA",
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CameraPreview(
    onScanned: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val onScannedState = rememberUpdatedState(onScanned)
    val scannedOnce = remember { AtomicBoolean(false) }

    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }

    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        }
    }

    DisposableEffect(controller, scanner) {
        val executor = context.mainExecutor
        val analyzer = MlKitAnalyzer(
            listOf(scanner),
            CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
            executor,
        ) { result ->
            if (result.getThrowable(scanner) != null) return@MlKitAnalyzer
            val value = result.getValue(scanner)
                ?.asSequence()
                ?.mapNotNull { it.rawValue ?: it.displayValue }
                ?.firstOrNull { it.isNotBlank() }
            if (value != null && scannedOnce.compareAndSet(false, true)) {
                onScannedState.value(value)
            }
        }
        controller.setImageAnalysisAnalyzer(executor, analyzer)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            scanner.close()
        }
    }

    // Bind here rather than from the view's attach callback, the way LightPass does it.
    LaunchedEffect(controller, lifecycleOwner) {
        runCatching { controller.bindToLifecycle(lifecycleOwner) }
            .onFailure { error -> onError("Camera: $error") }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                this.controller = controller
            }
        },
    )
}
