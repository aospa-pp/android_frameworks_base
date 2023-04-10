/*
 * Copyright (C) 2017 The LineageOS Project
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
package com.android.systemui.tuner;

import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragment;
import androidx.preference.SwitchPreference;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.res.R;

public class StatusBarTuner extends PreferenceFragment {

    private static final String NETWORK_TRAFFIC = "network_traffic";

    private MetricsLogger mMetricsLogger;

    private SwitchPreference mNetMonitor;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.status_bar_prefs);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mMetricsLogger = new MetricsLogger();
        mNetMonitor = (SwitchPreference) findPreference(NETWORK_TRAFFIC);

        mNetMonitor.setChecked(Settings.Secure.getIntForUser(getActivity().getContentResolver(),
            Settings.Secure.NETWORK_TRAFFIC_ENABLED, 0,
            UserHandle.USER_CURRENT) == 1);
    }

    @Override
    public void onResume() {
        super.onResume();
        mMetricsLogger.visibility(MetricsEvent.TUNER, true);
    }

    @Override
    public void onPause() {
        super.onPause();
        mMetricsLogger.visibility(MetricsEvent.TUNER, false);
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (preference == mNetMonitor) {
            boolean checked = ((SwitchPreference)preference).isChecked();
            Settings.Secure.putIntForUser(getActivity().getContentResolver(),
                    Settings.Secure.NETWORK_TRAFFIC_ENABLED, checked ? 1 : 0,
                    UserHandle.USER_CURRENT);
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }
}
