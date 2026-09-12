package com.unscientificjszhai.scantoinput.widget

/**
 * 由调用方持有并重复写入的 token 索引范围。
 * @property firstIndex 首索引；空范围为 -1。
 * @property lastIndex 尾索引；空范围为 -1。
 */
data class TokenIndexRange(var firstIndex: Int = -1, var lastIndex: Int = -1)
