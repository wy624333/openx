package com.wy.openx.data.local

import androidx.room.Entity
import com.wy.openx.data.model.TaskItem
import com.wy.openx.data.model.TaskStatus

/**
 * 任务面板（待办清单）持久化表。
 *
 * ## 为什么要落库
 * 原先任务只存在会话槽位（内存），**进程重启即清空**——用户排队/中断后重开 app，
 * 之前列好的计划全没了。参考实现（AiCode 1.12.0-rc1）用 Room 的 `todo_items` 表持久化，
 * 这里对齐。
 *
 * ## 与 `TaskItem` 的关系
 * 本表是**持久化真源**；运行时仍以会话槽位 `SessionSlot.tasks` 为准（UI 直接读它，
 * 避免每次渲染都查库）。同步时机见 `TaskRepository`：每次清单变更写库，
 * 切会话/启动时从库载入。
 *
 * ## 主键
 * 联合主键 `(conversationId, taskId)`：任务面板是**会话级**的，同一条任务在不同会话
 * 里可以各有一条；同一会话内 [taskId] 唯一。
 *
 * **不用自增主键**：[taskId] 由 `nextMessageId` 分配（与消息 id 同源），
 * 重建后必须能与 UI 已持有的 id 对上（否则面板上的「开始/删除」会找不到目标）。
 */
@Entity(tableName = "tasks", primaryKeys = ["conversationId", "taskId"])
data class TaskEntity(
    /** 归属会话。 */
    val conversationId: Long,
    /** 业务 id（= `com.wy.openx.data.model.TaskItem.id`），跨重启稳定。 */
    val taskId: Long,
    val description: String,
    /** `TaskStatus.name`（PENDING / IN_PROGRESS / COMPLETED / CANCELLED）。 */
    val status: String,
    /** 显示顺序（升序），由快照式 `todo` 工具的数组下标给出。 */
    val taskOrder: Int
)

/** `TaskEntity` ↔ `TaskItem` 的转换。放在实体文件里，避免多处各写一份映射。 */
fun TaskEntity.toItem(): TaskItem = TaskItem(
    id = taskId,
    description = description,
    // 容错：库里若有无法识别的状态（旧版本/手改），回退 PENDING 而不是崩在渲染层。
    status = runCatching { TaskStatus.valueOf(status) }.getOrDefault(TaskStatus.PENDING),
    order = taskOrder
)

fun TaskItem.toEntity(conversationId: Long): TaskEntity = TaskEntity(
    conversationId = conversationId,
    taskId = id,
    description = description,
    status = status.name,
    taskOrder = order
)
