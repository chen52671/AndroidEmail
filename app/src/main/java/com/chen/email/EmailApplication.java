/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.chen.email;

import android.app.Application;

import com.chen.email.preferences.EmailPreferenceMigrator;
import com.chen.mail.preferences.BasePreferenceMigrator;
import com.chen.mail.preferences.PreferenceMigratorHolder;
import com.chen.mail.preferences.PreferenceMigratorHolder.PreferenceMigratorCreator;
import com.chen.mail.utils.LogTag;

public class EmailApplication extends Application {
    private static final String LOG_TAG = "Email";

    static {
        LogTag.setLogTag(LOG_TAG);

        PreferenceMigratorHolder.setPreferenceMigratorCreator(new PreferenceMigratorCreator() {
            @Override
            public BasePreferenceMigrator createPreferenceMigrator() {
                return new EmailPreferenceMigrator();
            }
        });
    }
}
