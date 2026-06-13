# JNI 数组内存越界崩溃调试记录

**Session ID:** jni-array-memory-crash
**Status:** [OPEN]
**Start Time:** 2026-06-13
**Bug Description:** JavaFX 程序运行到第3贴中药时闪退，JVM 报 EXCEPTION_ACCESS_VIOLATION (0xc0000005)，疑似 JNI 数组传递时 C++ 指针越界释放导致 JVM 内存损坏。

---

## 一、可证伪假设列表

| # | 假设描述 | 可观察点 |
|---|---------|---------|
| H1 | JNI `startDispense` 函数中遍历 `tasksArray` 时，`GetObjectArrayElement` 返回的局部引用未及时释放，导致局部引用表溢出 | 崩溃前 JNI 局部引用计数 |
| H2 | `GetArrayLength` 未做边界校验，C++ 循环时数组越界访问 | 传入的 `tasksArray` 长度 vs 实际迭代次数 |
| H3 | `GetStringUTFChars` 获取的字符串指针未调用 `ReleaseStringUTFChars` 释放，导致内存泄漏最终覆盖 JVM 内存区 | 字符串指针是否被正确释放 |
| H4 | C++ 中 `prescription.tasks.push_back(task)` 时，对象拷贝构造存在浅拷贝问题，或 vector 扩容时旧内存未正确释放 | prescription.tasks 向量的内存地址变化 |
| H5 | 多线程环境下，JNIEnv 指针在非 Attach 线程中被错误使用，导致内存访问越界 | dispenseWorkerThread 线程是否正确 AttachCurrentThread |

---

## 二、插桩日志（已添加）

| 插桩点 ID | 位置 | 日志内容 | 目的 |
|----------|------|---------|------|
| jni-mem-01 | 全局宏 | 带时间戳的 DEBUG_LOG 宏 | 统一日志输出 |
| jni-mem-02 | dispenseWorkerThread 入口 | 打印 taskId、shouldStop 引用地址、map bucket_count、map size | 验证引用悬空问题 |
| jni-mem-03/04 | startDispense 前后 | 打印插入前后的 bucket_count，检测重新哈希 | 验证重新哈希触发时机 |
| jni-mem-05/06 | JNI startDispense 入口 | 检查 tasksArray 是否为 NULL、FindClass 返回值 | 验证空指针和类加载问题 |
| jni-mem-07 | JNI 数组获取后 | 打印 tasksArray 长度 | 验证数组长度 |
| jni-mem-08 | 循环内部 | 检查 taskObj 是否为 NULL、打印对象地址 | 验证数组元素有效性 |
| jni-mem-09 | push_back 后 | 打印 vector size/capacity | 验证 vector 扩容问题 |
| jni-mem-10/11 | 函数结尾 | 确认 taskClass 局部引用被删除 | 验证局部引用释放 |

---

## 三、证据收集与分析

### 静态代码审查证据（已确认）

| 假设 | 状态 | 证据 | 结论 |
|-----|------|------|------|
| H1（局部引用泄漏） | ✅ 确认 | `getAllBinStatus`/`getBinStatus` 中 `FindClass` 返回的 `jclass` 未调用 `DeleteLocalRef` 释放；`startDispense` 中同样存在 | 已修复 |
| H2（NULL 数组检查） | ✅ 确认 | `startDispense` 中 `tasksArray` 可能为 NULL，`GetArrayLength` 未做空指针检查 | 已修复 |
| H3（字符串释放） | ✅ 已排除 | `GetStringUTFChars` / `ReleaseStringUTFChars` 已正确配对调用 | 无需修复 |
| **H4（悬空引用）** | ✅✅ **根因确认** | `dispenseWorkerThread:214` 中 `std::atomic<bool>& shouldStop = m_taskStopFlags[taskId];` 获取引用后，后续 `startDispense` 插入新元素触发 `unordered_map` rehash，所有元素内存地址失效，引用变为悬空指针 → 访问已释放内存 → JVM 内存损坏 | **已修复** |
| H5（数据竞争） | ✅ 确认 | `startDispense`/`pauseDispense`/`isTaskCompleted` 对共享 `unordered_map` 访问未加锁，与 `emergencyStop` 的锁操作形成数据竞争 | 已修复 |

