package com.unscientificjszhai.scantoinput.widget

/**
 * 高性能 Token 几何布局信息。
 *
 * 存储单个 Token 在控件坐标系中的具体位置和尺寸，避免在绘制和触摸高频路径上重复测量。
 *
 * @property x Token 绘制的起始水平坐标（包含左内边距）。
 * @property y Token 绘制的起始垂直坐标（包含上内边距）。
 * @property width Token 的总宽度（包含水平内边距）。
 * @property height Token 的总高度（包含垂直内边距）。
 * @property baseline 文本在其对应 Token 局部坐标系中绘制的基准线偏移值。
 */
data class TokenLayoutInfo(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val baseline: Float
)
