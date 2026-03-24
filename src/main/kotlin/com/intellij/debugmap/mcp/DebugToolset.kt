@file:Suppress("FunctionName", "unused")

package com.intellij.debugmap.mcp

import com.intellij.debugmap.DebugMapService
import com.intellij.debugmap.model.BreakpointDef
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.mcpserver.reportToolActivity
import com.intellij.mcpserver.toolsets.Constants
import com.intellij.mcpserver.util.resolveInProject
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

class DebugToolset : McpToolset {

  @McpTool(name = "debug_add_breakpoint")
  @McpDescription("""
        |Creates a new line breakpoint at the specified file and line.
        |Fails if a breakpoint already exists at that location — use debug_update_breakpoint to modify an existing one.
    """)
  suspend fun add_breakpoint(
    @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION)
    path: String,
    @McpDescription("1-based line number of the breakpoint")
    line: Int,
    @McpDescription("Source text of the line — validated against the file to catch stale references")
    content: String,
    @McpDescription("Human-readable label for this breakpoint")
    description: String,
    @McpDescription("Breakpoint topic name. Required. Creates the topic if it does not exist.")
    topic: String,
  ): BreakpointResult {
    currentCoroutineContext().reportToolActivity("Adding breakpoint at $path:$line")
    val project = currentCoroutineContext().project
    val service = DebugMapService.getInstance(project)

    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(project.resolveInProject(path))
               ?: mcpFail("File not found: $path")

    val lineZeroBased = resolveLineByContent(file, line - 1, content) ?: run {
      val actual = lineContent(file, line - 1)
      mcpFail("Line $line contains '${actual ?: ""}', not '$content'. Re-read the file and pass the exact source text of the target line.")
    }
    val topicId = service.getTopicIdByName(topic) ?: service.createTopic(topic)

    val existing = service.getTopicBreakpoints(topicId).firstOrNull { it.fileUrl == file.url && it.line == lineZeroBased }
    if (existing != null) mcpFail("A breakpoint already exists at $path:$line. Use debug_update_breakpoint to modify it.")

    if (!service.ideManager.canPutAt(file, lineZeroBased)) {
      mcpFail("Cannot set breakpoint at $path:$line — not a valid breakpoint location (no executable code on this line)")
    }

    val def = BreakpointDef(
      topicId = topicId,
      fileUrl = file.url,
      line = lineZeroBased,
      name = description.takeIf { it.isNotBlank() },
    )
    service.addBreakpointByToolWindow(topicId, def)
    service.promoteTopicToTop(topicId)

    return BreakpointResult(path = path, line = line, status = "created", id = def.id)
  }

  @McpTool(name = "debug_update_breakpoint")
  @McpDescription("""
        |Updates properties of an existing line breakpoint identified by its stable id.
        |Only the parameters you provide are changed; omitted parameters keep their current value.
        |
        |Dependency management:
        | - Provide dependsOnId to set a master-breakpoint dependency.
        | - Pass clearDependency=true to remove an existing dependency.
        | - Omit both to leave the dependency unchanged.
    """)
  suspend fun update_breakpoint(
    @McpDescription("Stable breakpoint id returned by debug_list_breakpoints or debug_add_breakpoint.")
    id: String,
    @McpDescription("Enable or disable the breakpoint")
    enabled: Boolean? = null,
    @McpDescription("Human-readable label for this breakpoint. Pass empty string to clear.")
    description: String? = null,
    @McpDescription("Condition expression that must evaluate to true for the breakpoint to fire. Pass empty string to clear.")
    condition: String? = null,
    @McpDescription("Expression to evaluate and log to the console when the breakpoint is hit. Pass empty string to clear.")
    logExpression: String? = null,
    @McpDescription("Log a standard hit notification message to the console when the breakpoint is reached.")
    logMessage: Boolean? = null,
    @McpDescription("Log a full call-stack trace to the console when the breakpoint is reached.")
    logStack: Boolean? = null,
    @McpDescription("Suspend policy: ALL, THREAD, or NONE.")
    suspendPolicy: String? = null,
    @McpDescription("Id of the master breakpoint to depend on. Omit to leave the dependency unchanged.")
    dependsOnId: String? = null,
    @McpDescription("Set to true to remove the existing master-breakpoint dependency.")
    clearDependency: Boolean = false,
    @McpDescription("If true (default), this breakpoint stays permanently enabled after master fires. If false, fires once then disables itself.")
    dependencyLeaveEnabled: Boolean = true,
    @McpDescription("New 1-based line number for the breakpoint. Requires content for validation. Omit to keep unchanged.")
    line: Int? = null,
    @McpDescription("Source text of the new line. Required when line is provided.")
    content: String? = null,
  ): BreakpointResult {
    val project = currentCoroutineContext().project
    val service = DebugMapService.getInstance(project)

    currentCoroutineContext().reportToolActivity("Updating breakpoint id=$id")
    val def = service.findBreakpointById(id) ?: mcpFail("No breakpoint found with id $id")

    if (line != null && content == null) {
      mcpFail("content is required when changing line")
    }

    val newLineZeroBased: Int = if (line != null) {
      val file = VirtualFileManager.getInstance().refreshAndFindFileByUrl(def.fileUrl)
                 ?: mcpFail("File not found: ${def.fileUrl}")
      val resolved = resolveLineByContent(file, line - 1, content!!) ?: run {
        val actual = lineContent(file, line - 1)
        mcpFail("Line $line contains '${actual ?: ""}', not '$content'. Re-read the file and pass the exact source text of the target line.")
      }
      if (!service.ideManager.canPutAt(file, resolved)) {
        mcpFail("Cannot set breakpoint at line $line — not a valid breakpoint location (no executable code on this line)")
      }
      resolved
    }
    else {
      def.line
    }

    // Resolve master dependency: set by id, clear explicitly, or leave unchanged.
    val (newMasterBreakpointId, newMasterLeaveEnabled) = when {
      clearDependency -> Pair(null, null)
      dependsOnId != null -> {
        service.findBreakpointById(dependsOnId) ?: mcpFail("Master breakpoint not found with id $dependsOnId")
        Pair(dependsOnId, dependencyLeaveEnabled)
      }
      else -> Pair(def.masterBreakpointId, def.masterLeaveEnabled)
    }

    val updatedDef = def.copy(
      line = newLineZeroBased,
      name = when {
        description == null -> def.name
        description.isBlank() -> null
        else -> description.trim()
      },
      enabled = enabled ?: def.enabled,
      condition = if (condition != null) condition.ifBlank { null } else def.condition,
      logExpression = if (logExpression != null) logExpression.ifBlank { null } else def.logExpression,
      logMessage = logMessage ?: def.logMessage,
      logStack = logStack ?: def.logStack,
      suspendPolicy = if (suspendPolicy != null) {
        when (suspendPolicy.uppercase()) {
          "ALL", "THREAD", "NONE" -> suspendPolicy.uppercase()
          else -> mcpFail("Invalid suspend policy '$suspendPolicy'. Valid values: ALL, THREAD, NONE")
        }
      }
      else def.suspendPolicy,
      masterBreakpointId = newMasterBreakpointId,
      masterLeaveEnabled = newMasterLeaveEnabled,
    )

    service.updateBreakpointByToolWindow(updatedDef)

    return BreakpointResult(path = def.fileUrl, line = newLineZeroBased + 1, status = "updated", id = id)
  }

  @McpTool(name = "debug_remove_breakpoint")
  @McpDescription("""
        |Removes the breakpoint identified by its stable id, or by file and line.
        |If topic is specified (path+line mode), removes only from that topic; otherwise removes from the active topic.
        |Reports not_found if no matching breakpoint exists.
    """)
  suspend fun remove_breakpoint(
    @McpDescription("Stable breakpoint id. When provided, path/line/topic are ignored.")
    id: String? = null,
    @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION)
    path: String = "",
    @McpDescription("1-based line number of the breakpoint to remove")
    line: Int = -1,
    @McpDescription("Breakpoint topic name. Required. Creates the topic if it does not exist.")
    topic: String? = null,
  ): BreakpointResult {
    val project = currentCoroutineContext().project
    val service = DebugMapService.getInstance(project)

    if (id != null) {
      currentCoroutineContext().reportToolActivity("Removing breakpoint id=$id")
      val def = service.findBreakpointById(id) ?: return BreakpointResult(path = "", line = -1, status = "not_found", id = id)
      service.removeBreakpointByToolWindow(def.topicId, def.fileUrl, def.line, def.column)
      return BreakpointResult(path = def.fileUrl, line = def.line + 1, status = "removed", id = id)
    }

    currentCoroutineContext().reportToolActivity("Removing breakpoint at $path:$line")
    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(project.resolveInProject(path))
               ?: mcpFail("File not found: $path")

    val lineZeroBased = line - 1
    val activeTopicId = resolveTopicId(service, topic)

    val exists = service.getTopicBreakpoints(activeTopicId).any { it.fileUrl == file.url && it.line == lineZeroBased }
    if (!exists) return BreakpointResult(path = path, line = line, status = "not_found", id = "")

    service.removeBreakpointByToolWindow(activeTopicId, file.url, lineZeroBased)

    return BreakpointResult(path = path, line = line, status = "removed", id = "")
  }

  @McpTool(name = "debug_list_breakpoints")
  @McpDescription("""
        |Lists line breakpoints, optionally filtered by topic and/or path substring.
        |Returns file URL, 1-based line number, topic name, and active flag.
        |Also returns enabled state, condition, log expression, log-message flag, suspend policy, source content, and any master-breakpoint dependency.
    """)
  suspend fun list_breakpoints(
    @McpDescription("Filter by topic name. Omit to include all topics.")
    topic: String? = null,
    @McpDescription("Filter by path substring. Omit to include all files.")
    path: String? = null,
  ): BreakpointListResult {
    currentCoroutineContext().reportToolActivity("Listing breakpoints")
    val project = currentCoroutineContext().project
    val service = DebugMapService.getInstance(project)

    val activeTopicId = service.getActiveTopicId()
    val items = mutableListOf<BreakpointInfo>()

    for (t in service.getTopics()) {
      if (topic != null && t.name != topic) continue
      val isActive = t.id == activeTopicId
      for (def in t.breakpoints) {
        if (path != null && !def.fileUrl.contains(path)) continue
        val vf = VirtualFileManager.getInstance().findFileByUrl(def.fileUrl)
        items.add(BreakpointInfo(
          id = def.id,
          path = def.fileUrl,
          line = def.line + 1,
          topic = t.name,
          active = isActive,
          enabled = def.enabled,
          description = def.name?.takeIf { it.isNotBlank() },
          condition = def.condition?.takeIf { it.isNotBlank() },
          logExpression = def.logExpression?.takeIf { it.isNotBlank() },
          logMessage = def.logMessage,
          suspendPolicy = def.suspendPolicy,
          content = vf?.let { lineContent(it, def.line) },
          dependsOnId = def.masterBreakpointId,
          dependencyLeaveEnabled = def.masterLeaveEnabled,
          status = def.status.name,
        ))
      }
    }

    return BreakpointListResult(breakpoints = items, total = items.size)
  }

  @McpTool(name = "debug_list_topics")
  @McpDescription("""
        |Lists all debug-map topics. Topics are shared between breakpoints and bookmarks.
        |Returns each topic's name, whether it is the active topic, its description, status (PIN/OPEN/CLOSE), and a count of its breakpoints and bookmarks.
        |Topics are returned in display order: PIN topics first, then OPEN, then CLOSE.
    """)
  suspend fun list_topics(): TopicListResult {
    currentCoroutineContext().reportToolActivity("Listing topics")
    val project = currentCoroutineContext().project
    val service = DebugMapService.getInstance(project)

    val activeTopicId = service.getActiveTopicId()
    val topics = service.getTopics()
      .map { t ->
        TopicInfo(
          name = t.name,
          active = t.id == activeTopicId,
          status = t.status.name,
          breakpointCount = t.breakpoints.size,
          bookmarkCount = t.bookmarks.size,
        )
      }
    return TopicListResult(topics = topics, total = topics.size)
  }

  // ----- helpers -----

  private fun resolveTopicId(service: DebugMapService, topic: String?): Int {
    return if (topic != null) {
      service.getTopicIdByName(topic) ?: mcpFail("Breakpoint topic not found: $topic")
    }
    else {
      service.getActiveTopicId() ?: mcpFail("No active topic")
    }
  }

  // ----- data classes -----

  @Serializable
  data class BreakpointResult(
    val path: String,
    val line: Int,
    /** "created" | "updated" | "removed" | "not_found" */
    val status: String,
    /** Stable primary key of the breakpoint. */
    val id: String = "",
  )

  @Serializable
  data class BreakpointInfo(
    /** Stable primary key — use this in debug_update_breakpoint and debug_remove_breakpoint instead of path+line. */
    val id: String,
    val path: String,
    val line: Int,
    val topic: String,
    val active: Boolean,
    val enabled: Boolean? = null,
    val description: String? = null,
    val condition: String? = null,
    val logExpression: String? = null,
    val logMessage: Boolean? = null,
    /** "ALL" | "THREAD" | "NONE" */
    val suspendPolicy: String? = null,
    val content: String? = null,
    val dependsOnId: String? = null,
    val dependencyLeaveEnabled: Boolean? = null,
    /** Read-only. "NORMAL" | "STALE". STALE means the stored line number is unreliable. */
    val status: String,
  )

  @Serializable
  data class BreakpointListResult(
    val breakpoints: List<BreakpointInfo>,
    val total: Int,
  )

  @Serializable
  data class TopicInfo(
    val name: String,
    val active: Boolean,
    val description: String? = null,
    /** "PIN" | "OPEN" | "CLOSE" */
    val status: String,
    val breakpointCount: Int,
    val bookmarkCount: Int,
  )

  @Serializable
  data class TopicListResult(
    val topics: List<TopicInfo>,
    val total: Int,
  )

}
