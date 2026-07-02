package com.aistudio.capcutai.vhksld

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player

class MainActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private val TAG = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // setContentView(R.layout.activity_main) // uncomment & provide a layout if you have one

        // Initialize ExoPlayer
        player = ExoPlayer.Builder(this).build().also { exo ->
            // Example media item (replace with your URI)
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

    // Use correct types: MediaItem and PlaybackException/Throwable
    open fun onCompleted(mediaItem: MediaItem) {
        // Playback completed for mediaItem
        Log.i(TAG, "Playback completed for mediaItem: ${mediaItem.mediaId ?: mediaItem.localConfiguration?.uri}")
        Toast.makeText(this, "Playback completed", Toast.LENGTH_SHORT).show()
    }

    open fun onError(mediaItem: MediaItem?, throwable: Throwable?) {
        Log.e(TAG, "Playback error for mediaItem=$mediaItem", throwable)
        Toast.makeText(this, "Playback error: ${throwable?.message ?: "unknown"}", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
