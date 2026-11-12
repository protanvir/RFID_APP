package com.tas.racetiming;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.rscja.scanner.utility.ScannerUtility;
import com.tas.racetiming.network.UploadManager;
import com.tas.racetiming.network.WiclaxClient;
import com.tas.racetiming.scan.ScanController;
import com.tas.racetiming.scan.ScanService;
import com.tas.racetiming.ui.dashboard.DashboardFragment;
import com.tas.racetiming.ui.settings.SettingsFragment;
import com.tas.racetiming.uhf.UHFManager;

public class MainActivity extends AppCompatActivity {

    private static final String TAG_DASHBOARD = "dashboard";
    private static final String TAG_SETTINGS = "settings";

    private FragmentManager fragmentManager;
    private DashboardFragment dashboardFragment;
    private SettingsFragment settingsFragment;
    private TextView tvConnectionBadge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // Never auto-sleep/lock while the app is in front (a race session can
        // run for hours); the physical power button still locks the device.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        checkStoragePermission();

        tvConnectionBadge = findViewById(R.id.tv_connection_badge);

        fragmentManager = getSupportFragmentManager();

        // Reattach restored instances instead of creating duplicates
        dashboardFragment = (DashboardFragment) fragmentManager.findFragmentByTag(TAG_DASHBOARD);
        settingsFragment = (SettingsFragment) fragmentManager.findFragmentByTag(TAG_SETTINGS);
        if (dashboardFragment == null) {
            dashboardFragment = new DashboardFragment();
            fragmentManager.beginTransaction()
                    .add(R.id.nav_host_fragment, dashboardFragment, TAG_DASHBOARD)
                    .commit();
        }

        ImageButton btnSettings = findViewById(R.id.btn_settings);

        btnSettings.setOnClickListener(v -> {
            if (settingsFragment != null && settingsFragment.isVisible()) {
                showDashboard();
            } else {
                showSettings();
            }
        });

        // Load saved Wiclax config and start
        SharedPreferences prefs = getSharedPreferences("tas_timer_settings", 0);
        String savedUrl = prefs.getString("server_url", "http://wiclax.com:35014");
        String savedUser = prefs.getString("user", "");
        String savedDeviceId = prefs.getString("device_id", "C72-SN-001");

        WiclaxClient wc = WiclaxClient.getInstance();
        wc.configure(savedUrl, savedUser, savedDeviceId);
        wc.start();

        // Header badge: the real upload state, not just "ON/OFF"
        UploadManager.getInstance().addListener(badgeListener);


        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }

    private boolean backlogServiceStarted = false;

    private final UploadManager.UploadListener badgeListener = (platform, sent, failed, pending) -> {
        // Passings left over from a previous run: keep the app alive until
        // they are uploaded, even if the user leaves straight away
        if (!backlogServiceStarted && pending > 0 && !UploadManager.PLATFORM_OFF.equals(platform)) {
            backlogServiceStarted = true;
            ScanService.start(this);
        }
        String text;
        int color;
        if (UploadManager.PLATFORM_OFF.equals(platform)) {
            text = "UPLOAD OFF";
            color = R.color.outline;
        } else if (failed > 0) {
            text = platform.toUpperCase(java.util.Locale.US) + " RETRYING (" + pending + ")";
            color = android.R.color.holo_red_light;
        } else if (pending > 0) {
            text = platform.toUpperCase(java.util.Locale.US) + " SENDING (" + pending + ")";
            color = android.R.color.holo_orange_light;
        } else {
            text = platform.toUpperCase(java.util.Locale.US) + " OK";
            color = android.R.color.holo_green_light;
        }
        tvConnectionBadge.setText(text);
        tvConnectionBadge.setTextColor(getResources().getColor(color, getTheme()));
    };

    // show/hide keeps DashboardFragment alive — replace() destroyed its view,
    // which stopped scanning and the upload queue mid-race
    public void showDashboard() {
        androidx.fragment.app.FragmentTransaction tx = fragmentManager.beginTransaction();
        if (settingsFragment != null) tx.hide(settingsFragment);
        tx.show(dashboardFragment).commit();
    }

    public void showSettings() {
        androidx.fragment.app.FragmentTransaction tx = fragmentManager.beginTransaction();
        if (settingsFragment == null) {
            settingsFragment = new SettingsFragment();
            tx.add(R.id.nav_host_fragment, settingsFragment, TAG_SETTINGS);
        }
        tx.hide(dashboardFragment).show(settingsFragment).commit();
    }

    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            }
        }
    }

    // The system Keyboard Emulator (com.rscja.scanner) listens to the scan
    // trigger globally and fires the barcode/QR engine. Close it while this
    // app is in front so the trigger only drives our RFID inventory, and
    // restore it when leaving so other apps keep their scan key.
    @Override
    protected void onResume() {
        super.onResume();
        ScanController.uiResumed = true;
        try {
            ScannerUtility.getScannerInerface().close(this);
        } catch (Throwable t) {
            Log.w("MainActivity", "Keyboard emulator close failed: " + t.getMessage());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ScanController.uiResumed = false;
        // While a scan is running in the background the system scanner stays
        // closed, so it cannot fight our reader for the RFID module.
        // ScanController reopens it when that scan stops.
        if (ScanController.getInstance().isScanning()) return;
        try {
            ScannerUtility.getScannerInerface().open(this);
        } catch (Throwable t) {
            Log.w("MainActivity", "Keyboard emulator open failed: " + t.getMessage());
        }
    }

    private static boolean isTriggerKey(int keyCode) {
        return keyCode == 139 || keyCode == 280 || keyCode == 291
                || keyCode == 293 || keyCode == 294 || keyCode == 311
                || keyCode == 312 || keyCode == 313 || keyCode == 315
                || keyCode == 591 || keyCode == 593 || keyCode == 594
                || keyCode == 596;
    }

    // Trigger modes (Settings): toggle = press to start, press again to stop;
    // hold = scan only while the trigger is held down.
    private boolean holdToScan() {
        return getSharedPreferences(ScanController.PREFS, 0)
                .getBoolean(ScanController.KEY_TRIGGER_HOLD, false);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isTriggerKey(keyCode)) {
            if (event.getRepeatCount() == 0
                    && dashboardFragment != null && dashboardFragment.isVisible()) {
                if (holdToScan()) {
                    dashboardFragment.startScan();
                } else {
                    dashboardFragment.toggleScan();
                }
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isTriggerKey(keyCode)) {
            if (holdToScan() && dashboardFragment != null) dashboardFragment.stopScan();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (settingsFragment != null && settingsFragment.isVisible()) {
            showDashboard();
        } else if (ScanController.getInstance().isScanning()) {
            // leaving with Back closes the app and stops the reader: make sure
            // that is not an accident mid-race
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Stop scanning and close?")
                    .setMessage("The reader is scanning. Closing the app stops it. "
                            + "Use the Home button instead to keep scanning in the background.")
                    .setPositiveButton("STOP AND CLOSE", (d, w) -> super.onBackPressed())
                    .setNegativeButton("Keep scanning", null)
                    .show();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        UploadManager.getInstance().removeListener(badgeListener);
        // Only a deliberate close (Back) stops the reader. If Android destroys
        // the activity while it is in the background, scanning carries on in
        // ScanService. Uploads always continue either way.
        if (isFinishing()) {
            ScanController.getInstance().stop();
            WiclaxClient.getInstance().stop();
            UHFManager.getInstance().free();
        }
    }
}
