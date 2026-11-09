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

import com.tas.racetiming.R;
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

    private EditText etServerUrl, etUser, etDeviceId, etWiclaxOffset;
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
        etWiclaxOffset = view.findViewById(R.id.et_wiclax_offset);
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

        List<EditText> autoSaveFields = Arrays.asList(etEpcPrefix, etServerUrl, etUser, etDeviceId, etWiclaxOffset,
                etRufusApiKey, etRufusSerial, etRufusAlias);
        for (EditText et : autoSaveFields) {
            et.setOnFocusChangeListener((v, hasFocus) -> { if (!hasFocus) savePrefs(); });
        }

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
        UHFManager uhf = UHFManager.getInstance();
        int temp = uhf.getTemperature();
        if (temp != Integer.MIN_VALUE) {
            tvModuleTemp.setText(String.valueOf(temp));
        }
        int batt = uhf.getBatteryHealth();
        if (batt >= 0) {
            tvBatteryHealth.setText(batt + "%");
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
                .putInt(UploadManager.KEY_WICLAX_OFFSET_MIN, parseOffset())
                .putString(KEY_RUFUS_API_KEY, etRufusApiKey.getText().toString())
                .putString(KEY_RUFUS_SERIAL, etRufusSerial.getText().toString())
                .putString(KEY_RUFUS_ALIAS, etRufusAlias.getText().toString())
                .apply();
    }

    // minutes; blank or invalid -> 0, clamped to +/-24h
    private int parseOffset() {
        try {
            int v = Integer.parseInt(etWiclaxOffset.getText().toString().trim());
            return Math.max(-1440, Math.min(1440, v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void loadPrefs() {
        SharedPreferences p = getPrefs();
        int offset = p.getInt(UploadManager.KEY_WICLAX_OFFSET_MIN, 0);
        etWiclaxOffset.setText(offset == 0 ? "" : String.valueOf(offset));
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
