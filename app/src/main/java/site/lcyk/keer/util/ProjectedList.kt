package site.lcyk.keer.util

import java.util.AbstractList

class ProjectedList<T> private constructor(
    private val delegate: List<T>,
) : AbstractList<T>() {
    override val size: Int
        get() = delegate.size

    override fun get(index: Int): T = delegate[index]

    override fun equals(other: Any?): Boolean {
        return other is ProjectedList<*> && delegate === other.delegate
    }

    override fun hashCode(): Int {
        return System.identityHashCode(delegate)
    }

    companion object {
        private val EMPTY = ProjectedList(emptyList<Any?>())

        @Suppress("UNCHECKED_CAST")
        fun <T> empty(): ProjectedList<T> = EMPTY as ProjectedList<T>

        fun <T> wrap(items: List<T>): ProjectedList<T> {
            if (items.isEmpty()) {
                return empty()
            }
            if (items is ProjectedList<T>) {
                return items
            }
            return ProjectedList(items)
        }
    }
}

fun <T> reuseOrWrapProjectedList(
    previous: ProjectedList<T>,
    nextItems: List<T>,
): ProjectedList<T> {
    if (previous.size == nextItems.size) {
        var identical = true
        for (index in nextItems.indices) {
            if (previous[index] !== nextItems[index]) {
                identical = false
                break
            }
        }
        if (identical) {
            return previous
        }
    }
    return ProjectedList.wrap(nextItems)
}
