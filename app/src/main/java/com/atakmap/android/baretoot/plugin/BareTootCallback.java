package com.atakmap.android.baretoot.plugin;


import android.app.Activity;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.widget.Toast;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.missionpackage.api.SaveAndSendCallback;
import com.atakmap.android.missionpackage.file.MissionPackageManifest;
import com.atakmap.android.missionpackage.file.task.MissionPackageBaseTask;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.digi.xbee.api.RemoteXBeeDevice;
import com.digi.xbee.api.android.XBeeDevice;
import com.digi.xbee.api.exceptions.XBeeException;
import com.digi.xbee.api.models.XBee64BitAddress;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;

public class BareTootCallback implements SaveAndSendCallback {
        private final static String TAG = "BareTootCallback";
        @Override
        public void onMissionPackageTaskComplete(MissionPackageBaseTask missionPackageBaseTask, boolean success) {

            Log.d(TAG, "onMissionPackageTaskComplete: " + success);

            MissionPackageManifest missionPackageManifest = missionPackageBaseTask.getManifest();

            File file = new File(missionPackageManifest.getPath());
            Log.d(TAG, file.getAbsolutePath());

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MapView.getMapView().getContext());
            SharedPreferences.Editor editor = prefs.edit();

            if (FileSystemUtils.isFile(file)) {
                // check file size
                if (FileSystemUtils.getFileSize(file) > 1024 * 1024) {
                    Toast.makeText(MapView.getMapView().getContext(), "File is too large to send, 1MB Max", Toast.LENGTH_LONG).show();
                    editor.putBoolean("plugin_baretoot_file_transfer", false);
                    editor.apply();
                    return;
                } else {
                    int mpSize = (int) FileSystemUtils.getFileSize(file);
                    byte[] fileData = new byte[mpSize];
                    byte[] header = String.format(Locale.ENGLISH,"TOOT:%d:", mpSize).getBytes(StandardCharsets.UTF_8);
                    Log.d(TAG, "File size: " + mpSize);
                    try (FileInputStream fis = new FileInputStream(file)) {
                        fis.read(fileData, 0, mpSize);
                    } catch (IOException e) {
                        e.printStackTrace();
                        return;
                    }
                    Log.d(TAG, fileData.length + " bytes read from file");
                    if (fileData.length != mpSize) {
                        Log.d(TAG, "Failed to read file");
                        return;
                    }
                    new Thread(() -> {
                        // ready to send file
                        boolean error = false;
                        XBeeDevice device = BareToot.getBeeDevice();
                        if (device == null || !device.isOpen()) {
                            Log.d(TAG, "XBee device is not open");
                            return;
                        }
                        if (device.getNetwork().getDevices().size() > 0) {
                            // flag to indicate we are in a file transfer mode
                            editor.putBoolean("plugin_baretoot_file_transfer", true);
                            editor.apply();
                            Log.d(TAG, "Sending file: " + file.getAbsolutePath());
                            ((Activity) MapView.getMapView().getContext()).runOnUiThread(() -> {
                                Toast.makeText(MapView.getMapView().getContext(), "Sending file: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
                            });
                            try {
                                // split data into 256 byte strings
                                for (int i = 0; i < fileData.length; i += 256) {
                                    byte[] chunk = Arrays.copyOfRange(fileData, i, Math.min(fileData.length, i + 256));
                                    try {
                                        device.sendBroadcastData(chunk);
                                    } catch (XBeeException e) {
                                        e.printStackTrace();
                                        error = true;
                                        break;
                                    }
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                                error = true;
                            }
                        } else {
                            Log.d(TAG, "XBee device is null");
                        }

                        // done
                        editor.putBoolean("plugin_baretoot_file_transfer", false);
                        editor.apply();
                        boolean finalError = error;
                        ((Activity) MapView.getMapView().getContext()).runOnUiThread(() -> {
                            if (finalError) {
                                Toast.makeText(MapView.getMapView().getContext(), "Failed to send file: " + file.getAbsoluteFile(), Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(MapView.getMapView().getContext(), "Sent file: " + file.getAbsoluteFile(), Toast.LENGTH_LONG).show();
                            }
                        });
                    }).start();
                }
            } else {
                Log.d(TAG, "Invalid file");
            }
        }
    }