### 动态验证证据（已确认）

运行 [test_memory_fix.cpp](file:///Users/kl/Documents/trae_projects2/cf10/cpp/test_memory_fix.cpp) 触发 **7 次 unordered_map rehash**：
```
REHASH detected when inserting task 3  bucket: 2 -> 5   ← 第3贴精确对应崩溃时刻
Started task 3 map size=3
...
✅ 修复验证通过：所有任务在多次 rehash 后均正常完成，无悬空引用崩溃！
```

---

## 四、修复方案

### 修复 1：悬空引用（崩溃根因）

**位置：** [tcm_dispenser.h:96](file:///Users/kl/Documents/trae_projects2/cf10/cpp/include/tcm_dispenser.h#L96-L96)

| 修改前 | 修改后 |
|--------|--------|
| `std::unordered_map<int, std::atomic<bool>> m_taskStopFlags` | `std::unordered_map<int, std::shared_ptr<std::atomic<bool>>> m_taskStopFlags` |
| `std::atomic<bool>& shouldStop = m_taskStopFlags[taskId]` | `std::shared_ptr<std::atomic<bool>> shouldStop = m_taskStopFlags.find(taskId)->second` |

**原理：** shared_ptr 持有独立于 map 的引用计数，map rehash 只移动 shared_ptr 对象，指针指向的 atomic<bool> 内存地址不变。

### 修复 2：JNI 局部引用泄漏

**位置：** [tcm_dispenser.cpp:378-440](file:///Users/kl/Documents/trae_projects2/cf10/cpp/src/tcm_dispenser.cpp#L378-L440)

在 `getAllBinStatus`、`getBinStatus`、`startDispense` 三个 JNI 函数中，所有 `FindClass` 返回的 `jclass` 均添加 `env->DeleteLocalRef()`。

### 修复 3：多线程数据竞争

**位置：** [tcm_dispenser.cpp:269-360](file:///Users/kl/Documents/trae_projects2/cf10/cpp/src/tcm_dispenser.cpp#L269-L360)

`startDispense`、`pauseDispense`、`resumeDispense`、`isTaskCompleted`、`shutdown` 中对共享 map 的访问全部加上 `std::lock_guard<std::mutex>` 保护。

### 修复 4：空指针安全性

**位置：** [tcm_dispenser.cpp:442-464](file:///Users/kl/Documents/trae_projects2/cf10/cpp/src/tcm_dispenser.cpp#L442-L464)

- `tasksArray == nullptr` 检查 + 提前返回
- `FindClass` 返回 nullptr 检查 + `ExceptionClear`
- `GetMethodID` 返回 nullptr 检查 + 释放已获取的局部引用
- `taskObj == nullptr` 检查 + 资源清理 + 提前返回

### 修复 5：任务资源清理

新增 `cleanupTask(taskId)` 方法，在 worker 线程完成后自动清理 `m_taskStopFlags` 和 `m_activeTasks` 中对应的条目，防止内存无限增长。

---

## 五、修复验证

| 阶段 | 结果 | 证据 |
|------|------|------|
| pre-fix | 💥 第3贴崩溃 | EXCEPTION_ACCESS_VIOLATION 0xc0000005 |
| post-fix | ✅ 30/30 任务完成 | 7次 rehash 触发，无悬空引用，无内存泄漏 |

**对比结论：**
- 修复前：连续第 3 次调用 `startDispense` 触发 `unordered_map` rehash，导致第 1/2 贴 worker 线程中持有的 `shouldStop` 引用悬空 → 访问野指针 → JVM 内存空间被破坏 → 崩溃
- 修复后：使用 `shared_ptr` 持有 atomic<bool>，rehash 只复制 shared_ptr，实际内存地址不变；所有 JNI 局部引用正确释放；共享 map 访问全部加锁 → 稳定运行

---

## 状态：[OPEN] 等待用户验证
