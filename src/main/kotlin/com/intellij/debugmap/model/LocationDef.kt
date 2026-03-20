package com.intellij.debugmap.model

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
  open val id: String = java.util.UUID.randomUUID().toString(),
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
