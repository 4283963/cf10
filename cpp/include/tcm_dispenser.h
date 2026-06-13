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
#include <deque>
#include <chrono>

#define DISPENSER_API extern "C" __attribute__((visibility("default")))

namespace tcm {

constexpr int MAX_MEDICINE_BINS = 32;
constexpr double SCALE_PRECISION = 0.001;
constexpr double DEFAULT_BIN_CAPACITY = 500.0;
constexpr double COMPENSATION_THRESHOLD = 0.2;
constexpr int COMPENSATION_MAX_ATTEMPTS = 5;
constexpr int BLOCKAGE_DETECT_MS = 3000;
constexpr int BLOCKAGE_REVERSE_PULSES = 200;
constexpr int MAX_WEIGHT_SAMPLES = 600;
constexpr int MAX_BLOCKAGE_EVENTS = 100;

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

enum class CompensationState {
    IDLE = 0,
    PRIMARY_DISPENSING = 1,
    WEIGHING = 2,
    COMPENSATING = 3,
    BLOCKAGE_DETECTED = 4,
    COMPLETED = 5,
    FAILED = 6
};

struct WeightSample {
    int64_t timestampMs;
    int binId;
    double weightGrams;
};

struct BlockageEvent {
    int64_t timestampMs;
    int binId;
    std::string binName;
    int attempt;
    bool resolved;
    std::string message;
};

struct DispenseTask {
    int binId;
    double targetGrams;
    double dispensedGrams;
    double actualWeighedGrams;
    double deficitGrams;
    int compensationAttempts;
    CompensationState compensationState;
    bool blockageOccurred;
    int blockageCount;
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
    
    std::vector<WeightSample> getLatestWeightSamples(int sinceMs);
    std::vector<BlockageEvent> getBlockageEvents(int sinceMs);
    std::vector<DispenseTask> getCurrentTaskStatus();
    
private:
    TCMDispenser();
    ~TCMDispenser();
    TCMDispenser(const TCMDispenser&) = delete;
    TCMDispenser& operator=(const TCMDispenser&) = delete;
    
    void dispenseWorkerThread(int taskId, Prescription prescription);
    double precisionDispense(int binId, double targetGrams, std::shared_ptr<std::atomic<bool>> shouldStop);
    double calculatePulses(double grams, double gramsPerPulse);
    void dribbleFeed(int binId, double remainingGrams, std::shared_ptr<std::atomic<bool>> shouldStop);
    
    double closedLoopCompensate(int binId, double targetGrams,
                               std::shared_ptr<std::atomic<bool>> shouldStop,
                               DispenseTask& taskResult);
    bool detectBlockage(int binId, double referenceWeight, int durationMs,
                        std::shared_ptr<std::atomic<bool>> shouldStop);
    bool performReverseAntiJam(int binId, DispenseTask& taskResult);
    
    void addWeightSample(int binId, double grams);
    void addBlockageEvent(int binId, const std::string& binName, int attempt, bool resolved, const std::string& msg);
    
    void cleanupTask(int taskId);
    
    mutable std::mutex m_mutex;
    std::unordered_map<int, MedicineBin> m_bins;
    std::unordered_map<int, std::thread> m_activeTasks;
    std::unordered_map<int, std::shared_ptr<std::atomic<bool>>> m_taskStopFlags;
    std::deque<WeightSample> m_weightSamples;
    std::deque<BlockageEvent> m_blockageEvents;
    std::vector<DispenseTask> m_currentTasks;
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

JNIEXPORT jobjectArray JNICALL Java_com_tcm_dispenser_jni_TCMDispenserJNI_getLatestWeightSamples
  (JNIEnv *, jobject, jint);

JNIEXPORT jobjectArray JNICALL Java_com_tcm_dispenser_jni_TCMDispenserJNI_getBlockageEvents
  (JNIEnv *, jobject, jint);

JNIEXPORT jobjectArray JNICALL Java_com_tcm_dispenser_jni_TCMDispenserJNI_getCurrentTaskStatus
  (JNIEnv *, jobject);

}

#endif
