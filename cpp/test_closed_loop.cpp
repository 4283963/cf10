#include <iostream>
#include <thread>
#include <chrono>
#include <cassert>
#include <cmath>
#include <atomic>
#include <memory>
#include <vector>
#include <unordered_map>
#include <string>

struct DispenseTask {
    int binId;
    double targetGrams;
    double dispensedGrams;
    double actualWeighedGrams;
    double deficitGrams;
    int compensationAttempts;
    int compensationState;
    int blockageCount;
    bool blockageOccurred;
    bool isCompleted;
    bool hasError;
    std::string errorMessage;
};

enum CompensationState {
    IDLE = 0,
    PRIMARY_DISPENSING = 1,
    WEIGHING = 2,
    COMPENSATING = 3,
    BLOCKAGE_DETECTED = 4,
    COMPLETED = 5,
    FAILED = 6
};

constexpr double COMPENSATION_THRESHOLD = 0.2;
constexpr int COMPENSATION_MAX_ATTEMPTS = 5;
constexpr int BLOCKAGE_DETECT_MS = 3000;
constexpr int BLOCKAGE_REVERSE_PULSES = 200;
constexpr double SCALE_PRECISION = 0.001;

double simulatedWeight = 0.0;
bool simulateBlockage = false;
int blockageSimulationRemaining = 0;

double readScaleSim(int binId) {
    double noise = (static_cast<double>(rand()) / RAND_MAX - 0.5) * 0.002;
    return simulatedWeight + noise;
}

bool sendPulseSim(int binId, int pulses, int dir) {
    if (simulateBlockage && blockageSimulationRemaining > 0) {
        blockageSimulationRemaining--;
        std::cout << "  [SIM] 堵料模拟: " << pulses << " 步，未出药" << std::endl;
        return true;
    }
    double grams = pulses * 0.005 * dir;
    simulatedWeight += grams;
    std::cout << "  [SIM] 电机 " << binId << ": " << (dir > 0 ? "正转" : "反转")
              << " " << pulses << " 步，重量变化: " << grams << "g" << std::endl;
    return true;
}

struct MockBin {
    int id;
    std::string name;
    double remaining;
    double gramsPerPulse;
};

std::unordered_map<int, MockBin> mockBins = {
    {1, {1, "人参", 500.0, 0.005}}
};

bool detectBlockageSim(int binId, double referenceWeight, int durationMs,
                      std::shared_ptr<std::atomic<bool>> shouldStop) {
    auto start = std::chrono::steady_clock::now();
    double maxChange = 0.0;

    while (!shouldStop->load()) {
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - start);
        if (elapsed.count() >= durationMs) break;

        double current = readScaleSim(binId);
        double change = std::abs(current - referenceWeight);
        if (change > maxChange) {
            maxChange = change;
        }
        if (maxChange > 0.01) {
            std::cout << "  [BLOCK] 检测到重量变化 " << maxChange << "g > 0.01g，未堵料" << std::endl;
            return false;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }

    bool blocked = !shouldStop->load() && (maxChange < 0.01);
    std::cout << "  [BLOCK] " << (blocked ? "⚠ 检测到堵料！maxChange=" + std::to_string(maxChange) : "未堵料") << std::endl;
    return blocked;
}

bool performAntiJamSim(int binId, DispenseTask& task) {
    std::cout << "  [ANTIJAM] 执行反转防卡..." << std::endl;

    sendPulseSim(binId, BLOCKAGE_REVERSE_PULSES, -1);
    std::this_thread::sleep_for(std::chrono::milliseconds(500));
    sendPulseSim(binId, BLOCKAGE_REVERSE_PULSES / 2, 1);

    simulateBlockage = false;
    std::cout << "  [ANTIJAM] 防卡完成，堵料已解除" << std::endl;
    return true;
}

