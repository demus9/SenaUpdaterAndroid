package com.sena30k.updater;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

public final class UpdaterApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
