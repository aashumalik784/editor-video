package com.aistudio.capcutai.vhksld

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

class MainActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private val TAG = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // setContentView(R.layout.activity_main) // uncomment & provide a layout if you have one

        // Initialize ExoPlayer (Media3)
        player = ExoPlayer.Builder(this).build().also { exo ->
            // Example: prepare a media item if you need
            // val item = MediaItem.fromUri("https://storage.googleapis.com/exoplayer-test-media-0/play.mp3")
            // exo.setMediaItem(item)
            // exo.prepare()

            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    super.onPlaybackStateChanged(playbackState)
                    if (playbackState == Player.STATE_ENDED) {
                        exo.currentMediaItem?.let { mediaItem ->
                            onCompleted(mediaItem)
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    super.onPlayerError(error)
                    val current = exo.currentMediaItem
                    onError(current, error)
                }
            })
        }
    }

    // Use correct types from androidx.media3
    open fun onCompleted(mediaItem: MediaItem) {
        Log.i(TAG, "Playback completed for mediaItem: ${mediaItem.mediaId ?: mediaItem.localConfiguration?.uri}")
        Toast.makeText(this@MainActivity, "Playback completed", Toast.LENGTH_SHORT).show()
    }

    open fun onError(mediaItem: MediaItem?, throwable: Throwable?) {
        Log.e(TAG, "Playback error for mediaItem=$mediaItem", throwable)
        Toast.makeText(this@MainActivity, "Playback error: ${throwable?.message ?: "unknown"}", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
