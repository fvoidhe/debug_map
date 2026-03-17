package com.intellij.debugmap.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks a list of recently visited [LocationDef] items, capped at [maxSize].
 *
 * Adding an item moves it to the front and removes any existing entry matching [isSame].
 * Call [clear] to wipe the list (e.g. on debug-session end).
 */
class RecentLocationTracker<T : LocationDef>(
  private val maxSize: Int = 10,
  private val isSame: (T, T) -> Boolean,
) {
  private val _recent = MutableStateFlow<List<T>>(emptyList())
  val recent: StateFlow<List<T>> = _recent.asStateFlow()

  fun add(def: T) {
    val current = _recent.value.toMutableList()
    current.removeAll { isSame(it, def) }
    current.add(0, def)
    if (current.size > maxSize) current.removeAt(current.size - 1)
    _recent.value = current
  }

  fun clear() {
    _recent.value = emptyList()
  }
}
