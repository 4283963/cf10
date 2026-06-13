package com.tcm.dispenser.model;

public class DispenseTask {
    private int binId;
    private String medicineName;
    private double targetGrams;
    private double dispensedGrams;
    private double actualWeighedGrams;
    private double deficitGrams;
    private int compensationAttempts;
    private int compensationStateCode;
    private int blockageCount;
    private boolean blockageOccurred;
    private boolean isCompleted;
    private boolean hasError;
    private String errorMessage;

    public DispenseTask() {
    }

    public DispenseTask(int binId, double targetGrams, double dispensedGrams,
                        double actualWeighedGrams, double deficitGrams,
                        int compensationAttempts, int compensationStateCode,
                        int blockageCount, boolean blockageOccurred,
                        boolean isCompleted, boolean hasError, String errorMessage) {
        this.binId = binId;
        this.targetGrams = targetGrams;
        this.dispensedGrams = dispensedGrams;
        this.actualWeighedGrams = actualWeighedGrams;
        this.deficitGrams = deficitGrams;
        this.compensationAttempts = compensationAttempts;
        this.compensationStateCode = compensationStateCode;
        this.blockageCount = blockageCount;
        this.blockageOccurred = blockageOccurred;
        this.isCompleted = isCompleted;
        this.hasError = hasError;
        this.errorMessage = errorMessage;
    }

    public DispenseTask(int binId, String medicineName, double targetGrams) {
        this.binId = binId;
        this.medicineName = medicineName;
        this.targetGrams = targetGrams;
        this.dispensedGrams = 0.0;
        this.actualWeighedGrams = 0.0;
        this.deficitGrams = 0.0;
        this.compensationAttempts = 0;
        this.compensationStateCode = 0;
        this.blockageCount = 0;
        this.blockageOccurred = false;
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

    public double getActualWeighedGrams() { return actualWeighedGrams; }
    public void setActualWeighedGrams(double actualWeighedGrams) { this.actualWeighedGrams = actualWeighedGrams; }

    public double getDeficitGrams() { return deficitGrams; }
    public void setDeficitGrams(double deficitGrams) { this.deficitGrams = deficitGrams; }

    public int getCompensationAttempts() { return compensationAttempts; }
    public void setCompensationAttempts(int compensationAttempts) { this.compensationAttempts = compensationAttempts; }

    public int getCompensationStateCode() { return compensationStateCode; }
    public void setCompensationStateCode(int code) { this.compensationStateCode = code; }

    public CompensationState getCompensationState() {
        return CompensationState.fromCode(compensationStateCode);
    }

    public int getBlockageCount() { return blockageCount; }
    public void setBlockageCount(int blockageCount) { this.blockageCount = blockageCount; }

    public boolean isBlockageOccurred() { return blockageOccurred; }
    public void setBlockageOccurred(boolean blockageOccurred) { this.blockageOccurred = blockageOccurred; }

    public boolean isCompleted() { return isCompleted; }
    public void setCompleted(boolean completed) { isCompleted = completed; }

    public boolean isHasError() { return hasError; }
    public void setHasError(boolean hasError) { this.hasError = hasError; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public double getProgress() {
        if (targetGrams <= 0) return 0;
        double current = Math.max(dispensedGrams, actualWeighedGrams);
        return Math.min(100.0, (current / targetGrams) * 100.0);
    }

    public String getStatusText() {
        CompensationState state = getCompensationState();
        if (blockageOccurred) {
            return state.getDescription() + " (堵料×" + blockageCount + ")";
        }
        if (compensationAttempts > 0) {
            return state.getDescription() + " (补差×" + compensationAttempts + ")";
        }
        return state.getDescription();
    }

    @Override
    public String toString() {
        return String.format("药仓%d %s: 目标%.2fg 已出%.2fg 实测%.2fg 欠量%.2fg %.1f%% [%s] %s",
                binId, medicineName, targetGrams, dispensedGrams, actualWeighedGrams, deficitGrams,
                getProgress(), getStatusText(),
                isCompleted ? "✓" : (hasError ? "✗" : "…"));
    }
}
