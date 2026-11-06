package com.voicetodo.speech

/**
 * 豆包（火山引擎）新版控制台的语音识别配置。
 *
 * [apiKey] 只随加密存储读写；[toString] 永远不返回密钥。
 */
data class DoubaoSpeechSettings(
    val apiKey: String,
    val resourceId: String = DEFAULT_RESOURCE_ID,
    val uploadAcknowledged: Boolean = false,
) {
    fun normalized(): DoubaoSpeechSettings = copy(
        apiKey = apiKey.trim(),
        resourceId = resourceId.trim(),
    )

    fun validate(): DoubaoSpeechSettings {
        val clean = normalized()
        require(clean.apiKey.isNotEmpty()) { "请填写豆包语音 API Key" }
        require(clean.resourceId.isNotEmpty()) { "请填写豆包语音资源 ID" }
        return clean
    }

    override fun toString(): String =
        "DoubaoSpeechSettings(apiKey=***, resourceId=$resourceId, " +
            "uploadAcknowledged=$uploadAcknowledged)"

    companion object {
        const val DEFAULT_RESOURCE_ID = "volc.seedasr.sauc.duration"
        const val LEGACY_RESOURCE_ID = "volc.bigasr.sauc.duration"
        const val ENDPOINT = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
    }
}
