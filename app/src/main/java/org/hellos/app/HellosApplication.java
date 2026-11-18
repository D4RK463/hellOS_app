// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app;

import android.app.Application;
import android.content.Context;

import androidx.lifecycle.ProcessLifecycleOwner;

import com.google.android.material.color.DynamicColors;

import org.hellos.app.heos.HeosRepository;

public final class HellosApplication extends Application {

    private HeosRepository repository;

    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        repository = new HeosRepository(this);
        // connected while any activity of the app is visible, disconnected otherwise
        ProcessLifecycleOwner.get().getLifecycle().addObserver(repository);
    }

    public static HeosRepository repository(Context context) {
        return ((HellosApplication) context.getApplicationContext()).repository;
    }
}
