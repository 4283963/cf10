#ifndef TCM_DISPENSER_H
#define TCM_DISPENSER_H

#include <jni.h>
#include <vector>
#include <mutex>
#include <atomic>
#include <string>
#include <unordered_map>
#include <thread>
#include <condition_variable>
#include <memory>

#define DISPENSER_API extern "C" __attribute__((visibility("default")))

namespace tcm {

constexpr int MAX_MEDICINE_BINS = 32;
constexpr double SCALE_PRECISION = 0.001;
constexpr double DEFAULT_BIN_CAPACITY = 500.0;

struct MedicineBin {
    int id;
    std::string name;
    std::string code;
    double remainingGrams;
    double capacityGrams;
    int stepperMotorId;
    int scaleChannel;
    double gramsPerPulse;
    bool isOnline;
};

struct DispenseTask {
    int binId;
    double targetGrams;
    double dispensedGrams;
    bool isCompleted;
    bool hasError;
    std::string errorMessage;
};

struct Prescription {
    std::string prescriptionId;
    std::string patientName;
    std::vector<DispenseTask> tasks;
};

enum class DispenserStatus {
    IDLE = 0,
    DISPENSING = 1,
    PAUSED = 2,
    ERROR = 3,
    EMERGENCY_STOP = 4
};

class TCMDispenser {
public:
    static TCMDispenser& getInstance();
    
    bool initialize();
    bool shutdown();
    
    std::vector<MedicineBin> getAllBinStatus();
    MedicineBin getBinStatus(int binId);
    bool updateBinRemaining(int binId, double grams);
    
    int startDispense(const Prescription& prescription);
    bool pauseDispense(int taskId);
    bool resumeDispense(int taskId);
    bool emergencyStop();
    bool isTaskCompleted(int taskId);
    
    double readScale(int channel);
    bool sendStepperPulse(int motorId, int pulses, int direction);
    
    DispenserStatus getStatus() const;
    std::string getLastError() const;
    
private:
    TCMDispenser();
    ~TCMDispenser();
    TCMDispenser(const TCMDispenser&) = delete;
    TCMDispenser& operator=(const TCMDispenser&) = delete;
    
    void dispenseWorkerThread(int taskId, Prescription prescription);
    double precisionDispense(int binId, double targetGrams, std::shared_ptr<std::atomic<bool>> shouldStop);
    double calculatePulses(double grams, double gramsPerPulse);
    void dribbleFeed(int binId, double remainingGrams, std::shared_ptr<std::atomic<bool>> shouldStop);
    
    void cleanupTask(int taskId);
    
    mutable std::mutex m_mutex;
    std::unordered_map<int, MedicineBin> m_bins;
    std::unordered_map<int, std::thread> m_activeTasks;
    std::unordered_map<int, std::shared_ptr<std::atomic<bool>>> m_taskStopFlags;
    std::atomic<DispenserStatus> m_status;
    std::atomic<int> m_nextTaskId;
    std::string m_lastError;
    bool m_initialized;
};

}

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_tcm_dispenser_jni_TCMDispenserJNI_initialize
  (JNIEnv *, jobject);

JNIEXPORT jboolean JNICALL Java_com_tcm_dispenser_jni_TCMDispenserJNI_shutdown
  (JNIEnv *, jobject);

JNIEXPORT jobjectArray JNICALL Java_com_tcm_dispenser_jni_TCMDispenserJNI_getAllBinStatus
  (JNIEnv *, jobject);

JNIEXPORT jobject JNICALL Java_com_tcm_dispenser_jni_TCMDispenserJNI_getBinStatus
  (JNIEnv *, jobject, jint);

JNIEXPORT jint JNICALL Java_com_tcm_dispenser_jni_TCMDispenserJNI_startDispense
  (JNIEnv *, jobject, jstring, jstring, jobjectArray);

JNIEXPORT jboolean JNICALL Java_com_tcm_dispenser_jni_TCMDispenserJNI_emergencyStop
  (JNIEnv *, jobject);

JNIEXPORT jint JNICALL Java_com_tcm_dispenser_jni_TCMDispenserJNI_getStatus
  (JNIEnv *, jobject);

JNIEXPORT jboolean JNICALL Java_com_tcm_dispenser_jni_TCMDispenserJNI_isTaskCompleted
  (JNIEnv *, jobject, jint);

JNIEXPORT jdouble JNICALL Java_com_tcm_dispenser_jni_TCMDispenserJNI_readScale
  (JNIEnv *, jobject, jint);

}

#endif
