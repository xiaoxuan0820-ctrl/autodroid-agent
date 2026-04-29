package com.apk.claw.android.tool.impl

import com.apk.claw.android.agent.Persona
import com.apk.claw.android.skill.SkillManager
import com.apk.claw.android.skill.SkillManager.Category
import com.apk.claw.android.skill.SkillManager.TriggerType
import com.apk.claw.android.skill.SkillManager.Skill
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * 创建技能工具
 *
 * 用户通过自然语言描述需求，AI 自动生成结构化 Skill。
 * 这是 CiCi 实现"零代码创建自动化"的核心入口。
 *
 * 用法示例：
 *   create_skill(name="天气提醒", description="每天早上查天气", trigger="每天 8:00", steps=["查天气App", "分析结果"])
 *   create_skill(name="打卡神器", trigger="工作日 9:00,18:00", steps=["打开钉钉打卡"])
 */
class CreateSkillTool : BaseTool() {

    override fun getName(): String = "create_skill"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("name", "string", "技能名称，简短易记，如 '天气提醒'", true),
        ToolParameter("description", "string", "技能功能描述，一句话说明能做什么", true),
        ToolParameter("steps", "string", "执行步骤描述，用逗号或换行分隔。如 '打开天气App,截屏分析天气,发送结果给用户'", false),
        ToolParameter("trigger", "string", "触发方式：留空=手动触发；'每天 HH:mm'=每日定时；'工作日 HH:mm'=仅工作日；支持多时段如 '9:00,18:00'", false),
        ToolParameter("prompt", "string", "自定义执行指令（可选），给 AI 的详细执行说明，覆盖 steps", false),
        ToolParameter("category", "string", "分类：productivity/social/office/creative/utility/custom，默认 custom", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val name = requireString(params, "name").trim()
        if (name.length > 50) {
            return ToolResult.error("技能名称不能超过50个字")
        }

        val description = params["description"]?.toString()?.trim()
            ?: return ToolResult.error("请提供技能描述")
        if (description.length > 200) {
            return ToolResult.error("技能描述不能超过200个字")
        }

        // 解析触发方式
        val triggerStr = params["trigger"]?.toString()?.trim() ?: ""
        val (triggerType, triggerTime, workdaysOnly) = parseTrigger(triggerStr)

        // 解析分类
        val category = parseCategory(params["category"]?.toString()?.trim() ?: "")

        // 解析执行步骤或自定义 prompt
        val stepsStr = params["steps"]?.toString()?.trim() ?: ""
        val customPrompt = params["prompt"]?.toString()?.trim() ?: ""

        val steps = if (stepsStr.isNotBlank()) {
            stepsStr.split("\n", ",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapIndexed { index, step ->
                    SkillManager.SkillStep(
                        description = step,
                        action = step,
                        waitAfterMs = if (step.contains("打开") || step.contains("open")) 3000 else 1000
                    )
                }
        } else {
            emptyList()
        }

        // 自动推断图标
        val icon = inferIcon(name, description, category)

        // 推断标签
        val tags = inferTags(name, description, category)

        // 构建 system prompt
        val systemPrompt = buildSystemPrompt(description, steps, customPrompt)

        val skill = Skill(
            name = name,
            description = description,
            icon = icon,
            category = category,
            triggerType = triggerType,
            triggerTime = triggerTime,
            workdaysOnly = workdaysOnly,
            steps = steps,
            systemPrompt = systemPrompt,
            tags = tags
        )

        SkillManager.create(skill)

        return ToolResult.success(formatResult(skill))
    }

    // ==================== 内部解析 ====================

    private fun parseTrigger(trigger: String): Triple<TriggerType, String, Boolean> {
        if (trigger.isBlank()) return Triple(TriggerType.MANUAL, "", false)

        val lowered = trigger.lowercase()

        // 工作日模式：'工作日 9:00,18:00'
        if (lowered.startsWith("工作日") || lowered.startsWith("workday")) {
            val time = trigger.substringAfter(" ").trim()
            return Triple(TriggerType.WORKDAY, time, true)
        }

        // 每天模式：'每天 9:00,18:00' 或 '每日 9:00'
        if (lowered.startsWith("每天") || lowered.startsWith("每日") || lowered.startsWith("every")) {
            val time = trigger.substringAfter(" ").trim()
            return Triple(TriggerType.DAILY, time, false)
        }

        // 纯时间格式 '9:00,18:00' → 默认每天
        if (Regex("^\\d{1,2}:\\d{2}").containsMatchIn(trigger)) {
            return Triple(TriggerType.DAILY, trigger, false)
        }

        // 周日特殊处理
        if (lowered.contains("周日") || lowered.contains("周末") || lowered.contains("weekend")) {
            val time = trigger.substringAfter(" ").trim()
            return Triple(TriggerType.DAILY, if (time.isNotBlank()) time else "10:00", false)
        }

        // 默认手动
        return Triple(TriggerType.MANUAL, "", false)
    }

