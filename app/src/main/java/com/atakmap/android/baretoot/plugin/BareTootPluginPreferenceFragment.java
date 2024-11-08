package com.atakmap.android.baretoot.plugin;

import android.annotation.SuppressLint;
import android.content.Context;

import android.os.Bundle;

import com.atakmap.android.preference.PluginPreferenceFragment;

public class BareTootPluginPreferenceFragment extends PluginPreferenceFragment {

    @SuppressLint("StaticFieldLeak")
    private static Context pluginContext;

    public BareTootPluginPreferenceFragment() {
        super(pluginContext, R.xml.preferences);
    }

    @SuppressLint("ValidFragment")
    public BareTootPluginPreferenceFragment(final Context pluginContext) {
        super(pluginContext, R.xml.preferences);
        this.pluginContext = pluginContext;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public String getSubTitle() {
        return getSubTitle("Tool Preferences", pluginContext.getString(R.string.preferences_title));
    }
}