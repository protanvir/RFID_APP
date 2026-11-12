package com.tas.racetiming.model;

/** One row of the live feed. */
public class TagRead {
    private final String epc;
    private final String display;
    private final String timestamp;
    private final String rssi;

    /** display = text shown in the BIB column (bib number, or a marker label). */
    public TagRead(String epc, String display, String timestamp, String rssi) {
        this.epc = epc;
        this.display = display;
        this.timestamp = timestamp;
        this.rssi = rssi;
    }

    public String getEpc() {
        return epc;
    }

    public String getDisplay() {
        return display;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getRssi() {
        return rssi;
    }
}
