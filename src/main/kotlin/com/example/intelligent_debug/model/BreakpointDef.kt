package com.example.intelligent_debug.model

/**
 * A breakpoint definition owned by a group.
 * [line] is 0-based (matching [com.intellij.xdebugger.breakpoints.XLineBreakpoint.getLine]).
 *
 * Natural order: file name alphabetically, then by [line].
 * [compareTo] returns 0 iff (fileUrl, line) are equal — used by [java.util.TreeSet] for uniqueness.
 */
data class BreakpointDef(
  val groupId: Int,
  val fileUrl: String,
  val line: Int,
  val typeId: String = "java-line",
  val condition: String? = null,
  val logExpression: String? = null,
  val name: String? = null,
) : Comparable<BreakpointDef> {

  override fun compareTo(other: BreakpointDef): Int =
    compareValuesBy(
      this, other,
      { it.fileUrl.substringAfterLast('/') },
      { it.fileUrl },
      { it.line },
    )
}
