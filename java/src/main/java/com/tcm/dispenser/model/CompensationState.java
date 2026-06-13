package com.tcm.dispenser.model;

public enum CompensationState {
    IDLE(0, "空闲"),
    PRIMARY_DISPENSING(1, "主下药中"),
    WEIGHING(2, "称重中"),
    COMPENSATING(3, "微量补差中"),
    BLOCKAGE_DETECTED(4, "堵料检测"),
    COMPLETED(5, "已完成"),
    FAILED(6, "失败");

    private final int code;
    private final String description;

    CompensationState(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() { return code; }
    public String getDescription() { return description; }

    public static CompensationState fromCode(int code) {
        for (CompensationState state : values()) {
            if (state.code == code) return state;
        }
        return IDLE;
    }
}
