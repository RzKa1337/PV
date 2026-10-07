package com.solartracker.pro.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabHistoryTest {
    @Test
    fun backReturnsToPreviouslyVisitedTabs() {
        var history = emptyList<Int>()
        history = TabHistory.push(history, current = 0, next = 3)
        history = TabHistory.push(history, current = 3, next = 5)
        assertEquals(listOf(0, 3), history)
        val (tab1, h1) = TabHistory.back(history, current = 5, home = 0)!!
        assertEquals(3, tab1)
        val (tab2, h2) = TabHistory.back(h1, current = tab1, home = 0)!!
        assertEquals(0, tab2)
        assertNull(TabHistory.back(h2, current = tab2, home = 0))
    }

    @Test
    fun selectingSameTabDoesNotGrowHistory() {
        assertEquals(listOf(1), TabHistory.push(listOf(1), current = 2, next = 2))
    }

    @Test
    fun revisitedTabMovesToTheTopAndHistoryIsBounded() {
        assertEquals(listOf(2, 1), TabHistory.push(listOf(1, 2), current = 1, next = 4))
        val long = (0 until 40).fold(emptyList<Int>()) { h, i -> TabHistory.push(h, current = i, next = i + 1) }
        assertEquals(TabHistory.MAX_SIZE, long.size)
    }

    @Test
    fun withoutHistoryBackGoesHomeFirst() {
        assertEquals(0 to emptyList<Int>(), TabHistory.back(emptyList(), current = 7, home = 0))
    }
}
