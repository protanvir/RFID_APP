package com.tas.racetiming.ui.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.tas.racetiming.R;
import com.tas.racetiming.network.WiclaxClient;
import com.tas.racetiming.uhf.UHFManager;

import java.util.Arrays;
import java.util.List;

public class SettingsFragment extends Fragment {

    private static final String PREFS_NAME = "tas_timer_settings";

    private SeekBar sbPower;
    private TextView tvPowerValue, tvPowerLabel;
    private androidx.appcompat.widget.SwitchCompat switchEpc;
    private EditText etEpcPrefix;

    private EditText etServerUrl, etUser, etDeviceId;
    private TextView tvConnectionStatus;
    private View statusDot;
    private Button btnSave;

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

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        sbPower = view.findViewById(R.id.sb_power);
        tvPowerValue = view.findViewById(R.id.tv_power_value);
        tvPowerLabel = view.findViewById(R.id.tv_power_label);
        switchEpc = view.findViewById(R.id.switch_epc_filter);
        etEpcPrefix = view.findViewById(R.id.et_epc_prefix);

        etServerUrl = view.findViewById(R.id.et_server_url);
        etUser = view.findViewById(R.id.et_user);
        etDeviceId = view.findViewById(R.id.et_device_id);
        tvConnectionStatus = view.findViewById(R.id.tv_connection_status);
        statusDot = view.findViewById(R.id.tv_status_dot);
        btnSave = view.findViewById(R.id.btn_save);

        tvModuleTemp = view.findViewById(R.id.tv_module_temp);
        tvBatteryHealth = view.findViewById(R.id.tv_battery_health);

        loadPrefs();

        UHFManager uhf = UHFManager.getInstance();

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

        List<EditText> textFields = Arrays.asList(etEpcPrefix, etServerUrl, etUser, etDeviceId);
        for (EditText et : textFields) {
            et.setOnFocusChangeListener((v, hasFocus) -> { if (!hasFocus) savePrefs(); });
        }

        btnSave.setOnClickListener(v -> {
            savePrefs();
            reconfigureWiclax();
            Toast.makeText(getContext(), "Settings saved", Toast.LENGTH_SHORT).show();
        });

        wiclaxListener = (connected, info) -> {
            statusDot.setBackgroundResource(connected
                    ? R.drawable.status_dot_green : R.drawable.status_dot_red);
            tvConnectionStatus.setText(info);
        };
        WiclaxClient wc = WiclaxClient.getInstance();
        wc.addListener(wiclaxListener);
        wiclaxListener.onStatusChanged(wc.isConnected(), wc.getCurrentStatus());

        updateHardwareMonitor();
        hwHandler.postDelayed(hwRunnable, 5000);

        return view;
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

    private void savePrefs() {
        getPrefs().edit()
                .putInt("power", sbPower.getProgress())
                .putBoolean("epc_filter", switchEpc.isChecked())
                .putString("epc_prefix", etEpcPrefix.getText().toString())
                .putString("server_url", etServerUrl.getText().toString())
                .putString("user", etUser.getText().toString())
                .putString("device_id", etDeviceId.getText().toString())
                .apply();
    }

    private void loadPrefs() {
        SharedPreferences p = getPrefs();
        sbPower.setProgress(p.getInt("power", 26));
        switchEpc.setChecked(p.getBoolean("epc_filter", false));
        etEpcPrefix.setText(p.getString("epc_prefix", ""));
        etEpcPrefix.setEnabled(switchEpc.isChecked());
        etServerUrl.setText(p.getString("server_url", "http://wiclax.com:35014"));
        etUser.setText(p.getString("user", ""));
        etDeviceId.setText(p.getString("device_id", "C72-SN-001"));
        UHFManager.getInstance().setPower(p.getInt("power", 26));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        hwHandler.removeCallbacks(hwRunnable);
        savePrefs();
        if (wiclaxListener != null) {
            WiclaxClient.getInstance().removeListener(wiclaxListener);
        }
    }
}
