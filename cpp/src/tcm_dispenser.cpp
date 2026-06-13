#include "tcm_dispenser.h"
#include <cmath>
#include <chrono>
#include <thread>
#include <algorithm>
#include <iostream>
#include <fstream>
#include <iomanip>

// #region debug-point jni-mem-01
#define DEBUG_LOG(msg) do { \
    std::ofstream logfile("tcm_dispenser_debug.log", std::ios::app); \
    auto now = std::chrono::system_clock::now(); \
    std::time_t now_t = std::chrono::system_clock::to_time_t(now); \
    logfile << std::put_time(std::localtime(&now_t), "%Y-%m-%d %H:%M:%S") \
            << " [DEBUG] " << __func__ << ":" << __LINE__ << " " << msg << std::endl; \
} while(0)
// #endregion debug-point jni-mem-01

namespace tcm {

TCMDispenser::TCMDispenser()
    : m_status(DispenserStatus::IDLE)
    , m_nextTaskId(1)
    , m_initialized(false) {
}

TCMDispenser::~TCMDispenser() {
    shutdown();
}

TCMDispenser& TCMDispenser::getInstance() {
    static TCMDispenser instance;
    return instance;
}

bool TCMDispenser::initialize() {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_initialized) return true;

    const char* medicineNames[] = {
        "当归", "黄芪", "川芎", "白芍", "熟地黄", "桂枝", "麻黄", "杏仁",
        "石膏", "知母", "甘草", "人参", "白术", "茯苓", "陈皮", "半夏",
        "柴胡", "黄芩", "生姜", "大枣", "大黄", "黄连", "黄柏", "栀子",
        "金银花", "连翘", "薄荷", "桑叶", "菊花", "枸杞", "山药", "麦冬"
    };

    const char* medicineCodes[] = {
        "DG", "HQ", "CX", "BS", "SDH", "GZ", "MH", "XR",
        "SG", "ZM", "GC", "RS", "BZ", "FL", "CP", "BX",
        "CH", "HQ2", "SJ", "DZ", "DH", "HL", "HB", "ZZ",
        "JYH", "LQ", "BH", "SY", "JH", "GQ", "SY2", "MD"
    };

    for (int i = 0; i < MAX_MEDICINE_BINS; ++i) {
        MedicineBin bin;
        bin.id = i;
        bin.name = medicineNames[i];
        bin.code = medicineCodes[i];
        bin.remainingGrams = 200.0 + (rand() % 300) * 0.5;
        bin.capacityGrams = DEFAULT_BIN_CAPACITY;
        bin.stepperMotorId = i;
        bin.scaleChannel = i;
        bin.gramsPerPulse = 0.005;
        bin.isOnline = true;
        m_bins[i] = bin;
    }

    m_initialized = true;
    m_status = DispenserStatus::IDLE;
    return true;
}

bool TCMDispenser::shutdown() {
    std::lock_guard<std::mutex> lock(m_mutex);
    for (auto& kv : m_taskStopFlags) {
        if (kv.second) {
            kv.second->store(true);
        }
    }
    for (auto& kv : m_activeTasks) {
        if (kv.second.joinable()) {
            kv.second.detach();
        }
    }
    m_activeTasks.clear();
    m_taskStopFlags.clear();
    m_initialized = false;
    m_status = DispenserStatus::IDLE;
    return true;
}

std::vector<MedicineBin> TCMDispenser::getAllBinStatus() {
    std::lock_guard<std::mutex> lock(m_mutex);
    std::vector<MedicineBin> result;
    result.reserve(m_bins.size());
    for (const auto& kv : m_bins) {
        result.push_back(kv.second);
    }
    std::sort(result.begin(), result.end(), [](const MedicineBin& a, const MedicineBin& b) {
        return a.id < b.id;
    });
    return result;
}

MedicineBin TCMDispenser::getBinStatus(int binId) {
    std::lock_guard<std::mutex> lock(m_mutex);
    auto it = m_bins.find(binId);
    if (it != m_bins.end()) {
        return it->second;
    }
    MedicineBin empty{};
    empty.id = -1;
    return empty;
}

bool TCMDispenser::updateBinRemaining(int binId, double grams) {
    std::lock_guard<std::mutex> lock(m_mutex);
    auto it = m_bins.find(binId);
    if (it != m_bins.end()) {
        it->second.remainingGrams = std::max(0.0, grams);
        return true;
    }
    return false;
}

