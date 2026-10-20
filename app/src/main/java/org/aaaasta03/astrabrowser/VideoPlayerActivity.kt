package org.aaaasta03.astrabrowser

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import kotlin.math.abs

/**
 * Astra native player for downloaded files and compatible direct media URLs.
 * Web video pages such as YouTube continue to use their own player inside GeckoView.
 */
@OptIn(UnstableApi::class)
class VideoPlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var controlsOverlay: LinearLayout
    private lateinit var playPauseButton: TextView
    private lateinit var timeLabel: TextView
    private lateinit var seekBar: SeekBar
    private var userSeeking = false
    private var pendingSeekPosition = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val preferences by lazy {
        getSharedPreferences("astra_browser_library", MODE_PRIVATE)
    }

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private val resizeLabels = arrayOf("Fit", "Fill", "Zoom")
    private val playbackSpeeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    private val playbackSpeedLabels = arrayOf("0.5×", "0.75×", "Normal (1×)", "1.25×", "1.5×", "1.75×", "2×")

    private val refreshProgress = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }
    private val hideControls = Runnable {
        if (player?.isPlaying == true) setControlsVisible(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        enterImmersiveMode()

        playerView = PlayerView(this).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setResizeMode(resizeModes[preferences.getInt("native_video_fit_mode", 0).coerceIn(0, resizeModes.lastIndex)])
            contentDescription = "Astra video surface"
            setOnClickListener { toggleControls() }
        }

        controlsOverlay = buildControlsOverlay()
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView, FrameLayout.LayoutParams(-1, -1))
            addView(controlsOverlay, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)

        val source = intent.getStringExtra(EXTRA_SOURCE)
        if (source.isNullOrBlank()) {
            Toast.makeText(this, "No video source provided", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val mediaItem = MediaItem.Builder().setUri(Uri.parse(source)).build()
        player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(5_000L)
            .setSeekForwardIncrementMs(15_000L)
            .build().also { exo ->
                playerView.player = exo
                exo.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updatePlayPauseButton()
                        if (isPlaying) scheduleHideControls() else setControlsVisible(true)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        updateProgress()
                    }

                    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                        preferences.edit()
                            .putFloat("video_playback_speed", playbackParameters.speed.coerceIn(0.5f, 2f))
                            .apply()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Toast.makeText(
                            this@VideoPlayerActivity,
                            "Can't play this video format or stream",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                })
                exo.setPlaybackSpeed(preferences.getFloat("video_playback_speed", 1f).coerceIn(0.5f, 2f))
                exo.setMediaItem(mediaItem)
                exo.playWhenReady = true
                exo.prepare()
            }

        handler.post(refreshProgress)
        scheduleHideControls()
    }

    private fun buildControlsOverlay(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x22000000, 0x00000000, 0x77000000.toInt())
            )
            isClickable = false

            val top = LinearLayout(this@VideoPlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(controlButton("‹", "Back", 30f) { finish() },
                LinearLayout.LayoutParams(dp(48), dp(48)))
            top.addView(TextView(this@VideoPlayerActivity).apply {
                text = "ASTRA PLAYER"
                textSize = 13f
                letterSpacing = 0.12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                alpha = 0.88f
                setPadding(dp(8), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(top)

            addView(LinearLayout(this@VideoPlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                weightSum = 3f
                addView(controlButton("↶\n5", "Rewind 5 seconds", 19f) {
                    seekBy(-5_000L)
                }, LinearLayout.LayoutParams(dp(72), dp(68)).apply { marginEnd = dp(24) })
                playPauseButton = TextView(this@VideoPlayerActivity).apply {
                    text = "▶"
                    textSize = 27f
                    gravity = Gravity.CENTER
                    setTextColor(Color.BLACK)
                    background = circle(Color.WHITE)
                    contentDescription = "Play or pause"
                    setOnClickListener {
                        player?.let { if (it.isPlaying) it.pause() else it.play() }
                        updatePlayPauseButton()
                        scheduleHideControls()
                    }
                }
                addView(playPauseButton, LinearLayout.LayoutParams(dp(68), dp(68)))
                addView(controlButton("↷\n15", "Forward 15 seconds", 19f) {
                    seekBy(15_000L)
                }, LinearLayout.LayoutParams(dp(72), dp(68)).apply { marginStart = dp(24) })
            }, LinearLayout.LayoutParams(-1, 0, 1f))

            seekBar = SeekBar(this@VideoPlayerActivity).apply {
                max = 1000
                progressTintList = ColorStateList.valueOf(Color.WHITE)
                progressBackgroundTintList = ColorStateList.valueOf(0x99FFFFFF.toInt())
                thumbTintList = ColorStateList.valueOf(Color.WHITE)
                setPadding(0, 0, 0, 0)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val duration = player?.duration?.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
                            pendingSeekPosition = duration * progress / 1000
                            timeLabel.text = "${formatTime(pendingSeekPosition)}   ·   ${formatTime(duration)}"
                        }
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) {
                        userSeeking = true
                        handler.removeCallbacks(hideControls)
                    }

                    override fun onStopTrackingTouch(bar: SeekBar?) {
                        player?.seekTo(pendingSeekPosition)
                        userSeeking = false
                        scheduleHideControls()
                    }
                })
            }
            addView(seekBar, LinearLayout.LayoutParams(-1, dp(28)))

            val bottom = LinearLayout(this@VideoPlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(2), dp(2), 0)
            }
            timeLabel = TextView(this@VideoPlayerActivity).apply {
                text = "00:00   ·   00:00"
                textSize = 14f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            bottom.addView(timeLabel, LinearLayout.LayoutParams(0, dp(42), 1f))
            bottom.addView(controlButton("CC", "Subtitles and captions", 15f) {
                showTrackSelection("Subtitles / captions", C.TRACK_TYPE_TEXT)
            }, LinearLayout.LayoutParams(dp(46), dp(42)).apply { marginEnd = dp(10) })
            bottom.addView(controlButton("⚙", "Video settings", 24f) {
                showVideoSettings()
            }, LinearLayout.LayoutParams(dp(48), dp(42)))
            addView(bottom)
        }
    }

    private fun controlButton(
        text: String,
        description: String,
        size: Float,
        action: () -> Unit
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        contentDescription = description
        background = roundedControlBackground()
        setOnClickListener { action() }
    }

    private fun roundedControlBackground(): GradientDrawable =
        GradientDrawable().apply {
            setColor(0x44000000)
            cornerRadius = dp(28).toFloat()
        }

    private fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: Long.MAX_VALUE
        exo.seekTo((exo.currentPosition + deltaMs).coerceIn(0L, duration))
        updateProgress()
        scheduleHideControls()
    }

    private fun updatePlayPauseButton() {
        if (!::playPauseButton.isInitialized) return
        val playing = player?.isPlaying == true
        playPauseButton.text = if (playing) "Ⅱ" else "▶"
        playPauseButton.contentDescription = if (playing) "Pause" else "Play"
    }

    private fun updateProgress() {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        val position = exo.currentPosition.coerceAtLeast(0L)
        if (!userSeeking) {
            seekBar.progress = if (duration > 0) (position.toDouble() * seekBar.max / duration).toInt() else 0
            timeLabel.text = "${formatTime(position)}   ·   ${formatTime(duration)}"
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
        else String.format("%02d:%02d", totalSeconds / 60, seconds)
    }

    private fun toggleControls() {
        setControlsVisible(controlsOverlay.visibility != View.VISIBLE)
        if (controlsOverlay.visibility == View.VISIBLE) scheduleHideControls()
    }

    private fun setControlsVisible(visible: Boolean) {
        if (!::controlsOverlay.isInitialized) return
        controlsOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) enterImmersiveMode()
        else handler.removeCallbacks(hideControls)
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControls)
        if (player?.isPlaying == true && !userSeeking) {
            handler.postDelayed(hideControls, 3_500L)
        }
    }

    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    private fun showVideoSettings() {
        AlertDialog.Builder(this)
            .setTitle("Astra video settings")
            .setItems(arrayOf("Video quality", "Audio track", "Subtitles / captions", "Playback speed", "Video fit")) { _, which ->
                when (which) {
                    0 -> showTrackSelection("Video quality", C.TRACK_TYPE_VIDEO, adaptive = true)
                    1 -> showTrackSelection("Audio track", C.TRACK_TYPE_AUDIO)
                    2 -> showTrackSelection("Subtitles / captions", C.TRACK_TYPE_TEXT)
                    3 -> showPlaybackSpeed()
                    4 -> showVideoFit()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showTrackSelection(title: String, trackType: Int, adaptive: Boolean = false) {
        val exo = player ?: return
        if (exo.currentTracks.groups.none { it.type == trackType }) {
            Toast.makeText(this, "No selectable tracks are available for this video", Toast.LENGTH_SHORT).show()
            return
        }
        TrackSelectionDialogBuilder(this, title, exo, trackType)
            .setAllowAdaptiveSelections(adaptive)
            .build()
            .show()
    }

    private fun showPlaybackSpeed() {
        val exo = player ?: return
        val current = playbackSpeeds.indices.minByOrNull {
            abs(playbackSpeeds[it] - exo.playbackParameters.speed)
        } ?: 2
        AlertDialog.Builder(this)
            .setTitle("Playback speed")
            .setSingleChoiceItems(playbackSpeedLabels, current) { dialog, which ->
                val speed = playbackSpeeds[which]
                exo.setPlaybackSpeed(speed)
                preferences.edit().putFloat("video_playback_speed", speed).apply()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showVideoFit() {
        val current = preferences.getInt("native_video_fit_mode", 0).coerceIn(0, resizeModes.lastIndex)
        AlertDialog.Builder(this)
            .setTitle("Video fit")
            .setSingleChoiceItems(resizeLabels, current) { dialog, which ->
                val mode = resizeModes[which]
                playerView.setResizeMode(mode)
                preferences.edit().putInt("native_video_fit_mode", which).apply()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        player?.playWhenReady = true
        scheduleHideControls()
    }

    override fun onPause() {
        player?.playWhenReady = false
        handler.removeCallbacks(hideControls)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshProgress)
        handler.removeCallbacks(hideControls)
        playerView.player = null
        player?.release()
        player = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_SOURCE = "org.aaaasta03.astrabrowser.video.SOURCE"
    }
}
