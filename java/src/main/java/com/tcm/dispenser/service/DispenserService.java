package com.tcm.dispenser.service;

import com.tcm.dispenser.jni.TCMDispenserJNI;
import com.tcm.dispenser.model.*;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class DispenserService {

    private final TCMDispenserJNI jni;
    private final ScheduledExecutorService scheduler;
    private volatile int currentTaskId = -1;
    private volatile boolean monitoring = false;
    private Consumer<DispenserStatus> statusCallback;
    private Consumer<List<MedicineBin>> binStatusCallback;

    public DispenserService() {
        this.jni = TCMDispenserJNI.getInstance();
        this.scheduler = Executors.newScheduledThreadPool(2);
    }

    public boolean initialize() {
        boolean ok = jni.initialize();
        if (ok) {
            startMonitoring();
        }
        return ok;
    }

    public boolean shutdown() {
        monitoring = false;
        scheduler.shutdownNow();
        return jni.shutdown();
    }

    public List<MedicineBin> getAllBinStatus() {
        MedicineBin[] bins = jni.getAllBinStatus();
        return bins != null ? Arrays.asList(bins) : List.of();
    }

    public DispenserStatus getStatus() {
        int code = jni.getStatus();
        return DispenserStatus.fromCode(code);
    }

    public int startDispense(Prescription prescription) {
        List<DispenseTask> tasks = prescription.toDispenseTasks();
        DispenseTask[] taskArray = tasks.toArray(new DispenseTask[0]);
        currentTaskId = jni.startDispense(
                prescription.getPrescriptionId(),
                prescription.getPatientName(),
                taskArray
        );
        return currentTaskId;
    }

    public boolean emergencyStop() {
        boolean result = jni.emergencyStop();
        currentTaskId = -1;
        return result;
    }

    public double readScale(int channel) {
        return jni.readScale(channel);
    }

    public void setStatusCallback(Consumer<DispenserStatus> callback) {
        this.statusCallback = callback;
    }

    public void setBinStatusCallback(Consumer<List<MedicineBin>> callback) {
        this.binStatusCallback = callback;
    }

    private void startMonitoring() {
        monitoring = true;
        scheduler.scheduleAtFixedRate(() -> {
            if (!monitoring) return;
            try {
                DispenserStatus status = getStatus();
                if (statusCallback != null) {
                    statusCallback.accept(status);
                }
            } catch (Exception e) {
                System.err.println("状态轮询异常: " + e.getMessage());
            }
        }, 0, 500, TimeUnit.MILLISECONDS);

        scheduler.scheduleAtFixedRate(() -> {
            if (!monitoring) return;
            try {
                List<MedicineBin> bins = getAllBinStatus();
                if (binStatusCallback != null) {
                    binStatusCallback.accept(bins);
                }
            } catch (Exception e) {
                System.err.println("药仓状态轮询异常: " + e.getMessage());
            }
        }, 0, 2000, TimeUnit.MILLISECONDS);
    }
}