double TCMDispenser::readScale(int channel) {
    std::lock_guard<std::mutex> lock(m_mutex);
    auto it = m_bins.find(channel);
    if (it != m_bins.end()) {
        double noise = (static_cast<double>(rand()) / RAND_MAX - 0.5) * 0.002;
        return std::max(0.0, it->second.remainingGrams + noise);
    }
    return 0.0;
}

bool TCMDispenser::sendStepperPulse(int motorId, int pulses, int direction) {
    if (motorId < 0 || motorId >= MAX_MEDICINE_BINS) return false;
    std::this_thread::sleep_for(std::chrono::microseconds(50 * std::abs(pulses)));
    return true;
}

DispenserStatus TCMDispenser::getStatus() const {
    return m_status.load();
}

std::string TCMDispenser::getLastError() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_lastError;
}

double TCMDispenser::calculatePulses(double grams, double gramsPerPulse) {
    if (gramsPerPulse <= 0) return 0;
    return std::round(grams / gramsPerPulse);
}

double TCMDispenser::precisionDispense(int binId, double targetGrams, std::shared_ptr<std::atomic<bool>> shouldStop) {
    double dispensed = 0.0;
    double gramsPerPulse = 0.0;
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        auto it = m_bins.find(binId);
        if (it == m_bins.end()) return 0.0;
        gramsPerPulse = it->second.gramsPerPulse;
        if (it->second.remainingGrams < targetGrams) {
            m_lastError = "药仓 " + it->second.name + " 药量不足";
            return 0.0;
        }
    }

    const double coarseThreshold = targetGrams * 0.85;
    const double fineThreshold = targetGrams * 0.98;
    const double tolerance = targetGrams * 0.005 + SCALE_PRECISION;

    while (!shouldStop->load() && dispensed < coarseThreshold) {
        double remaining = coarseThreshold - dispensed;
        double pulses = calculatePulses(remaining * 0.5, gramsPerPulse);
        if (pulses < 1) pulses = 1;
        if (pulses > 100) pulses = 100;

        if (!sendStepperPulse(binId, static_cast<int>(pulses), 1)) {
            m_lastError = "步进电机通信失败";
            return dispensed;
        }

        double actualOutput = pulses * gramsPerPulse * (0.95 + static_cast<double>(rand()) / RAND_MAX * 0.1);
        dispensed += actualOutput;

        {
            std::lock_guard<std::mutex> lock(m_mutex);
            m_bins[binId].remainingGrams -= actualOutput;
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }

    while (!shouldStop->load() && dispensed < fineThreshold) {
        if (!sendStepperPulse(binId, 5, 1)) return dispensed;
        double actualOutput = 5 * gramsPerPulse * (0.95 + static_cast<double>(rand()) / RAND_MAX * 0.1);
        dispensed += actualOutput;

        {
            std::lock_guard<std::mutex> lock(m_mutex);
            m_bins[binId].remainingGrams -= actualOutput;
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(15));
    }

    while (!shouldStop->load() && (targetGrams - dispensed) > tolerance) {
        if (!sendStepperPulse(binId, 1, 1)) return dispensed;
        double actualOutput = gramsPerPulse * (0.95 + static_cast<double>(rand()) / RAND_MAX * 0.1);
        dispensed += actualOutput;

        {
            std::lock_guard<std::mutex> lock(m_mutex);
            m_bins[binId].remainingGrams -= actualOutput;
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }

    return dispensed;
}

void TCMDispenser::dispenseWorkerThread(int taskId, Prescription prescription) {
    m_status = DispenserStatus::DISPENSING;

    std::shared_ptr<std::atomic<bool>> shouldStop;
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        auto it = m_taskStopFlags.find(taskId);
        if (it != m_taskStopFlags.end()) {
            shouldStop = it->second;
        } else {
            m_status = DispenserStatus::ERROR;
            m_lastError = "任务不存在: " + std::to_string(taskId);
            return;
        }
    }

    // #region debug-point jni-mem-02
    DEBUG_LOG("Thread started for taskId=" << taskId 
              << " shouldStop ptr=" << shouldStop.get()
              << " use_count=" << shouldStop.use_count()
              << " map bucket_count=" << m_taskStopFlags.bucket_count()
              << " map size=" << m_taskStopFlags.size());
    // #endregion debug-point jni-mem-02

    for (size_t idx = 0; idx < prescription.tasks.size(); ++idx) {
        if (shouldStop->load()) {
            m_status = DispenserStatus::EMERGENCY_STOP;
            return;
        }

        auto& task = prescription.tasks[idx];
        task.compensationState = CompensationState::PRIMARY_DISPENSING;
        task.dispensedGrams = precisionDispense(task.binId, task.targetGrams, shouldStop);

        {
            std::lock_guard<std::mutex> lock(m_mutex);
            if (idx < m_currentTasks.size()) {
                m_currentTasks[idx].dispensedGrams = task.dispensedGrams;
                m_currentTasks[idx].compensationState = CompensationState::WEIGHING;
            }
        }

        if (shouldStop->load()) {
            m_status = DispenserStatus::EMERGENCY_STOP;
            return;
        }

        double finalGrams = closedLoopCompensate(task.binId, task.targetGrams, shouldStop, task);
        task.actualWeighedGrams = finalGrams;
        task.deficitGrams = std::max(0.0, task.targetGrams - finalGrams);

        task.isCompleted = (!shouldStop->load() &&
                           (task.compensationState == CompensationState::COMPLETED ||
                            finalGrams >= task.targetGrams * 0.995));

        if (!task.isCompleted && !shouldStop->load()) {
            task.hasError = true;
            task.errorMessage = m_lastError;
        }

        {
            std::lock_guard<std::mutex> lock(m_mutex);
            if (idx < m_currentTasks.size()) {
                m_currentTasks[idx] = task;
            }
        }
    }

    if (!shouldStop->load()) {
        m_status = DispenserStatus::IDLE;
    }

    cleanupTask(taskId);
}

