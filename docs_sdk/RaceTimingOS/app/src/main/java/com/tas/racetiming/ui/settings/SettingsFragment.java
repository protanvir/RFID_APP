package com.tas.racetiming.ui.settings;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.tas.racetiming.AppContext;
import com.tas.racetiming.R;
import com.tas.racetiming.scan.ScanController;
import com.tas.racetiming.tools.BibMap;
import com.tas.racetiming.tools.ClockCheck;
import com.tas.racetiming.tools.FileUtils;
import com.tas.racetiming.tools.QueueLine;
import com.tas.racetiming.tools.SettingsBackup;
import com.tas.racetiming.network.RufusClient;
import com.tas.racetiming.network.UploadManager;
import com.tas.racetiming.network.WiclaxClient;
import com.tas.racetiming.uhf.UHFManager;

import java.util.Arrays;
import java.util.List;

public class SettingsFragment extends Fragment {

    private static final String PREFS_NAME = "tas_timer_settings";

    private static final String KEY_PLATFORM = "selected_platform";
    private static final String KEY_RUFUS_API_KEY = "rufus_api_key";
    private static final String KEY_RUFUS_SERIAL = "rufus_serial";
    private static final String KEY_RUFUS_ALIAS = "rufus_alias";
    private static final String KEY_RUFUS_DEVICE_ID = "rufus_device_id";

    private SeekBar sbPower;
    private TextView tvPowerValue, tvPowerLabel;
    private androidx.appcompat.widget.SwitchCompat switchEpc;
    private EditText etEpcPrefix;

    private RadioGroup rgPlatform;
    private View wiclaxSection, rufusSection;

    private EditText etServerUrl, etUser, etDeviceId;
    private EditText etMinRssi, etKeepDays;
    private androidx.appcompat.widget.SwitchCompat switchTriggerHold;
    private TextView tvHealth, tvBibStatus;
    private TextView tvConnectionStatus;
    private View statusDot;
    private Button btnSave;

    private EditText etRufusApiKey, etRufusSerial, etRufusAlias;
    private TextView tvRufusStatus;
    private View rufusStatusDot;
    private Button btnBindRufus;

    private TextView tvModuleTemp, tvBatteryHealth;
    private final Handler hwHandler = new Handler();
    private final Runnable hwRunnable = new Runnable() {
        @Override
        public void run() {
            updateHardwareMonitor();
            hwHandler.postDelayed(this, 5000);
        }
    };

    private WiclaxClient.WiclaxListener wiclaxListener;
    private RufusClient.RufusListener rufusListener;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        sbPower = view.findViewById(R.id.sb_power);
        tvPowerValue = view.findViewById(R.id.tv_power_value);
        tvPowerLabel = view.findViewById(R.id.tv_power_label);
        switchEpc = view.findViewById(R.id.switch_epc_filter);
        etEpcPrefix = view.findViewById(R.id.et_epc_prefix);

        rgPlatform = view.findViewById(R.id.rg_platform);
        wiclaxSection = view.findViewById(R.id.wiclax_config_section);
        rufusSection = view.findViewById(R.id.rufus_config_section);

        etServerUrl = view.findViewById(R.id.et_server_url);
        etUser = view.findViewById(R.id.et_user);
        etDeviceId = view.findViewById(R.id.et_device_id);
        etMinRssi = view.findViewById(R.id.et_min_rssi);
        etKeepDays = view.findViewById(R.id.et_keep_days);
        switchTriggerHold = view.findViewById(R.id.switch_trigger_hold);
        tvHealth = view.findViewById(R.id.tv_health);
        tvBibStatus = view.findViewById(R.id.tv_bib_status);
        tvConnectionStatus = view.findViewById(R.id.tv_connection_status);
        statusDot = view.findViewById(R.id.tv_status_dot);
        btnSave = view.findViewById(R.id.btn_save);

        etRufusApiKey = view.findViewById(R.id.et_rufus_api_key);
        etRufusSerial = view.findViewById(R.id.et_rufus_serial);
        etRufusAlias = view.findViewById(R.id.et_rufus_alias);
        tvRufusStatus = view.findViewById(R.id.tv_rufus_status);
        rufusStatusDot = view.findViewById(R.id.tv_rufus_status_dot);
        btnBindRufus = view.findViewById(R.id.btn_bind_rufus);

