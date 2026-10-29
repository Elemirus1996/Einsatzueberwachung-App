package io.github.elemirus1996.einsatzspur.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.github.elemirus1996.einsatzspur.kern.QrAuswertung
import io.github.elemirus1996.einsatzspur.kern.TeamQRCode
import java.util.concurrent.Executors

/** Kamera-Vorschau mit QR-Auswertung (CameraX + ZXing). Meldet nur Team-QR-Codes. */
@Composable
fun QrScanner(gefunden: (TeamQRCode) -> Unit, abbrechen: () -> Unit) {
    val context = LocalContext.current
    val lebenszyklus = LocalLifecycleOwner.current
    var erlaubt by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var hinweis by remember { mutableStateOf<String?>(null) }
    val anfrage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { erlaubt = it }
    LaunchedEffect(Unit) { if (!erlaubt) anfrage.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (erlaubt) {
            val auswertung = remember { Executors.newSingleThreadExecutor() }
            var fertig by remember { mutableStateOf(false) }
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { vorschau ->
                        val anbieterZukunft = ProcessCameraProvider.getInstance(ctx)
                        anbieterZukunft.addListener({
                            val anbieter = anbieterZukunft.get()
                            val preview = Preview.Builder().build().also { it.surfaceProvider = vorschau.surfaceProvider }
                            val analyse = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            analyse.setAnalyzer(auswertung) { bild ->
                                val ebene = bild.planes[0]
                                val daten = ByteArray(ebene.buffer.remaining()).also { ebene.buffer.get(it) }
                                val ergebnis = QrAuswertung.teamCode(daten, bild.width, bild.height, ebene.rowStride)
                                bild.close()
                                ContextCompat.getMainExecutor(ctx).execute {
                                    when (ergebnis) {
                                        is QrAuswertung.Ergebnis.Team -> if (!fertig) {
                                            fertig = true
                                            gefunden(ergebnis.zugang)
                                        }
                                        QrAuswertung.Ergebnis.Fremd -> hinweis = "Das ist kein Team-QR-Code des Einsatzes."
                                        QrAuswertung.Ergebnis.Nichts -> Unit
                                    }
                                }
                            }
                            anbieter.unbindAll()
                            anbieter.bindToLifecycle(lebenszyklus, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyse)
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            DisposableEffect(Unit) {
                onDispose {
                    runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
                    auswertung.shutdown()
                }
            }
        } else {
            Text(
                "Für den QR-Scan braucht die App die Kamera. Alternativ Server und Code von Hand eingeben.",
                Modifier.align(Alignment.Center).padding(24.dp), color = Color.White,
            )
        }
        Column(Modifier.safeDrawingPadding().padding(8.dp)) {
            TextButton(onClick = abbrechen) { Text("‹ Abbrechen", color = Color.White) }
        }
        hinweis?.let {
            Text(
                it, Modifier.align(Alignment.BottomCenter).padding(32.dp)
                    .background(Color(0xCC000000), MaterialTheme.shapes.medium).padding(12.dp),
                color = Color.White,
            )
        }
    }
}
