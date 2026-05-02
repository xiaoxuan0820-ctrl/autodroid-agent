package com.apk.claw.android.skill

import com.apk.claw.android.memory.MemoryManager
import com.apk.claw.android.scheduler.TaskScheduler
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * 技能管理系统
 *
 * Skill = 可复用的自动化工作流，用户通过自然语言描述即可创建。
 * 与 WorkflowTemplateManager 互补：
 *   - WorkflowTemplate：由 Agent 自动学习执行步骤生成（底层、内部）
 *   - Skill：用户通过自然语言主动创建（上层、用户可见）
 */
object SkillManager {

    private const val TAG = "SkillManager"
    private val GSON = Gson()
    private const val STORAGE_KEY = "cici_skills"

    /** 触发方式 */
    enum class TriggerType {
        MANUAL,          // 手动执行（无定时）
        DAILY,           // 每天固定时间
        WORKDAY,         // 工作日固定时间
        CRON             // 自定义 cron
    }

    /** 技能分类 */
    enum class Category(val label: String, val icon: String) {
        PRODUCTIVITY("效率工具", "⚡"),
        SOCIAL("社交娱乐", "💬"),
        OFFICE("办公文档", "📄"),
        CREATIVE("创意创作", "🎨"),
        UTILITY("实用工具", "🔧"),
        CUSTOM("自定义", "✏️")
    }

    /** Skill 执行步骤 */
    data class SkillStep(
        val description: String,        // 步骤描述（给人看）
        val action: String,             // 操作指令（给 AI 执行参考）
        val waitAfterMs: Int = 1000     // 步骤后等待时间
    )

    /** Skill 数据模型 */
    data class Skill(
        val id: String = UUID.randomUUID().toString(),
        val name: String,                       // 技能名
        val description: String,                // 功能描述
        val icon: String = "🔧",               // 显示图标
        val category: Category = Category.CUSTOM,
        val triggerType: TriggerType = TriggerType.MANUAL,
        val triggerTime: String = "",           // "HH:mm" 或 cron 表达式
        val workdaysOnly: Boolean = false,      // 仅工作日
        val steps: List<SkillStep> = emptyList(), // 执行步骤（可选）
        val systemPrompt: String = "",          // 自定义 system prompt 后缀
        val tags: List<String> = emptyList(),
        /** 执行此技能需要的记忆上下文关键词，自动从 MemoryManager 获取相关记忆 */
        val requiredMemories: List<String> = emptyList(),
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
        val enabled: Boolean = true
    )

    // ==================== 存储 ====================

    fun getAll(): List<Skill> {
        val json = KVUtils.getString(STORAGE_KEY, "[]")
        return try {
            GSON.fromJson(json, object : TypeToken<List<Skill>>() {}.type) ?: emptyList()
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to load skills: ${e.message}")
            emptyList()
        }
    }

    private fun saveAll(skills: List<Skill>) {
        KVUtils.putString(STORAGE_KEY, GSON.toJson(skills))
    }

    fun getById(id: String): Skill? = getAll().find { it.id == id }

    fun getByCategory(category: Category): List<Skill> = getAll().filter { it.category == category }

    fun search(query: String): List<Skill> {
        val q = query.lowercase()
        return getAll().filter {
            it.name.lowercase().contains(q) ||
            it.description.lowercase().contains(q) ||
            it.tags.any { t -> t.lowercase().contains(q) }
        }
    }

    // ==================== CRUD ====================

    /**
     * 创建并注册技能
     * - 如果 triggerType != MANUAL，自动注册定时任务
     * - 保存到本地存储
     */
    fun create(skill: Skill): Skill {
        val skills = getAll().toMutableList()
        skills.add(skill)
        saveAll(skills)

        // 如果是定时触发，注册到 TaskScheduler
        if (skill.triggerType != TriggerType.MANUAL && skill.triggerTime.isNotBlank()) {
            registerScheduledTask(skill)
        }

        XLog.i(TAG, "✅ 创建技能: ${skill.name} (${skill.category.label})")
        return skill
    }

    /**
     * 更新技能
     */
    fun update(skill: Skill): Boolean {
        val skills = getAll().toMutableList()
        val index = skills.indexOfFirst { it.id == skill.id }
        if (index < 0) return false

        val old = skills[index]

        // 如果触发方式变了，取消旧定时任务
        if (old.triggerType != TriggerType.MANUAL && old.triggerTime.isNotBlank()) {
            unregisterScheduledTask(old)
        }

        skills[index] = skill.copy(updatedAt = System.currentTimeMillis())
        saveAll(skills)

        // 注册新定时任务
        if (skill.triggerType != TriggerType.MANUAL && skill.triggerTime.isNotBlank()) {
            registerScheduledTask(skill)
        }

        return true
    }

