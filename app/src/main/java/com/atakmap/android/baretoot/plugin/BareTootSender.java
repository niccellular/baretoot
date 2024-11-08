package com.atakmap.android.baretoot.plugin;

import android.content.Context;
import android.graphics.drawable.Drawable;

import com.atakmap.android.importexport.send.MissionPackageSender;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.missionpackage.MissionPackageMapComponent;
import com.atakmap.android.missionpackage.file.MissionPackageManifest;
import com.atakmap.android.missionpackage.file.task.MissionPackageBaseTask;

import com.atakmap.coremap.log.Log;


public class BareTootSender extends MissionPackageSender {

    private final static String TAG = "BareTootSender";
    private Context pluginContext;
    private MapView view;
    public BareTootSender(MapView view, Context pluginContext) {
        super(view);
        this.view = view;
        this.pluginContext = pluginContext;
    }

    @Override
    public boolean sendMissionPackage(MissionPackageManifest missionPackageManifest, MissionPackageBaseTask.Callback callback, Callback callback1) {
        Log.d(TAG, "sendMissionPackage");
        Log.d(TAG, missionPackageManifest.toString());
        MissionPackageMapComponent.getInstance().getFileIO().save(missionPackageManifest, true, new BareTootCallback());
        return true;
    }

    @Override
    public String getName() {
        return "BareTootSender";
    }

    @Override
    public Drawable getIcon() {
        return pluginContext.getDrawable(R.drawable.ic_launcher);
    }
}