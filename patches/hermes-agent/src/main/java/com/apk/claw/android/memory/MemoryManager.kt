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
        val updatedAt: Long = System.currentTimeMillis()
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
        cache = items
        KVUtils.putString(STORAGE_KEY, GSON.toJson(items))
    }

    // ==================== 增删改 ====================

    /** 保存一条记忆（key+appName 相同则更新） */
    fun save(
        category: Category,
        key: String,
        value: String,
        appName: String? = null,
        tags: List<String> = emptyList()
    ): MemoryItem {
        val items = loadAll().toMutableList()

        // 查找是否已存在相同 key+appName
        val existing = items.indexOfFirst { it.key == key && it.appName == appName && it.category == category }
        val item = if (existing >= 0) {
            items[existing].copy(value = value, tags = tags, updatedAt = System.currentTimeMillis())
        } else {
            MemoryItem(
                category = category,
                key = key,
                value = value,
                appName = appName,
                tags = tags
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

        val q = query.lowercase()
        return items.filter {
            it.key.lowercase().contains(q) ||
            it.value.lowercase().contains(q) ||
            it.tags.any { tag -> tag.lowercase().contains(q) } ||
            it.appName?.lowercase()?.contains(q) == true
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
     * - 总长度限制在 1500 字符以内
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

        // 按分类分组，格式化输出
        val grouped = relevant.groupBy { it.category }
        val sb = StringBuilder("\n\n## 记忆上下文\n")

        for ((category, items) in grouped) {
            sb.append("\n【${category.label}】\n")
            items.forEach { item ->
                val scope = if (item.appName != null) "[${item.appName}] " else ""
                sb.append("- $scope${item.key}: ${item.value}\n")
            }
        }

        // 限制长度
        val result = sb.toString()
        return if (result.length > 1500) result.take(1500) + "\n...(更多记忆未显示)" else result
    }

    /** 清空所有记忆 */
    fun clearAll() {
        saveAll(emptyList())
        XLog.i(TAG, "已清空所有记忆")
    }
}