int TCMDispenser::startDispense(const Prescription& prescription) {
    if (m_status.load() == DispenserStatus::DISPENSING) {
        m_lastError = "调剂机正在运行中，请等待当前任务完成";
        return -1;
    }

    int taskId = m_nextTaskId.fetch_add(1);

    // #region debug-point jni-mem-03
    size_t bucket_count_before = 0;
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        bucket_count_before = m_taskStopFlags.bucket_count();
    }
    DEBUG_LOG("Before insert: taskId=" << taskId 
              << " bucket_count=" << bucket_count_before
              << " map size=" << m_taskStopFlags.size());
    // #endregion debug-point jni-mem-03

    {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_taskStopFlags[taskId] = std::make_shared<std::atomic<bool>>(false);
        m_currentTasks = prescription.tasks;
        for (auto& t : m_currentTasks) {
            t.actualWeighedGrams = 0.0;
            t.deficitGrams = 0.0;
            t.compensationAttempts = 0;
            t.compensationState = CompensationState::IDLE;
            t.blockageOccurred = false;
            t.blockageCount = 0;
        }
    }

    // #region debug-point jni-mem-04
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        size_t bucket_count_after = m_taskStopFlags.bucket_count();
        bool rehashed = (bucket_count_after != bucket_count_before);
        DEBUG_LOG("After insert: taskId=" << taskId
                  << " bucket_count=" << bucket_count_after
                  << " REHASHED=" << (rehashed ? "YES (but safe with shared_ptr)" : "no"));
    }
    // #endregion debug-point jni-mem-04

    std::thread t(&TCMDispenser::dispenseWorkerThread, this, taskId, prescription);
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_activeTasks[taskId] = std::move(t);
    }
    m_activeTasks[taskId].detach();

    return taskId;
}

bool TCMDispenser::pauseDispense(int taskId) {
    std::lock_guard<std::mutex> lock(m_mutex);
    auto it = m_taskStopFlags.find(taskId);
    if (it != m_taskStopFlags.end()) {
        m_status = DispenserStatus::PAUSED;
        return true;
    }
    return false;
}

bool TCMDispenser::resumeDispense(int taskId) {
    if (m_status.load() == DispenserStatus::PAUSED) {
        m_status = DispenserStatus::DISPENSING;
        return true;
    }
    return false;
}

bool TCMDispenser::emergencyStop() {
    std::lock_guard<std::mutex> lock(m_mutex);
    for (auto& kv : m_taskStopFlags) {
        if (kv.second) {
            kv.second->store(true);
        }
    }
    m_status = DispenserStatus::EMERGENCY_STOP;
    return true;
}

