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

    for (auto& task : prescription.tasks) {
        if (shouldStop->load()) {
            m_status = DispenserStatus::EMERGENCY_STOP;
            return;
        }

        task.dispensedGrams = precisionDispense(task.binId, task.targetGrams, shouldStop);
        task.isCompleted = (!shouldStop->load() && task.dispensedGrams >= task.targetGrams * 0.995);
        if (!task.isCompleted && !shouldStop->load()) {
            task.hasError = true;
            task.errorMessage = m_lastError;
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
