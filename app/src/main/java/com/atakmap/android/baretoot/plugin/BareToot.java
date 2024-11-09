
package com.atakmap.android.baretoot.plugin;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.data.URIContentManager;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.baretoot.plugin.R;
import com.atakmap.app.preferences.ToolsPreferenceFragment;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.log.Log;

import com.digi.xbee.api.RemoteXBeeDevice;
import com.digi.xbee.api.android.XBeeDevice;
import com.digi.xbee.api.exceptions.XBeeException;
import com.digi.xbee.api.listeners.IDataReceiveListener;
import com.digi.xbee.api.listeners.IDiscoveryListener;
import com.digi.xbee.api.models.XBee64BitAddress;
import com.digi.xbee.api.models.XBeeMessage;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import java.util.HashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

public class BareToot implements IPlugin,
        CommsMapComponent.PreSendProcessor,
        SharedPreferences.OnSharedPreferenceChangeListener,
        IDiscoveryListener,
        IDataReceiveListener {

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane templatePane;
    Button connect;
    TextView status, discovered;
    private static final String TAG = "BareToot";
    private static XBeeDevice myXBeeDevice = null;
    private boolean connected = false;
    private HashMap<String, byte[]> deviceData = new HashMap<>();
    ScheduledExecutorService scheduleTaskExecutor = Executors.newScheduledThreadPool(1);
    private View mainView;
    private BareTootSender baretootSender;
    private SharedPreferences prefs;
    private SharedPreferences.Editor editor;
    public BareToot(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        // obtain the UI service
        uiService = serviceController.getService(IHostUIService.class);

        // initialize the toolbar button for the plugin

        // create the button
        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        pluginContext.getResources().getDrawable(R.drawable.ic_launcher),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showPane();
                    }
                })
                .build();

        // register the plugin preferences
        ToolsPreferenceFragment.register(
                new ToolsPreferenceFragment.ToolPreference(
                        pluginContext.getString(R.string.preferences_title),
                        pluginContext.getString(R.string.preferences_summary),
                        pluginContext.getString(R.string.baretoot_preferences),
                        pluginContext.getResources().getDrawable(R.drawable.ic_launcher),
                        new PluginPreferencesFragment(
                                pluginContext)));

        mainView = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);
        CommsMapComponent.getInstance().registerPreSendProcessor(this);
        URIContentManager.getInstance().registerSender(baretootSender = new BareTootSender(MapView.getMapView(), pluginContext));
        prefs = PreferenceManager.getDefaultSharedPreferences(MapView.getMapView().getContext());
        prefs.registerOnSharedPreferenceChangeListener(this);
        editor = prefs.edit();
    }

    @Override
    public void onStart() {
        // the plugin is starting, add the button to the toolbar
        if (uiService == null)
            return;

        uiService.addToolbarItem(toolbarItem);
        editor.putBoolean("plugin_baretoot_file_transfer", false);
        editor.apply();

        if (myXBeeDevice == null) {
            // TODO: Is baudrate 9600 correct?
            myXBeeDevice = new XBeeDevice(MapView.getMapView().getContext(), 9600);
        }
        if (!connected) {
            Log.d(TAG, "Setting up XBee");
            status = mainView.findViewById(R.id.status);
            discovered = mainView.findViewById(R.id.discovered);
            connect = mainView.findViewById(R.id.connect);
            connect.setOnClickListener(v -> {
                connected = myXBeeDevice.isOpen();
                if (connected) {
                    Toast.makeText(MapView.getMapView().getContext(), "Disconnecting from XBee device...", Toast.LENGTH_SHORT).show();
                    // close the connection
                    if (myXBeeDevice.isOpen()) {
                        myXBeeDevice.getNetwork().stopDiscoveryProcess();
                        myXBeeDevice.close();
                    }
                    connected = false;
                    connect.setText("Connect");
                    status.setText("");
                    discovered.setText("");
                } else {
                    Toast.makeText(MapView.getMapView().getContext(), "Connecting to XBee device...", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        // open the connection
                        try {
                            myXBeeDevice.open();
                            connected = myXBeeDevice.isOpen();
                            Log.d(TAG, "Connected to " + myXBeeDevice.getNodeID());
                            // TODO: Make these plugin preferences
                            myXBeeDevice.getNetwork().setDiscoveryTimeout(10000);
                            myXBeeDevice.setReceiveTimeout(10000);

                            myXBeeDevice.addDataListener(this);
                            myXBeeDevice.getNetwork().addDiscoveryListener(this);
                            myXBeeDevice.getNetwork().startDiscoveryProcess();
                            ((Activity) MapView.getMapView().getContext()).runOnUiThread(() -> {
                                Toast.makeText(MapView.getMapView().getContext(), "Connected", Toast.LENGTH_SHORT).show();
                                connect.setText(connected ? "Disconnect" : "Connect");
                                try {
                                    status.setText("*** Local XBee Device Details ***\n[+] Node ID: " + myXBeeDevice.getNodeID() +"\n[+] Network ID: 0x" + bytesToHex(myXBeeDevice.getPANID()) + "\n[+] Address: " + myXBeeDevice.get64BitAddress().toString() +"\n[+] TX Power: " + myXBeeDevice.getPowerLevel());
                                } catch (XBeeException e) {
                                    e.printStackTrace();
                                }
                                Toast.makeText(MapView.getMapView().getContext(), "Discovery in progress...", Toast.LENGTH_SHORT).show();
                                discovered.setText("Discovery in progress...");
                            });
                        } catch (XBeeException e) {
                            e.printStackTrace();
                            ((Activity) MapView.getMapView().getContext()).runOnUiThread(() -> {
                                Toast.makeText(MapView.getMapView().getContext(), "Failed to connect", Toast.LENGTH_SHORT).show();
                            });
                        }
                    }).start();
                }
            });

            // TODO: Make this a plugin preference
            // run thread to discover new devices
            if (scheduleTaskExecutor.isShutdown()) {
                Log.d(TAG, "Starting discovery thread");
                scheduleTaskExecutor.scheduleAtFixedRate(() -> {
                    if (!connected)
                        return;
                    if (myXBeeDevice.getNetwork().isDiscoveryRunning())
                        return;
                    myXBeeDevice.getNetwork().startDiscoveryProcess();
                }, 0, 15, TimeUnit.MINUTES);
            }
        }
    }

    @Override
    public void onStop() {
        // the plugin is stopping, remove the button from the toolbar
        if (uiService == null)
            return;

        uiService.removeToolbarItem(toolbarItem);
        URIContentManager.getInstance().unregisterSender(baretootSender);
        scheduleTaskExecutor.shutdownNow();
        if (myXBeeDevice.isOpen())
            myXBeeDevice.close();
        editor.putBoolean("plugin_baretoot_file_transfer", false);
        editor.apply();
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        ToolsPreferenceFragment.unregister(pluginContext.getString(R.string.preferences_title));
    }

    private void showPane() {
        // instantiate the plugin view if necessary
        if(templatePane == null) {
            // Remember to use the PluginLayoutInflator if you are actually inflating a custom view
            // In this case, using it is not necessary - but I am putting it here to remind
            // developers to look at this Inflator

            templatePane = new PaneBuilder(mainView)
                    // relative location is set to default; pane will switch location dependent on
                    // current orientation of device screen
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    // pane will take up 50% of screen width in landscape mode
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    // pane will take up 50% of screen height in portrait mode
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }

        // if the plugin pane is not visible, show it!
        if(!uiService.isPaneVisible(templatePane)) {
            uiService.showPane(templatePane, null);
        }
    }

    @Override
    public void processCotEvent(CotEvent cotEvent, String[] strings) {
        if (!connected) {
            Log.d(TAG, "XBee not connected");
            return;
        }

        if (prefs.getBoolean("plugin_baretoot_file_transfer", false)) {
            Log.d(TAG, "File transfer in progress");
            return;
        }

        // TODO: Is this necessary?
        if (myXBeeDevice.getNetwork().isDiscoveryRunning()) {
            Log.d(TAG, "Discovery in progress");
            return;
        }

        if (connected && myXBeeDevice.getNetwork().getDevices().size() > 0) {
            new Thread(() -> {
                try {
                    String data = cotEvent.toString();
                    // split data into 256 byte strings
                    for (int i = 0; i < data.length(); i += 256) {
                        String chunk = data.substring(i, Math.min(data.length(), i + 256));
                        myXBeeDevice.sendBroadcastData(chunk.getBytes(StandardCharsets.UTF_8));
                    }
                } catch (XBeeException e) {
                    Log.e(TAG, "Error sending data to XBee device", e);
                }
            }).start();
        }
    }

    public boolean endsWith(byte[] needle, byte[] haystack) {
        if (needle.length > haystack.length)
            return false;
        for (int i = 0; i < needle.length; i++) {
            if (needle[needle.length - i - 1] != haystack[haystack.length - i - 1])
                return false;
        }
        return true;
    }

    public byte[] append(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        for (int i = 0; i < a.length; i++)
            result[i] = a[i];
        for (int i = 0; i < b.length; i++)
            result[a.length + i] = b[i];
        return result;
    }

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    // TODO: add a timer to clear the buffer if the complete data is not received after sometime
    @Override
    public void dataReceived(XBeeMessage xbeeMessage) {
        XBee64BitAddress address = xbeeMessage.getDevice().get64BitAddress();
        byte[] raw = xbeeMessage.getData();
        String data = new String(raw, StandardCharsets.UTF_8);
        Log.d(TAG, "Received data from " + address + ": " + data.substring(0,4));

        // if the device is already in the map, append the data
        if (deviceData.containsKey(address.toString()) && Objects.requireNonNull(deviceData.get(address.toString())).length > 0) {
            byte[] old = deviceData.get(address.toString());
            deviceData.put(address.toString(), append(old, raw));
        // else add the device and data to the map, if the data is a zip file, set the flag
        } else {
            deviceData.put(address.toString(), raw);
            if (data.startsWith("PK")) {
                if (!prefs.getBoolean("plugin_baretoot_file_transfer", false)) {
                    editor.putBoolean("plugin_baretoot_file_transfer", true);
                    editor.apply();
                }
            }
        }

        // check if we have received the complete data
        for (String key : deviceData.keySet()) {
            byte[] value = deviceData.get(key);
            if (endsWith("Created by ATAK. Mission Package version 2".getBytes(StandardCharsets.UTF_8), value)) {
                // drop the zip file so atak can process it
                Log.d(TAG, "Received data package with length: " + value.length);
                try (FileOutputStream fos = new FileOutputStream(String.format(Locale.US, Environment.getExternalStorageDirectory().getAbsolutePath()+"/atak/tools/datapackage/baretoot_%X.zip", new Random().nextInt()))) {
                    fos.write(value);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                // done
                deviceData.put(key, new byte[0]);
                editor.putBoolean("plugin_baretoot_file_transfer", false);
                editor.apply();
            } else if (new String(value).endsWith("</event>")) {
                try {
                    CotEvent cotEvent = CotEvent.parse(new String(value));
                    if (cotEvent.isValid()) {
                        CotDetail detail = cotEvent.getDetail();
                        CotDetail contact = detail.getChild("contact");
                        if (contact != null) {
                            detail.removeChild(contact);
                            contact.setAttribute("endpoint", address + ":0");
                            detail.addChild(contact);
                            cotEvent.setDetail(detail);
                            Log.d(TAG, "Modified CotEvent: " + cotEvent);
                        }
                        CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing CoT data", e);
                }
                deviceData.put(key, new byte[0]);
            }
        }
    }

    @Override
    public void deviceDiscovered(RemoteXBeeDevice discoveredDevice) {
        Log.d(TAG, "Discovered device: " + discoveredDevice.toString());
        ((Activity) MapView.getMapView().getContext()).runOnUiThread(() -> {
            discovered.setText("Discovered devices: " + myXBeeDevice.getNetwork().getDevices().toString());
        });
    }

    @Override
    public void discoveryError(String error) {
        Log.e(TAG, "Discovery error: " + error);
        ((Activity) MapView.getMapView().getContext()).runOnUiThread(() -> {
            discovered.setText("Discovery error: " + error);
        });
    }

    @Override
    public void discoveryFinished(String error) {
        Log.d(TAG, "Discovery finished: " + (error == null ? "OK" : "Error: " + error));
        ((Activity) MapView.getMapView().getContext()).runOnUiThread(() -> {
            Toast.makeText(MapView.getMapView().getContext(),"Discovery finished: " + (error == null ? "OK" : "Error: " + error), Toast.LENGTH_SHORT).show();
        });
    }

    public static XBeeDevice getBeeDevice() {
        return myXBeeDevice;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key == null) return;
        if (!connected) return;
        boolean updateStatus = false;

        switch (key) {
            case "plugin_baretoot_tx_power": {
                String rate = prefs.getString("plugin_baretoot_tx_power", "0");
                Log.d(TAG, "TX Power: " + rate);
                try {
                    switch(rate) {
                        case "0":
                            myXBeeDevice.setParameter("PL", new byte[] {0x0});
                            break;
                        case "1":
                            myXBeeDevice.setParameter("PL", new byte[] {0x1});
                            break;
                        case "2":
                            myXBeeDevice.setParameter("PL", new byte[] {0x2});
                            break;
                    }
                    updateStatus = true;
                } catch (Exception e) {
                    e.printStackTrace();
                    return;
                }
                break;
            }
            case "plugin_baretoot_encryption_key":
                String aes = prefs.getString("plugin_baretoot_encryption_key", "atakatak");
                Log.d(TAG, "Encryption Key: " + aes);
                // Hash the user key to make sure its 32 bytes
                byte[] hash;
                try {
                    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                    hash = digest.digest(aes.getBytes(StandardCharsets.UTF_8));
                    myXBeeDevice.setParameter("EE", new byte[] {0x1});
                    myXBeeDevice.setParameter("KY", hash);
                    updateStatus = true;
                } catch (Exception e) {
                    e.printStackTrace();
                    return;
                }
                break;
            case "plugin_baretoot_network_id": {
                String id = prefs.getString("plugin_baretoot_network_id", "4242");
                Log.d(TAG, "Network ID: " + id);
                ByteBuffer buffer = ByteBuffer.allocate(2);
                try {
                    myXBeeDevice.setParameter("ID", buffer.putShort(Short.parseShort(id)).array());
                    updateStatus = true;
                } catch (Exception e) {
                    e.printStackTrace();
                    return;
                }
                break;
            }
        }

        if (updateStatus) {
            try {
                myXBeeDevice.applyChanges();
                myXBeeDevice.writeChanges();
                status.setText("*** Local XBee Device Details ***\n[+] Node ID: " + myXBeeDevice.getNodeID() +"\n[+] Network ID: 0x" + bytesToHex(myXBeeDevice.getPANID()) + "\n[+] Address: " + myXBeeDevice.get64BitAddress().toString() +"\n[+] TX Power: " + myXBeeDevice.getPowerLevel());
                if (!myXBeeDevice.getNetwork().isDiscoveryRunning())
                    myXBeeDevice.getNetwork().startDiscoveryProcess();
            } catch (XBeeException e) {
                e.printStackTrace();
            }
        }
    }
}