double closedLoopCompensateSim(int binId, double targetGrams,
                              std::shared_ptr<std::atomic<bool>> shouldStop,
                              DispenseTask& task) {
    task.compensationState = WEIGHING;

    std::this_thread::sleep_for(std::chrono::milliseconds(200));

    double stableWeight = 0.0;
    double sum = 0.0;
    for (int i = 0; i < 5; i++) {
        sum += readScaleSim(binId);
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
    stableWeight = sum / 5.0;

    double deficit = targetGrams - stableWeight;
    task.actualWeighedGrams = stableWeight;
    task.deficitGrams = deficit;

    std::cout << std::endl;
    std::cout << "[COMPENSATE] 目标: " << targetGrams << "g 实测: " << stableWeight
              << "g 欠量: " << deficit << "g" << std::endl;

    if (deficit < COMPENSATION_THRESHOLD) {
        task.compensationState = COMPLETED;
        std::cout << "[COMPENSATE] ✓ 无需补差，欠量在容许范围内" << std::endl;
        return stableWeight;
    }

    task.compensationAttempts = 0;

    while (deficit >= COMPENSATION_THRESHOLD &&
           task.compensationAttempts < COMPENSATION_MAX_ATTEMPTS &&
           !shouldStop->load()) {

        task.compensationAttempts++;
        task.compensationState = COMPENSATING;

        std::cout << "[COMPENSATE] 补差 #" << task.compensationAttempts
                  << " 需要: " << deficit << "g" << std::endl;

        double preWeight = readScaleSim(binId);

        double pulses = (deficit * 1.2) / 0.005;
        if (pulses < 3) pulses = 3;
        if (pulses > 50) pulses = 50;

        sendPulseSim(binId, static_cast<int>(pulses), 1);
        mockBins[binId].remaining -= pulses * 0.005;

        std::this_thread::sleep_for(std::chrono::milliseconds(100));

        if (detectBlockageSim(binId, preWeight, 1500, shouldStop)) {
            task.compensationState = BLOCKAGE_DETECTED;
            task.blockageCount++;
            task.blockageOccurred = true;
            if (!performAntiJamSim(binId, task)) {
                task.compensationState = FAILED;
                return stableWeight;
            }
        }

        sum = 0.0;
        for (int i = 0; i < 3; i++) {
            sum += readScaleSim(binId);
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
        stableWeight = sum / 3.0;
        deficit = targetGrams - stableWeight;
        task.actualWeighedGrams = stableWeight;
        task.deficitGrams = deficit;

        std::cout << "[COMPENSATE] 实测: " << stableWeight << "g 欠量: " << deficit << "g" << std::endl;
    }

    if (deficit < COMPENSATION_THRESHOLD) {
        task.compensationState = COMPLETED;
        std::cout << "[COMPENSATE] ✓ 补差完成，最终: " << stableWeight
                  << "g 欠量: " << deficit << "g" << std::endl;
    } else {
        task.compensationState = FAILED;
        task.hasError = true;
        task.errorMessage = "补差超限";
        std::cout << "[COMPENSATE] ✗ 补差失败，欠量: " << deficit << "g" << std::endl;
    }
    return stableWeight;
}

void test_compensation_no_blockage() {
    std::cout << std::endl;
    std::cout << "===== 测试1: 正常补差（无堵料）=====" << std::endl;

    simulatedWeight = 2.5;
    simulateBlockage = false;

    auto shouldStop = std::make_shared<std::atomic<bool>>(false);
    DispenseTask task = {};
    task.binId = 1;
    task.targetGrams = 3.0;
    task.dispensedGrams = 2.5;
    task.blockageOccurred = false;
    task.blockageCount = 0;
    task.hasError = false;
    task.isCompleted = false;
    task.compensationAttempts = 0;
    task.compensationState = IDLE;

    double result = closedLoopCompensateSim(1, 3.0, shouldStop, task);

    std::cout << "DEBUG: result=" << result
              << " state=" << task.compensationState
              << " attempts=" << task.compensationAttempts
              << " blockageOccurred=" << task.blockageOccurred
              << " blockageCount=" << task.blockageCount << std::endl;

    assert(std::abs(result - 3.0) < 0.2);
    assert(task.compensationState == COMPLETED);
    assert(task.compensationAttempts >= 1);
    assert(!task.blockageOccurred);

    std::cout << "✓ 测试1通过：正常补差功能正确" << std::endl;
}

void test_compensation_with_blockage() {
    std::cout << std::endl;
    std::cout << "===== 测试2: 补差时堵料+防卡 =====" << std::endl;

    simulatedWeight = 2.5;
    simulateBlockage = true;
    blockageSimulationRemaining = 3;

    auto shouldStop = std::make_shared<std::atomic<bool>>(false);
    DispenseTask task = {};
    task.binId = 1;
    task.targetGrams = 3.0;
    task.dispensedGrams = 2.5;
    task.blockageOccurred = false;
    task.blockageCount = 0;
    task.hasError = false;
    task.isCompleted = false;
    task.compensationAttempts = 0;
    task.compensationState = IDLE;

    double result = closedLoopCompensateSim(1, 3.0, shouldStop, task);

    std::cout << "DEBUG: result=" << result
              << " state=" << task.compensationState
              << " attempts=" << task.compensationAttempts
              << " blockageOccurred=" << task.blockageOccurred
              << " blockageCount=" << task.blockageCount << std::endl;

    assert(task.blockageOccurred);
    assert(task.blockageCount >= 1);
    assert(std::abs(result - 3.0) < 0.25);
    assert(task.compensationState == COMPLETED);

    std::cout << "✓ 测试2通过：堵料检测和反转防卡功能正确" << std::endl;
}

void test_no_compensation_needed() {
    std::cout << std::endl;
    std::cout << "===== 测试3: 欠量<0.2g无需补差 =====" << std::endl;

    simulatedWeight = 2.85;
    simulateBlockage = false;

    auto shouldStop = std::make_shared<std::atomic<bool>>(false);
    DispenseTask task = {};
    task.binId = 1;
    task.targetGrams = 3.0;
    task.dispensedGrams = 2.85;
    task.blockageOccurred = false;
    task.blockageCount = 0;
    task.hasError = false;
    task.isCompleted = false;
    task.compensationAttempts = 0;
    task.compensationState = IDLE;

    double result = closedLoopCompensateSim(1, 3.0, shouldStop, task);

    std::cout << "DEBUG_TEST3: result=" << result
              << " state=" << task.compensationState
              << " attempts=" << task.compensationAttempts
              << " blockageOccurred=" << task.blockageOccurred << std::endl;

    assert(std::abs(result - 2.85) < 0.05);
    assert(task.compensationState == COMPLETED);
    assert(task.compensationAttempts == 0);
    assert(!task.blockageOccurred);

    std::cout << "✓ 测试3通过：欠量不足0.2g时正确跳过补差" << std::endl;
}

int main() {
    srand(time(0));
    std::cout << "=== 重量闭环补差与堵料防卡功能单元测试 ===" << std::endl;

    test_compensation_no_blockage();
    test_compensation_with_blockage();
    test_no_compensation_needed();

    std::cout << std::endl;
    std::cout << "=== 所有测试通过！ ===" << std::endl;
    return 0;
}
