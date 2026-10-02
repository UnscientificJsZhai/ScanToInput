package com.unscientificjszhai.scantoinput.widget

/** 提供与平台无关的 token 文字测量边界。 */
fun interface TokenTextMeasurer {
    /**
     * 测量单个逻辑 token，平台实现可同时缓存绘制布局。
     * @param index token 在当前列表中的索引。
     * @param text 保持原样的 token 文本。
     * @param maximumWidth 包含 token 内边距的最大允许宽度，始终大于零。
     * @return 宽度不超过上限、宽高非负的测量结果。
     */
    fun measure(index: Int, text: String, maximumWidth: Float): TokenTextMetrics
}
