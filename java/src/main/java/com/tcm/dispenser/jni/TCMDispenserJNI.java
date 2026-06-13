package com.tcm.dispenser.jni;

import com.tcm.dispenser.model.DispenseTask;
import com.tcm.dispenser.model.MedicineBin;

public class TCMDispenserJNI {

    static {
        try {
            System.loadLibrary("tcm_dispenser");
        } catch (UnsatisfiedLinkError e) {
            System.err.println("无法加载本地库 tcm_dispenser: " + e.getMessage());
            System.err.println("请确保 libtcm_dispenser.dylib / tcm_dispenser.dll / libtcm_dispenser.so 在 java.library.path 中");
        }
    }

    private static TCMDispenserJNI instance;

    public static synchronized TCMDispenserJNI getInstance() {
        if (instance == null) {
            instance = new TCMDispenserJNI();
        }
        return instance;
    }

    private TCMDispenserJNI() {
    }

    public native boolean initialize();

    public native boolean shutdown();

    public native MedicineBin[] getAllBinStatus();

    public native MedicineBin getBinStatus(int binId);

    public native int startDispense(String prescriptionId, String patientName, DispenseTask[] tasks);

    public native boolean emergencyStop();

    public native int getStatus();

    public native boolean isTaskCompleted(int taskId);

    public native double readScale(int channel);
}
