package com.example.chameleon

import com.example.chameleon.log.LogKind
import com.example.chameleon.log.LogStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LogStore] 的单测：容量裁剪与序号分配。
 *
 * 日志 id 被日志页 `LazyColumn` 用作 item key，因此**必须稳定递增且不重复**——
 * 一旦重复，滚动与重组就会出现错位。
 */
class LogStoreTest {

    @Test
    fun append后按序累积() {
        val store = LogStore()

        store.append(LogKind.TX, "first")
        store.append(LogKind.RX, "second")

        assertEquals(2, store.log.value.size)
        assertEquals("first", store.log.value[0].text)
        assertEquals("second", store.log.value[1].text)
        assertEquals(LogKind.TX, store.log.value[0].kind)
    }

    @Test
    fun 超出容量丢弃最旧的并保留最新() {
        val store = LogStore(capacity = 3)

        repeat(5) { store.append(LogKind.INFO, "line-$it") }

        val texts = store.log.value.map { it.text }
        assertEquals(listOf("line-2", "line-3", "line-4"), texts)
    }

    @Test
    fun 日志id单调递增不重复() {
        val store = LogStore(capacity = 3)
        val ids = mutableListOf<Long>()

        repeat(6) {
            store.append(LogKind.INFO, "line-$it")
            ids += store.log.value.last().id
        }

        assertEquals(ids, ids.sorted())
        assertTrue("日志 id 不能重复（LazyColumn 用其做 key）", ids.toSet().size == ids.size)
    }

    @Test
    fun clear清空全部() {
        val store = LogStore().apply { append(LogKind.ERROR, "boom") }

        store.clear()

        assertTrue(store.log.value.isEmpty())
    }
}
