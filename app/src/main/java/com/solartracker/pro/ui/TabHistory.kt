package com.solartracker.pro.ui

/**
 * Back stack of visited bottom-navigation tabs (tab indices). Pure logic, kept small and testable:
 * the system Back button walks back through the tabs instead of closing the app.
 */
internal object TabHistory {
    const val MAX_SIZE = 16

    /** History after moving from [current] to [next]; a tab appears at most once (most recent visit wins). */
    fun push(history: List<Int>, current: Int, next: Int): List<Int> =
        if (current == next) history else (history.filter { it != current } + current).takeLast(MAX_SIZE)

    /**
     * Where Back leads from [current]: the previously visited tab, or [home] when there is no history.
     * Returns null when already on [home] with nothing to go back to (the app may exit).
     */
    fun back(history: List<Int>, current: Int, home: Int): Pair<Int, List<Int>>? {
        val remaining = history.filter { it != current }
        return when {
            remaining.isNotEmpty() -> remaining.last() to remaining.dropLast(1)
            current != home -> home to emptyList()
            else -> null
        }
    }
}
