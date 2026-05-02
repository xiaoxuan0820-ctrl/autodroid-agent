package com.apk.claw.android.memory

import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 结构化记忆系统
 *
 * 按分类存储用户信息，在 Agent 执行任务时只注入与当前任务相关的记忆，
 * 避免 V6 那种"所有记忆一股脑塞给所有任务"的问题。
 *
 * 记忆分类：
 * - USER_PROFILE: 用户身份信息（姓名、工号等）
 * - PREFERENCE: 偏好（主题、常用设置）
 * - APP_CONTEXT: 应用上下文（账号、常用功能）
 * - FACT: 一般事实
 * - CREDENTIAL: 凭证（不自动注入，需明确请求）
 */
object MemoryManager {

    private const val TAG = "MemoryManager"
    private val GSON = Gson()
    private const val STORAGE_KEY = "cici_memory_store"

    /** 最大记忆条数，超过时淘汰最低分记忆 */
    private const val MAX_MEMORIES = 500

    /** 构建上下文时取 Top-K 记忆 */
    private const val TOP_K_MEMORIES = 20

    // ==================== 同义词表 ====================

    private val SYNONYM_MAP = mapOf(
        "老板" to listOf("经理", "上司", "boss"),
        "地址" to listOf("位置", "地点"),
        "天气" to listOf("下雨", "温度", "气候"),
        "电话" to listOf("手机", "号码", "联系方式"),
        "邮件" to listOf("邮箱", "email"),
        "密码" to listOf("口令", "密钥"),
        "账号" to listOf("账户", "用户名"),
        "设置" to listOf("配置", "设定"),
        "名称" to listOf("名字", "姓名"),
        "备注" to listOf("注释", "说明", "附注"),
        "提醒" to listOf("提醒", "通知", "提示"),
        "打卡" to listOf("签到", "考勤")
    )

    // ==================== 分类 ====================

    enum class Category(val key: String, val label: String, val autoInject: Boolean) {
        USER_PROFILE("profile", "用户信息", true),
        PREFERENCE("pref", "偏好", true),
        APP_CONTEXT("app", "应用上下文", true),
        FACT("fact", "事实", true),
        CREDENTIAL("cred", "凭证", false) // 需要明确请求才注入
    }

    // ==================== 数据模型 ====================

    data class MemoryItem(
        val id: String = java.util.UUID.randomUUID().toString(),
        val category: Category = Category.FACT,
        val key: String = "",
        val value: String = "",
        /** 关联的应用包名或应用名，null=全局记忆 */
        val appName: String? = null,
        /** 标签，便于搜索 */
        val tags: List<String> = emptyList(),
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
        /** 上次访问时间，用于评分 */
        val lastAccessAt: Long = System.currentTimeMillis(),
        /** 重要性（0.0 ~ 1.0），默认 0.5 */
        val importance: Float = 0.5f
    )

    // ==================== 缓存 ====================

    @Volatile
    private var cache: List<MemoryItem>? = null

    private fun loadAll(): List<MemoryItem> {
        if (cache != null) return cache!!
        val json = KVUtils.getString(STORAGE_KEY, "[]")
        cache = try {
            GSON.fromJson(json, object : TypeToken<List<MemoryItem>>() {}.type)
                ?: emptyList()
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to load memories, resetting")
            emptyList()
        }
        return cache!!
    }

    private fun saveAll(items: List<MemoryItem>) {
        // 检查并淘汰最低分记忆
        val trimmed = if (items.size > MAX_MEMORIES) {
            val scored = items.map { item ->
                val daysSinceAccess = (System.currentTimeMillis() - item.lastAccessAt) / (1000L * 60 * 60 * 24)
                val score = item.importance * (1.0f - (daysSinceAccess.coerceAtMost(90) / 90.0f))
                item to score
            }
            val sorted = scored.sortedByDescending { it.second }
            sorted.take(MAX_MEMORIES).map { it.first }
        } else {
            items
        }
        cache = trimmed
        KVUtils.putString(STORAGE_KEY, GSON.toJson(trimmed))
    }

    // ==================== 查询扩展 ====================

    /**
     * 对查询关键词做同义扩展，返回扩展后的关键词列表（包含原词）。
     */
    private fun expandQuery(query: String): List<String> {
        val results = mutableSetOf(query)
        // 检查 query 是否匹配同义词表中的任意 key 或 value
        for ((key, synonyms) in SYNONYM_MAP) {
            if (query.contains(key, ignoreCase = true)) {
                results.addAll(synonyms)
            }
            for (syn in synonyms) {
                if (query.contains(syn, ignoreCase = true)) {
                    results.add(key)
                    break
                }
            }
        }
        return results.toList()
    }

    // ==================== 增删改 ====================

