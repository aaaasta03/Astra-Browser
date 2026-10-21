package org.aaaasta03.astrabrowser

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
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
import kotlin.math.roundToInt

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
    private lateinit var speedButton: TextView
    private lateinit var lockButton: TextView
    private var userSeeking = false
    private var pendingSeekPosition = 0L
    private var controlsLocked = false
    private var controlsShownInPip = false
    private var enteringPictureInPicture = false
    private var lifecyclePausedPlayback = false
    private var lifecyclePauseWasPlaying = false
    private var lastAudibleVolume = 1f
    private var playerBrightnessPercent = 70
    private var playerVolumePercent = 100
    private var gestureStartY = 0f
    private var gestureStartLevel = 0
    private var activeLevelGesture: LevelGesture? = null
    private lateinit var brightnessHud: LinearLayout
    private lateinit var volumeHud: LinearLayout
    private lateinit var brightnessLevelBar: VerticalLevelBar
    private lateinit var volumeLevelBar: VerticalLevelBar
    private lateinit var brightnessPercentLabel: TextView
    private lateinit var volumePercentLabel: TextView

    private enum class LevelGesture { BRIGHTNESS, VOLUME }

    private val hideLevelIndicators = Runnable {
        if (::brightnessHud.isInitialized) brightnessHud.visibility = View.GONE
        if (::volumeHud.isInitialized) volumeHud.visibility = View.GONE
    }
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
        if (preferences.contains("native_video_brightness_percent")) {
            applyPlayerBrightness(preferences.getInt("native_video_brightness_percent", 70), persist = false)
        }
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
        brightnessHud = buildLevelIndicator(LevelGesture.BRIGHTNESS)
        volumeHud = buildLevelIndicator(LevelGesture.VOLUME)
        val root = PlayerGestureRoot(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView, FrameLayout.LayoutParams(-1, -1))
            addView(controlsOverlay, FrameLayout.LayoutParams(-1, -1))
            addView(brightnessHud, FrameLayout.LayoutParams(dp(68), dp(178), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(18) })
            addView(volumeHud, FrameLayout.LayoutParams(dp(68), dp(178), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(18) })
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
        val initialVolumePercent = preferences.getInt("native_video_volume_percent", 100).coerceIn(0, 100)
        lastAudibleVolume = preferences.getFloat(
            "native_video_last_audible_volume",
            if (initialVolumePercent > 0) initialVolumePercent / 100f else 1f
        ).coerceIn(0.01f, 1f)

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
                        updatePlaybackSpeedButton()
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
                exo.volume = initialVolumePercent / 100f
                exo.setMediaItem(mediaItem)
                exo.playWhenReady = true
                exo.prepare()
            }

        updateMuteButton()
        updatePlaybackSpeedButton()
        updateLockButton()
        handler.post(refreshProgress)
        scheduleHideControls()
    }

    private inner class PlayerGestureRoot(context: android.content.Context) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var candidate = false

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    candidate = !controlsLocked && !isInPictureInPictureMode &&
                        (controlsOverlay.visibility != View.VISIBLE ||
                            (event.y >= height * 0.18f && event.y <= height * 0.86f))
                    activeLevelGesture = null
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (candidate && activeLevelGesture == null && abs(dy) > slop && abs(dy) > abs(dx) * 1.15f) {
                        beginLevelGesture(if (downX < width / 2f) LevelGesture.BRIGHTNESS else LevelGesture.VOLUME)
                        return activeLevelGesture != null
                    }
                    if (activeLevelGesture != null) return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (activeLevelGesture == null) candidate = false
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val type = activeLevelGesture ?: return true
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (height <= 0) return true
                    val delta = ((downY - event.y) * 100f / height).toInt()
                    val level = (gestureStartLevel + delta).coerceIn(0, 100)
                    if (type == LevelGesture.BRIGHTNESS) {
                        playerBrightnessPercent = level
                        applyPlayerBrightness(level, persist = false)
                    } else {
                        playerVolumePercent = level
                        applyMediaVolumePercent(level, persist = false)
                    }
                    updateLevelIndicator(type, level)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (type == LevelGesture.BRIGHTNESS) applyPlayerBrightness(playerBrightnessPercent)
                    else applyMediaVolumePercent(playerVolumePercent)
                    activeLevelGesture = null
                    candidate = false
                    handler.removeCallbacks(hideLevelIndicators)
                    handler.postDelayed(hideLevelIndicators, 950L)
                }
            }
            return true
        }
    }

    private inner class VerticalLevelBar(context: android.content.Context) : View(context) {
        var levelPercent: Int = 50
            set(value) { field = value.coerceIn(0, 100); invalidate() }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB9A7FF.toInt() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = dp(5).toFloat()
            val left = (width - w) / 2f
            val top = dp(3).toFloat()
            val bottom = height - dp(3).toFloat()
            val radius = w / 2f
            canvas.drawRoundRect(RectF(left, top, left + w, bottom), radius, radius, track)
            val fillHeight = (bottom - top) * levelPercent / 100f
            if (fillHeight > 0f) canvas.drawRoundRect(RectF(left, bottom - fillHeight, left + w, bottom), radius, radius, fill)
        }
    }

    private fun beginLevelGesture(type: LevelGesture) {
        if (controlsLocked || isInPictureInPictureMode) return
        handler.removeCallbacks(hideLevelIndicators)
        activeLevelGesture = type
        gestureStartLevel = if (type == LevelGesture.BRIGHTNESS) playerBrightnessPercent else playerVolumePercent
        updateLevelIndicator(type, gestureStartLevel)
    }

    private fun updateLevelIndicator(type: LevelGesture, percent: Int) {
        val level = percent.coerceIn(0, 100)
        if (type == LevelGesture.BRIGHTNESS) {
            brightnessPercentLabel.text = "$level%"
            brightnessLevelBar.levelPercent = level
            brightnessHud.visibility = View.VISIBLE
            volumeHud.visibility = View.GONE
        } else {
            volumePercentLabel.text = "$level%"
            volumeLevelBar.levelPercent = level
            volumeHud.visibility = View.VISIBLE
            brightnessHud.visibility = View.GONE
        }
    }

    private fun buildLevelIndicator(type: LevelGesture): LinearLayout {
        val bright = type == LevelGesture.BRIGHTNESS
        val bar = VerticalLevelBar(this).apply { levelPercent = if (bright) playerBrightnessPercent else playerVolumePercent }
        val percent = TextView(this).apply {
            text = if (bright) "$playerBrightnessPercent%" else "$playerVolumePercent%"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        if (bright) { brightnessLevelBar = bar; brightnessPercentLabel = percent }
        else { volumeLevelBar = bar; volumePercentLabel = percent }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = GradientDrawable().apply {
                setColor(0xDD17171D.toInt())
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), 0x44FFFFFF)
            }
            visibility = View.GONE
            isClickable = false
            addView(TextView(this@VideoPlayerActivity).apply {
                text = if (bright) "☼" else "♫"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(-1, dp(30)))
            addView(bar, LinearLayout.LayoutParams(dp(28), dp(88)).apply { topMargin = dp(7); bottomMargin = dp(6) })
            addView(percent, LinearLayout.LayoutParams(-1, dp(24)))
        }
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
                LinearLayout.LayoutParams(dp(48), dp(48)))
            titleLabel = TextView(this@VideoPlayerActivity).apply {
                text = "Now playing"
                textSize = 14f
                maxLines = 1
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1
                isFocusable = true
                isFocusableInTouchMode = true
                isSelected = true
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE)
                setPadding(dp(8), 0, dp(10), 0)
            }
            top.addView(titleLabel, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(controlButton("↻", "Rotate orientation", 21f) {
                toggleOrientation()
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            muteButton = controlButton("🔊", "Mute audio", 19f) {
                player?.let {
                    if (it.volume > 0f) {
                        lastAudibleVolume = it.volume
                        applyMediaVolumePercent(0)
                    } else {
                        applyMediaVolumePercent((lastAudibleVolume * 100f).roundToInt().coerceIn(1, 100))
                    }
                }
            }
            top.addView(muteButton, LinearLayout.LayoutParams(dp(48), dp(48)))
            top.addView(controlButton("▣", "Picture in Picture", 20f) {
                enterPlayerPictureInPicture()
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
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
            timeline.addView(seekBar, LinearLayout.LayoutParams(0, dp(42), 1f))
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
            dock.addView(lockButton, LinearLayout.LayoutParams(dp(48), dp(48)))
            dock.addView(controlButton("CC", "Subtitles and captions", 13f) {
                showTrackSelection("Subtitles / captions", C.TRACK_TYPE_TEXT)
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            dock.addView(controlButton("♫", "Audio tracks", 19f) {
                showTrackSelection("Audio track", C.TRACK_TYPE_AUDIO)
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            speedButton = controlButton("1×", "Playback speed", 14f) {
                showPlaybackSpeed()
            }
            dock.addView(speedButton, LinearLayout.LayoutParams(dp(48), dp(48)))
            dock.addView(controlButton("▣", "Video fit", 18f) {
                showVideoFit()
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            dock.addView(controlButton("⚙", "Video settings", 20f) {
                showVideoSettings()
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
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

    private fun getSystemBrightnessPercent(): Int = runCatching {
        val level = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 180)
        (level * 100f / 255f).roundToInt().coerceIn(0, 100)
    }.getOrDefault(70)

    /** Applies brightness to this Activity window only; system brightness is never written. */
    private fun applyPlayerBrightness(percent: Int, persist: Boolean = true) {
        val normalized = percent.coerceIn(0, 100)
        playerBrightnessPercent = normalized
        val params = window.attributes
        params.screenBrightness = normalized / 100f
        window.attributes = params
        if (persist) {
            preferences.edit().putInt("native_video_brightness_percent", normalized).apply()
        }
    }

    private fun applyMediaVolumePercent(percent: Int, persist: Boolean = true) {
        val normalized = percent.coerceIn(0, 100)
        playerVolumePercent = normalized
        val volume = normalized / 100f
        player?.volume = volume
        if (volume > 0f) lastAudibleVolume = volume
        if (persist) {
            preferences.edit().apply {
                putInt("native_video_volume_percent", normalized)
                if (volume > 0f) putFloat("native_video_last_audible_volume", volume)
            }.apply()
        }
        updateMuteButton()
    }

    private fun updateMuteButton() {
        if (!::muteButton.isInitialized) return
        val muted = (player?.volume ?: 1f) <= 0f
        muteButton.text = if (muted) "🔇" else "🔊"
        muteButton.contentDescription = if (muted) "Unmute audio" else "Mute audio"
    }

    private fun updatePlaybackSpeedButton() {
        if (!::speedButton.isInitialized) return
        val speed = player?.playbackParameters?.speed ?: 1f
        val label = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()
        speedButton.text = "${label}×"
        speedButton.contentDescription = "Playback speed, ${label} times"
    }

    private fun updateLockButton() {
        if (!::lockButton.isInitialized) return
        lockButton.text = if (controlsLocked) "🔒" else "🔓"
        lockButton.contentDescription = if (controlsLocked) "Unlock controls" else "Lock controls"
    }

    private fun updateProgress() {
        val exo = player ?: return
        val reportedDuration = exo.duration
        val durationKnown = reportedDuration > 0 && reportedDuration != C.TIME_UNSET
        val duration = if (durationKnown) reportedDuration else 0L
        val position = exo.currentPosition.coerceAtLeast(0L)
        if (!userSeeking) {
            seekBar.progress = if (durationKnown) (position.toDouble() * seekBar.max / duration).toInt() else 0
            elapsedLabel.text = formatTime(position)
            totalLabel.text = if (durationKnown) formatTime(duration) else "—"
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
            enteringPictureInPicture = true
            runCatching {
                val entered = enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build()
                )
                if (!entered) {
                    enteringPictureInPicture = false
                    Toast.makeText(this, "Picture-in-Picture is unavailable on this device", Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                enteringPictureInPicture = false
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
        val activePlayer = player
        val trackGroups = activePlayer?.currentTracks?.groups.orEmpty()
        val speedValue = activePlayer?.playbackParameters?.speed ?: 1f
        val speedLabel = speedValue.toString().removeSuffix(".0")
        val fitIndex = preferences.getInt("native_video_fit_mode", 0).coerceIn(0, resizeLabels.lastIndex)
        val videoFormat = activePlayer?.videoFormat
        val resolutionLabel = if (videoFormat != null && videoFormat.width > 0 && videoFormat.height > 0) {
            "${videoFormat.width} × ${videoFormat.height}"
        } else {
            "Resolution not reported"
        }

        val dialog = Dialog(this)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(9), dp(18), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 24, 31))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), 0x44FFFFFF)
            }
        }

        val handle = View(this).apply {
            background = GradientDrawable().apply {
                setColor(0x66FFFFFF)
                cornerRadius = dp(3).toFloat()
            }
        }
        sheet.addView(handle, LinearLayout.LayoutParams(dp(42), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(16)
        })

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        headerText.addView(TextView(this).apply {
            text = "Astra video settings"
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
        })
        headerText.addView(TextView(this).apply {
            text = titleLabel.text.toString().ifBlank { "Now playing" }
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(0xFFB8B8C5.toInt())
        })
        header.addView(headerText, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(controlButton("×", "Close video settings", 24f) {
            dialog.dismiss()
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        sheet.addView(header, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(14)
        })

        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        fun addSection(label: String) {
            rows.addView(TextView(this).apply {
                text = label.uppercase(java.util.Locale.ROOT)
                textSize = 11f
                letterSpacing = 0.08f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(0xFFB9A7FF.toInt())
                setPadding(dp(4), dp(10), dp(4), dp(8))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        fun addRow(icon: String, title: String, detail: String, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(68)
                setPadding(dp(10), dp(7), dp(10), dp(7))
                isClickable = true
                isFocusable = true
                background = GradientDrawable().apply {
                    setColor(0xFF292933.toInt())
                    cornerRadius = dp(15).toFloat()
                    setStroke(dp(1), 0x20FFFFFF)
                }
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }
            row.addView(TextView(this).apply {
                text = icon
                textSize = 19f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(dp(42), dp(48)))
            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(7), dp(2), dp(5), dp(2))
            }
            labels.addView(TextView(this).apply {
                text = title
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE)
            })
            labels.addView(TextView(this).apply {
                text = detail
                textSize = 11f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(0xFFB8B8C5.toInt())
            })
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = "›"
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(0xFFB9A7FF.toInt())
            }, LinearLayout.LayoutParams(dp(28), dp(48)))
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(7)
            })
        }

        addSection("On-screen controls")
        rows.addView(TextView(this).apply {
            text = "Swipe vertically on the left side of the video to adjust brightness, or on the right side to adjust media volume. A compact vertical indicator appears while you swipe."
            textSize = 13f
            setTextColor(0xFFD2D2DC.toInt())
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = GradientDrawable().apply {
                setColor(0xFF292933.toInt())
                cornerRadius = dp(15).toFloat()
                setStroke(dp(1), 0x20FFFFFF)
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        addSection("Playback")
        addRow(
            "▣",
            "Video quality",
            if (trackGroups.any { it.type == C.TRACK_TYPE_VIDEO }) "Current: $resolutionLabel · choose available tracks" else "No selectable quality tracks detected"
        ) { showTrackSelection("Video quality", C.TRACK_TYPE_VIDEO, adaptive = true) }
        addRow(
            "1×",
            "Playback speed",
            "Currently $speedLabel× · tap to change speed"
        ) { showPlaybackSpeed() }
        addRow(
            "▧",
            "Video scaling",
            "Current mode: ${resizeLabels[fitIndex]}"
        ) { showVideoFit() }

        addSection("Audio and accessibility")
        addRow(
            "♫",
            "Audio track",
            if (trackGroups.any { it.type == C.TRACK_TYPE_AUDIO }) "Choose an available audio track" else "No selectable alternate audio tracks detected"
        ) { showTrackSelection("Audio track", C.TRACK_TYPE_AUDIO) }
        addRow(
            "CC",
            "Subtitles and captions",
            if (trackGroups.any { it.type == C.TRACK_TYPE_TEXT }) "Choose captions or a subtitle track" else "No selectable subtitle tracks detected"
        ) { showTrackSelection("Subtitles / captions", C.TRACK_TYPE_TEXT) }

        addSection("Diagnostics")
        addRow(
            "ⓘ",
            "Playback information",
            "Position, duration, resolution and audio details"
        ) { showPlaybackInformation() }

        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            addView(rows, FrameLayout.LayoutParams(-1, -2))
        }
        val maxScrollHeight = minOf(dp(420), (resources.displayMetrics.heightPixels * 0.62f).toInt())
        sheet.addView(scroll, LinearLayout.LayoutParams(-1, maxScrollHeight))

        dialog.setContentView(sheet)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val params = window.attributes
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.gravity = Gravity.BOTTOM
            params.dimAmount = 0.58f
            window.attributes = params
        }
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
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
        if (lifecyclePausedPlayback) {
            player?.playWhenReady = lifecyclePauseWasPlaying
            lifecyclePausedPlayback = false
            lifecyclePauseWasPlaying = false
        }
        scheduleHideControls()
    }

    override fun onPause() {
        if (!isInPictureInPictureMode && !enteringPictureInPicture) {
            lifecyclePauseWasPlaying = player?.playWhenReady == true
            lifecyclePausedPlayback = true
            player?.playWhenReady = false
        }
        handler.removeCallbacks(hideControls)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isInPictureInPictureMode) enterImmersiveMode()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        enteringPictureInPicture = false
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
        handler.removeCallbacks(hideLevelIndicators)
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
