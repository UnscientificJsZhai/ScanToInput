package com.unscientificjszhai.scantoinput.widget

/**
 * 单个逻辑 token 的已测量文字几何，不依赖绘图平台。
 * @property width 包含内边距、且不超过测量上限的宽度。
 * @property height 包含内边距的高度。
 * @property baseline 第一行文字相对 token 顶部的基线。
 * @property occupiesWholeLine 是否独占一条流式布局行。
 */
data class TokenTextMetrics(
    val width: Float,
    val height: Float,
    val baseline: Float,
    val occupiesWholeLine: Boolean
)
