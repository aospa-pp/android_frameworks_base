/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.media

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.IndentingPrintWriter
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.android.internal.graphics.ColorUtils
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.policy.KeyguardStateController
import java.io.PrintWriter
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Draws the currently playing media's artwork full screen behind the notification shade while the
 * device is on the lockscreen, optionally blurred.
 *
 * The artwork comes from the media controls pipeline ([MediaDataManager]), the same source the QS
 * media carousel uses: it is driven by the media notification rather than raw session events, and
 * its artwork (including the URI fallbacks that video apps rely on) is already resolved by
 * [com.android.systemui.media.controls.domain.pipeline.MediaDataLoader]. Consuming it keeps this
 * view consistent with the media controls by construction, and avoids the session bookkeeping
 * races of talking to MediaSessionManager directly.
 *
 * The visibility is a pure function of cached state, so the shade expansion, the scrim updates and
 * the bouncer transitions can query it as often as they like without doing bitmap work. Hiding is
 * deliberately instant: the blurred view has to leave the screen the moment the panel starts
 * moving, not after an animation.
 */
@SysUISingleton
class MediaArtController
@Inject
constructor(
    private val context: Context,
    private val shadeInteractor: ShadeInteractor,
    private val statusBarStateController: StatusBarStateController,
    private val keyguardStateController: KeyguardStateController,
    private val mediaDataManager: MediaDataManager,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainExecutor: Executor,
    dumpManager: DumpManager,
) : Dumpable {

    /** Blur radius matching the iOS-style lockscreen blur (see the glassblur ratio). */
    private val blurRadius = 1000

    /** Fade applied on top of the artwork, in percent, when no override is set. */
    private val defaultFadeLevel = 40

    /** The main thread, used for the view work and the settings observer. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** A private background thread for the bitmap work: the pipeline artwork can be large. */
    private val bitmapExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var scrim: FrameLayout? = null

    /** The artwork + fade layers, rebuilt only when the artwork changes. */
    private var artDrawable: LayerDrawable? = null

    /** The drawable currently set as the scrim background. */
    private var appliedDrawable: Drawable? = null
    private var processedKey: String? = null

    /** Every live entry, by key, as last reported by the pipeline. */
    private val entries = LinkedHashMap<String, MediaData>()

    /** Monotonic counter handed out on every entry upsert, for the recency pick. */
    private var loadSeq = 0L
    private val recency = HashMap<String, Long>()

    private var enabled = false
    private var filter = 1
    private var fadeLevel = defaultFadeLevel

    /** Cached inputs for [shouldShow], all maintained by change callbacks. */
    private var panelCollapsed = true
    private var dozing = false
    private var bouncerShowing = false
    private var mediaPlaying = false
    private var mediaPackageName: String? = null

    /** Whether [scrim] is currently showing; keeps the show/hide idempotent. */
    private var showing = false

    private val settingsObserver =
        object : android.database.ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                updateSettings()
            }
        }

    private val mediaDataListener =
        object : MediaDataManager.Listener {
            override fun onMediaDataLoaded(
                key: String,
                oldKey: String?,
                data: MediaData,
                immediately: Boolean,
            ) {
                mainExecutor.execute {
                    if (oldKey != null && oldKey != key) {
                        entries.remove(oldKey)
                        recency.remove(oldKey)
                    }
                    recency[key] = ++loadSeq
                    entries[key] = data
                    onEntriesChanged()
                }
            }

            override fun onMediaDataRemoved(key: String, userInitiated: Boolean) {
                mainExecutor.execute {
                    entries.remove(key)
                    recency.remove(key)
                    onEntriesChanged()
                }
            }

            override fun onCurrentActiveMediaChanged(key: String?, data: MediaData?) {
                mainExecutor.execute { onEntriesChanged() }
            }
        }

    private val statusBarStateListener =
        object : StatusBarStateController.StateListener {
            override fun onDozingChanged(dozing: Boolean) {
                if (this@MediaArtController.dozing == dozing) return
                this@MediaArtController.dozing = dozing
                applyVisibility()
            }
        }

    private val keyguardStateCallback =
        object : KeyguardStateController.Callback {
            override fun onPrimaryBouncerShowingChanged() {
                bouncerShowing = keyguardStateController.isPrimaryBouncerShowing
                applyVisibility()
            }

            override fun onKeyguardShowingChanged() {
                applyVisibility()
            }

            override fun onKeyguardFadingAwayChanged() {
                applyVisibility()
            }

            override fun onKeyguardGoingAwayChanged() {
                applyVisibility()
            }
        }

    init {
        dumpManager.registerDumpable(TAG, this)
        registerSettingsObserver()
        updateSettings()

        statusBarStateController.addCallback(statusBarStateListener)
        keyguardStateController.addCallback(keyguardStateCallback)
        dozing = statusBarStateController.isDozing
        bouncerShowing = keyguardStateController.isPrimaryBouncerShowing

        applicationScope.launch {
            // Watch the raw expansion values of both the shade and the QS panel.
            // isShadeFullyCollapsed stays true while only the QS panel is pulled open, and
            // isAnyExpanded additionally reports the legacy "input transfer is about to happen"
            // state, which is true on the lockscreen even with everything collapsed.
            combine(
                    shadeInteractor.shadeExpansion,
                    shadeInteractor.qsExpansion,
                ) { shade, qs -> shade <= 0f && qs <= 0f }
                .distinctUntilChanged()
                .collect { collapsed ->
                    if (panelCollapsed != collapsed) {
                        panelCollapsed = collapsed
                        mainExecutor.execute { applyVisibility() }
                    }
                }
        }

        mediaDataManager.addListener(mediaDataListener)
    }

    /**
     * Adds the artwork view to the shade window. Call from the status bar view setup, after the
     * notification container exists.
     */
    fun attach(shadeWindowView: View) {
        if (scrim != null) return
        val parent =
            shadeWindowView.findViewById<ViewGroup>(R.id.notification_container_parent)
                ?: return
        val view =
            object : FrameLayout(context) {
                override fun onDetachedFromWindow() {
                    super.onDetachedFromWindow()
                    scrim = null
                    showing = false
                }
            }
        view.layoutParams =
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        view.visibility = View.GONE
        view.setRenderEffect(
            RenderEffect.createBlurEffect(
                blurRadius.toFloat(),
                blurRadius.toFloat(),
                Shader.TileMode.MIRROR,
            )
        )
        parent.addView(view, 0)
        scrim = view
        applyVisibility()
    }

    private fun registerSettingsObserver() {
        val resolver = context.contentResolver
        listOf(SETTING_ENABLED, SETTING_FILTER, SETTING_FADE_LEVEL).forEach { key ->
            resolver.registerContentObserver(Settings.System.getUriFor(key), false, settingsObserver)
        }
    }

    private fun updateSettings() {
        val resolver = context.contentResolver
        enabled = Settings.System.getInt(resolver, SETTING_ENABLED, 0) == 1
        val newFilter = Settings.System.getInt(resolver, SETTING_FILTER, 1)
        val newFadeLevel = Settings.System.getInt(resolver, SETTING_FADE_LEVEL, defaultFadeLevel)
        mainExecutor.execute {
            val fadeChanged = newFadeLevel != fadeLevel
            filter = newFilter
            fadeLevel = newFadeLevel
            scrim?.setRenderEffect(
                if (filter == 1) {
                    RenderEffect.createBlurEffect(
                        blurRadius.toFloat(),
                        blurRadius.toFloat(),
                        Shader.TileMode.MIRROR,
                    )
                } else {
                    null
                }
            )
            if (fadeChanged) {
                // The fade level is baked into the drawable layers: rebuild it.
                processedKey = null
                onEntriesChanged()
            } else {
                applyVisibility()
            }
        }
    }

    /** Syncs the tracked playback state with the loaded entries and updates the artwork. */
    private fun onEntriesChanged() {
        // Most recently loaded entry that is active, playing and has artwork.
        val current =
            entries.entries
                .filter { it.value.active && it.value.isPlaying == true && it.value.artwork != null }
                .maxByOrNull { recency[it.key] ?: 0L }
                ?.value
        val playing = current != null
        val packageName = current?.packageName
        if (mediaPlaying != playing || mediaPackageName != packageName) {
            mediaPlaying = playing
            mediaPackageName = packageName
        }
        if (current == null) {
            if (artDrawable != null || processedKey != null) {
                artDrawable = null
                processedKey = null
            }
            applyVisibility()
            return
        }
        loadArtwork(current.notificationKey ?: current.packageName, current.artwork!!)
    }

    private fun loadArtwork(key: String, artwork: android.graphics.drawable.Icon) {
        if (key == processedKey) return
        processedKey = key

        val level = fadeLevel
        bitmapExecutor.execute {
            val bitmap =
                try {
                    val drawable = artwork.loadDrawable(context)
                    when {
                        drawable == null -> null
                        drawable is BitmapDrawable -> drawable.bitmap
                        else -> {
                            // Vector or resource art: rasterize.
                            val b = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
                            drawable.setBounds(0, 0, 1024, 1024)
                            drawable.draw(Canvas(b))
                            b
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to load artwork", e)
                    null
                }
            if (bitmap == null) {
                mainExecutor.execute { applyVisibility() }
                return@execute
            }
            val resized = centerCrop(bitmap)
            val fade = ColorUtils.blendARGB(Color.TRANSPARENT, Color.BLACK, level / 100f)
            val drawable =
                LayerDrawable(
                    arrayOf<Drawable>(
                        BitmapDrawable(context.resources, resized),
                        // A solid colour instead of a full-size overlay bitmap: the old
                        // implementation allocated another screen-sized ARGB bitmap per update.
                        ColorDrawable(fade),
                    )
                )
            mainExecutor.execute {
                // Drop the result if the artwork moved on while we were loading.
                if (processedKey != key) return@execute
                artDrawable = drawable
                applyVisibility()
            }
        }
    }

    /** Centre-crops [bitmap] to the current display, scaled so it always covers it. */
    private fun centerCrop(bitmap: Bitmap): Bitmap {
        val bounds: Rect =
            context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val ratioW = bounds.width() / bitmap.width.toFloat()
        val ratioH = bounds.height() / bitmap.height.toFloat()
        val scale = maxOf(ratioH, ratioW)
        val width = (scale * bitmap.width).toInt().coerceAtLeast(0)
        val height = (scale * bitmap.height).toInt().coerceAtLeast(0)
        if (width == 0 || height == 0) return bitmap
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val xShift = ((width - bounds.width()) / 2).coerceAtLeast(0)
        val yShift = ((height - bounds.height()) / 2).coerceAtLeast(0)
        val cropWidth = minOf(bounds.width(), scaled.width - xShift)
        val cropHeight = minOf(bounds.height(), scaled.height - yShift)
        if (cropWidth <= 0 || cropHeight <= 0) return scaled
        return Bitmap.createBitmap(scaled, xShift, yShift, cropWidth, cropHeight)
    }

    /** True when the artwork should currently be visible. Cheap: no binder, no bitmap work. */
    private fun shouldShow(): Boolean =
        enabled &&
            artDrawable != null &&
            mediaPlaying &&
            panelCollapsed &&
            !dozing &&
            !bouncerShowing &&
            statusBarStateController.state == StatusBarState.KEYGUARD &&
            keyguardStateController.isShowing &&
            !keyguardStateController.isOccluded &&
            !keyguardStateController.isKeyguardFadingAway &&
            !keyguardStateController.isKeyguardGoingAway &&
            context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    /**
     * Syncs the view with [shouldShow] and the current artwork. Idempotent, and hiding never
     * animates: the panel expansion has to drop the blurred view immediately, otherwise the blur
     * keeps getting re-rendered while the shade is dragged. The background is swapped in place
     * while visible, so a track change does not need a hide/show cycle.
     */
    private fun applyVisibility() {
        val view = scrim ?: return
        val show = shouldShow()
        if (show != showing) {
            showing = show
            if (show) {
                view.visibility = View.VISIBLE
                view.alpha = 0f
                view.animate().alpha(1f).setDuration(FADE_IN_MS).start()
            } else {
                view.animate().cancel()
                view.alpha = 0f
                view.visibility = View.GONE
            }
        }
        val drawable = if (show) artDrawable else null
        if (drawable !== appliedDrawable) {
            appliedDrawable = drawable
            view.background = drawable
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        IndentingPrintWriter(pw, "  ").let {
            it.println("MediaArtController:")
            it.increaseIndent()
            it.println("enabled=$enabled filter=$filter fadeLevel=$fadeLevel")
            it.println("panelCollapsed=$panelCollapsed dozing=$dozing bouncerShowing=$bouncerShowing")
            it.println(
                "sbState=${statusBarStateController.state}" +
                    " keyguardShowing=${keyguardStateController.isShowing}" +
                    " occluded=${keyguardStateController.isOccluded}" +
                    " fadingAway=${keyguardStateController.isKeyguardFadingAway}" +
                    " goingAway=${keyguardStateController.isKeyguardGoingAway}"
            )
            it.println(
                "mediaPlaying=$mediaPlaying player=$mediaPackageName entries=${entries.size}" +
                    " showing=$showing hasArt=${artDrawable != null}"
            )
            it.decreaseIndent()
        }
    }

    companion object {
        private const val TAG = "MediaArtController"
        private const val FADE_IN_MS = 300L

        /** Settings.System keys, shared with the Settings app toggle. */
        const val SETTING_ENABLED = "ls_media_art_enabled"
        const val SETTING_FILTER = "ls_media_art_filter"
        const val SETTING_FADE_LEVEL = "ls_media_art_fade_level"
    }
}