        tvModuleTemp = view.findViewById(R.id.tv_module_temp);
        tvBatteryHealth = view.findViewById(R.id.tv_battery_health);

        loadPrefs();

        UHFManager uhf = UHFManager.getInstance();

        rgPlatform.setOnCheckedChangeListener((group, checkedId) -> {
            updateSectionVisibility(checkedId);
            savePrefs();
        });

        sbPower.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvPowerValue.setText(String.valueOf(progress));
                updatePowerLabel(progress);
                if (fromUser) { uhf.setPower(progress); savePrefs(); }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        switchEpc.setOnCheckedChangeListener((buttonView, isChecked) -> {
            etEpcPrefix.setEnabled(isChecked);
            uhf.setEpcFilter(etEpcPrefix.getText().toString(), isChecked);
            savePrefs();
        });

        List<EditText> autoSaveFields = Arrays.asList(etEpcPrefix, etServerUrl, etUser, etDeviceId,
                etMinRssi, etKeepDays, etRufusApiKey, etRufusSerial, etRufusAlias);
        for (EditText et : autoSaveFields) {
            et.setOnFocusChangeListener((v, hasFocus) -> { if (!hasFocus) savePrefs(); });
        }

        switchTriggerHold.setOnCheckedChangeListener((b, checked) -> savePrefs());

        view.findViewById(R.id.btn_check_clock).setOnClickListener(v -> {
            ClockCheck.runAsync();
            Toast.makeText(getContext(), "Checking clock…", Toast.LENGTH_SHORT).show();
        });
        view.findViewById(R.id.btn_reload_bibs).setOnClickListener(v -> reloadBibs());
        view.findViewById(R.id.btn_resend).setOnClickListener(v -> showResendDialog());
        view.findViewById(R.id.btn_export_settings).setOnClickListener(v -> exportSettings());
        view.findViewById(R.id.btn_import_settings).setOnClickListener(v -> confirmImportSettings());
        updateBibStatus();
        updateHealth();

        btnSave.setOnClickListener(v -> {
            savePrefs();
            reconfigureWiclax();
            Toast.makeText(getContext(), "Settings saved", Toast.LENGTH_SHORT).show();
        });

        btnBindRufus.setOnClickListener(v -> {
            savePrefs();
            reconfigureRufus();
            RufusClient.getInstance().bindDevice();
            Toast.makeText(getContext(), "Binding device…", Toast.LENGTH_SHORT).show();
        });

        wiclaxListener = (connected, info) -> {
            statusDot.setBackgroundResource(connected
                    ? R.drawable.status_dot_green : R.drawable.status_dot_red);
            tvConnectionStatus.setText(info);
        };
        WiclaxClient wc = WiclaxClient.getInstance();
        wc.addListener(wiclaxListener);
        wiclaxListener.onStatusChanged(wc.isConnected(), wc.getCurrentStatus());

        rufusListener = (status, error) -> {
            tvRufusStatus.setText(status);
            rufusStatusDot.setBackgroundResource(error
                    ? R.drawable.status_dot_red : R.drawable.status_dot_green);
        };
        RufusClient rc = RufusClient.getInstance();
        rc.addListener(rufusListener);
        rufusListener.onStatusChanged(rc.getStatus(), rc.isStatusError());

        updateHardwareMonitor();
        hwHandler.postDelayed(hwRunnable, 5000);

