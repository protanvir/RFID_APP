package com.tas.racetiming.ui.dashboard;

import android.content.Intent;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
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
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.tas.racetiming.R;
import com.tas.racetiming.model.SessionData;
import com.tas.racetiming.model.TagRead;
import com.tas.racetiming.network.WiclaxClient;
import com.tas.racetiming.tools.FileUtils;
import com.tas.racetiming.uhf.UHFManager;

import java.io.File;
import java.util.Locale;

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
            boolean hasData = reads > 0;
            boolean allOk = sent > 0 && fail == 0;
            boolean anyFail = fail > 0;
            if (hasData && allOk) {
                statusDot.setBackgroundResource(R.drawable.status_dot_green);
                tvSendStatus.setText("Sending OK");
            } else if (anyFail) {
                statusDot.setBackgroundResource(R.drawable.status_dot_red);
                tvSendStatus.setText("Send errors");
            } else if (hasData) {
                statusDot.setBackgroundResource(R.drawable.status_dot_orange);
                tvSendStatus.setText("Sending...");
            } else {
                statusDot.setBackgroundResource(R.drawable.status_dot_gray);
                tvSendStatus.setText("Ready");
            }
            tvSendCounts.setText("OK " + sent + " / Fail " + fail);
        });

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
        Log.i("TAG_READ", "sending to Wiclax...");
        WiclaxClient.getInstance().sendPassing(epc, rssi);
        Log.i("TAG_READ", "sendPassing returned");
    }

    private void clearReads() {
        UHFManager uhf = UHFManager.getInstance();
        if (session.isScanning()) {
            session.stop();
            uhf.stopInventory();
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

    private void shareCsv() {
        File file = new File(FileUtils.getDailyFileName());
        if (!file.exists()) {
            Toast.makeText(getContext(), R.string.no_csv_today, Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, getString(R.string.download_csv)));
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
        timerHandler.removeCallbacks(timerRunnable);
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
    }
}
