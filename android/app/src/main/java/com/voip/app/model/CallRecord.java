package com.voip.app.model;

public class CallRecord {
    public static final String TYPE_OUTGOING = "outgoing";
    public static final String TYPE_INCOMING = "incoming";
    public static final String TYPE_MISSED   = "missed";

    public String type;
    public String number;
    public String displayName;
    public long timestamp;
    public int durationSeconds;

    public CallRecord() {}

    public CallRecord(String type, String number, String displayName, long timestamp, int durationSeconds) {
        this.type = type;
        this.number = number;
        this.displayName = displayName;
        this.timestamp = timestamp;
        this.durationSeconds = durationSeconds;
    }
}
