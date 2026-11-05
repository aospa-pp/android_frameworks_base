/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.systemui.screenshot

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.UserHandle
import android.view.Display
import com.android.internal.R
import com.android.internal.messages.nano.SystemMessageProto
import com.android.systemui.statusbar.notification.NotificationUtils
import com.android.systemui.util.NotificationChannels
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/** Convenience class to handle showing and hiding notifications while taking a screenshot. */
class ScreenshotNotificationsController
@AssistedInject
internal constructor(
    @Assisted private val displayId: Int,
    private val context: Context,
    private val notificationManager: NotificationManager,
    private val devicePolicyManager: DevicePolicyManager,
) {
    private val res = context.resources

    /**
     * Sends a notification that the screenshot capture has failed.
     *
     * @param msgResId The resource ID of the error message string to display.
     */
    fun notifyScreenshotError(msgResId: Int) {
        val errorMsg = res.getString(msgResId)
        showErrorNotification(
            errorMsg,
            res.getString(com.android.systemui.res.R.string.screenshot_failed_title),
        )
    }

    /**
     * Sends a notification that the screenshot capture has failed for custom save location.
     *
     * @param message The exact error message string to display.
     */
    fun notifyCustomUriSaveError(message: String) {
        val titleMessage =
            context.getString(
                com.android.systemui.res.R.string.screenshot_custom_uri_save_fail_title,
                Environment.DIRECTORY_SCREENSHOTS,
            )
        showErrorNotification(message, titleMessage)
    }

    /**
     * Builds and displays the actual error notification. Errors for the non-default display are
     * shown in a unique separate notification.
     *
     * @param baseMessage The main text of the error to show.
     */
    private fun showErrorNotification(baseMessage: String, titleMessage: String) {
        val displayErrorString =
            if (displayId != Display.DEFAULT_DISPLAY) {
                " ($externalDisplayString)"
            } else {
                ""
            }
        val errorMsg = baseMessage + displayErrorString

        // Repurpose the existing notification or create a new one
        val builder =
            Notification.Builder(context, NotificationChannels.ALERTS)
                .setTicker(titleMessage)
                .setContentTitle(titleMessage)
                .setContentText(errorMsg)
                .setSmallIcon(com.android.systemui.res.R.drawable.stat_notify_image_error)
                .setWhen(System.currentTimeMillis())
                .setVisibility(Notification.VISIBILITY_PUBLIC) // ok to show outside lockscreen
                .setCategory(Notification.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setColor(context.getColor(R.color.system_notification_accent_color))
        val intent =
            devicePolicyManager.createAdminSupportIntent(
                DevicePolicyManager.POLICY_DISABLE_SCREEN_CAPTURE
            )
        if (intent != null) {
            val pendingIntent =
                PendingIntent.getActivityAsUser(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE,
                    null,
                    UserHandle.CURRENT,
                )
            builder.setContentIntent(pendingIntent)
        }
        NotificationUtils.overrideNotificationAppName(context, builder, true)
        val notification = Notification.BigTextStyle(builder).bigText(errorMsg).build()
        // A different id for external displays to keep the 2 error notifications separated.
        val id =
            if (displayId == Display.DEFAULT_DISPLAY) {
                SystemMessageProto.SystemMessage.NOTE_GLOBAL_SCREENSHOT
            } else {
                SystemMessageProto.SystemMessage.NOTE_GLOBAL_SCREENSHOT_EXTERNAL_DISPLAY
            }
        notificationManager.notify(id, notification)
    }

    /**
     * Shows a notification with the screenshot preview and share/delete actions.
     *
     * Every screenshot gets its own notification, the id being derived from the uri, so multiple
     * screenshots pile up in the shade and stay individually accessible; the group summary keeps
     * them collapsed. This needs no Settings side: the channel is blockable.
     */
    fun showPostActionNotification(uri: Uri, bitmap: Bitmap) {
        // The notification id is the uri hash, so the same uri can be dismissed someplace else.
        val id = uri.toString().hashCode()
        val contentIntent =
            PendingIntent.getActivity(
                context,
                id,
                Intent(Intent.ACTION_VIEW)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .setDataAndType(uri, MIME_IMAGE),
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        // Grant the receiver read access explicitly: the implicit grant of the EXTRA_STREAM uri
        // is being discontinued.
        val shareIntent =
            Intent(Intent.ACTION_SEND)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .setType(MIME_IMAGE)
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_RECEIVER_FOREGROUND
                )
        shareIntent.clipData = ClipData.newRawUri(null, uri)
        val sharePendingIntent =
            PendingIntent.getActivity(
                context,
                id,
                shareIntent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val deleteIntent =
            PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, DeleteScreenshotReceiver::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            Notification.Builder(context, NotificationChannels.SCREENSHOTS_HEADSUP)
                .setTicker(res.getString(com.android.systemui.res.R.string.screenshot_saved_title))
                .setContentTitle(res.getString(com.android.systemui.res.R.string.screenshot_saved_title))
                .setContentText(res.getString(com.android.systemui.res.R.string.screenrecord_save_text))
                .setSmallIcon(com.android.systemui.res.R.drawable.screenshot_image)
                .setWhen(System.currentTimeMillis())
                .setAutoCancel(true)
                .setGroup(GROUP_KEY)
                .setStyle(Notification.BigPictureStyle().bigPicture(bitmap).bigLargeIcon(bitmap))
                .setColor(context.getColor(R.color.system_notification_accent_color))
                .addAction(
                    Notification.Action.Builder(
                        /* icon = */ 0,
                        res.getString(
                            com.android.systemui.res.R.string.screenrecord_share_label
                        ),
                        sharePendingIntent,
                    ).build()
                )
                .addAction(
                    Notification.Action.Builder(
                        /* icon = */ 0,
                        res.getString(
                            com.android.systemui.res.R.string.screenshot_delete_label
                        ),
                        deleteIntent,
                    ).build()
                )
                .setContentIntent(contentIntent)
        NotificationUtils.overrideNotificationAppName(context, builder, true)
        notificationManager.notify(TAG, id, builder.build())
        maybePostGroup()
    }

    /** Cancels the preview notification for [id] and the group summary if it was the last. */
    fun dismissPostActionNotification(id: Int) {
        notificationManager.cancel(TAG, id)
        maybeDismissGroup()
        maybeCloseSystemDialogs()
    }

    private fun maybePostGroup() {
        // Only post the summary once there is something to collapse.
        if (countGroupedNotifications() < 2) return
        val builder =
            Notification.Builder(context, NotificationChannels.SCREENSHOTS_HEADSUP)
                .setSmallIcon(com.android.systemui.res.R.drawable.screenshot_image)
                .setContentTitle(res.getString(com.android.systemui.res.R.string.screenshot_saved_title))
                .setGroup(GROUP_KEY)
                .setGroupSummary(true)
        NotificationUtils.overrideNotificationAppName(context, builder, true)
        notificationManager.notify(TAG, GROUP_ID, builder.build())
    }

    private fun maybeDismissGroup() {
        // Drop the summary only when the last grouped notification is gone.
        if (countGroupedNotifications() >= 1) return
        notificationManager.cancel(TAG, GROUP_ID)
    }

    private fun maybeCloseSystemDialogs() {
        // Only collapse the shade when the last grouped notification is deleted.
        if (countGroupedNotifications() > 0) return
        context.closeSystemDialogs()
    }

    private fun countGroupedNotifications(): Int =
        notificationManager.activeNotifications.count { it.tag == TAG && it.id != GROUP_ID }

    private val externalDisplayString: String
        get() =
            res.getString(
                com.android.systemui.res.R.string.screenshot_failed_external_display_indication
            )

    /** Factory for [ScreenshotNotificationsController]. */
    @AssistedFactory
    fun interface Factory {
        fun create(displayId: Int): ScreenshotNotificationsController
    }

    companion object {
        private const val TAG = "ScreenshotNotificationManager"
        private const val MIME_IMAGE = "image/*"
        private const val GROUP_KEY = "screenshot_post_action_group"
        private const val GROUP_ID = 2107821532
    }
}
