package com.intellij.debugmap.model

import com.intellij.debugmap.generateNanoId

/**
 * Base class for a location-based definition owned by a topic.
 * [line] is 0-based (matching [com.intellij.xdebugger.breakpoints.XLineBreakpoint.getLine]).
 */
abstract class LocationDef(
  open val topicId: Int,
  open val fileUrl: String,
  open val line: Int,
  open val name: String? = null,
  /** Stable random primary key. Generated once on first creation; preserved across copy() and upserts. */
  open val id: String = generateNanoId(),
  /** Structural PSI path of the anchor line (e.g. "MyClass:myMethod"), null if unavailable. */
  open val logicalLocation: String? = null,
  /** Trimmed source text of the anchor line, null if unavailable. */
  open val content: String? = null,
  /** PSI token texts on the anchor line (internal use only; not persisted or exposed via MCP). */
  open val linePsiStrings: List<String> = emptyList(),
  open val isStale: Boolean = false,
) : Comparable<LocationDef> {

  /** Returns true if [other] refers to the same breakable/bookmarkable position as this def. */
  open fun sameLocation(other: LocationDef): Boolean = fileUrl == other.fileUrl && line == other.line

  override fun compareTo(other: LocationDef): Int =
    compareValuesBy(
      this, other,
      { it.fileUrl.substringAfterLast('/') },
      { it.fileUrl },
      { it.line },
    )
}