        return view;
    }

    private void updateSectionVisibility(int checkedId) {
        if (checkedId == R.id.rb_platform_wiclax) {
            wiclaxSection.setVisibility(View.VISIBLE);
            rufusSection.setVisibility(View.GONE);
        } else if (checkedId == R.id.rb_platform_rufus) {
            wiclaxSection.setVisibility(View.GONE);
            rufusSection.setVisibility(View.VISIBLE);
        } else {
            wiclaxSection.setVisibility(View.GONE);
            rufusSection.setVisibility(View.GONE);
        }
    }

    private void updateHardwareMonitor() {
        updateHealth();
        UHFManager uhf = UHFManager.getInstance();
        // asking the module for its temperature while it is scanning disturbs
        // the UART traffic, so only poll it when the reader is idle
        if (!ScanController.getInstance().isScanning()) {
            int temp = uhf.getTemperature();
            if (temp != Integer.MIN_VALUE) {
                tvModuleTemp.setText(String.valueOf(temp));
            }
        }
        int batt = uhf.getBatteryHealth();
        if (batt >= 0) {
            tvBatteryHealth.setText(batt + "%");
        }
    }

    // ---- status panel ----

    private void updateHealth() {
        if (tvHealth == null || getContext() == null) return;
        UploadManager um = UploadManager.getInstance();
        StringBuilder sb = new StringBuilder();
        String platform = um.getPlatform();
        sb.append("Upload: ").append(UploadManager.PLATFORM_OFF.equals(platform) ? "off" : platform);
        sb.append("\nWaiting to upload: ").append(um.pendingCount());
        long last = um.getLastSuccessMs();
        sb.append("\nLast upload: ").append(last == 0 ? "none since app start"
                : new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date(last)));
        String err = um.getLastError();
        sb.append("\nLast error: ").append(err.isEmpty() ? "none" : err);
        sb.append("\nSequence number: ").append(um.getCurrentSeq());
        sb.append("\n").append(ClockCheck.describe());
        sb.append("\nBattery: ").append(batteryPercentText());
        sb.append("\nReader: ").append(ScanController.getInstance().isScanning() ? "scanning" : "idle");
        try {
            sb.append("\nApp version: ").append(requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName);
        } catch (Exception ignored) {}
        tvHealth.setText(sb.toString());
    }

    private String batteryPercentText() {
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager)
                    requireContext().getSystemService(android.content.Context.BATTERY_SERVICE);
            int pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (pct > 0 && pct <= 100) return pct + "%" + (bm.isCharging() ? " (charging)" : "");
        } catch (Exception ignored) {}
        return "unknown";
    }

    // ---- bib list ----

    private void updateBibStatus() {
        int n = BibMap.size();
        tvBibStatus.setText(n > 0
                ? n + " bibs loaded from " + BibMap.FILE
                : "No bib list. Chips show as short numbers.\nTo use one, put a file at "
                        + BibMap.FILE + " with one \"CHIP,BIB\" pair per line.");
    }

    private void reloadBibs() {
        new Thread(() -> {
            int n = BibMap.load();
            hwHandler.post(() -> {
                if (getContext() == null) return;
                updateBibStatus();
                Toast.makeText(getContext(), n > 0 ? n + " bibs loaded" : "No bib list file found",
                        Toast.LENGTH_SHORT).show();
            });
        }, "BibLoad").start();
    }

    // ---- re-send from log ----

    private void showResendDialog() {
        if (getContext() == null) return;
        if (UploadManager.PLATFORM_OFF.equals(UploadManager.getInstance().getPlatform())) {
            Toast.makeText(getContext(), "Choose Wiclax or Rufus first", Toast.LENGTH_LONG).show();
            return;
        }
        android.widget.LinearLayout box = new android.widget.LinearLayout(getContext());
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText from = new EditText(getContext());
        from.setHint("From (HH:mm or HH:mm:ss)");
        final EditText to = new EditText(getContext());
        to.setHint("To (HH:mm or HH:mm:ss)");
        box.addView(from);
        box.addView(to);

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Re-send reads from today's log")
                .setMessage("Uploads every read logged today between the two times, with its "
                        + "original read time. Reads that were already uploaded will be sent "
                        + "again and may show twice on the platform.")
                .setView(box)
                .setPositiveButton("RE-SEND", (d, w) -> resend(from.getText().toString(), to.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Today's date at the given "HH:mm" or "HH:mm:ss"; -1 if it cannot be read. */
    private static long timeTodayMs(String text, boolean endOfSecond) {
        String[] p = text.trim().split(":");
        if (p.length < 2 || p.length > 3) return -1;
        try {
            int h = Integer.parseInt(p[0].trim()), m = Integer.parseInt(p[1].trim());
            int s = p.length == 3 ? Integer.parseInt(p[2].trim()) : (endOfSecond ? 59 : 0);
            if (h < 0 || h > 23 || m < 0 || m > 59 || s < 0 || s > 59) return -1;
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.set(java.util.Calendar.HOUR_OF_DAY, h);
            c.set(java.util.Calendar.MINUTE, m);
            c.set(java.util.Calendar.SECOND, s);
            c.set(java.util.Calendar.MILLISECOND, endOfSecond ? 999 : 0);
            return c.getTimeInMillis();
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void resend(String fromText, String toText) {
        final long from = timeTodayMs(fromText, false);
        final long to = timeTodayMs(toText, true);
        if (from < 0 || to < 0 || to < from) {
            Toast.makeText(getContext(), "Enter the times as HH:mm, From before To", Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(() -> {
            List<QueueLine> reads = FileUtils.readLogRange(from, to);
            UploadManager.getInstance().enqueueAll(reads);
            hwHandler.post(() -> {
                if (getContext() == null) return;
                Toast.makeText(getContext(), reads.isEmpty()
                        ? "No reads in today's log for that time range"
                        : reads.size() + " reads queued for upload", Toast.LENGTH_LONG).show();
            });
        }, "Resend").start();
    }

    // ---- settings backup ----

    private void exportSettings() {
        savePrefs();
        // savePrefs() uses apply(); wait for it so the file has the latest values
        hwHandler.postDelayed(() -> {
            if (getContext() == null) return;
            try {
                int n = SettingsBackup.exportTo(getPrefs());
                Toast.makeText(getContext(), n + " settings saved to " + SettingsBackup.FILE,
                        Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(getContext(), "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, 300);
    }

    private void confirmImportSettings() {
        if (getContext() == null) return;
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Import settings?")
                .setMessage("Replaces the current settings with the ones in " + SettingsBackup.FILE + ".")
                .setPositiveButton("IMPORT", (d, w) -> {
                    try {
                        int n = SettingsBackup.importFrom(getPrefs());
                        loadPrefs();
                        ScanController.getInstance().applyReaderSettings();
                        Toast.makeText(getContext(), n + " settings imported", Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Toast.makeText(getContext(), "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Number in a field, or the fallback when it is blank or not a number. */
    private static int intOr(EditText et, int fallback) {
        try {
            return Integer.parseInt(et.getText().toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void updatePowerLabel(int level) {
        String l = level >= 26 ? getString(R.string.high_power) :
                   level >= 14 ? getString(R.string.medium_power) :
                   getString(R.string.low_power);
        tvPowerLabel.setText(l);
    }

    private SharedPreferences getPrefs() {
        return requireActivity().getSharedPreferences(PREFS_NAME, 0);
    }

    private void reconfigureWiclax() {
        String url = etServerUrl.getText().toString().trim();
        if (url.isEmpty()) url = "http://wiclax.com:35014";
        WiclaxClient.getInstance().configure(url,
                etUser.getText().toString().trim(),
                etDeviceId.getText().toString().trim());
    }

    private void reconfigureRufus() {
        String key = etRufusApiKey.getText().toString().trim();
        String sn = etRufusSerial.getText().toString().trim();
        String alias = etRufusAlias.getText().toString().trim();
        RufusClient.getInstance().configure(key, sn, alias);
    }

    private void savePrefs() {
        int checkedId = rgPlatform.getCheckedRadioButtonId();
        String platform = checkedId == R.id.rb_platform_wiclax ? "wiclax"
                : checkedId == R.id.rb_platform_rufus ? "rufus" : "off";

        getPrefs().edit()
                .putString(KEY_PLATFORM, platform)
                .putInt("power", sbPower.getProgress())
                .putBoolean("epc_filter", switchEpc.isChecked())
                .putString("epc_prefix", etEpcPrefix.getText().toString())
                .putString("server_url", etServerUrl.getText().toString().trim())
                .putString("user", etUser.getText().toString().trim())
                .putString("device_id", etDeviceId.getText().toString().trim())
                // min signal is a negative dBm value; anything >= 0 means "off"
                .putInt(ScanController.KEY_MIN_RSSI, Math.min(0, intOr(etMinRssi, 0)))
                .putBoolean(ScanController.KEY_TRIGGER_HOLD, switchTriggerHold.isChecked())
                .putInt(AppContext.KEY_KEEP_DAYS, Math.max(0, intOr(etKeepDays, 0)))
                .putString(KEY_RUFUS_API_KEY, etRufusApiKey.getText().toString())
                .putString(KEY_RUFUS_SERIAL, etRufusSerial.getText().toString())
                .putString(KEY_RUFUS_ALIAS, etRufusAlias.getText().toString())
                .apply();
        UHFManager.getInstance().setMinRssi(Math.min(0, intOr(etMinRssi, 0)));
    }

    private void loadPrefs() {
        SharedPreferences p = getPrefs();
        int minRssi = p.getInt(ScanController.KEY_MIN_RSSI, 0);
        etMinRssi.setText(minRssi == 0 ? "" : String.valueOf(minRssi));
        int keepDays = p.getInt(AppContext.KEY_KEEP_DAYS, 0);
        etKeepDays.setText(keepDays == 0 ? "" : String.valueOf(keepDays));
        switchTriggerHold.setChecked(p.getBoolean(ScanController.KEY_TRIGGER_HOLD, false));
        sbPower.setProgress(p.getInt("power", 26));
        switchEpc.setChecked(p.getBoolean("epc_filter", false));
        etEpcPrefix.setText(p.getString("epc_prefix", ""));
        etEpcPrefix.setEnabled(switchEpc.isChecked());
        etServerUrl.setText(p.getString("server_url", "http://wiclax.com:35014"));
        etUser.setText(p.getString("user", ""));
        etDeviceId.setText(p.getString("device_id", "C72-SN-001"));
        UHFManager.getInstance().setPower(p.getInt("power", 26));

        String platform = p.getString(KEY_PLATFORM, "off");
        int checkedId;
        switch (platform) {
            case "wiclax": checkedId = R.id.rb_platform_wiclax; break;
            case "rufus":  checkedId = R.id.rb_platform_rufus;  break;
            default:       checkedId = R.id.rb_platform_off;    break;
        }
        rgPlatform.check(checkedId);
        updateSectionVisibility(checkedId);

        etRufusApiKey.setText(p.getString(KEY_RUFUS_API_KEY, ""));
        String defaultSn = p.getString(KEY_RUFUS_SERIAL, "");
        if (defaultSn.isEmpty()) {
            // Build.SERIAL returns "unknown" on API 26+ without privileged perms;
            // fall back to ANDROID_ID, which is stable per device
            if (Build.SERIAL != null && !Build.SERIAL.isEmpty() && !"unknown".equals(Build.SERIAL)) {
                defaultSn = Build.SERIAL;
            } else {
                String androidId = android.provider.Settings.Secure.getString(
                        requireActivity().getContentResolver(),
                        android.provider.Settings.Secure.ANDROID_ID);
                defaultSn = (androidId != null && !androidId.isEmpty())
                        ? "C72-" + androidId : "C72-SN-001";
            }
        }
        etRufusSerial.setText(defaultSn);
        String defaultAlias = p.getString(KEY_RUFUS_ALIAS, "");
        if (defaultAlias.isEmpty()) {
            defaultAlias = "C72-UHF-" + defaultSn;
        }
        etRufusAlias.setText(defaultAlias);

        String cachedDeviceId = p.getString(KEY_RUFUS_DEVICE_ID, null);
        RufusClient rc = RufusClient.getInstance();
        if (cachedDeviceId != null && !cachedDeviceId.isEmpty()) {
            rc.setDeviceId(cachedDeviceId);
        }
        reconfigureRufus();

        WiclaxClient.getInstance().configure(
                p.getString("server_url", "http://wiclax.com:35014"),
                p.getString("user", ""),
                p.getString("device_id", "C72-SN-001"));
    }

    // Fragment is hidden/shown rather than destroyed on navigation; polling the
    // UHF module temperature during an active scan disturbs UART traffic, so
    // stop it whenever this screen is not visible
    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            hwHandler.removeCallbacks(hwRunnable);
            savePrefs();
        } else {
            updateHardwareMonitor();
            hwHandler.postDelayed(hwRunnable, 5000);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        hwHandler.removeCallbacks(hwRunnable);
        savePrefs();

        String deviceId = RufusClient.getInstance().getDeviceId();
        if (deviceId != null) {
            getPrefs().edit().putString(KEY_RUFUS_DEVICE_ID, deviceId).apply();
        }

        if (wiclaxListener != null) {
            WiclaxClient.getInstance().removeListener(wiclaxListener);
        }
        if (rufusListener != null) {
            RufusClient.getInstance().removeListener(rufusListener);
        }
    }
}
