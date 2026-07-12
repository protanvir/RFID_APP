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
import android.view.KeyEvent;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.tas.racetiming.network.WiclaxClient;
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
        wc.addListener((connected, info) -> tvConnectionBadge.setText(connected ? "ON" : "OFF"));
        tvConnectionBadge.setText("...");
        wc.start();
    }

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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == 139 || keyCode == 280 || keyCode == 291
                || keyCode == 293 || keyCode == 294 || keyCode == 311
                || keyCode == 312 || keyCode == 313 || keyCode == 315
                || keyCode == 591 || keyCode == 593 || keyCode == 594
                || keyCode == 596) {
            if (event.getRepeatCount() == 0) {
                if (dashboardFragment != null && dashboardFragment.isVisible()) {
                    dashboardFragment.onTriggerPressed();
                }
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (settingsFragment != null && settingsFragment.isVisible()) {
            showDashboard();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        WiclaxClient.getInstance().stop();
        UHFManager.getInstance().free();
    }
}
