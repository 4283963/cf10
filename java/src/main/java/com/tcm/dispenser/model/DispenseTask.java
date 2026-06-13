package com.tcm.dispenser.model;

public class DispenseTask {
    private int binId;
    private String medicineName;
    private double targetGrams;
    private double dispensedGrams;
    private boolean isCompleted;
    private boolean hasError;
    private String errorMessage;

    public DispenseTask() {
    }

    public DispenseTask(int binId, String medicineName, double targetGrams) {
        this.binId = binId;
        this.medicineName = medicineName;
        this.targetGrams = targetGrams;
        this.dispensedGrams = 0.0;
        this.isCompleted = false;
        this.hasError = false;
    }

    public int getBinId() { return binId; }
    public void setBinId(int binId) { this.binId = binId; }

    public String getMedicineName() { return medicineName; }
    public void setMedicineName(String medicineName) { this.medicineName = medicineName; }

    public double getTargetGrams() { return targetGrams; }
    public void setTargetGrams(double targetGrams) { this.targetGrams = targetGrams; }

    public double getDispensedGrams() { return dispensedGrams; }
    public void setDispensedGrams(double dispensedGrams) { this.dispensedGrams = dispensedGrams; }

    public boolean isCompleted() { return isCompleted; }
    public void setCompleted(boolean completed) { isCompleted = completed; }

    public boolean isHasError() { return hasError; }
    public void setHasError(boolean hasError) { this.hasError = hasError; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public double getProgress() {
        if (targetGrams <= 0) return 0;
        return Math.min(100.0, (dispensedGrams / targetGrams) * 100.0);
    }

    @Override
    public String toString() {
        return String.format("药仓%d %s: 目标%.2fg 已出%.2fg %.1f%% %s",
                binId, medicineName, targetGrams, dispensedGrams, getProgress(),
                isCompleted ? "✓" : (hasError ? "✗" : "…"));
    }
}