bool TCMDispenser::isTaskCompleted(int taskId) {
    std::lock_guard<std::mutex> lock(m_mutex);
    auto it = m_activeTasks.find(taskId);
    if (it == m_activeTasks.end()) return true;
    return !it->second.joinable() || m_status.load() == DispenserStatus::IDLE;
}

void TCMDispenser::cleanupTask(int taskId) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_taskStopFlags.erase(taskId);
    auto it = m_activeTasks.find(taskId);
    if (it != m_activeTasks.end()) {
        if (it->second.joinable()) {
            it->second.detach();
        }
        m_activeTasks.erase(it);
    }
}

void TCMDispenser::addWeightSample(int binId, double grams) {
    auto now = std::chrono::system_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count();
    
    WeightSample sample;
    sample.timestampMs = ms;
    sample.binId = binId;
    sample.weightGrams = grams;
    
    m_weightSamples.push_back(sample);
    while (m_weightSamples.size() > MAX_WEIGHT_SAMPLES) {
        m_weightSamples.pop_front();
    }
}

void TCMDispenser::addBlockageEvent(int binId, const std::string& binName, int attempt, bool resolved, const std::string& msg) {
    auto now = std::chrono::system_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count();
    
    BlockageEvent event;
    event.timestampMs = ms;
    event.binId = binId;
    event.binName = binName;
    event.attempt = attempt;
    event.resolved = resolved;
    event.message = msg;
    
    m_blockageEvents.push_back(event);
    while (m_blockageEvents.size() > MAX_BLOCKAGE_EVENTS) {
        m_blockageEvents.pop_front();
    }
}

std::vector<WeightSample> TCMDispenser::getLatestWeightSamples(int sinceMs) {
    std::lock_guard<std::mutex> lock(m_mutex);
    std::vector<WeightSample> result;
    
    auto now = std::chrono::system_clock::now();
    auto nowMs = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count();
    
    int64_t threshold = nowMs - sinceMs;
    
    for (auto it = m_weightSamples.rbegin(); it != m_weightSamples.rend(); ++it) {
        if (it->timestampMs >= threshold) {
            result.push_back(*it);
        } else {
            break;
        }
    }
    std::reverse(result.begin(), result.end());
    return result;
}

std::vector<BlockageEvent> TCMDispenser::getBlockageEvents(int sinceMs) {
    std::lock_guard<std::mutex> lock(m_mutex);
    std::vector<BlockageEvent> result;
    
    auto now = std::chrono::system_clock::now();
    auto nowMs = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count();
    
    int64_t threshold = nowMs - sinceMs;
    
    for (auto it = m_blockageEvents.rbegin(); it != m_blockageEvents.rend(); ++it) {
        if (it->timestampMs >= threshold) {
            result.push_back(*it);
        } else {
            break;
        }
    }
    std::reverse(result.begin(), result.end());
    return result;
}

std::vector<DispenseTask> TCMDispenser::getCurrentTaskStatus() {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_currentTasks;
}

