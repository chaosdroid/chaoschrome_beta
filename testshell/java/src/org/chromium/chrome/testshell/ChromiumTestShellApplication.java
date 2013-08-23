// Copyright (c) 2012 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.testshell;

import android.app.Application;
import android.content.Intent;

import org.chromium.base.PathUtils;
import org.chromium.content.browser.ResourceExtractor;
import org.chromium.content.common.CommandLine;

import java.util.ArrayList;

/**
 * A basic test shell {@link Application}.  Handles setting up the native library and
 * loading the right resources.
 */
public class ChromiumTestShellApplication extends Application {
    private static final String PRIVATE_DATA_DIRECTORY_SUFFIX = "chromiumtestshell";
    private static final String[] CHROME_MANDATORY_PAKS = {
        "en-US.pak",
        "resources.pak",
        "chrome_100_percent.pak",
    };
    private static final String COMMAND_LINE_FILE =
            "/data/local/tmp/chromium-testshell-command-line";

    ArrayList<ChromiumTestShellApplicationObserver> mObservers;

    @Override
    public void onCreate() {
        super.onCreate();

        ResourceExtractor.setMandatoryPaksToExtract(CHROME_MANDATORY_PAKS);
        PathUtils.setPrivateDataDirectorySuffix(PRIVATE_DATA_DIRECTORY_SUFFIX);

        mObservers = new ArrayList<ChromiumTestShellApplicationObserver>();
    }

    @Override
    public void sendBroadcast(Intent intent) {
        boolean shouldFire = true;
        for (ChromiumTestShellApplicationObserver observer : mObservers) {
            shouldFire &= observer.onSendBroadcast(intent);
        }

        if (shouldFire) {
            super.sendBroadcast(intent);
        }
    }

    public void addObserver(ChromiumTestShellApplicationObserver observer) {
        mObservers.add(observer);
    }

    public void removeObserver(ChromiumTestShellApplicationObserver observer) {
        mObservers.remove(observer);
    }

    public static void initCommandLine() {
        if (!CommandLine.isInitialized())
            CommandLine.initFromFile(COMMAND_LINE_FILE);
    }
}
