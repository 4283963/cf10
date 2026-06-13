package com.tcm.dispenser.model;

public class PrescriptionItem {
    private int binId;
    private String medicineName;
    private String medicineCode;
    private double dosageGrams;

    public PrescriptionItem() {
    }

    public PrescriptionItem(int binId, String medicineName, String medicineCode, double dosageGrams) {
        this.binId = binId;
        this.medicineName = medicineName;
        this.medicineCode = medicineCode;
        this.dosageGrams = dosageGrams;
    }

    public int getBinId() { return binId; }
    public void setBinId(int binId) { this.binId = binId; }

    public String getMedicineName() { return medicineName; }
    public void setMedicineName(String medicineName) { this.medicineName = medicineName; }

    public String getMedicineCode() { return medicineCode; }
    public void setMedicineCode(String medicineCode) { this.medicineCode = medicineCode; }

    public double getDosageGrams() { return dosageGrams; }
    public void setDosageGrams(double dosageGrams) { this.dosageGrams = dosageGrams; }
}
