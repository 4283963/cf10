package com.tcm.dispenser.model;

public enum DispenserStatus {
    IDLE(0, "空闲"),
    DISPENSING(1, "调剂中"),
    PAUSED(2, "已暂停"),
    ERROR(3, "故障"),
    EMERGENCY_STOP(4, "紧急停止");

    private final int code;
    private final String label;

    DispenserStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() { return code; }
    public String getLabel() { return label; }

    public static DispenserStatus fromCode(int code) {
        for (DispenserStatus s : values()) {
            if (s.code == code) return s;
        }
        return ERROR;
    }
}
