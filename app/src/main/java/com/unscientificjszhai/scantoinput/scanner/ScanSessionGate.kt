package com.unscientificjszhai.scantoinput.scanner

/**
 * 相机请求的纯业务代际门闩。调用者在同一状态锁中执行判断和发布/交付。
 * 区分正在绑定和已绑定，避免 bind 返回之前接受帧。
 */
internal class ScanSessionGate {
    private var sequence = 0L
    private var active: Long? = null
    private var bound = false
    private var released = false

    /**
     * 创建新请求；重复 start 不替换当前请求或回调。
     * @return 新请求身份；已运行或终态释放时为 null。
     */
    fun start(): Long? {
        if (released || active != null) return null
        val generation = ++sequence
        active = generation
        bound = false
        return generation
    }

    /**
     * 验证异步流程仍属于当前请求。
     * @param generation 捕获的请求身份。
     * @return 是否仍允许该请求继续绑定。
     */
    fun isCurrent(generation: Long): Boolean = active == generation

    /**
     * bind 成功返回后，把仍有效的请求置为可交付状态。
     * @param generation 完成绑定的请求身份。
     * @return 是否接受本次绑定发布。
     */
    fun publish(generation: Long): Boolean {
        if (!isCurrent(generation)) return false
        bound = true
        return true
    }

    /**
     * 核对是否允许开始识别或交付结果。
     * @param generation 安装分析器时捕获的请求身份。
     * @return 请求是否当前有效且绑定完成。
     */
    fun canDeliver(generation: Long): Boolean = isCurrent(generation) && bound

    /** 立即失效当前请求，后续旧回调不能重新发布它。 */
    fun stop() {
        active = null
        bound = false
    }

    /** 进入不可再次启动的释放终态。 */
    fun release() {
        stop()
        released = true
    }
}
