package com.apk.claw.android.scheduler

import com.apk.claw.android.utils.XLog

/**
 * 定时任务预设模板
 *
 * 提供常见场景的一键模板，用户选一个模板就能创建定时任务。
 */
object ScheduledTaskTemplates {

    private const val TAG = "TaskTemplates"

    data class TaskTemplate(
        val id: String,
        val name: String,
        val description: String,
        val icon: String,
        val category: Category,
        val prompt: String,
        val defaultTimeSlots: List<TaskScheduler.TimeSlot> = emptyList(),
        val defaultWorkdaysOnly: Boolean = false,
        val editable: Boolean = true        // 是否允许用户编辑
    ) {
        enum class Category {
            CHECK_IN,       // 打卡签到
            SOCIAL,         // 社交娱乐
            SHOPPING,       // 购物领积分
            MEDIA,          // 视频音乐
            UTILITY,        // 工具
            CUSTOM          // 自定义
        }
    }

    /** 获取所有模板 */
    fun getAll(): List<TaskTemplate> = listOf(
        // ==================== 打卡签到 ====================
        TaskTemplate(
            id = "dingtalk_checkin",
            name = "钉钉打卡",
            description = "打开钉钉 → 考勤打卡 → 点击签到",
            icon = "📍",
            category = TaskTemplate.Category.CHECK_IN,
            prompt = "打开钉钉应用，等待完全加载后，点击底部'工作台'，找到并点击'考勤打卡'，点击'签到'按钮确认打卡成功。完成后截图验证。",
            defaultTimeSlots = listOf(
                TaskScheduler.TimeSlot(9, 0),
                TaskScheduler.TimeSlot(18, 0)
            ),
            defaultWorkdaysOnly = true
        ),
        TaskTemplate(
            id = "feishu_checkin",
            name = "飞书签到",
            description = "打开飞书 → 工作台 → 签到",
            icon = "📍",
            category = TaskTemplate.Category.CHECK_IN,
            prompt = "打开飞书应用，等待加载完成后，点击底部'工作台'，找到并点击'签到'应用，点击签到按钮。完成后截图验证。",
            defaultTimeSlots = listOf(
                TaskScheduler.TimeSlot(9, 0),
                TaskScheduler.TimeSlot(18, 0)
            ),
            defaultWorkdaysOnly = true
        ),
        TaskTemplate(
            id = "qq_checkin",
            name = "QQ 打卡",
            description = "打开 QQ → 打卡签到",
            icon = "📍",
            category = TaskTemplate.Category.CHECK_IN,
            prompt = "打开QQ应用，等待加载后，点击左上角头像，在侧边栏中找到'我的打卡'或签到入口，点击签到。完成后截图验证。",
            defaultTimeSlots = listOf(TaskScheduler.TimeSlot(9, 30)),
            defaultWorkdaysOnly = true
        ),

        // ==================== 社交娱乐 ====================
        TaskTemplate(
            id = "douyin_browse",
            name = "抖音刷视频",
            description = "打开抖音 → 滑动观看视频 → 点赞互动",
            icon = "📱",
            category = TaskTemplate.Category.SOCIAL,
            prompt = "打开抖音应用，等待视频开始播放后，每次向下滑动切换视频，共滑动5次，每次间隔3秒。对第3个视频双击点赞。完成后截图验证。",
            defaultTimeSlots = listOf(TaskScheduler.TimeSlot(12, 0)),
            defaultWorkdaysOnly = false
        ),
        TaskTemplate(
            id = "wechat_moment",
            name = "刷朋友圈",
            description = "打开微信 → 朋友圈 → 浏览点赞",
            icon = "💬",
            category = TaskTemplate.Category.SOCIAL,
            prompt = "打开微信应用，点击底部'发现'标签，点击'朋友圈'进入，向下滑动浏览朋友圈内容。完成后截图验证。",
            defaultTimeSlots = listOf(TaskScheduler.TimeSlot(8, 30)),
            defaultWorkdaysOnly = false
        ),

        // ==================== 购物签到 ====================
        TaskTemplate(
            id = "taobao_sign",
            name = "淘宝签到领积分",
            description = "打开淘宝 → 签到领金币",
            icon = "🛒",
            category = TaskTemplate.Category.SHOPPING,
            prompt = "打开淘宝应用，等待首页加载完成。点击底部'我的淘宝'，找到'签到领金币'或'每日签到'入口，点击签到，等待签到成功提示。完成后截图验证。",
            defaultTimeSlots = listOf(TaskScheduler.TimeSlot(8, 0)),
            defaultWorkdaysOnly = false
        ),
        TaskTemplate(
            id = "jingdong_sign",
            name = "京东签到领京豆",
            description = "打开京东 → 签到领京豆",
            icon = "🛒",
            category = TaskTemplate.Category.SHOPPING,
            prompt = "打开京东应用，等待首页加载完成。点击底部'我的'，找到'签到领京豆'或'每日签到'入口，点击签到。完成后截图验证。",
            defaultTimeSlots = listOf(TaskScheduler.TimeSlot(8, 30)),
            defaultWorkdaysOnly = false
        ),

        // ==================== 视频音乐 ====================
        TaskTemplate(
            id = "netease_sign",
            name = "网易云音乐签到",
            description = "打开网易云 → 每日签到",
            icon = "🎵",
            category = TaskTemplate.Category.MEDIA,
            prompt = "打开网易云音乐应用，点击底部'我的'，找到每日签到入口，点击签到。完成后截图验证。",
            defaultTimeSlots = listOf(TaskScheduler.TimeSlot(10, 0)),
            defaultWorkdaysOnly = false
        ),
        TaskTemplate(
            id = "bilibili_sign",
            name = "B站签到",
            description = "打开哔哩哔哩 → 每日签到领硬币",
            icon = "📺",
            category = TaskTemplate.Category.MEDIA,
            prompt = "打开哔哩哔哩应用，点击底部'我的'，找到'每日签到'或领硬币入口，点击签到。完成后截图验证。",
            defaultTimeSlots = listOf(TaskScheduler.TimeSlot(10, 0)),
            defaultWorkdaysOnly = false
        ),

        // ==================== 自定义 ====================
        TaskTemplate(
            id = "custom",
            name = "自定义任务",
            description = "自由输入任务描述，设置时间和重复方式",
            icon = "✏️",
            category = TaskTemplate.Category.CUSTOM,
            prompt = "",
            defaultTimeSlots = emptyList(),
            defaultWorkdaysOnly = false,
            editable = true
        )
    )

