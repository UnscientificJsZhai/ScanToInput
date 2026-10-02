package com.unscientificjszhai.scantoinput.scanner

import org.junit.Assert.*
import org.junit.Test

/** 覆盖扫描请求的启动、绑定发布、停止和释放终态。 */
class ScanSessionGateTest {
    /** 旧请求无法在停止后重新绑定或交付；重复启动保留当前身份。 */
    @Test
    fun requestMustBeCurrentAndPublished() {
        val gate = ScanSessionGate()
        assertFalse(gate.canDeliver(0))
        val first = gate.start()!!
        assertNull(gate.start())
        assertTrue(gate.isCurrent(first))
        assertFalse(gate.canDeliver(first))
        assertTrue(gate.publish(first))
        assertTrue(gate.canDeliver(first))
        gate.stop()
        assertFalse(gate.canDeliver(first))
        assertFalse(gate.publish(first))
        val second = gate.start()!!
        assertTrue(second > first)
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.publish(second))
        gate.release()
        assertNull(gate.start())
        assertFalse(gate.canDeliver(second))
        gate.stop()
        gate.release()
        assertNull(gate.start())
    }
}