    /** 保存一条记忆（key+appName 相同则更新） */
    fun save(
        category: Category,
        key: String,
        value: String,
        appName: String? = null,
        tags: List<String> = emptyList(),
        importance: Float = 0.5f
    ): MemoryItem {
        val items = loadAll().toMutableList()

        // 查找是否已存在相同 key+appName
        val existing = items.indexOfFirst { it.key == key && it.appName == appName && it.category == category }
        val item = if (existing >= 0) {
            items[existing].copy(value = value, tags = tags, updatedAt = System.currentTimeMillis(), importance = importance)
        } else {
            MemoryItem(
                category = category,
                key = key,
                value = value,
                appName = appName,
                tags = tags,
                importance = importance
            )
        }

        if (existing >= 0) {
            items[existing] = item
        } else {
            items.add(item)
        }

        saveAll(items)
        XLog.i(TAG, "💾 保存记忆: [${category.label}] $key = ${value.take(50)}")
        return item
    }

    /** 删除记忆 */
    fun delete(memoryId: String): Boolean {
        val items = loadAll().toMutableList()
        val removed = items.removeAll { it.id == memoryId }
        if (removed) saveAll(items)
        return removed
    }

    /** 删除某应用的所有记忆 */
    fun deleteAppMemories(appName: String) {
        val items = loadAll().toMutableList()
        items.removeAll { it.appName == appName }
        saveAll(items)
        XLog.i(TAG, "已删除 $appName 的所有记忆")
    }

    /** 获取所有记忆 */
    fun getAll(): List<MemoryItem> = loadAll()

    /** 获取记忆总数 */
    fun count(): Int = loadAll().size

    // ==================== 搜索 ====================

    /** 搜索记忆 */
    fun search(query: String?, limit: Int = 20): List<MemoryItem> {
        val items = loadAll()
        if (query.isNullOrBlank()) return items.take(limit)

        // 同义词扩展
        val q = query.lowercase()
        val expandedQueries = expandQuery(q)

        return items.filter { item ->
            val keyLower = item.key.lowercase()
            val valueLower = item.value.lowercase()
            val appLower = item.appName?.lowercase() ?: ""
            val tagLower = item.tags.joinToString(" ").lowercase()

            expandedQueries.any { expanded ->
                keyLower.contains(expanded) ||
                valueLower.contains(expanded) ||
                tagLower.contains(expanded) ||
                appLower.contains(expanded)
            }
        }.take(limit)
    }

    /** 获取某应用的记忆 */
    fun getAppMemories(appName: String): List<MemoryItem> {
        return loadAll().filter { it.appName == appName }
    }

    // ==================== 上下文构建 ====================

    /**
     * 构建记忆上下文，供 Agent 注入
     *
     * 只注入与当前任务相关的记忆，避免跨任务干扰：
     * - 全局记忆（appName=null）始终注入
     * - 跟当前应用相关的记忆注入
     * - 跟任务描述关键词匹配的记忆注入
     * - CREDENTIAL 类型不自动注入
     * - 按评分排序取 Top-20（importance × 时效衰减）
     * - 每次读取时更新 lastAccessAt
     */
    fun buildMemoryContext(currentApp: String?, taskPrompt: String): String {
        val all = loadAll()
        if (all.isEmpty()) return ""

        val promptLower = taskPrompt.lowercase()

        // 筛选相关记忆
        val relevant = all.filter { item ->
            if (!item.category.autoInject) return@filter false

            // 全局记忆
            if (item.appName == null) return@filter true

            // 当前应用匹配
            if (currentApp != null && item.appName.equals(currentApp, ignoreCase = true)) return@filter true

            // 任务描述匹配
            item.appName.lowercase().let { app ->
                promptLower.contains(app) ||
                item.tags.any { tag -> promptLower.contains(tag.lowercase()) } ||
                item.key.lowercase().let { key -> promptLower.contains(key) }
            }
        }

        if (relevant.isEmpty()) return ""

        // 评分排序：importance × (1 - daysSinceLastAccess / 90)
        val now = System.currentTimeMillis()
        val scored = relevant.map { item ->
            val daysSinceAccess = (now - item.lastAccessAt) / (1000L * 60 * 60 * 24)
            val score = item.importance * (1.0f - (daysSinceAccess.coerceAtMost(90) / 90.0f))
            item to score
        }
        val topItems = scored.sortedByDescending { it.second }.take(TOP_K_MEMORIES).map { it.first }

        // 更新被选中记忆的 lastAccessAt
        val allItems = loadAll().toMutableList()
        var changed = false
        for (selected in topItems) {
            val idx = allItems.indexOfFirst { it.id == selected.id }
            if (idx >= 0) {
                allItems[idx] = allItems[idx].copy(lastAccessAt = now)
                changed = true
            }
        }
        if (changed) {
            cache = allItems
            KVUtils.putString(STORAGE_KEY, GSON.toJson(allItems))
        }

        // 按分类分组，格式化输出
        val grouped = topItems.groupBy { it.category }
        val sb = StringBuilder("\n\n## 记忆上下文\n")

        for ((category, items) in grouped) {
            sb.append("\n【${category.label}】\n")
            items.forEach { item ->
                val scope = if (item.appName != null) "[${item.appName}] " else ""
                sb.append("- $scope${item.key}: ${item.value}\n")
            }
        }

        return sb.toString()
    }

    /** 清空所有记忆 */
    fun clearAll() {
        saveAll(emptyList())
        XLog.i(TAG, "已清空所有记忆")
    }
}
