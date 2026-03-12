package com.intellij.debugmap.model

data class BreakpointDef(
  override val groupId: Int,
  override val fileUrl: String,
  override val line: Int,
  /** Zero-based column; 0 = whole-line breakpoint, positive = inline (lambda) breakpoint. */
  val column: Int = 0,
  val typeId: String = "java-line",
  val condition: String? = null,
  val logExpression: String? = null,
  override val name: String? = null,
  /** null means IDE default (true). */
  val enabled: Boolean? = null,
  /** Whether to log a standard hit message to the console. null means IDE default (false). */
  val logMessage: Boolean? = null,
  /** Suspend policy name: "ALL", "THREAD", or "NONE". null means IDE default ("ALL"). */
  val suspendPolicy: String? = null,
  /** fileUrl of the master breakpoint this one depends on, or null if no dependency. */
  val masterFileUrl: String? = null,
  /** Zero-based line of the master breakpoint, or null if no dependency. */
  val masterLine: Int? = null,
  /** If true, this breakpoint stays enabled after the master fires; if false, fires once then disables. */
  val masterLeaveEnabled: Boolean? = null,
) : LocationDef(groupId, fileUrl, line, name) {

  override fun sameLocation(other: LocationDef): Boolean =
    other is BreakpointDef && fileUrl == other.fileUrl && line == other.line && column == other.column
}
