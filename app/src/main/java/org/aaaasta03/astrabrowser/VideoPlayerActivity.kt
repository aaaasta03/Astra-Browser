package org.aaaasta03.astrabrowser

import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
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
 * Astra's native player for downloaded files and compatible direct media sources.
 * Web pages such as YouTube remain in GeckoView and use the site's own player.
 */
@OptIn(UnstableApi::class)
class VideoPlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var controlsOverlay: LinearLayout
    private lateinit var playPauseButton: TextView
    private lateinit var elapsedLabel: TextView
    private lateinit var totalLabel: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var titleLabel: TextView
    private lateinit var muteButton: TextView
    private lateinit var lockButton: TextView
    private var userSeeking = false
    private var pendingSeekPosition = 0L
    private var controlsLocked = false
    private var controlsShownInPip = false
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
            handler.postDelayed(this, 500L)
        }
    }
    private val hideControls = Runnable {
        if (player?.isPlaying == true && !controlsLocked && !isInPictureInPictureMode) {
            setControlsVisible(false)
        }
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
            setOnClickListener { if (!controlsLocked) toggleControls() }
            setOnLongClickListener {
                if (controlsLocked) {
                    controlsLocked = false
                    updateLockButton()
                    setControlsVisible(true)
                    Toast.makeText(this@VideoPlayerActivity, "Video controls unlocked", Toast.LENGTH_SHORT).show()
                    true
                } else false
            }
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
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank {
            Uri.parse(source).lastPathSegment
                ?.substringBefore('?')
                ?.substringBeforeLast('.', Uri.parse(source).lastPathSegment.orEmpty())
                ?.replace('_', ' ')
                ?.replace('-', ' ')
                ?.take(72)
                ?.takeIf { it.isNotBlank() }
                ?: "Now playing"
        }
        titleLabel.text = title

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(source))
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setTitle(title).build()
            )
            .build()

        player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
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

        updateMuteButton()
        updateLockButton()
        handler.post(refreshProgress)
        scheduleHideControls()
    }

    private fun buildControlsOverlay(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x66000000, 0x08000000, 0x88000000.toInt())
            )
            isClickable = false

            val top = LinearLayout(this@VideoPlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(controlButton("‹", "Back", 30f) { finish() },
                LinearLayout.LayoutParams(dp(44), dp(44)))
            titleLabel = TextView(this@VideoPlayerActivity).apply {
                text = "Now playing"
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE)
                setPadding(dp(8), 0, dp(10), 0)
            }
            top.addView(titleLabel, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(controlButton("↻", "Rotate orientation", 21f) {
                toggleOrientation()
            }, LinearLayout.LayoutParams(dp(42), dp(44)))
            muteButton = controlButton("🔊", "Mute audio", 19f) {
                player?.let { it.volume = if (it.volume > 0f) 0f else 1f }
                updateMuteButton()
            }
            top.addView(muteButton, LinearLayout.LayoutParams(dp(42), dp(44)))
            top.addView(controlButton("▣", "Picture in Picture", 20f) {
                enterPlayerPictureInPicture()
            }, LinearLayout.LayoutParams(dp(42), dp(44)))
            addView(top)

            addView(LinearLayout(this@VideoPlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(controlButton("↶\n10", "Rewind 10 seconds", 18f) {
                    seekBy(-10_000L)
                }, LinearLayout.LayoutParams(dp(68), dp(64)).apply { marginEnd = dp(22) })

                playPauseButton = TextView(this@VideoPlayerActivity).apply {
                    text = "▶"
                    textSize = 27f
                    gravity = Gravity.CENTER
                    setTextColor(Color.BLACK)
                    background = circle(Color.WHITE)
                    contentDescription = "Play"
                    setOnClickListener {
                        player?.let { if (it.isPlaying) it.pause() else it.play() }
                        updatePlayPauseButton()
                        scheduleHideControls()
                    }
                }
                addView(playPauseButton, LinearLayout.LayoutParams(dp(64), dp(64)))

                addView(controlButton("↷\n10", "Forward 10 seconds", 18f) {
                    seekBy(10_000L)
                }, LinearLayout.LayoutParams(dp(68), dp(64)).apply { marginStart = dp(22) })
            }, LinearLayout.LayoutParams(-1, 0, 1f))

            val timeline = LinearLayout(this@VideoPlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            elapsedLabel = TextView(this@VideoPlayerActivity).apply {
                text = "00:00"
                textSize = 12f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            timeline.addView(elapsedLabel, LinearLayout.LayoutParams(dp(54), dp(34)))
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
                            elapsedLabel.text = formatTime(pendingSeekPosition)
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
            timeline.addView(seekBar, LinearLayout.LayoutParams(0, dp(30), 1f))
            totalLabel = TextView(this@VideoPlayerActivity).apply {
                text = "00:00"
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            timeline.addView(totalLabel, LinearLayout.LayoutParams(dp(60), dp(34)))
            addView(timeline)

            val dock = LinearLayout(this@VideoPlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = pillBackground()
                setPadding(dp(5), dp(2), dp(5), dp(2))
            }
            lockButton = controlButton("🔓", "Lock video controls", 17f) {
                controlsLocked = true
                updateLockButton()
                setControlsVisible(false)
                Toast.makeText(this@VideoPlayerActivity, "Controls locked · long-press the video to unlock", Toast.LENGTH_LONG).show()
            }
            dock.addView(lockButton, LinearLayout.LayoutParams(dp(42), dp(44)))
            dock.addView(controlButton("CC", "Subtitles and captions", 13f) {
                showTrackSelection("Subtitles / captions", C.TRACK_TYPE_TEXT)
            }, LinearLayout.LayoutParams(dp(42), dp(44)))
            dock.addView(controlButton("♫", "Audio tracks", 19f) {
                showTrackSelection("Audio track", C.TRACK_TYPE_AUDIO)
            }, LinearLayout.LayoutParams(dp(42), dp(44)))
            dock.addView(controlButton("1×", "Playback speed", 14f) {
                showPlaybackSpeed()
            }, LinearLayout.LayoutParams(dp(42), dp(44)))
            dock.addView(controlButton("▣", "Video fit", 18f) {
                showVideoFit()
            }, LinearLayout.LayoutParams(dp(42), dp(44)))
            dock.addView(controlButton("⚙", "Video settings", 20f) {
                showVideoSettings()
            }, LinearLayout.LayoutParams(dp(42), dp(44)))
            addView(dock, LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(3)
            })
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
            setColor(0x33000000)
            cornerRadius = dp(24).toFloat()
        }

    private fun pillBackground(): GradientDrawable =
        GradientDrawable().apply {
            setColor(0x55000000)
            cornerRadius = dp(26).toFloat()
            setStroke(dp(1), 0x44FFFFFF)
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

    private fun updateMuteButton() {
        if (!::muteButton.isInitialized) return
        val muted = (player?.volume ?: 1f) <= 0f
        muteButton.text = if (muted) "🔇" else "🔊"
        muteButton.contentDescription = if (muted) "Unmute audio" else "Mute audio"
    }

    private fun updateLockButton() {
        if (!::lockButton.isInitialized) return
        lockButton.text = if (controlsLocked) "🔒" else "🔓"
        lockButton.contentDescription = if (controlsLocked) "Unlock controls" else "Lock controls"
    }

    private fun updateProgress() {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        val position = exo.currentPosition.coerceAtLeast(0L)
        if (!userSeeking) {
            seekBar.progress = if (duration > 0) (position.toDouble() * seekBar.max / duration).toInt() else 0
            elapsedLabel.text = formatTime(position)
            totalLabel.text = formatTime(duration)
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0L) / 1000L
        val seconds = totalSeconds % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) String.format("%d:%02d:%02d", hours, (totalSeconds / 60) % 60, seconds)
        else String.format("%02d:%02d", totalSeconds / 60, seconds)
    }

    private fun toggleControls() {
        if (controlsLocked || isInPictureInPictureMode) return
        setControlsVisible(controlsOverlay.visibility != View.VISIBLE)
        if (controlsOverlay.visibility == View.VISIBLE) scheduleHideControls()
    }

    private fun setControlsVisible(visible: Boolean) {
        if (!::controlsOverlay.isInitialized) return
        if (controlsLocked && visible) return
        controlsOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) enterImmersiveMode()
        else handler.removeCallbacks(hideControls)
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControls)
        if (player?.isPlaying == true && !controlsLocked && !isInPictureInPictureMode && controlsOverlay.visibility == View.VISIBLE) {
            handler.postDelayed(hideControls, 4_000L)
        }
    }

    private fun toggleOrientation() {
        val next = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        requestedOrientation = next
    }

    private fun enterPlayerPictureInPicture() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build()
                )
            }.onFailure {
                Toast.makeText(this, "Picture-in-Picture is unavailable on this device", Toast.LENGTH_SHORT).show()
            }
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
            .setItems(arrayOf("Video quality", "Audio track", "Subtitles / captions", "Playback speed", "Video fit", "Playback information")) { _, which ->
                when (which) {
                    0 -> showTrackSelection("Video quality", C.TRACK_TYPE_VIDEO, adaptive = true)
                    1 -> showTrackSelection("Audio track", C.TRACK_TYPE_AUDIO)
                    2 -> showTrackSelection("Subtitles / captions", C.TRACK_TYPE_TEXT)
                    3 -> showPlaybackSpeed()
                    4 -> showVideoFit()
                    5 -> showPlaybackInformation()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showPlaybackInformation() {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        AlertDialog.Builder(this)
            .setTitle("Playback information")
            .setMessage(
                "${titleLabel.text}\n\n" +
                    "Position: ${formatTime(exo.currentPosition)} / ${formatTime(duration)}\n" +
                    "Speed: ${exo.playbackParameters.speed}×\n" +
                    "Video: ${exo.videoFormat?.let { "${it.width} × ${it.height}" } ?: "Not available"}\n" +
                    "Audio: ${exo.audioFormat?.sampleRate?.let { "${it} Hz" } ?: "Not available"}"
            )
            .setPositiveButton("OK", null)
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
        if (hasFocus && !isInPictureInPictureMode) enterImmersiveMode()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        controlsShownInPip = controlsOverlay.visibility == View.VISIBLE
        if (isInPictureInPictureMode) {
            controlsOverlay.visibility = View.GONE
        } else if (!controlsLocked && controlsShownInPip) {
            controlsOverlay.visibility = View.VISIBLE
        }
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
        const val EXTRA_TITLE = "org.aaaasta03.astrabrowser.video.TITLE"
    }
}
