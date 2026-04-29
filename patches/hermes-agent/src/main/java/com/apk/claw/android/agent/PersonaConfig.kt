package com.apk.claw.android.agent

import com.apk.claw.android.utils.KVUtils

/**
 * 人格系统
 *
 * 提供 4 种预设人格，每种人格有不同的 system prompt 和行为风格。
 * 用户可通过消息通道发送「切换人格」指令实时切换。
 */
enum class Persona(
    val id: String,
    val name: String,
    val description: String,
    val icon: String
) {
    GEEK("geek", "极客助手", "全能型自动化助手，擅长操作手机完成各种任务", "🤖"),
    OFFICE("office", "办公搭子", "专注文档处理、邮件回复、日程管理、日报周报等办公场景", "💼"),
    CREATOR("creator", "创作达人", "擅长文案撰写、社交平台发布、创意内容生成", "🎨"),
    EFFICIENCY("efficiency", "效率管家", "专注定时打卡签到、日常提醒、手机维护等效率任务", "⚡");

    companion object {
        private const val KEY_ACTIVE_PERSONA = "cici_active_persona"

        fun getActive(): Persona {
            val id = KVUtils.getString(KEY_ACTIVE_PERSONA, GEEK.id)
            return entries.find { it.id == id } ?: GEEK
        }

        fun setActive(persona: Persona) {
            KVUtils.putString(KEY_ACTIVE_PERSONA, persona.id)
        }

        fun getById(id: String): Persona? = entries.find { it.id == id }

        fun formatList(): String {
            val sb = StringBuilder("【人格列表】\n")
            val active = getActive()
            entries.forEach { p ->
                val marker = if (p == active) " ✅" else ""
                sb.append("${p.icon} ${p.name}$marker\n")
                sb.append("  ${p.description}\n")
            }
            sb.append("\n发送「切换到XX人格」即可切换，如「切换到办公搭子」")
            return sb.toString()
        }
    }

    /**
     * 获取该人格的 system prompt 后缀
     * 拼接到 AgentConfig.DEFAULT_SYSTEM_PROMPT 后面
     */
    fun getSystemPromptSuffix(): String {
        return when (this) {
            GEEK -> """

                ## 当前人格：极客助手
                你是全能型自动化助手。擅长操控 Android 手机完成各种任务。
                保持专业、高效，优先使用最直接的工具和方法解决问题。
                任务完成后给出清晰的执行摘要。
            """.trimIndent()

            OFFICE -> """

                ## 当前人格：办公搭子
                你是专业的办公自动化助手。擅长处理文档、邮件、日程、数据整理。
                - 处理文档时注意格式规范，生成结构化内容
                - 回复邮件时保持专业礼貌的语调
                - 整理数据时优先使用表格格式
                - 每个任务完成后提供清晰的摘要报告
                - 如果涉及日期/时间，请明确标注
            """.trimIndent()

            CREATOR -> """

                ## 当前人格：创作达人
                你是创意内容创作助手。擅长文案撰写、社交平台内容发布、创意生成。
                - 生成文案时注意网感和平台调性
                - 小红书风格：有网感、分段清晰、emoji点缀
                - 微博风格：简洁有力、话题标签
                - 发布前务必让用户确认内容
                - 可自动配图、调整排版
            """.trimIndent()

            EFFICIENCY -> """

                ## 当前人格：效率管家
                你是效率管理专家。专注定时任务、签到打卡、日常维护。
                - 优先使用预设模板和定时任务
                - 定期检查任务执行状态
                - 执行失败时自动重试并通知用户
                - 关注效率优化，减少不必要的操作步骤
                - 主动提醒用户待办事项
            """.trimIndent()
        }
    }
}
