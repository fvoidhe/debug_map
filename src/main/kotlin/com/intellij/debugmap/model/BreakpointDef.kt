package com.intellij.debugmap.model

import com.intellij.debugmap.generateNanoId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

data class BreakpointDef(
  override val topicId: Int,
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
  /** Whether to log a full call-stack trace to the console. null means IDE default (false). */
  val logStack: Boolean? = null,
  /** Suspend policy name: "ALL", "THREAD", or "NONE". null means IDE default ("ALL"). */
  val suspendPolicy: String? = null,
  /** Stable id of the master breakpoint this one depends on, or null if no dependency. */
  val masterBreakpointId: String? = null,
  /** If true, this breakpoint stays enabled after the master fires; if false, fires once then disables. */
  val masterLeaveEnabled: Boolean? = null,
  override val id: String = generateNanoId(),
  override val logicalLocation: String? = null,
  override val content: String? = null,
  override val linePsiStrings: List<String> = emptyList(),
  override val isStale: Boolean = false,
) : LocationDef(topicId, fileUrl, line, name, id) {

  override fun sameLocation(other: LocationDef): Boolean =
    other is BreakpointDef && fileUrl == other.fileUrl && line == other.line && column == other.column

  fun toJson(): JsonObject = buildJsonObject {
    put("fileUrl", JsonPrimitive(fileUrl))
    put("line", JsonPrimitive(line))
    if (column != 0) put("column", JsonPrimitive(column))
    put("typeId", JsonPrimitive(typeId))
    condition?.let { put("condition", JsonPrimitive(it)) }
    logExpression?.let { put("logExpression", JsonPrimitive(it)) }
    name?.let { put("name", JsonPrimitive(it)) }
    enabled?.let { put("enabled", JsonPrimitive(it)) }
    logMessage?.let { put("logMessage", JsonPrimitive(it)) }
    logStack?.let { put("logStack", JsonPrimitive(it)) }
    suspendPolicy?.let { put("suspendPolicy", JsonPrimitive(it)) }
    masterBreakpointId?.let { put("masterBreakpointId", JsonPrimitive(it)) }
    masterLeaveEnabled?.let { put("masterLeaveEnabled", JsonPrimitive(it)) }
    content?.let { put("content", JsonPrimitive(it)) }
    if (linePsiStrings.isNotEmpty()) put("linePsiStrings", JsonArray(linePsiStrings.map { JsonPrimitive(it) }))
    if (isStale) put("isStale", JsonPrimitive(true))
  }

  companion object {
    /** Parses a breakpoint from JSON. Throws [IllegalArgumentException] if required fields are missing. */
    fun fromJson(obj: JsonObject): BreakpointDef {
      val fileUrl = obj["fileUrl"]?.jsonPrimitive?.contentOrNull
        ?: throw IllegalArgumentException("missing fileUrl")
      val line = obj["line"]?.jsonPrimitive?.intOrNull
        ?: throw IllegalArgumentException("missing line")
      return BreakpointDef(
        topicId = 0,
        fileUrl = fileUrl,
        line = line,
        column = obj["column"]?.jsonPrimitive?.intOrNull ?: 0,
        typeId = obj["typeId"]?.jsonPrimitive?.contentOrNull ?: "java-line",
        condition = obj["condition"]?.jsonPrimitive?.contentOrNull,
        logExpression = obj["logExpression"]?.jsonPrimitive?.contentOrNull,
        name = obj["name"]?.jsonPrimitive?.contentOrNull,
        enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull,
        logMessage = obj["logMessage"]?.jsonPrimitive?.booleanOrNull,
        logStack = obj["logStack"]?.jsonPrimitive?.booleanOrNull,
        suspendPolicy = obj["suspendPolicy"]?.jsonPrimitive?.contentOrNull,
        masterBreakpointId = obj["masterBreakpointId"]?.jsonPrimitive?.contentOrNull,
        masterLeaveEnabled = obj["masterLeaveEnabled"]?.jsonPrimitive?.booleanOrNull,
      )
    }
  }
}