    fun delete(id: String): Boolean {
        val skills = getAll().toMutableList()
        val skill = skills.find { it.id == id } ?: return false

        if (skill.triggerType != TriggerType.MANUAL && skill.triggerTime.isNotBlank()) {
            unregisterScheduledTask(skill)
        }

        skills.removeAll { it.id == id }
        saveAll(skills)
        XLog.i(TAG, "🗑️ 删除技能: ${skill.name}")
        return true
    }

    fun toggle(id: String, enabled: Boolean): Boolean {
        val skills = getAll().toMutableList()
        val index = skills.indexOfFirst { it.id == id }
        if (index < 0) return false

        skills[index] = skills[index].copy(enabled = enabled)
        saveAll(skills)

        val skill = skills[index]
        if (enabled && skill.triggerType != TriggerType.MANUAL && skill.triggerTime.isNotBlank()) {
            registerScheduledTask(skill)
        } else {
            unregisterScheduledTask(skill)
        }
        return true
    }

    // ==================== 定时任务注册 ====================

    /**
     * 将 Skill 注册为定时任务
     */
    private fun registerScheduledTask(skill: Skill) {
        if (!skill.enabled) return

        val prompt = buildSkillPrompt(skill)
        val repeat = skill.triggerType != TriggerType.CRON
        val workdaysOnly = skill.triggerType == TriggerType.WORKDAY

        // 解析时间槽
        val timeSlots = when (skill.triggerType) {
            TriggerType.DAILY, TriggerType.WORKDAY -> {
                TaskScheduler.parseTimeSlots(skill.triggerTime)
            }
            TriggerType.CRON -> {
                // cron 表达式暂不支持，降级为解析 HH:mm
                TaskScheduler.parseTimeSlots(skill.triggerTime)
            }
            TriggerType.MANUAL -> emptyList()
        }

        if (timeSlots.isEmpty()) return

        TaskScheduler.addTask(
            name = skill.name,
            prompt = prompt,
            timeSlots = timeSlots,
            repeat = repeat,
            workdaysOnly = workdaysOnly
        )
        XLog.i(TAG, "⏰ 注册技能定时任务: ${skill.name} @ ${skill.triggerTime}")
    }

    /**
     * 取消 Skill 的定时任务
     */
    private fun unregisterScheduledTask(skill: Skill) {
        val tasks = TaskScheduler.getAllTasks()
        tasks.filter { it.name == skill.name }.forEach { task ->
            TaskScheduler.deleteTask(task.id)
        }
        XLog.i(TAG, "⏹️ 取消技能定时任务: ${skill.name}")
    }

    // ==================== 执行 ====================

    /**
     * 构建技能执行时的 system prompt
     * 包含技能的基本信息 + 执行步骤 + 相关记忆上下文
     */
    fun buildSkillPrompt(skill: Skill): String {
        val sb = StringBuilder()
        sb.appendLine("【技能任务】${skill.name}")
        sb.appendLine("描述: ${skill.description}")

        if (skill.steps.isNotEmpty()) {
            sb.appendLine("\n执行步骤:")
            skill.steps.forEachIndexed { i, step ->
                sb.appendLine("  ${i + 1}. ${step.description}")
            }
            sb.appendLine("\n请严格按照上述步骤执行，每步完成后验证效果再继续下一步。")
        }

        if (skill.systemPrompt.isNotBlank()) {
            sb.appendLine("\n额外要求:")
            sb.appendLine(skill.systemPrompt)
        }

        // 追加相关记忆上下文
        if (skill.requiredMemories.isNotEmpty()) {
            val memoryQuery = skill.requiredMemories.joinToString(" ")
            val memories = MemoryManager.search(memoryQuery, 10)
            if (memories.isNotEmpty()) {
                sb.appendLine("\n## 技能相关记忆")
                memories.forEach { mem ->
                    val scope = if (mem.appName != null) "[${mem.appName}] " else ""
                    sb.appendLine("- $scope${mem.key}: ${mem.value}")
                }
            }
        }

        return sb.toString()
    }

    // ==================== 内置技能 ====================