bool TCMDispenser::detectBlockage(int binId, double referenceWeight, int durationMs,
                                std::shared_ptr<std::atomic<bool>> shouldStop) {
    auto start = std::chrono::steady_clock::now();
    double lastWeight = referenceWeight;
    double maxChange = 0.0;
    
    while (!shouldStop->load()) {
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start;
        if (elapsed.count() >= durationMs) {
            break;
        }

        double currentWeight = 0.0;
        {
            std::lock_guard<std::mutex> lock(m_mutex);
            auto it = m_bins.find(binId);
            if (it != m_bins.end()) {
                double noise = (static_cast<double>(rand()) / RAND_MAX - 0.5) * 0.002;
                currentWeight = std::max(0.0, it->second.remainingGrams + noise);
            }
        }
        addWeightSample(binId, currentWeight);
        
        double weightChange = std::abs(currentWeight - lastWeight);
        if (weightChange > maxChange) {
            maxChange = weightChange;
        }
        
        if (maxChange > 0.01) {
            DEBUG_LOG("Blockage check passed: max weight change=" << maxChange << "g > 0.01g, not blocked");
            return false;
        }
        
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
    
    bool blocked = !shouldStop->load() && (maxChange < 0.01);
    if (blocked) {
        DEBUG_LOG("Blockage CONFIRMED: max weight change=" << maxChange << "g < 0.01g over " << durationMs << "ms");
    }
    return blocked;
}

bool TCMDispenser::performReverseAntiJam(int binId, DispenseTask& taskResult) {
    std::string binName = "Unknown";
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        auto it = m_bins.find(binId);
        if (it != m_bins.end()) {
            binName = it->second.name;
        }
    }
    
    DEBUG_LOG("Blockage detected for bin " << binId << " (" << binName << "), performing reverse anti-jam");
    addBlockageEvent(binId, binName, taskResult.blockageCount + 1, false,
                    "检测到堵料，执行反转防卡");
    
    if (!sendStepperPulse(binId, BLOCKAGE_REVERSE_PULSES, -1)) {
        m_lastError = "反转防卡失败";
        addBlockageEvent(binId, binName, taskResult.blockageCount + 1, false, "反转防卡电机通信失败");
        return false;
    }
    
    std::this_thread::sleep_for(std::chrono::milliseconds(500));
    
    if (!sendStepperPulse(binId, BLOCKAGE_REVERSE_PULSES / 2, 1)) {
        m_lastError = "正转恢复失败";
        return false;
    }
    
    taskResult.blockageOccurred = true;
    taskResult.blockageCount++;
    
    addBlockageEvent(binId, binName, taskResult.blockageCount, true, "堵料已解除");
    return true;
}