    private fun parseCategory(cat: String): Category {
        return when (cat.lowercase().trim()) {
            "productivity", "效率", "效率工具" -> Category.PRODUCTIVITY
            "social", "社交", "社交娱乐" -> Category.SOCIAL
            "office", "办公", "办公文档" -> Category.OFFICE
            "creative", "创意", "创作", "创意创作" -> Category.CREATIVE
            "utility", "工具", "实用工具" -> Category.UTILITY
            else -> Category.CUSTOM
        }
    }

    private fun inferIcon(name: String, description: String, category: Category): String {
        val text = "$name $description".lowercase()
        return when {
            text.contains("天气") || text.contains("weather") -> "🌤️"
            text.contains("新闻") || text.contains("news") || text.contains("资讯") -> "📰"
            text.contains("打卡") || text.contains("签到") || text.contains("check") -> "📍"
            text.contains("抖音") || text.contains("视频") || text.contains("video") -> "📱"
            text.contains("淘宝") || text.contains("京东") || text.contains("购物") -> "🛒"
            text.contains("邮件") || text.contains("email") || text.contains("mail") -> "📧"
            text.contains("日报") || text.contains("周报") || text.contains("报告") -> "📋"
            text.contains("清理") || text.contains("clean") || text.contains("缓存") -> "🧹"
            text.contains("小红书") || text.contains("微博") || text.contains("post") -> "✍️"
            text.contains("闹钟") || text.contains("提醒") || text.contains("remind") -> "⏰"
            text.contains("音乐") || text.contains("music") || text.contains("播") -> "🎵"
            text.contains("截图") || text.contains("screen") || text.contains("screenshot") -> "📸"
            else -> when (category) {
                Category.PRODUCTIVITY -> "⚡"
                Category.SOCIAL -> "💬"
                Category.OFFICE -> "📄"
                Category.CREATIVE -> "🎨"
                Category.UTILITY -> "🔧"
                Category.CUSTOM -> "✏️"
            }
        }
    }

    private fun inferTags(name: String, description: String, category: Category): List<String> {
        val tags = mutableListOf<String>()
        val text = "$name $description".lowercase()
        if (text.contains("签到") || text.contains("打卡")) tags.add("签到")
        if (text.contains("天气")) tags.add("天气")
        if (text.contains("新闻") || text.contains("资讯")) tags.add("资讯")
        if (text.contains("视频") || text.contains("抖音")) tags.add("视频")
        if (text.contains("办公") || text.contains("日报") || text.contains("文档")) tags.add("办公")
        if (text.contains("清理")) tags.add("清理")
        return tags
    }

    private fun buildSystemPrompt(description: String, steps: List<SkillManager.SkillStep>, customPrompt: String): String {
        if (customPrompt.isNotBlank()) return customPrompt

        val sb = StringBuilder()
        sb.appendLine("执行技能任务: $description")

        if (steps.isNotEmpty()) {
            sb.appendLine("\n执行步骤:")
            steps.forEachIndexed { i, step ->
                sb.appendLine("  ${i + 1}. ${step.description}")
            }
            sb.appendLine("\n请严格按照上述步骤逐步执行，每完成一步验证效果后再继续。")
            sb.appendLine("完成后向用户报告执行结果。")
        } else {
            sb.appendLine("\n根据你的能力自动完成这个任务，完成后报告结果。")
        }

        return sb.toString()
    }

    private fun formatResult(skill: Skill): String {
        val triggerStr = when (skill.triggerType) {
            TriggerType.MANUAL -> "手动触发"
            TriggerType.DAILY -> "每天 ${skill.triggerTime}"
            TriggerType.WORKDAY -> "工作日 ${skill.triggerTime}"
            TriggerType.CRON -> skill.triggerTime
        }
        val stepCount = if (skill.steps.isNotEmpty()) "${skill.steps.size}步" else "AI自主执行"
        return "✅ 已创建技能「${skill.name}」\n" +
               "   ${skill.icon} ${skill.description}\n" +
               "   触发: $triggerStr\n" +
               "   步骤: $stepCount\n" +
               "   分类: ${skill.category.label}\n" +
               "可通过 list_skills 查看所有技能"
    }

