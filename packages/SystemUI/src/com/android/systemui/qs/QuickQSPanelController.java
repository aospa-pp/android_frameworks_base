/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs;

import static com.android.systemui.media.dagger.MediaModule.QUICK_QS_PANEL;
import static com.android.systemui.qs.dagger.QSScopeModule.QS_USING_COLLAPSED_LANDSCAPE_MEDIA;
import static com.android.systemui.qs.dagger.QSScopeModule.QS_USING_MEDIA_PLAYER;

import static android.provider.Settings.System.QS_BRIGHTNESS_SLIDER_POSITION;
import static android.provider.Settings.System.QS_SHOW_AUTO_BRIGHTNESS;
import static android.provider.Settings.System.QS_SHOW_BRIGHTNESS_SLIDER;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;

import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.haptics.qs.QSLongPressEffect;
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaCarouselInteractor;
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager;
import com.android.systemui.media.controls.ui.view.MediaHost;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.customize.QSCustomizerController;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.SplitShadeStateController;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.settings.brightness.BrightnessController;
import com.android.systemui.settings.brightness.BrightnessMirrorHandler;
import com.android.systemui.settings.brightness.BrightnessSliderController;
import com.android.systemui.settings.brightness.MirrorController;
import com.android.systemui.util.leak.RotationUtils;
import com.android.systemui.util.settings.SystemSettings;

import kotlinx.coroutines.flow.StateFlow;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;


/** Controller for {@link QuickQSPanel}. */
@QSScope
public class QuickQSPanelController extends QSPanelControllerBase<QuickQSPanel> {

    private final Provider<Boolean> mUsingCollapsedLandscapeMediaProvider;
    private final BrightnessController mBrightnessController;
    private final BrightnessSliderController mBrightnessSliderController;
    private final BrightnessMirrorHandler mBrightnessMirrorHandler;
    private MirrorController mBrightnessMirrorController;

    private final ContentObserver mSettingsObserver;
    private final Executor mMainExecutor;
    private final SystemSettings mSystemSettings;
    private final UserTracker mUserTracker;
    private final UserTracker.Callback mUserTrackerCallback;

    private final MediaCarouselInteractor mMediaCarouselInteractor;

