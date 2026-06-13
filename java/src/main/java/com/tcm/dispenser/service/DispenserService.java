package com.tcm.dispenser.service;

import com.tcm.dispenser.jni.TCMDispenserJNI;
import com.tcm.dispenser.model.*;

import java.util.ArrayList;
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
    private volatile long lastBlockageTimestamp = 0;

    private Consumer<DispenserStatus> statusCallback;
    private Consumer<List<MedicineBin>> binStatusCallback;
    private Consumer<List<WeightSample>> weightSampleCallback;
    private Consumer<List<BlockageEvent>> blockageEventCallback;
    private Consumer<List<DispenseTask>> taskStatusCallback;

    public DispenserService() {
        this.jni = TCMDispenserJNI.getInstance();
        this.scheduler = Executors.newScheduledThreadPool(4);
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
        lastBlockageTimestamp = 0;
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

    public List<WeightSample> getLatestWeightSamples(int sinceMs) {
        WeightSample[] samples = jni.getLatestWeightSamples(sinceMs);
        return samples != null ? Arrays.asList(samples) : List.of();
    }

    public List<BlockageEvent> getBlockageEvents(int sinceMs) {
        BlockageEvent[] events = jni.getBlockageEvents(sinceMs);
        return events != null ? Arrays.asList(events) : List.of();
    }

    public List<DispenseTask> getCurrentTaskStatus() {
        DispenseTask[] tasks = jni.getCurrentTaskStatus();
        return tasks != null ? Arrays.asList(tasks) : List.of();
    }

    public int getCurrentTaskId() {
        return currentTaskId;
    }

    public boolean isTaskCompleted() {
        return currentTaskId >= 0 && jni.isTaskCompleted(currentTaskId);
    }

    public void setStatusCallback(Consumer<DispenserStatus> callback) {
        this.statusCallback = callback;
    }

    public void setBinStatusCallback(Consumer<List<MedicineBin>> callback) {
        this.binStatusCallback = callback;
    }

    public void setWeightSampleCallback(Consumer<List<WeightSample>> callback) {
        this.weightSampleCallback = callback;
    }

    public void setBlockageEventCallback(Consumer<List<BlockageEvent>> callback) {
        this.blockageEventCallback = callback;
    }

    public void setTaskStatusCallback(Consumer<List<DispenseTask>> callback) {
        this.taskStatusCallback = callback;
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

        scheduler.scheduleAtFixedRate(() -> {
            if (!monitoring) return;
            try {
                List<WeightSample> samples = getLatestWeightSamples(2000);
                if (weightSampleCallback != null && !samples.isEmpty()) {
                    weightSampleCallback.accept(new ArrayList<>(samples));
                }
            } catch (Exception e) {
                System.err.println("重量采样轮询异常: " + e.getMessage());
            }
        }, 0, 200, TimeUnit.MILLISECONDS);

        scheduler.scheduleAtFixedRate(() -> {
            if (!monitoring) return;
            try {
                long since = lastBlockageTimestamp > 0 ? 60000 : 300000;
                List<BlockageEvent> events = getBlockageEvents((int) since);
                if (!events.isEmpty()) {
                    BlockageEvent last = events.get(events.size() - 1);
                    if (last.getTimestampMs() > lastBlockageTimestamp) {
                        lastBlockageTimestamp = last.getTimestampMs();
                        if (blockageEventCallback != null) {
                            blockageEventCallback.accept(new ArrayList<>(events));
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("堵料事件轮询异常: " + e.getMessage());
            }
        }, 0, 500, TimeUnit.MILLISECONDS);

        scheduler.scheduleAtFixedRate(() -> {
            if (!monitoring || currentTaskId < 0) return;
            try {
                List<DispenseTask> tasks = getCurrentTaskStatus();
                if (taskStatusCallback != null && !tasks.isEmpty()) {
                    taskStatusCallback.accept(new ArrayList<>(tasks));
                }
            } catch (Exception e) {
                System.err.println("任务状态轮询异常: " + e.getMessage());
            }
        }, 0, 300, TimeUnit.MILLISECONDS);
    }
}
