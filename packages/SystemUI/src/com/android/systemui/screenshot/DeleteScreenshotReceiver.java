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

package com.android.systemui.screenshot;

import static com.android.systemui.screenshot.DeleteScreenshotReceiver.EXTRA_SCREENSHOT_URI_ID;
import static com.android.systemui.screenshot.SmartActionsReceiver.EXTRA_ACTION_TYPE;
import static com.android.systemui.screenshot.SmartActionsReceiver.EXTRA_ID;
import static com.android.systemui.screenshot.SmartActionsReceiver.EXTRA_SMART_ACTIONS_ENABLED;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.Display;

import com.android.systemui.dagger.qualifiers.Background;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Removes the file at the provided uri, either as the intent data (from the screenshot preview
 * notification) or as the {@link #EXTRA_SCREENSHOT_URI_ID} extra (from the chip delete action),
 * and dismisses the preview notification of that uri, if any.
 */
public class DeleteScreenshotReceiver extends BroadcastReceiver {
    private static final String TAG = "DeleteScreenshotReceiver";

    public static final String EXTRA_SCREENSHOT_URI_ID = "android:screenshot_uri_id";

    private final ScreenshotSmartActions mScreenshotSmartActions;
    private final Executor mBackgroundExecutor;
    private final ScreenshotNotificationsController mNotificationsController;

    @Inject
    public DeleteScreenshotReceiver(ScreenshotSmartActions screenshotSmartActions,
            @Background Executor backgroundExecutor,
            ScreenshotNotificationsController.Factory notificationsControllerFactory) {
        mScreenshotSmartActions = screenshotSmartActions;
        mBackgroundExecutor = backgroundExecutor;
        mNotificationsController =
                notificationsControllerFactory.create(Display.DEFAULT_DISPLAY);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Uri uri = intent.getData();
        if (uri == null && intent.hasExtra(EXTRA_SCREENSHOT_URI_ID)) {
            uri = Uri.parse(intent.getStringExtra(EXTRA_SCREENSHOT_URI_ID));
        }
        if (uri == null) {
            return;
        }

        // And delete the image from the media store
        final Uri screenshotUri = uri;
        mBackgroundExecutor.execute(() -> {
            ContentResolver resolver = context.getContentResolver();
            try {
                resolver.delete(screenshotUri, null, null);
            } catch (Exception e) {
                android.util.Log.w(TAG, "Failed to delete screenshot " + screenshotUri, e);
            }
        });
        if (intent.getBooleanExtra(EXTRA_SMART_ACTIONS_ENABLED, false)) {
            mScreenshotSmartActions.notifyScreenshotAction(
                    intent.getStringExtra(EXTRA_ID), intent.getStringExtra(EXTRA_ACTION_TYPE),
                    false, null);
        }

        // And dismiss the preview notification, if any
        mNotificationsController.dismissPostActionNotification(
                screenshotUri.toString().hashCode());
    }
}
