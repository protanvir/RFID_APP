package com.tas.racetiming.model;

public class TagRead {
    private final String epc;
    private final String timestamp;
    private final String rssi;

    public TagRead(String epc, String timestamp, String rssi) {
        this.epc = epc;
        this.timestamp = timestamp;
        this.rssi = rssi;
    }

    public String getEpc() {
        return epc;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getRssi() {
        return rssi;
    }
}
