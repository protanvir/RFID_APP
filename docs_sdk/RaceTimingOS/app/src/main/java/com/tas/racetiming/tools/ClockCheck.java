package com.tas.racetiming.tools;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Compares the device clock with internet time (SNTP). Every passing time
 * comes from the device clock, so a wrong clock silently shifts all results.
 * This only measures and reports; it never changes the clock or the
 * timestamps.
 */
public final class ClockCheck {
    private static final String TAG = "ClockCheck";
    private static final String[] SERVERS = {"time.google.com", "pool.ntp.org", "time.cloudflare.com"};
    /** Offsets larger than this are reported as a problem. */
    public static final long WARN_MS = 1000;
    private static final long NTP_EPOCH_OFFSET_S = 2208988800L; // 1900 -> 1970

    public interface Listener {
        void onClockChecked();
    }

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private static volatile boolean checked = false;
    private static volatile boolean ok = false;
    /** internet time minus device time, ms (positive = device clock is behind). */
    private static volatile long offsetMs = 0;
    private static volatile boolean running = false;

    private ClockCheck() {}

    public static void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** True once a check has finished, successfully or not. */
    public static boolean isChecked() {
        return checked;
    }

    /** True if the last check reached a time server. */
    public static boolean isOk() {
        return ok;
    }

    public static long getOffsetMs() {
        return offsetMs;
    }

    public static boolean isOff() {
        return ok && Math.abs(offsetMs) > WARN_MS;
    }

    /** One-line result for the UI. */
    public static String describe() {
        if (!checked) return "Clock: not checked yet";
        if (!ok) return "Clock: could not reach a time server";
        double s = offsetMs / 1000.0;
        if (Math.abs(offsetMs) <= WARN_MS) {
            return String.format(java.util.Locale.US, "Clock OK (%+.2f s)", -s);
        }
        return String.format(java.util.Locale.US, "Device clock is %.1f s %s",
                Math.abs(s), offsetMs > 0 ? "SLOW" : "FAST");
    }

    public static void runAsync() {
        if (running) return;
        running = true;
        new Thread(() -> {
            boolean success = false;
            long off = 0;
            for (String host : SERVERS) {
                try {
                    off = query(host);
                    success = true;
                    break;
                } catch (Exception e) {
                    Log.w(TAG, host + " failed: " + e.getMessage());
                }
            }
            offsetMs = off;
            ok = success;
            checked = true;
            running = false;
            Log.i(TAG, describe());
            main.post(() -> {
                for (Listener l : listeners) l.onClockChecked();
            });
        }, "ClockCheck").start();
    }

    // Minimal SNTP client (RFC 4330): returns server time minus device time.
    private static long query(String host) throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(4000);
            byte[] buf = new byte[48];
            buf[0] = 0x1B; // LI=0, VN=3, Mode=3 (client)
            InetAddress addr = InetAddress.getByName(host);

            long sendWall = System.currentTimeMillis();
            long sendTick = SystemClock.elapsedRealtime();
            socket.send(new DatagramPacket(buf, buf.length, addr, 123));
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            socket.receive(resp);
            long roundTrip = SystemClock.elapsedRealtime() - sendTick;

            if (resp.getLength() < 48) throw new Exception("short reply");
            int stratum = buf[1] & 0xFF;
            if (stratum == 0) throw new Exception("kiss-of-death reply");
            long serverMs = readTimestampMs(buf, 40); // transmit timestamp
            if (serverMs <= 0) throw new Exception("empty timestamp");
            // device time at the moment the server stamped the reply
            long deviceAtReply = sendWall + roundTrip / 2;
            return serverMs - deviceAtReply;
        }
    }

    private static long readTimestampMs(byte[] b, int off) {
        long seconds = 0, fraction = 0;
        for (int i = 0; i < 4; i++) seconds = (seconds << 8) | (b[off + i] & 0xFF);
        for (int i = 4; i < 8; i++) fraction = (fraction << 8) | (b[off + i] & 0xFF);
        if (seconds == 0) return 0;
        return (seconds - NTP_EPOCH_OFFSET_S) * 1000 + (fraction * 1000L) / 0x100000000L;
    }
}