    override fun getDescriptionEN(): String =
        "Create an automation skill with natural language. " +
        "Set trigger='每天 HH:mm' for daily, '工作日 HH:mm' for workdays. " +
        "Steps supports comma-separated descriptions. " +
        "After creation, the skill can be listed and auto-triggered."

    override fun getDescriptionCN(): String =
        "零代码创建自动化技能。输入技能名称、描述、执行步骤和触发时间，AI自动生成结构化技能。\n" +
        "trigger 格式：留空=手动，'每天 9:00'=每日，'工作日 9:00,18:00'=工作日多时段。\n" +
        "创建后自动注册定时任务，到点自动执行。"
}

/**
 * 列出所有技能
 */
class ListSkillsTool : BaseTool() {

    override fun getName(): String = "list_skills"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("query", "string", "搜索关键词（可选），按名称/描述/标签搜索", false),
        ToolParameter("category", "string", "按分类筛选：productivity/social/office/creative/utility/custom", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val query = params["query"]?.toString()?.trim()
        val categoryStr = params["category"]?.toString()?.trim()

        val skills = when {
            !query.isNullOrBlank() -> SkillManager.search(query)
            !categoryStr.isNullOrBlank() -> {
                val cat = parseCategory(categoryStr)
                SkillManager.getByCategory(cat)
            }
            else -> SkillManager.getAll()
        }

        if (skills.isEmpty()) {
            val msg = when {
                !query.isNullOrBlank() -> "没有找到匹配「$query」的技能"
                !categoryStr.isNullOrBlank() -> "「${categoryStr}」分类下暂无技能"
                else -> "暂无技能。试试用 create_skill 创建第一个技能吧！"
            }
            return ToolResult.success(msg)
        }

        val sb = StringBuilder("【技能列表】（共${skills.size}个）\n")
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

        return ToolResult.success(sb.toString())
    }

    private fun parseCategory(cat: String): Category {
        return when (cat.lowercase().trim()) {
            "productivity", "效率", "效率工具" -> Category.PRODUCTIVITY
            "social", "社交", "社交娱乐" -> Category.SOCIAL
            "office", "办公", "办公文档" -> Category.OFFICE
            "creative", "创意", "创作", "创意创作" -> Category.CREATIVE
            "utility", "工具", "实用工具" -> Category.UTILITY
            else -> Category.CUSTOM
        }
    }

    override fun getDescriptionEN(): String = "List all created skills."
    override fun getDescriptionCN(): String = "列出所有已创建的自动化技能。可选 query 搜索或 category 按分类筛选。"
}

/**
 * 删除技能
 */
class DeleteSkillTool : BaseTool() {

    override fun getName(): String = "delete_skill"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("name", "string", "要删除的技能名称", true)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val name = requireString(params, "name").trim()
        val skills = SkillManager.getAll().filter {
            it.name == name || it.id == name
        }

        if (skills.isEmpty()) {
            return ToolResult.error("未找到技能「$name」，使用 list_skills 查看所有技能")
        }

        skills.forEach { SkillManager.delete(it.id) }
        return ToolResult.success("已删除技能「$name」")
    }

    override fun getDescriptionEN(): String = "Delete a skill by name."
    override fun getDescriptionCN(): String = "按名称删除一个自动化技能。"
}

/**
 * 切换人格
 */
class SwitchPersonaTool : BaseTool() {

    override fun getName(): String = "switch_persona"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("persona", "string", "目标人格：geek（极客助手）/ office（办公搭子）/ creator（创作达人）/ efficiency（效率管家）", true)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val personaStr = requireString(params, "persona").trim().lowercase()

        val persona = Persona.getById(personaStr) ?: run {
            return ToolResult.error(
                "未知人格「$personaStr」，可用人格：geek（极客助手）、office（办公搭子）、creator（创作达人）、efficiency（效率管家）"
            )
        }

        val oldPersona = Persona.getActive()
        Persona.setActive(persona)

        return ToolResult.success(
            "✅ 已切换人格：${oldPersona.icon} ${oldPersona.name} → ${persona.icon} ${persona.name}\n" +
            "${persona.description}\n" +
            "下次任务执行时将使用新的人格风格。"
        )
    }

    override fun getDescriptionEN(): String =
        "Switch AI persona. Options: geek/office/creator/efficiency."

    override fun getDescriptionCN(): String =
        "切换 AI 人格风格。可用：geek（极客助手）/ office（办公搭子）/ creator（创作达人）/ efficiency（效率管家）。"
}