    /** 按分类获取模板 */
    fun getByCategory(category: TaskTemplate.Category): List<TaskTemplate> {
        return getAll().filter { it.category == category }
    }

    /** 通过ID获取模板 */
    fun getById(id: String): TaskTemplate? {
        return getAll().find { it.id == id }
    }

    /** 创建任务从模板 */
    fun createTaskFromTemplate(
        templateId: String,
        timeSlots: List<TaskScheduler.TimeSlot>? = null,
        customPrompt: String? = null
    ): TaskScheduler.ScheduledTask? {
        val template = getById(templateId) ?: return null

        val prompt = customPrompt ?: template.prompt
        if (prompt.isBlank()) return null

        val slots = timeSlots ?: template.defaultTimeSlots
        if (slots.isEmpty()) return null

        return TaskScheduler.addTask(
            name = template.name,
            prompt = prompt,
            timeSlots = slots,
            workdaysOnly = template.defaultWorkdaysOnly
        )
    }

    /** 格式化模板列表为字符串 */
    fun formatTemplateList(): String {
        val sb = StringBuilder("【任务模板列表】\n")

        for (category in TaskTemplate.Category.entries) {
            if (category == TaskTemplate.Category.CUSTOM) continue
            val templates = getByCategory(category)
            if (templates.isEmpty()) continue

            val categoryName = when (category) {
                TaskTemplate.Category.CHECK_IN -> "📍 打卡签到"
                TaskTemplate.Category.SOCIAL -> "💬 社交娱乐"
                TaskTemplate.Category.SHOPPING -> "🛒 购物签到"
                TaskTemplate.Category.MEDIA -> "🎵 视频音乐"
                TaskTemplate.Category.UTILITY -> "🔧 工具"
                TaskTemplate.Category.CUSTOM -> "✏️ 自定义"
            }
            sb.append("\n$categoryName\n")
            templates.forEach { t ->
                val timeStr = if (t.defaultTimeSlots.isNotEmpty())
                    " ⏰${t.defaultTimeSlots.joinToString(",") { it.toTimeString() }}"
                else ""
                sb.append("  ${t.icon} ${t.name}${timeStr}\n")
                sb.append("    ${t.description}\n")
            }
        }

        return sb.toString()
    }
}
