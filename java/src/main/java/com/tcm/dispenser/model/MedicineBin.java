package com.tcm.dispenser.model;

public class MedicineBin {
    private int id;
    private String name;
    private String code;
    private double remainingGrams;
    private double capacityGrams;
    private double gramsPerPulse;
    private boolean isOnline;

    public MedicineBin() {
    }

    public MedicineBin(int id, String name, String code,
                       double remainingGrams, double capacityGrams,
                       double gramsPerPulse, boolean isOnline) {
        this.id = id;
        this.name = name;
        this.code = code;
        this.remainingGrams = remainingGrams;
        this.capacityGrams = capacityGrams;
        this.gramsPerPulse = gramsPerPulse;
        this.isOnline = isOnline;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public double getRemainingGrams() { return remainingGrams; }
    public void setRemainingGrams(double remainingGrams) { this.remainingGrams = remainingGrams; }

    public double getCapacityGrams() { return capacityGrams; }
    public void setCapacityGrams(double capacityGrams) { this.capacityGrams = capacityGrams; }

    public double getGramsPerPulse() { return gramsPerPulse; }
    public void setGramsPerPulse(double gramsPerPulse) { this.gramsPerPulse = gramsPerPulse; }

    public boolean isOnline() { return isOnline; }
    public void setOnline(boolean online) { isOnline = online; }

    public double getRemainingPercentage() {
        if (capacityGrams <= 0) return 0;
        return (remainingGrams / capacityGrams) * 100.0;
    }

    public String getLevelLabel() {
        double pct = getRemainingPercentage();
        if (pct < 10) return "紧急";
        if (pct < 25) return "偏低";
        if (pct < 50) return "正常";
        return "充足";
    }

    @Override
    public String toString() {
        return String.format("[%d] %s(%s) 剩余:%.1fg/%.1fg %s",
                id, name, code, remainingGrams, capacityGrams, isOnline ? "在线" : "离线");
    }
}
