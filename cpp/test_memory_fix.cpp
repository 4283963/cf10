#include <iostream>
#include <thread>
#include <atomic>
#include <memory>
#include <unordered_map>
#include <vector>
#include <mutex>
#include <cassert>

// 模拟核心修复的验证测试
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

class TCMDispenserTest {
public:
    std::unordered_map<int, std::shared_ptr<std::atomic<bool>>> m_taskStopFlags;
    std::unordered_map<int, std::thread> m_activeTasks;
    std::mutex m_mutex;
    std::atomic<int> m_nextTaskId{1};
    std::atomic<bool> m_running{false};
    int completedCount = 0;

    void workerThread(int taskId, Prescription prescription) {
        m_running.store(true);
        
        // 修复前：std::atomic<bool>& shouldStop = m_taskStopFlags[taskId];
        // 修复后：使用 shared_ptr，避免 rehash 导致悬空引用
        std::shared_ptr<std::atomic<bool>> shouldStop;
        {
            std::lock_guard<std::mutex> lock(m_mutex);
            auto it = m_taskStopFlags.find(taskId);
            if (it != m_taskStopFlags.end()) {
                shouldStop = it->second;
            } else {
                return;
            }
        }

        // 模拟长时间运行的下药过程
        for (int i = 0; i < 50; i++) {
            if (shouldStop->load()) {
                std::cout << "Task " << taskId << " stopped" << std::endl;
                return;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }

        completedCount++;
        m_running.store(false);
        std::cout << "Task " << taskId << " completed, use_count=" << shouldStop.use_count() << std::endl;

        // 清理
        std::lock_guard<std::mutex> lock(m_mutex);
        m_taskStopFlags.erase(taskId);
        auto it = m_activeTasks.find(taskId);
        if (it != m_activeTasks.end()) {
            if (it->second.joinable()) it->second.detach();
            m_activeTasks.erase(it);
        }
    }

    int startTask() {
        int taskId = m_nextTaskId.fetch_add(1);

        size_t bucket_before = 0;
        {
            std::lock_guard<std::mutex> lock(m_mutex);
            bucket_before = m_taskStopFlags.bucket_count();
        }

        {
            std::lock_guard<std::mutex> lock(m_mutex);
            m_taskStopFlags[taskId] = std::make_shared<std::atomic<bool>>(false);
        }

        size_t bucket_after = 0;
        {
            std::lock_guard<std::mutex> lock(m_mutex);
            bucket_after = m_taskStopFlags.bucket_count();
        }

        if (bucket_after != bucket_before) {
            std::cout << "  REHASH detected when inserting task " << taskId 
                      << " bucket: " << bucket_before << " -> " << bucket_after << std::endl;
        }

        Prescription p;
        p.prescriptionId = "RX" + std::to_string(taskId);

        std::thread t(&TCMDispenserTest::workerThread, this, taskId, p);
        {
            std::lock_guard<std::mutex> lock(m_mutex);
            m_activeTasks[taskId] = std::move(t);
        }
        m_activeTasks[taskId].detach();

        return taskId;
    }
};

int main() {
    std::cout << "=== 悬空引用修复验证测试 ===" << std::endl;
    std::cout << "模拟连续创建任务，触发 unordered_map rehash..." << std::endl;

    TCMDispenserTest tester;

    // 连续创建 30 个任务，必然触发多次 rehash
    for (int i = 0; i < 30; i++) {
        int id = tester.startTask();
        std::cout << "Started task " << id 
                  << " map size=" << tester.m_taskStopFlags.size() << std::endl;
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }

    // 等待所有任务完成
    std::this_thread::sleep_for(std::chrono::seconds(3));
    std::cout << std::endl;
    std::cout << "测试完成，完成任务数: " << tester.completedCount << "/30" << std::endl;
    std::cout << "最终 map size: " << tester.m_taskStopFlags.size() << std::endl;
    
    if (tester.completedCount == 30) {
        std::cout << "✅ 修复验证通过：所有任务在多次 rehash 后均正常完成，无悬空引用崩溃！" << std::endl;
    } else {
        std::cout << "⚠️  部分任务未完成" << std::endl;
    }

    return 0;
}
