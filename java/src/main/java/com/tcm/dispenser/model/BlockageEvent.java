package com.tcm.dispenser.model;

public class BlockageEvent {
    private long timestampMs;
    private int binId;
    private String binName;
    private int attempt;
    private boolean resolved;
    private String message;

    public BlockageEvent() {
    }

    public BlockageEvent(long timestampMs, int binId, String binName, int attempt, boolean resolved, String message) {
        this.timestampMs = timestampMs;
        this.binId = binId;
        this.binName = binName;
        this.attempt = attempt;
        this.resolved = resolved;
        this.message = message;
    }

    public long getTimestampMs() { return timestampMs; }
    public void setTimestampMs(long timestampMs) { this.timestampMs = timestampMs; }

    public int getBinId() { return binId; }
    public void setBinId(int binId) { this.binId = binId; }

    public String getBinName() { return binName; }
    public void setBinName(String binName) { this.binName = binName; }

    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }

    public boolean isResolved() { return resolved; }
    public void setResolved(boolean resolved) { this.resolved = resolved; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    @Override
    public String toString() {
        return String.format("[%d] %s (Bin%d) 第%d次: %s %s",
                timestampMs, binName, binId, attempt, message,
                resolved ? "✓" : "✗");
    }
}
