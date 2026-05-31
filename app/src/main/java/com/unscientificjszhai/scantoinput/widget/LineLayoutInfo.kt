package com.unscientificjszhai.scantoinput.widget

/**
 * 每一行 Token 的布局区间信息。
 *
 * 存储每行在 Y 轴的起始和结束边界，以及该行包含的 Token 索引范围，用于二分法快速定位行。
 *
 * @property top 该行在 Y 轴的顶边位置。
 * @property bottom 该行在 Y 轴的底边位置。
 * @property firstTokenIndex 该行第一个 Token 的全局索引。
 * @property lastTokenIndex 该行最后一个 Token 的全局索引（包含）。
 */
data class LineLayoutInfo(
    val top: Float,
    val bottom: Float,
    val firstTokenIndex: Int,
    val lastTokenIndex: Int
)