    @Inject
    QuickQSPanelController(QuickQSPanel view, QSHost qsHost,
            @Main Executor mainExecutor, @Main Handler mainHandler,
            SystemSettings systemSettings, UserTracker userTracker,
            QSCustomizerController qsCustomizerController,
            @Named(QS_USING_MEDIA_PLAYER) boolean usingMediaPlayer,
            @Named(QUICK_QS_PANEL) MediaHost mediaHost,
            @Named(QS_USING_COLLAPSED_LANDSCAPE_MEDIA)
                    Provider<Boolean> usingCollapsedLandscapeMediaProvider,
            MetricsLogger metricsLogger, UiEventLogger uiEventLogger, QSLogger qsLogger,
            DumpManager dumpManager, SplitShadeStateController splitShadeStateController,
            BrightnessController.Factory brightnessControllerFactory,
            BrightnessSliderController.Factory brightnessSliderFactory,
            Provider<QSLongPressEffect> longPressEffectProvider,
            MediaCarouselInteractor mediaCarouselInteractor
    ) {
        super(view, qsHost, qsCustomizerController, usingMediaPlayer, mediaHost, metricsLogger,
                uiEventLogger, qsLogger, dumpManager, splitShadeStateController,
                longPressEffectProvider);
        mUsingCollapsedLandscapeMediaProvider = usingCollapsedLandscapeMediaProvider;
        mMediaCarouselInteractor = mediaCarouselInteractor;

        mBrightnessSliderController = brightnessSliderFactory.create(getContext(), mView);
        mView.setBrightnessView(mBrightnessSliderController.getRootView());
        mBrightnessController = brightnessControllerFactory.create(mBrightnessSliderController);
        mBrightnessMirrorHandler = new BrightnessMirrorHandler(mBrightnessController);
        mMainExecutor = mainExecutor;
        mSystemSettings = systemSettings;
        mUserTracker = userTracker;
        mSettingsObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                switch (uri.getLastPathSegment()) {
                    case QS_SHOW_BRIGHTNESS_SLIDER:
                        mView.updateBrightnessSliderVisibility(mSystemSettings.getIntForUser(
                                QS_SHOW_BRIGHTNESS_SLIDER, 1,
                                mUserTracker.getUserId()));
                        break;
                    case QS_BRIGHTNESS_SLIDER_POSITION:
                        mView.updateBrightnessSliderPosition(mSystemSettings.getIntForUser(
                                QS_BRIGHTNESS_SLIDER_POSITION, 0,
                                mUserTracker.getUserId()));
                        break;
                    case QS_SHOW_AUTO_BRIGHTNESS:
                        mView.updateAutoBrightnessVisibility(mSystemSettings.getIntForUser(
                                QS_SHOW_AUTO_BRIGHTNESS, 1,
                                mUserTracker.getUserId()));
                        break;
                }
            }
        };
        mUserTrackerCallback = new UserTracker.Callback() {
            @Override
            public void onUserChanged(int newUser, Context userContext) {
                updateSettings();
            }
        };
    }

    @Override
    protected void onInit() {
        super.onInit();
        updateMediaExpansion();
        mMediaHost.setShowsOnlyActiveMedia(true);
        mMediaHost.init(MediaHierarchyManager.LOCATION_QQS);
        mBrightnessSliderController.init();
    }

    @Override
    StateFlow<Boolean> getMediaVisibleFlow() {
        return mMediaCarouselInteractor.getHasActiveMediaOrRecommendation();
    }

    private void updateMediaExpansion() {
        int rotation = getRotation();
        boolean isLandscape = rotation == RotationUtils.ROTATION_LANDSCAPE
                || rotation == RotationUtils.ROTATION_SEASCAPE;
        boolean usingCollapsedLandscapeMedia = mUsingCollapsedLandscapeMediaProvider.get();
        if (!usingCollapsedLandscapeMedia || !isLandscape) {
            mMediaHost.setExpansion(MediaHost.EXPANDED);
        } else {
            mMediaHost.setExpansion(MediaHost.COLLAPSED);
        }
    }

    @VisibleForTesting
    protected int getRotation() {
        return RotationUtils.getRotation(getContext());
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();

        mSystemSettings.registerContentObserverForUserSync(QS_BRIGHTNESS_SLIDER_POSITION,
                mSettingsObserver, UserHandle.USER_ALL);
        mSystemSettings.registerContentObserverForUserSync(QS_SHOW_AUTO_BRIGHTNESS,
                mSettingsObserver, UserHandle.USER_ALL);
        mSystemSettings.registerContentObserverForUserSync(QS_SHOW_BRIGHTNESS_SLIDER,
                mSettingsObserver, UserHandle.USER_ALL);
        mUserTracker.addCallback(mUserTrackerCallback, mMainExecutor);
        updateSettings();

        mView.setBrightnessRunnable(() -> {
            mView.updateResources();
            updateBrightnessMirror();
        });

        mBrightnessMirrorHandler.onQsPanelAttached();
    }

    @Override
    protected void onViewDetached() {
        super.onViewDetached();
        mUserTracker.removeCallback(mUserTrackerCallback);
        mSystemSettings.unregisterContentObserverSync(mSettingsObserver);
        mView.setBrightnessRunnable(null);
        mBrightnessMirrorHandler.onQsPanelDettached();
    }

    private void updateBrightnessMirror() {
        if (mBrightnessMirrorController != null) {
            mBrightnessSliderController.setMirrorControllerAndMirror(mBrightnessMirrorController);
        }
    }

    private void updateSettings() {
        mView.updateBrightnessSliderVisibility(mSystemSettings.getIntForUser(
                QS_SHOW_BRIGHTNESS_SLIDER, 1,
                mUserTracker.getUserId()));
        mView.updateBrightnessSliderPosition(mSystemSettings.getIntForUser(
                QS_BRIGHTNESS_SLIDER_POSITION, 0,
                mUserTracker.getUserId()));
        mView.updateAutoBrightnessVisibility(mSystemSettings.getIntForUser(
                QS_SHOW_AUTO_BRIGHTNESS, 1,
                mUserTracker.getUserId()));
    }

    @Override
    void setListening(boolean listening) {
        super.setListening(listening);

        // Set the listening as soon as the QS fragment starts listening regardless of the
        //expansion, so it will update the current brightness before the slider is visible.
        if (listening) {
            mBrightnessController.registerCallbacks();
        } else {
            mBrightnessController.unregisterCallbacks();
        }
    }

    public boolean isListening() {
        return mView.isListening();
    }

    private void setMaxTiles(int parseNumTiles) {
        mView.setMaxTiles(parseNumTiles);
        setTiles();
    }

    @Override
    public void refreshAllTiles() {
        mBrightnessController.checkRestrictionAndSetEnabled();
        super.refreshAllTiles();
    }

    @Override
    protected void onConfigurationChanged() {
        int newMaxTiles = getResources().getInteger(R.integer.quick_qs_panel_max_tiles);
        if (newMaxTiles != mView.getNumQuickTiles()) {
            setMaxTiles(newMaxTiles);
        }
        updateMediaExpansion();
    }

    @Override
    public void setTiles() {
        List<QSTile> tiles = new ArrayList<>();
        for (QSTile tile : mHost.getTiles()) {
            tiles.add(tile);
            if (tiles.size() == mView.getNumQuickTiles()) {
                break;
            }
        }
        super.setTiles(tiles, /* collapsedView */ true);
    }

    public void setContentMargins(int marginStart, int marginEnd) {
        mView.setContentMargins(marginStart, marginEnd, mMediaHost.getHostView());
    }

    public int getNumQuickTiles() {
        return mView.getNumQuickTiles();
    }

    public void setBrightnessMirror(MirrorController brightnessMirrorController) {
        mBrightnessMirrorController = brightnessMirrorController;
        mBrightnessMirrorHandler.setController(brightnessMirrorController);
    }
}