double TCMDispenser::closedLoopCompensate(int binId, double targetGrams,
                               std::shared_ptr<std::atomic<bool>> shouldStop,
                               DispenseTask& taskResult) {
    taskResult.compensationState = CompensationState::WEIGHING;
    
    std::this_thread::sleep_for(std::chrono::milliseconds(500));
    
    double stableWeight = 0.0;
    double sum = 0.0;
    for (int i = 0; i < 5; ++i) {
        double w = readScale(binId);
        sum += w;
        addWeightSample(binId, w);
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
    stableWeight = sum / 5.0;
    
    double deficit = targetGrams - stableWeight;
    taskResult.actualWeighedGrams = stableWeight;
    taskResult.deficitGrams = deficit;
    
    if (deficit < COMPENSATION_THRESHOLD) {
        taskResult.compensationState = CompensationState::COMPLETED;
        DEBUG_LOG("Bin " << binId << " dispensing completed. Target=" << targetGrams
                  << " Actual=" << stableWeight << " Deficit=" << deficit
                  << "g (within tolerance)");
        return stableWeight;
    }
    
    taskResult.compensationAttempts = 0;
    double totalCompensated = 0.0;
    
    while (deficit >= COMPENSATION_THRESHOLD &&
           taskResult.compensationAttempts < COMPENSATION_MAX_ATTEMPTS &&
           !shouldStop->load()) {
        
        taskResult.compensationAttempts++;
        taskResult.compensationState = CompensationState::COMPENSATING;
        
        DEBUG_LOG("Compensation attempt " << taskResult.compensationAttempts
                  << " for bin " << binId
                  << ": need " << deficit << "g");
        
        double preWeight = readScale(binId);
        addWeightSample(binId, preWeight);
        
        double pulses = calculatePulses(deficit * 1.2, 0.005);
        if (pulses < 3) pulses = 3;
        if (pulses > 50) pulses = 50;
        
        if (!sendStepperPulse(binId, static_cast<int>(pulses), 1)) {
            taskResult.compensationState = CompensationState::FAILED;
            taskResult.hasError = true;
            m_lastError = "补差给药电机通信失败";
            return stableWeight;
        }
        
        totalCompensated += pulses * 0.005;
        
        {
            std::lock_guard<std::mutex> lock(m_mutex);
            auto it = m_bins.find(binId);
            if (it != m_bins.end()) {
                it->second.remainingGrams -= pulses * 0.005;
            }
        }
        
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
        
        if (detectBlockage(binId, preWeight, BLOCKAGE_DETECT_MS, shouldStop)) {
            taskResult.compensationState = CompensationState::BLOCKAGE_DETECTED;
            taskResult.blockageOccurred = true;
            
            if (!performReverseAntiJam(binId, taskResult)) {
                taskResult.compensationState = CompensationState::FAILED;
                return stableWeight;
            }
        }
        
        sum = 0.0;
        for (int i = 0; i < 3; ++i) {
            double w = readScale(binId);
            sum += w;
            addWeightSample(binId, w);
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
        stableWeight = sum / 3.0;
        deficit = targetGrams - stableWeight;
        taskResult.actualWeighedGrams = stableWeight;
        taskResult.deficitGrams = deficit;
    }
    
    if (deficit < COMPENSATION_THRESHOLD) {
        taskResult.compensationState = CompensationState::COMPLETED;
        DEBUG_LOG("Bin " << binId << " compensation completed. Final="
                  << stableWeight << "g Deficit=" << deficit << "g");
    } else {
        taskResult.compensationState = CompensationState::FAILED;
        taskResult.hasError = true;
        m_lastError = "补差次数超限，最终欠量 " + std::to_string(deficit) + "g";
    }
    
    return stableWeight;
}

}

using namespace tcm;

JNIEXPORT jboolean JNICALL Java_com_tcm_dispenser_jni_TCMDispenserJNI_initialize
  (JNIEnv *env, jobject obj) {
    return TCMDispenser::getInstance().initialize() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_tcm_dispenser_jni_TCMDispenserJNI_shutdown
  (JNIEnv *env, jobject obj) {
    return TCMDispenser::getInstance().shutdown() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL Java_com_tcm_dispenser_jni_TCMDispenserJNI_getAllBinStatus
  (JNIEnv *env, jobject obj) {
    std::vector<MedicineBin> bins = TCMDispenser::getInstance().getAllBinStatus();

    jclass binClass = env->FindClass("com/tcm/dispenser/model/MedicineBin");
    if (binClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    jmethodID constructor = env->GetMethodID(binClass, "<init>", "(ILjava/lang/String;Ljava/lang/String;DDDDZ)V");
    if (constructor == nullptr) {
        env->DeleteLocalRef(binClass);
        return nullptr;
    }

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(bins.size()), binClass, nullptr);

    for (size_t i = 0; i < bins.size(); ++i) {
        const MedicineBin& bin = bins[i];
        jstring name = env->NewStringUTF(bin.name.c_str());
        jstring code = env->NewStringUTF(bin.code.c_str());
        jobject binObj = env->NewObject(binClass, constructor,
            bin.id, name, code,
            bin.remainingGrams, bin.capacityGrams,
            bin.gramsPerPulse,
            bin.isOnline ? JNI_TRUE : JNI_FALSE);
        env->SetObjectArrayElement(result, static_cast<jsize>(i), binObj);
        env->DeleteLocalRef(name);
        env->DeleteLocalRef(code);
        env->DeleteLocalRef(binObj);
    }

    env->DeleteLocalRef(binClass);
    return result;
}

JNIEXPORT jobject JNICALL Java_com_tcm_dispenser_jni_TCMDispenserJNI_getBinStatus
  (JNIEnv *env, jobject obj, jint binId) {
    MedicineBin bin = TCMDispenser::getInstance().getBinStatus(binId);
    if (bin.id < 0) return nullptr;

    jclass binClass = env->FindClass("com/tcm/dispenser/model/MedicineBin");
    if (binClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    jmethodID constructor = env->GetMethodID(binClass, "<init>", "(ILjava/lang/String;Ljava/lang/String;DDDDZ)V");
    if (constructor == nullptr) {
        env->DeleteLocalRef(binClass);
        return nullptr;
    }
    jstring name = env->NewStringUTF(bin.name.c_str());
    jstring code = env->NewStringUTF(bin.code.c_str());
    jobject result = env->NewObject(binClass, constructor,
        bin.id, name, code,
        bin.remainingGrams, bin.capacityGrams,
        bin.gramsPerPulse,
        bin.isOnline ? JNI_TRUE : JNI_FALSE);
    env->DeleteLocalRef(name);
    env->DeleteLocalRef(code);
    env->DeleteLocalRef(binClass);
    return result;
}

JNIEXPORT jint JNICALL Java_com_tcm_dispenser_jni_TCMDispenserJNI_startDispense
  (JNIEnv *env, jobject obj, jstring prescriptionId, jstring patientName, jobjectArray tasksArray) {
    Prescription prescription;

    // #region debug-point jni-mem-05
    DEBUG_LOG("JNI startDispense called");
    if (tasksArray == nullptr) {
        DEBUG_LOG("ERROR: tasksArray is NULL!");
        return -1;
    }
    // #endregion debug-point jni-mem-05

    const char* pid = env->GetStringUTFChars(prescriptionId, nullptr);
    prescription.prescriptionId = pid;
    env->ReleaseStringUTFChars(prescriptionId, pid);

    const char* pname = env->GetStringUTFChars(patientName, nullptr);
    prescription.patientName = pname;
    env->ReleaseStringUTFChars(patientName, pname);

    jclass taskClass = env->FindClass("com/tcm/dispenser/model/DispenseTask");

    // #region debug-point jni-mem-06
    DEBUG_LOG("FindClass returned taskClass=" << static_cast<void*>(taskClass));
    if (taskClass == nullptr) {
        DEBUG_LOG("ERROR: FindClass failed for DispenseTask");
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        return -1;
    }
    // #endregion debug-point jni-mem-06

    jfieldID binIdField = env->GetFieldID(taskClass, "binId", "I");
    jfieldID targetGramsField = env->GetFieldID(taskClass, "targetGrams", "D");

    jsize len = env->GetArrayLength(tasksArray);

    // #region debug-point jni-mem-07
    DEBUG_LOG("tasksArray length=" << len);
    // #endregion debug-point jni-mem-07

    for (jsize i = 0; i < len; ++i) {
        jobject taskObj = env->GetObjectArrayElement(tasksArray, i);

        // #region debug-point jni-mem-08
        if (taskObj == nullptr) {
            DEBUG_LOG("ERROR: taskObj at index " << i << " is NULL!");
            env->DeleteLocalRef(taskClass);
            return -1;
        }
        DEBUG_LOG("Processing task index=" << i << " taskObj=" << static_cast<void*>(taskObj));
        // #endregion debug-point jni-mem-08

        DispenseTask task;
        task.binId = env->GetIntField(taskObj, binIdField);
        task.targetGrams = env->GetDoubleField(taskObj, targetGramsField);
        task.dispensedGrams = 0.0;
        task.isCompleted = false;
        task.hasError = false;
        prescription.tasks.push_back(task);

        // #region debug-point jni-mem-09
        DEBUG_LOG("Task " << i << ": binId=" << task.binId 
                  << " targetGrams=" << task.targetGrams
                  << " tasks vector size=" << prescription.tasks.size()
                  << " vector capacity=" << prescription.tasks.capacity());
        // #endregion debug-point jni-mem-09

        env->DeleteLocalRef(taskObj);
    }

    // #region debug-point jni-mem-10
    DEBUG_LOG("All tasks parsed, total=" << prescription.tasks.size() 
              << " calling C++ startDispense...");
    // #endregion debug-point jni-mem-10

    // #region debug-point jni-mem-11
    env->DeleteLocalRef(taskClass);
    DEBUG_LOG("Deleted local ref for taskClass");
    // #endregion debug-point jni-mem-11

    return TCMDispenser::getInstance().startDispense(prescription);
}

JNIEXPORT jboolean JNICALL Java_com_tcm_dispenser_jni_TCMDispenserJNI_emergencyStop
  (JNIEnv *env, jobject obj) {
    return TCMDispenser::getInstance().emergencyStop() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_com_tcm_dispenser_jni_TCMDispenserJNI_getStatus
  (JNIEnv *env, jobject obj) {
    return static_cast<jint>(TCMDispenser::getInstance().getStatus());
}

JNIEXPORT jboolean JNICALL Java_com_tcm_dispenser_jni_TCMDispenserJNI_isTaskCompleted
  (JNIEnv *env, jobject obj, jint taskId) {
    return TCMDispenser::getInstance().isTaskCompleted(taskId) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jdouble JNICALL Java_com_tcm_dispenser_jni_TCMDispenserJNI_readScale
  (JNIEnv *env, jobject obj, jint channel) {
    return TCMDispenser::getInstance().readScale(channel);
}

JNIEXPORT jobjectArray JNICALL Java_com_tcm_dispenser_jni_TCMDispenserJNI_getLatestWeightSamples
  (JNIEnv *env, jobject obj, jint sinceMs) {
    std::vector<WeightSample> samples = TCMDispenser::getInstance().getLatestWeightSamples(sinceMs);

    jclass sampleClass = env->FindClass("com/tcm/dispenser/model/WeightSample");
    if (sampleClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    jmethodID constructor = env->GetMethodID(sampleClass, "<init>", "(JID)V");
    if (constructor == nullptr) {
        env->DeleteLocalRef(sampleClass);
        return nullptr;
    }

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(samples.size()), sampleClass, nullptr);

    for (size_t i = 0; i < samples.size(); ++i) {
        const WeightSample& s = samples[i];
        jobject sampleObj = env->NewObject(sampleClass, constructor,
            static_cast<jlong>(s.timestampMs),
            static_cast<jint>(s.binId),
            static_cast<jdouble>(s.weightGrams));
        env->SetObjectArrayElement(result, static_cast<jsize>(i), sampleObj);
        env->DeleteLocalRef(sampleObj);
    }

    env->DeleteLocalRef(sampleClass);
    return result;
}

JNIEXPORT jobjectArray JNICALL Java_com_tcm_dispenser_jni_TCMDispenserJNI_getBlockageEvents
  (JNIEnv *env, jobject obj, jint sinceMs) {
    std::vector<BlockageEvent> events = TCMDispenser::getInstance().getBlockageEvents(sinceMs);

    jclass eventClass = env->FindClass("com/tcm/dispenser/model/BlockageEvent");
    if (eventClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    jmethodID constructor = env->GetMethodID(eventClass, "<init>", "(JILjava/lang/String;IZLjava/lang/String;)V");
    if (constructor == nullptr) {
        env->DeleteLocalRef(eventClass);
        return nullptr;
    }

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(events.size()), eventClass, nullptr);

    for (size_t i = 0; i < events.size(); ++i) {
        const BlockageEvent& e = events[i];
        jstring binName = env->NewStringUTF(e.binName.c_str());
        jstring message = env->NewStringUTF(e.message.c_str());
        jobject eventObj = env->NewObject(eventClass, constructor,
            static_cast<jlong>(e.timestampMs),
            static_cast<jint>(e.binId),
            binName,
            static_cast<jint>(e.attempt),
            e.resolved ? JNI_TRUE : JNI_FALSE,
            message);
        env->SetObjectArrayElement(result, static_cast<jsize>(i), eventObj);
        env->DeleteLocalRef(binName);
        env->DeleteLocalRef(message);
        env->DeleteLocalRef(eventObj);
    }

    env->DeleteLocalRef(eventClass);
    return result;
}

JNIEXPORT jobjectArray JNICALL Java_com_tcm_dispenser_jni_TCMDispenserJNI_getCurrentTaskStatus
  (JNIEnv *env, jobject obj) {
    std::vector<DispenseTask> tasks = TCMDispenser::getInstance().getCurrentTaskStatus();

    jclass taskClass = env->FindClass("com/tcm/dispenser/model/DispenseTask");
    if (taskClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    jmethodID constructor = env->GetMethodID(taskClass, "<init>", "(IDDDDIIIZZZLjava/lang/String;)V");
    if (constructor == nullptr) {
        env->DeleteLocalRef(taskClass);
        return nullptr;
    }

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(tasks.size()), taskClass, nullptr);

    for (size_t i = 0; i < tasks.size(); ++i) {
        const DispenseTask& t = tasks[i];
        jstring errorMsg = env->NewStringUTF(t.errorMessage.c_str());
        jobject taskObj = env->NewObject(taskClass, constructor,
            static_cast<jint>(t.binId),
            static_cast<jdouble>(t.targetGrams),
            static_cast<jdouble>(t.dispensedGrams),
            static_cast<jdouble>(t.actualWeighedGrams),
            static_cast<jdouble>(t.deficitGrams),
            static_cast<jint>(t.compensationAttempts),
            static_cast<jint>(static_cast<int>(t.compensationState)),
            static_cast<jint>(t.blockageCount),
            t.blockageOccurred ? JNI_TRUE : JNI_FALSE,
            t.isCompleted ? JNI_TRUE : JNI_FALSE,
            t.hasError ? JNI_TRUE : JNI_FALSE,
            errorMsg);
        env->SetObjectArrayElement(result, static_cast<jsize>(i), taskObj);
        env->DeleteLocalRef(errorMsg);
        env->DeleteLocalRef(taskObj);
    }

    env->DeleteLocalRef(taskClass);
    return result;
}
