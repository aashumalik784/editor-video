package com.aistudio.capcutai.vhksld

import android.net.Uri
import android.os.Bundle
import com.arthenica.ffmpegkit.ReturnCode
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
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegKit
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
                    trimVideo(context, videoUri!!) { success ->
                        isProcessing = false
                        val msg = if (success) "Export Success!" else "Export Failed"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
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

fun trimVideo(context: android.content.Context, inputUri: Uri, onComplete: (Boolean) -> Unit) {
    val inputStream = context.contentResolver.openInputStream(inputUri)
    val inputFile = File(context.cacheDir, "input.mp4")
    inputStream?.use { input ->
        inputFile.outputStream().use { output -> input.copyTo(output) }
    }

    val outputFile = File(context.getExternalFilesDir(null), "edited_${System.currentTimeMillis()}.mp4")
    
    val cmd = "-i ${inputFile.absolutePath} -ss 0 -t 5 -c copy ${outputFile.absolutePath}"
    
    FFmpegKit.executeAsync(cmd) { executionId, returnCode ->
        if (returnCode == ReturnCode.SUCCESS) {
            onComplete(true)
        } else {
            onComplete(false)
        }
    }
}
