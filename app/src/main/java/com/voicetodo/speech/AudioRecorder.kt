package com.voicetodo.speech

/**
 * 一次只持有一段临时音频的录音边界。
 *
 * 实现不得把音频上传或长期落盘；[cancel] 与 [release] 必须丢弃仍持有的采样。
 */
interface AudioRecorder {
    val sampleRate: Int

    /** 开始一次新录音；已经占用或硬件不可用时返回 false。 */
    fun start(): Boolean

    /** 结束当前录音并移交采样；实现内部不得继续保留副本。 */
    fun stop(): FloatArray

    /** 结束当前录音并丢弃采样。 */
    fun cancel()

    /** 释放麦克风及全部临时缓冲。 */
    fun release()
}
