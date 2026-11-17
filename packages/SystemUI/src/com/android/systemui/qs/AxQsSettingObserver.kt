/*
 * Copyright (C) 2025 Paranoid Android
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

package com.android.systemui.qs

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.SystemExitRestarter
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Inject

/**
 * Watches [Settings.System.AXION_QS_ENABLED] and restarts SystemUI when it changes, so the newly
 * selected Quick Settings style is picked up without a manual reboot.
 *
 * The restart is immediate and unconditional: the injected [SystemExitRestarter] is used directly
 * rather than the [com.android.systemui.flags.Restarter] interface, whose implementation only
 * restarts once the device has gone idle (screen off), which never happens while the user is
 * sitting in Settings flipping the switch.
 */
@SysUISingleton
class AxQsSettingObserver
@Inject
constructor(
    private val context: Context,
    private val restarter: SystemExitRestarter,
) : CoreStartable {
    override fun start() {
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.AXION_QS_ENABLED),
            false,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    restarter.restartSystemUI("Axion Quick Settings toggled")
                }
            },
        )
    }
}

@Module
interface AxQsSettingObserverModule {
    @Binds
    @IntoMap
    @ClassKey(AxQsSettingObserver::class)
    fun bindsAxQsSettingObserver(startable: AxQsSettingObserver): CoreStartable
}
