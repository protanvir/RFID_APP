package com.tas.racetiming.ui.dashboard;

import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.tas.racetiming.R;
import com.tas.racetiming.model.SessionData;
import com.tas.racetiming.model.TagRead;
import com.tas.racetiming.network.RufusClient;
import com.tas.racetiming.network.WiclaxClient;
import com.tas.racetiming.tools.FileUtils;
import com.tas.racetiming.uhf.UHFManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedList;

public class DashboardFragment extends Fragment {

    private TextView tvTimer, tvTotalReads, tvUniqueTags, tvSendStatus, tvSendCounts;
    private Button btnScan, btnClear, btnDownloadCsv;
    private LinearLayout feedList;
    private View statusDot;
    private final SessionData session = new SessionData();
    private final LinkedList<TagRead> recentReads = new LinkedList<>();
    private final Handler timerHandler = new Handler();
    private ToneGenerator toneGenerator;
    private long lastBeepMs = 0;

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            String time = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString();
            tvTimer.setText(time);
            tvTotalReads.setText(String.valueOf(session.getTotalReads()));
            tvUniqueTags.setText(String.valueOf(session.getUniqueTagCount()));
            timerHandler.postDelayed(this, 1000);
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_dashboard, container, false);

        tvTimer = view.findViewById(R.id.tv_session_timer);
        tvTotalReads = view.findViewById(R.id.tv_total_reads);
        tvUniqueTags = view.findViewById(R.id.tv_unique_tags);
        btnScan = view.findViewById(R.id.btn_scan);
        btnClear = view.findViewById(R.id.btn_clear);
        btnDownloadCsv = view.findViewById(R.id.btn_download_csv);
        feedList = view.findViewById(R.id.feed_list);
        statusDot = view.findViewById(R.id.v_status_dot);
        tvSendStatus = view.findViewById(R.id.tv_send_status);
        tvSendCounts = view.findViewById(R.id.tv_send_counts);

        toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);

        btnScan.setOnClickListener(v -> onScanClick());
        btnClear.setOnClickListener(v -> clearReads());
        btnDownloadCsv.setOnClickListener(v -> shareCsv());

        updateScanButton(false);

        timerHandler.post(timerRunnable);

        WiclaxClient wc = WiclaxClient.getInstance();
        wc.addListener((connected, info) -> {
            String dbg = wc.getDebugInfo();
            int sent = 0, fail = 0, reads = 0;
            try {
                for (String p : dbg.split(" ")) {
                    if (p.startsWith("sent=")) sent = Integer.parseInt(p.substring(5));
                    if (p.startsWith("fail=")) fail = Integer.parseInt(p.substring(5));
                    if (p.startsWith("reads=")) reads = Integer.parseInt(p.substring(6));
                }
            } catch (Exception ignored) {}
            updateSendStatus(sent, fail, reads, "Wiclax");
        });

        RufusClient rc = RufusClient.getInstance();
        rc.addListener(status -> updateRufusStatus(status));

        return view;
    }

    public void onTriggerPressed() {
        onScanClick();
    }

    private void onScanClick() {
        UHFManager uhf = UHFManager.getInstance();
        if (session.isScanning()) {
            // STOP
            session.stop();
            uhf.stopInventory();
            if ("rufus".equals(getPlatform())) {
                RufusClient.getInstance().closeSession();
            }
            updateScanButton(false);
        } else {
            // START – re-set the callback because stopInventory() clears it
            if (!uhf.isInitialized()) {
                if (getActivity() != null) {
                    uhf.init(getActivity());
                }
            }
            uhf.setTagCallback(this::onTagRead);
            session.start();

            if ("rufus".equals(getPlatform())) {
                RufusClient rc = RufusClient.getInstance();
                rc.startSession(new RufusClient.SessionCallback() {
                    @Override
                    public void onReady() {}
                    @Override
                    public void onError(String message) {
                        Toast.makeText(getContext(), "Rufus: " + message, Toast.LENGTH_SHORT).show();
                    }
                });
            }
            if (uhf.startInventory()) {
                updateScanButton(true);
            }
        }
    }

    private void onTagRead(String epc, String rssi, String user) {
        Log.i("TAG_READ", "epc=" + epc + " rssi=" + rssi + " user=" + user);
        session.incrementReads(epc);
        String time = android.text.format.DateFormat.format("HH:mm:ss.S", System.currentTimeMillis()).toString();
        TagRead read = new TagRead(epc, time, rssi);
        recentReads.addFirst(read);
        if (recentReads.size() > 20) recentReads.removeLast();
        renderFeed();
        playBeep();
        FileUtils.writeTagToCsv(epc, rssi);
        String platform = getPlatform();
        if ("rufus".equals(platform)) {
            Log.i("TAG_READ", "sending to Rufus...");
            RufusClient.getInstance().sendPassing(epc);
        } else if ("wiclax".equals(platform)) {
            Log.i("TAG_READ", "sending to Wiclax...");
            WiclaxClient.getInstance().sendPassing(epc, rssi);
        }
    }

    private String getPlatform() {
        SharedPreferences p = requireActivity().getSharedPreferences("tas_timer_settings", 0);
        return p.getString("selected_platform", "off");
    }

    private void clearReads() {
        UHFManager uhf = UHFManager.getInstance();
        if (session.isScanning()) {
            session.stop();
            uhf.stopInventory();
            if ("rufus".equals(getPlatform())) {
                RufusClient.getInstance().closeSession();
            }
            updateScanButton(false);
        }
        recentReads.clear();
        feedList.removeAllViews();
        session.reset();
        tvTotalReads.setText("0");
        tvUniqueTags.setText("0");
    }

    private void updateScanButton(boolean scanning) {
        if (scanning) {
            btnScan.setText(R.string.stop_scanning);
            btnScan.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), android.R.color.holo_red_dark));
        } else {
            btnScan.setText(R.string.start_scanning);
            btnScan.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), android.R.color.holo_green_dark));
        }
    }

    private void playBeep() {
        long now = System.currentTimeMillis();
        if (now - lastBeepMs < 80) return;
        lastBeepMs = now;
        try {
            if (toneGenerator != null) {
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 60);
            }
        } catch (Exception ignored) {}
    }

    private void updateSendStatus(int sent, int fail, int reads, String label) {
        boolean hasData = reads > 0;
        boolean allOk = sent > 0 && fail == 0;
        boolean anyFail = fail > 0;
        if (hasData && allOk) {
            statusDot.setBackgroundResource(R.drawable.status_dot_green);
            tvSendStatus.setText(label + " OK");
        } else if (anyFail) {
            statusDot.setBackgroundResource(R.drawable.status_dot_red);
            tvSendStatus.setText(label + " errors");
        } else if (hasData) {
            statusDot.setBackgroundResource(R.drawable.status_dot_orange);
            tvSendStatus.setText(label + "...");
        } else {
            statusDot.setBackgroundResource(R.drawable.status_dot_gray);
            tvSendStatus.setText("Ready");
        }
        tvSendCounts.setText("OK " + sent + " / Fail " + fail);
    }

    private void updateRufusStatus(String s) {
        boolean isError = s.contains("error") || s.contains("fail") || s.contains("No") || s.contains("Not");
        boolean isBusy = s.contains("…") || s.contains("Session active") || s.contains("Bound") || s.contains("Sent");
        if (isError) {
            statusDot.setBackgroundResource(R.drawable.status_dot_red);
            tvSendStatus.setText(s);
        } else if (isBusy) {
            statusDot.setBackgroundResource(R.drawable.status_dot_green);
            tvSendStatus.setText(s);
        } else {
            statusDot.setBackgroundResource(R.drawable.status_dot_gray);
            tvSendStatus.setText("Rufus " + s);
        }
    }

    private void shareCsv() {
        File src = new File(FileUtils.getDailyFileName());
        if (!src.exists()) {
            Toast.makeText(getContext(), R.string.no_csv_today, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            File destDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "racetiming");
            if (!destDir.exists()) destDir.mkdirs();
            File dest = new File(destDir, src.getName());
            copyFile(src, dest);
            Toast.makeText(getContext(), "Saved to " + dest.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(getContext(), "Download failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void copyFile(File src, File dst) throws Exception {
        InputStream in = new FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        out.close();
    }

    private void renderFeed() {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            feedList.removeAllViews();
            int count = 0;
            for (TagRead read : recentReads) {
                if (count++ >= 20) break;
                LinearLayout rowLayout = new LinearLayout(getContext());
                rowLayout.setOrientation(LinearLayout.HORIZONTAL);
                rowLayout.setPadding(16, 16, 16, 16);
                rowLayout.setBackgroundColor(getResources().getColor(
                        count % 2 == 0 ? R.color.zinc_900 : R.color.surface_container_lowest,
                        getContext().getTheme()));

                TextView tvEpc = new TextView(getContext());
                tvEpc.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                tvEpc.setText(read.getEpc());
                tvEpc.setTextColor(getResources().getColor(R.color.primary_container, getContext().getTheme()));
                tvEpc.setTextSize(16);

                TextView tvTime = new TextView(getContext());
                tvTime.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                tvTime.setGravity(android.view.Gravity.CENTER);
                tvTime.setText(read.getTimestamp());
                tvTime.setTextColor(getResources().getColor(R.color.on_surface, getContext().getTheme()));
                tvTime.setTextSize(13);

                TextView tvRssi = new TextView(getContext());
                tvRssi.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                tvRssi.setGravity(android.view.Gravity.END);
                tvRssi.setText(read.getRssi().isEmpty() ? "--" : read.getRssi());
                tvRssi.setTextColor(getResources().getColor(R.color.secondary, getContext().getTheme()));
                tvRssi.setTextSize(13);

                rowLayout.addView(tvEpc);
                rowLayout.addView(tvTime);
                rowLayout.addView(tvRssi);
                feedList.addView(rowLayout);
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        UHFManager uhf = UHFManager.getInstance();
        if (session.isScanning()) {
            session.stop();
            uhf.stopInventory();
        }
        RufusClient.getInstance().closeSession();
        timerHandler.removeCallbacks(timerRunnable);
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
    }
}
