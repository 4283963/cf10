package com.tcm.dispenser.model;

import java.util.ArrayList;
import java.util.List;

public class Prescription {
    private String prescriptionId;
    private String patientName;
    private String patientId;
    private String doctorName;
    private String department;
    private String createTime;
    private int dosageCount;
    private List<PrescriptionItem> items;

    public Prescription() {
        this.items = new ArrayList<>();
        this.dosageCount = 1;
    }

    public String getPrescriptionId() { return prescriptionId; }
    public void setPrescriptionId(String prescriptionId) { this.prescriptionId = prescriptionId; }

    public String getPatientName() { return patientName; }
    public void setPatientName(String patientName) { this.patientName = patientName; }

    public String getPatientId() { return patientId; }
    public void setPatientId(String patientId) { this.patientId = patientId; }

    public String getDoctorName() { return doctorName; }
    public void setDoctorName(String doctorName) { this.doctorName = doctorName; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }

    public int getDosageCount() { return dosageCount; }
    public void setDosageCount(int dosageCount) { this.dosageCount = dosageCount; }

    public List<PrescriptionItem> getItems() { return items; }
    public void setItems(List<PrescriptionItem> items) { this.items = items; }

    public void addItem(PrescriptionItem item) {
        this.items.add(item);
    }

    public List<DispenseTask> toDispenseTasks() {
        List<DispenseTask> tasks = new ArrayList<>();
        for (PrescriptionItem item : items) {
            DispenseTask task = new DispenseTask();
            task.setBinId(item.getBinId());
            task.setMedicineName(item.getMedicineName());
            task.setTargetGrams(item.getDosageGrams() * dosageCount);
            tasks.add(task);
        }
        return tasks;
    }

    @Override
    public String toString() {
        return String.format("处方号:%s 患者:%s 剂数:%d 药味数:%d",
                prescriptionId, patientName, dosageCount, items.size());
    }
}
