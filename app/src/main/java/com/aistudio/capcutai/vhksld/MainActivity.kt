package com.aistudio.capcutai.vhksld

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.transformer.*
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                VideoEditorScreen()
            }
        }
    }
}

@Composable
fun VideoEditorScreen() {
    val context = LocalContext.current
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    val pickVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        videoUri = uri
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Aashu Video Editor", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(32.dp))

        Button(onClick = { pickVideoLauncher.launch("video/*") }) {
            Text("1. Select Video")
        }

        Spacer(Modifier.height(16.dp))

        if (videoUri != null) {
            Text("Video Selected ✓")
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    isProcessing = true
                    trimVideo(context, videoUri!!) { success, path ->
                        isProcessing = false
                        val msg = if (success) "Saved: $path" else "Export Failed"
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                },
                enabled = !isProcessing
            ) {
                Text(if (isProcessing) "Processing..." else "2. Trim 5 Sec & Export")
            }
        }
        
        if (isProcessing) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
        }
    }
}

fun trimVideo(context: android.content.Context, inputUri: Uri, onComplete: (Boolean, String) -> Unit) {
    val outputFile = File(context.getExternalFilesDir(null), "edited_${System.currentTimeMillis()}.mp4")
    
    val inputMediaItem = MediaItem.Builder()
        .setUri(inputUri)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(0)
                .setEndPositionMs(5000)
                .build()
        )
        .build()

    val transformer = Transformer.Builder(context)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(mediaItem: MediaItem, result: ExportResult) {
                onComplete(true, outputFile.absolutePath)
            }

            override fun onError(mediaItem: MediaItem, result: ExportResult, exception: ExportException) {
                onComplete(false, exception.message ?: "Unknown error")
            }
        })
        .build()

    transformer.start(inputMediaItem, outputFile.absolutePath)
}
