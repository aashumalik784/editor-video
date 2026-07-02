package com.aistudio.capcutai.vhksld

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player

class MainActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // setContentView(R.layout.activity_main) // uncomment & adjust as needed

        // Create and prepare player
        player = ExoPlayer.Builder(this).build().also { exo ->
            // example: add a media item, prepare, etc.
            // val item = MediaItem.fromUri("https://example.com/video.mp4")
            // exo.setMediaItem(item)
            // exo.prepare()

            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        exo.currentMediaItem?.let { mediaItem ->
                            // call our completion handler with the correct MediaItem type
                            onCompleted(mediaItem)
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    // pass the current media item (if any) and the error to our handler
                    val current = exo.currentMediaItem
                    if (current != null) {
                        onError(current, error)
                    } else {
                        // fallback handling if no media item
                        onError(null, error)
                    }
                }
            })
        }
    }

    // Corrected signatures: use MediaItem (not Composition)
    // If the interface you're implementing expects non-null MediaItem, keep MediaItem (non-null).
    // If it expects nullable, change to MediaItem?.
    open fun onCompleted(mediaItem: MediaItem) {
        // TODO: your completion logic here
        // Example:
        // Log.d("MainActivity", "Playback completed for: ${mediaItem.mediaId ?: mediaItem.localConfiguration?.uri}")
    }

    open fun onError(mediaItem: MediaItem?, throwable: Throwable?) {
        // TODO: your error handling logic here
        // mediaItem may be null if the error occurred before any media was set.
        // Example:
        // Log.e("MainActivity", "Playback error for item=$mediaItem", throwable)
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