    /**
     * 获取内置预设技能列表
     * 用户首次打开技能市场时展示
     */
    fun getBuiltinSkills(): List<Skill> = listOf(
        Skill(
            id = "builtin_daily_news",
            name = "每日资讯摘要",
            description = "每天早上推送热点新闻摘要到消息通道",
            icon = "📰",
            category = Category.PRODUCTIVITY,
            triggerType = TriggerType.DAILY,
            triggerTime = "8:00",
            tags = listOf("新闻", "资讯", "每日"),
            systemPrompt = "搜索今日热点新闻，整理成5-8条的摘要列表发送给用户。包含来源链接。"
        ),
        Skill(
            id = "builtin_weather_reminder",
            name = "天气提醒",
            description = "每天早上查看天气，如果下雨就提醒用户带伞",
            icon = "🌤️",
            category = Category.PRODUCTIVITY,
            triggerType = TriggerType.DAILY,
            triggerTime = "7:30",
            tags = listOf("天气", "提醒"),
            systemPrompt = "打开天气应用或搜索今日天气预报。如果预报有雨，发送消息提醒用户带伞。如果气温低于10度，提醒加衣服。"
        ),
        Skill(
            id = "builtin_douyin_browse",
            name = "抖音自动刷视频",
            description = "定时刷抖音，滑动观看视频并点赞互动",
            icon = "📱",
            category = Category.SOCIAL,
            triggerType = TriggerType.DAILY,
            triggerTime = "12:00",
            tags = listOf("抖音", "娱乐", "刷视频"),
            steps = listOf(
                SkillStep("打开抖音App，等待视频开始播放", "open_app(package_name=\"com.ss.android.ugc.aweme\")", 3000),
                SkillStep("观看当前视频5秒", "wait(ms=5000)"),
                SkillStep("向下滑动切换视频，共滑动5次", "swipe(x1=540, y1=1500, x2=540, y2=300, duration=300), 重复5次"),
                SkillStep("对第3个视频双击点赞", "double_tap(x=540, y=900)")
            ),
            systemPrompt = "每次滑动后等待3秒让视频加载。如果出现弹窗，尝试关闭。"
        ),
        Skill(
            id = "builtin_daily_report",
            name = "日报提醒",
            description = "每天下午提醒提交日报，周五自动汇总周报",
            icon = "📋",
            category = Category.OFFICE,
            triggerType = TriggerType.WORKDAY,
            triggerTime = "17:30",
            workdaysOnly = true,
            tags = listOf("日报", "周报", "办公"),
            systemPrompt = "提醒用户提交今日日报。如果是周五，额外提醒用户汇总本周工作形成周报。"
        ),
        Skill(
            id = "builtin_taobao_sign",
            name = "淘宝签到领金币",
            description = "每天自动打开淘宝签到领金币",
            icon = "🛒",
            category = Category.UTILITY,
            triggerType = TriggerType.DAILY,
            triggerTime = "8:00",
            tags = listOf("淘宝", "签到", "积分"),
            steps = listOf(
                SkillStep("打开淘宝App", "open_app(package_name=\"com.taobao.taobao\")", 3000),
                SkillStep("点击底部'我的淘宝'", "查找并点击'我的淘宝'标签"),
                SkillStep("找到并点击'签到领金币'入口，完成签到", "查找并点击'签到领金币'或'每日签到'按钮")
            )
        ),
        Skill(
            id = "builtin_weekly_cleanup",
            name = "手机空间清理",
            description = "每周清理缓存和截图文件夹",
            icon = "🧹",
            category = Category.UTILITY,
            triggerType = TriggerType.DAILY,
            triggerTime = "周日 10:00",
            tags = listOf("清理", "缓存", "存储"),
            systemPrompt = "打开手机管家或设置-存储空间，清理系统缓存。然后打开相册，删除30天前的截图（只删截图，不删照片）。完成后报告清理了多少空间。"
        ),
        Skill(
            id = "builtin_social_post",
            name = "社交平台发文助手",
            description = "生成文案并发布到社交平台（小红书/微博）",
            icon = "✍️",
            category = Category.CREATIVE,
            triggerType = TriggerType.MANUAL,
            tags = listOf("社交", "文案", "发布"),
            systemPrompt = "先询问用户想发布什么内容、配什么图。生成文案后让用户确认，确认后打开对应社交App自动发布。发布后截图验证。"
        )
    )

    // ==================== 格式化输出 ====================

    fun formatSkillList(): String {
        val skills = getAll()
        if (skills.isEmpty()) return "暂无自定义技能。可以使用 create_skill 工具创建新技能。"

        val sb = StringBuilder("【技能列表】\n")
        skills.forEachIndexed { index, skill ->
            val status = if (skill.enabled) "✓" else "✗"
            val trigger = when (skill.triggerType) {
                TriggerType.MANUAL -> "手动"
                TriggerType.DAILY -> "每天 ${skill.triggerTime}"
                TriggerType.WORKDAY -> "工作日 ${skill.triggerTime}"
                TriggerType.CRON -> skill.triggerTime
            }
            sb.append("${index + 1}. [$status] ${skill.icon} ${skill.name}\n")
            sb.append("   ${skill.description}\n")
            sb.append("   触发: $trigger | ${skill.category.label}\n")
        }
        return sb.toString()
    }
}
