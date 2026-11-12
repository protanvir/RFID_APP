package com.tas.racetiming.ui.dashboard;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.tas.racetiming.R;
import com.tas.racetiming.model.TagRead;
import com.tas.racetiming.network.UploadManager;
import com.tas.racetiming.scan.ScanController;
import com.tas.racetiming.tools.BibMap;
import com.tas.racetiming.tools.ClockCheck;
import com.tas.racetiming.tools.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Live reads screen. Purely a view: scanning, logging and uploading are owned
 * by ScanController / UploadManager and keep running without this fragment.
 */
public class DashboardFragment extends Fragment
        implements ScanController.Listener, ClockCheck.Listener {

    private TextView tvTotalReads, tvUniqueTags, tvSendStatus, tvSendCounts, tvClockWarning;
    private TextView tvViewToggle, tvCol1, tvCol2, tvCol3;
    private Button btnScan, btnClear, btnDownloadCsv;
    private LinearLayout feedList;
    private View statusDot;
    private final Handler uiHandler = new Handler();
    private final ScanController scan = ScanController.getInstance();

    /** false = every read as it happens; true = one row per tag */
    private boolean byTagView = false;

    // Rebuilding the feed on every tag read floods the main thread at burst
    // rates; coalesce renders to at most one per interval
    private static final long FEED_RENDER_INTERVAL_MS = 200;
    private boolean feedRenderPending = false;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_dashboard, container, false);

        tvTotalReads = view.findViewById(R.id.tv_total_reads);
        tvUniqueTags = view.findViewById(R.id.tv_unique_tags);
        btnScan = view.findViewById(R.id.btn_scan);
        btnClear = view.findViewById(R.id.btn_clear);
        btnDownloadCsv = view.findViewById(R.id.btn_download_csv);
        feedList = view.findViewById(R.id.feed_list);
        statusDot = view.findViewById(R.id.v_status_dot);
        tvSendStatus = view.findViewById(R.id.tv_send_status);
        tvSendCounts = view.findViewById(R.id.tv_send_counts);
        tvClockWarning = view.findViewById(R.id.tv_clock_warning);
        tvViewToggle = view.findViewById(R.id.tv_view_toggle);
        tvCol1 = view.findViewById(R.id.tv_col1);
        tvCol2 = view.findViewById(R.id.tv_col2);
        tvCol3 = view.findViewById(R.id.tv_col3);

        btnScan.setOnClickListener(v -> toggleScan());
        btnClear.setOnClickListener(v -> scan.clear());
        btnDownloadCsv.setOnClickListener(v -> shareCsv());
        tvViewToggle.setOnClickListener(v -> {
            byTagView = !byTagView;
            applyViewMode();
            renderFeed();
        });
        view.findViewById(R.id.tv_manual_bib).setOnClickListener(v -> showManualBibDialog());
        view.findViewById(R.id.tv_gun_start).setOnClickListener(v -> confirmGunStart());

        scan.addListener(this);
        UploadManager.getInstance().addListener(uploadListener);
        ClockCheck.addListener(this);

        updateScanButton(scan.isScanning());
        applyViewMode();
        updateCounters();
        renderFeed();
        onClockChecked();

        return view;
    }

    private final UploadManager.UploadListener uploadListener = (platform, sent, failed, pending) -> {
        if (statusDot == null) return;
        tvSendCounts.setText("");
        if (UploadManager.PLATFORM_OFF.equals(platform)) {
            statusDot.setBackgroundResource(R.drawable.status_dot_gray);
            tvSendStatus.setText(pending > 0
                    ? "Upload off — " + pending + " waiting" : "Upload off (saving to CSV only)");
            return;
        }
        String name = platform.substring(0, 1).toUpperCase(Locale.US) + platform.substring(1);
        if (failed > 0) {
            statusDot.setBackgroundResource(R.drawable.status_dot_red);
            String why = UploadManager.getInstance().getLastError();
            tvSendStatus.setText("Retrying — " + pending + " waiting"
                    + (why.isEmpty() ? "" : "\n" + why));
        } else if (pending > 0) {
            statusDot.setBackgroundResource(R.drawable.status_dot_orange);
            tvSendStatus.setText(name + ": sending, " + pending + " waiting");
        } else {
            statusDot.setBackgroundResource(R.drawable.status_dot_green);
            tvSendStatus.setText(name + " OK — all uploaded");
        }
    };

    // ---- ScanController.Listener ----

    @Override
    public void onReadsChanged() {
        scheduleFeedRender();
    }

    @Override
    public void onScanStateChanged(boolean scanning) {
        if (btnScan != null) updateScanButton(scanning);
    }

    // ---- ClockCheck.Listener ----

    @Override
    public void onClockChecked() {
        if (tvClockWarning == null) return;
        if (ClockCheck.isOff()) {
            tvClockWarning.setText("⚠ " + ClockCheck.describe()
                    + " — passing times will be wrong. Fix the device time.");
            tvClockWarning.setVisibility(View.VISIBLE);
        } else {
            tvClockWarning.setVisibility(View.GONE);
        }
    }

    // ---- scanning ----

    /** Trigger pressed in toggle mode, or the START/STOP button. */
    public void toggleScan() {
        if (scan.isScanning()) {
            scan.stop();
        } else {
            startScan();
        }
    }

    public void startScan() {
        if (scan.isScanning()) return;
        if (!hasStorageAccess()) {
            // scan still works (live feed + counters), but CSV log and the
            // upload queue both live on external storage
            Toast.makeText(getContext(),
                    "Storage permission missing — reads will NOT be saved or uploaded",
                    Toast.LENGTH_LONG).show();
        }
        if (!scan.start()) {
            Toast.makeText(getContext(), "RFID reader did not start", Toast.LENGTH_LONG).show();
        }
    }

    public void stopScan() {
        scan.stop();
    }

    private boolean hasStorageAccess() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(requireContext(),
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void updateScanButton(boolean scanning) {
        if (getContext() == null) return;
        if (scanning) {
            btnScan.setText(R.string.stop_scanning);
            btnScan.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), android.R.color.holo_red_dark));
        } else {
            btnScan.setText(R.string.start_scanning);
            btnScan.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), android.R.color.holo_green_dark));
        }
    }

    // ---- manual passing / gun start ----

    private void showManualBibDialog() {
        if (getContext() == null) return;
        final EditText input = new EditText(getContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setHint("Bib number");
        new AlertDialog.Builder(requireContext())
                .setTitle("Add passing by hand")
                .setMessage("Records a passing for this bib at the moment you press ADD.")
                .setView(input)
                .setPositiveButton("ADD", (d, w) -> {
                    String epc = scan.manualRead(input.getText().toString());
                    if (epc == null) return;
                    Toast.makeText(getContext(), "Added passing for chip " + epc, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmGunStart() {
        if (getContext() == null) return;
        new AlertDialog.Builder(requireContext())
                .setTitle("Record gun start now?")
                .setMessage("Saves the current time as the race start in today's markers file. "
                        + "It is not sent to the timing platform.")
                .setPositiveButton("RECORD", (d, w) -> scan.markStart())
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- CSV download ----

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
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    // ---- feed ----

    private void applyViewMode() {
        tvViewToggle.setText(byTagView ? R.string.view_by_tag : R.string.view_all_reads);
        tvCol2.setText(byTagView ? R.string.last_seen_header : R.string.time_header);
        tvCol3.setText(byTagView ? R.string.count_rssi_header : R.string.rssi_header);
    }

    private void updateCounters() {
        tvTotalReads.setText(String.valueOf(scan.getTotalReads()));
        tvUniqueTags.setText(String.valueOf(scan.getUniqueTagCount()));
    }

    private void scheduleFeedRender() {
        if (feedRenderPending) return;
        feedRenderPending = true;
        uiHandler.postDelayed(() -> {
            feedRenderPending = false;
            renderFeed();
        }, FEED_RENDER_INTERVAL_MS);
    }

    private void renderFeed() {
        if (getContext() == null || feedList == null) return;
        updateCounters();
        feedList.removeAllViews();
        int row = 0;
        if (byTagView) {
            for (ScanController.TagStat s : scan.getTagStats(ScanController.FEED_MAX_ROWS)) {
                String best = Double.isNaN(s.bestRssi) ? "--" : String.format(Locale.US, "%.0f", s.bestRssi);
                addRow(row++, BibMap.display(s.epc), ScanController.feedTime(s.lastMs), s.count + " / " + best);
            }
        } else {
            for (TagRead read : scan.getRecentReads()) {
                addRow(row++, read.getDisplay(), read.getTimestamp(),
                        read.getRssi().isEmpty() ? "--" : read.getRssi());
            }
        }
    }

    private void addRow(int index, String col1, String col2, String col3) {
        LinearLayout rowLayout = new LinearLayout(getContext());
        rowLayout.setOrientation(LinearLayout.HORIZONTAL);
        rowLayout.setPadding(16, 16, 16, 16);
        rowLayout.setBackgroundColor(getResources().getColor(
                index % 2 == 0 ? R.color.surface_container_lowest : R.color.zinc_900,
                getContext().getTheme()));

        rowLayout.addView(cell(col1, R.color.primary_container, 16, Gravity.START));
        rowLayout.addView(cell(col2, R.color.on_surface, 13, Gravity.CENTER));
        rowLayout.addView(cell(col3, R.color.secondary, 13, Gravity.END));
        feedList.addView(rowLayout);
    }

    private TextView cell(String text, int colorRes, int sizeSp, int gravity) {
        TextView tv = new TextView(getContext());
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        tv.setGravity(gravity);
        tv.setText(text);
        tv.setTextColor(getResources().getColor(colorRes, getContext().getTheme()));
        tv.setTextSize(sizeSp);
        return tv;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Only detach from the shared state. Scanning and uploads are owned by
        // ScanController / UploadManager and deliberately keep running.
        scan.removeListener(this);
        UploadManager.getInstance().removeListener(uploadListener);
        ClockCheck.removeListener(this);
        uiHandler.removeCallbacksAndMessages(null);
        feedRenderPending = false;
        statusDot = null;
        btnScan = null;
        feedList = null;
        tvClockWarning = null;
    }
}
