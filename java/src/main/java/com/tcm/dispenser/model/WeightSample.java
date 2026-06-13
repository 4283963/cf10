package com.tcm.dispenser.model;

public class WeightSample {
    private long timestampMs;
    private int binId;
    private double weightGrams;

    public WeightSample() {
    }

    public WeightSample(long timestampMs, int binId, double weightGrams) {
        this.timestampMs = timestampMs;
        this.binId = binId;
        this.weightGrams = weightGrams;
    }

    public long getTimestampMs() { return timestampMs; }
    public void setTimestampMs(long timestampMs) { this.timestampMs = timestampMs; }

    public int getBinId() { return binId; }
    public void setBinId(int binId) { this.binId = binId; }

    public double getWeightGrams() { return weightGrams; }
    public void setWeightGrams(double weightGrams) { this.weightGrams = weightGrams; }

    @Override
    public String toString() {
        return String.format("[%d] Bin%d: %.3fg", timestampMs, binId, weightGrams);
    }
}
