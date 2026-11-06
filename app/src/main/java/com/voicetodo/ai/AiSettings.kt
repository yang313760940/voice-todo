package com.voicetodo.ai

import java.net.URI
import java.net.URL
import java.time.Instant
import java.time.ZoneId

/**
 * 用户自填的 OpenAI 兼容服务配置。
 *
 * 密钥只随加密存储读写；[toString] 永远不返回密钥，避免被日志或错误报告意外带出。
 */
data class AiSettings(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
) {
    fun normalized(): AiSettings = copy(
        baseUrl = baseUrl.trim().trimEnd('/'),
        model = model.trim(),
        apiKey = apiKey.trim(),
    )

    fun validate(): AiSettings {
        val clean = normalized()
        require(clean.baseUrl.isNotEmpty()) { "请填写 AI 服务地址" }
        val uri = runCatching { URI(clean.baseUrl) }.getOrNull()
            ?: throw IllegalArgumentException("AI 服务地址格式无效")
        require(uri.host.orEmpty().isNotBlank()) { "AI 服务地址缺少主机名" }
        val secure = uri.scheme.equals("https", ignoreCase = true)
        val localDebug = uri.scheme.equals("http", ignoreCase = true) &&
            uri.host.lowercase() in LOCAL_HTTP_HOSTS
        require(secure || localDebug) {
            "为保护密钥，请使用 https 地址（本机调试可用 127.0.0.1）"
        }
        require(clean.model.isNotEmpty()) { "请填写模型名称" }
        require(clean.apiKey.isNotEmpty()) { "请填写 API 密钥" }
        return clean
    }

    /** 实际请求地址；用户可填 `https://host/v1`，也可直接填到 `/chat/completions`。 */
    fun endpoint(): URL {
        val clean = normalized()
        val path = if (clean.baseUrl.endsWith("/chat/completions")) clean.baseUrl
        else "${clean.baseUrl}/chat/completions"
        return URL(path)
    }

    override fun toString(): String =
        "AiSettings(baseUrl=$baseUrl, model=$model, apiKey=***)"

    private companion object {
        val LOCAL_HTTP_HOSTS = setOf("127.0.0.1", "localhost")
    }
}

/** 一次用户主动整理所需的上下文；不包含历史事项或录音。 */
data class AiPromptContext(
    val now: Instant,
    val zoneId: ZoneId,
    /** 当前已有清单名称；只用于 AI 精确匹配，不发送事项内容。 */
    val listNames: List<String> = emptyList(),
)
