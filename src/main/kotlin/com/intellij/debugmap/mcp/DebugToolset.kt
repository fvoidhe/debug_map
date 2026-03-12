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
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.impl.breakpoints.XBreakpointBase
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
    @McpDescription("Breakpoint group name. Defaults to the currently active group if omitted.")
    group: String,
  ): BreakpointResult {
    currentCoroutineContext().reportToolActivity("Adding breakpoint at $path:$line")
    val project = currentCoroutineContext().project
    val service = DebugMapService.getInstance(project)

    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(project.resolveInProject(path))
               ?: mcpFail("File not found: $path")

    val lineZeroBased = line - 1
    val activeGroupId = resolveGroupId(service, group)

    val actual = lineContent(file, lineZeroBased)
    if (actual == null || actual.trim() != content.trim()) {
      mcpFail("Line $line contains '${actual ?: ""}', not '$content'. Re-read the file and pass the exact source text of the target line.")
    }

    val existing = service.getGroupBreakpoints(activeGroupId).firstOrNull { it.fileUrl == file.url && it.line == lineZeroBased }
    if (existing != null) mcpFail("A breakpoint already exists at $path:$line. Use debug_update_breakpoint to modify it.")

    if (!service.ideManager.canPutAt(file, lineZeroBased)) {
      mcpFail("Cannot set breakpoint at $path:$line — not a valid breakpoint location (no executable code on this line)")
    }

    service.addBreakpointByToolWindow(activeGroupId, BreakpointDef(
      groupId = activeGroupId,
      fileUrl = file.url,
      line = lineZeroBased,
      name = description.takeIf { it.isNotBlank() },
    ))

    return BreakpointResult(path = path, line = line, status = "created")
  }

  @McpTool(name = "debug_update_breakpoint")
  @McpDescription("""
        |Updates properties of an existing line breakpoint identified by file and line.
        |Only the parameters you provide are changed; omitted parameters keep their current value.
        |
        |Dependency management:
        | - Provide dependsOnPath + dependsOnLine to make this breakpoint fire only after a master breakpoint has been hit.
        | - Pass dependsOnPath as an empty string to clear an existing dependency.
        | - Omit dependsOnPath entirely to leave the dependency unchanged.
    """)
  suspend fun update_breakpoint(
    @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION)
    path: String,
    @McpDescription("1-based line number of the breakpoint")
    line: Int,
    @McpDescription("Breakpoint group name. Defaults to the currently active group if omitted.")
    group: String? = null,
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
    @McpDescription("Path of the master breakpoint. Provide to set a dependency, pass empty string to clear, omit to leave unchanged.")
    dependsOnPath: String? = null,
    @McpDescription("1-based line number of the master breakpoint. Required when dependsOnPath is non-empty.")
    dependsOnLine: Int? = null,
    @McpDescription("If true (default), this breakpoint stays permanently enabled after master fires. If false, fires once then disables itself.")
    dependencyLeaveEnabled: Boolean = true,
  ): BreakpointResult {
    currentCoroutineContext().reportToolActivity("Updating breakpoint at $path:$line")
    val project = currentCoroutineContext().project
    val service = DebugMapService.getInstance(project)

    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(project.resolveInProject(path))
               ?: mcpFail("File not found: $path")

    val lineZeroBased = line - 1
    val activeGroupId = resolveGroupId(service, group)

    service.getGroupBreakpoints(activeGroupId).firstOrNull { it.fileUrl == file.url && it.line == lineZeroBased }
    ?: mcpFail("No breakpoint found at $path:$line")

    val bp = service.ideManager.findLineBreakpoint(file.url, lineZeroBased)
    if (bp != null) {
      enabled?.let { bp.setEnabled(it) }
      description?.let { (bp as? XBreakpointBase<*, *, *>)?.userDescription = it.ifBlank { null } }
      condition?.let { bp.setCondition(it.ifBlank { null }) }
      logExpression?.let { bp.setLogExpression(it.ifBlank { null }) }
      logMessage?.let { bp.setLogMessage(it) }
      logStack?.let { bp.setLogStack(it) }
      suspendPolicy?.let {
        bp.setSuspendPolicy(when (it.uppercase()) {
                              "ALL" -> SuspendPolicy.ALL
                              "THREAD" -> SuspendPolicy.THREAD
                              "NONE" -> SuspendPolicy.NONE
                              else -> mcpFail("Invalid suspend policy '$it'. Valid values: ALL, THREAD, NONE")
                            })
      }

      when {
        dependsOnPath == null -> { /* leave unchanged */
        }
        dependsOnPath.isBlank() -> service.ideManager.clearMasterBreakpoint(bp)
        else -> {
          val masterFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(project.resolveInProject(dependsOnPath))
                           ?: mcpFail("Master file not found: $dependsOnPath")
          val masterLine = dependsOnLine ?: mcpFail("dependsOnLine is required when dependsOnPath is provided")
          val master = service.ideManager.findLineBreakpoint(masterFile.url, masterLine - 1)
                       ?: mcpFail("Master breakpoint not found at $dependsOnPath:$masterLine")
          service.ideManager.setMasterBreakpoint(bp, master, dependencyLeaveEnabled)
        }
      }
    }

    return BreakpointResult(path = path, line = line, status = "updated")
  }

  @McpTool(name = "debug_remove_breakpoint")
  @McpDescription("""
        |Removes the breakpoint at the specified file and line.
        |If group is specified, removes only from that group; otherwise removes from the active group.
        |Reports not_found if no matching breakpoint exists.
    """)
  suspend fun remove_breakpoint(
    @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION)
    path: String,
    @McpDescription("1-based line number of the breakpoint to remove")
    line: Int,
    @McpDescription("Breakpoint group name. Defaults to the currently active group if omitted.")
    group: String? = null,
  ): BreakpointResult {
    currentCoroutineContext().reportToolActivity("Removing breakpoint at $path:$line")
    val project = currentCoroutineContext().project
    val service = DebugMapService.getInstance(project)

    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(project.resolveInProject(path))
               ?: mcpFail("File not found: $path")

    val lineZeroBased = line - 1
    val activeGroupId = resolveGroupId(service, group)

    val exists = service.getGroupBreakpoints(activeGroupId).any { it.fileUrl == file.url && it.line == lineZeroBased }
    if (!exists) return BreakpointResult(path = path, line = line, status = "not_found")

    service.removeBreakpointByToolWindow(activeGroupId, file.url, lineZeroBased)

    return BreakpointResult(path = path, line = line, status = "removed")
  }

  @McpTool(name = "debug_list_breakpoints")
  @McpDescription("""
        |Lists line breakpoints, optionally filtered by group and/or path substring.
        |Returns file URL, 1-based line number, group name, and active flag.
        |Also returns enabled state, condition, log expression, log-message flag, suspend policy, source content, and any master-breakpoint dependency.
    """)
  suspend fun list_breakpoints(
    @McpDescription("Filter by group name. Omit to include all groups.")
    group: String? = null,
    @McpDescription("Filter by path substring. Omit to include all files.")
    path: String? = null,
  ): BreakpointListResult {
    currentCoroutineContext().reportToolActivity("Listing breakpoints")
    val project = currentCoroutineContext().project
    val service = DebugMapService.getInstance(project)

    val activeGroupId = service.getActiveGroupId()
    val items = mutableListOf<BreakpointInfo>()

    for (g in service.getGroups()) {
      if (group != null && g.name != group) continue
      val isActive = g.id == activeGroupId
      for (def in g.breakpoints) {
        if (path != null && !def.fileUrl.contains(path)) continue
        val vf = VirtualFileManager.getInstance().findFileByUrl(def.fileUrl)
        items.add(BreakpointInfo(
          path = def.fileUrl,
          line = def.line + 1,
          group = g.name,
          active = isActive,
          enabled = def.enabled,
          description = def.name?.takeIf { it.isNotBlank() },
          condition = def.condition?.takeIf { it.isNotBlank() },
          logExpression = def.logExpression?.takeIf { it.isNotBlank() },
          logMessage = def.logMessage,
          suspendPolicy = def.suspendPolicy,
          content = vf?.let { lineContent(it, def.line) },
          dependsOnPath = def.masterFileUrl,
          dependsOnLine = def.masterLine?.let { it + 1 },
          dependencyLeaveEnabled = def.masterLeaveEnabled,
        ))
      }
    }

    return BreakpointListResult(breakpoints = items, total = items.size)
  }

  @McpTool(name = "debug_list_groups")
  @McpDescription("""
        |Lists all debug-map groups. Groups are shared between breakpoints and bookmarks.
        |Returns each group's name, whether it is the active group, and a count of its breakpoints and bookmarks.
    """)
  suspend fun list_groups(): GroupListResult {
    currentCoroutineContext().reportToolActivity("Listing groups")
    val project = currentCoroutineContext().project
    val service = DebugMapService.getInstance(project)

    val activeGroupId = service.getActiveGroupId()
    val groups = service.getGroups().map { g ->
      GroupInfo(
        name = g.name,
        active = g.id == activeGroupId,
        breakpointCount = g.breakpoints.size,
        bookmarkCount = g.bookmarks.size,
      )
    }
    return GroupListResult(groups = groups, total = groups.size)
  }

  // ----- helpers -----

  private fun resolveGroupId(service: DebugMapService, group: String?): Int {
    return if (group != null) {
      service.getGroupIdByName(group) ?: mcpFail("Breakpoint group not found: $group")
    }
    else {
      service.getActiveGroupId() ?: mcpFail("No active group")
    }
  }

  // ----- data classes -----

  @Serializable
  data class BreakpointResult(
    val path: String,
    val line: Int,
    /** "created" | "updated" | "removed" | "not_found" */
    val status: String,
  )

  @Serializable
  data class BreakpointInfo(
    val path: String,
    val line: Int,
    val group: String,
    val active: Boolean,
    val enabled: Boolean? = null,
    val description: String? = null,
    val condition: String? = null,
    val logExpression: String? = null,
    val logMessage: Boolean? = null,
    /** "ALL" | "THREAD" | "NONE" */
    val suspendPolicy: String? = null,
    val content: String? = null,
    val dependsOnPath: String? = null,
    val dependsOnLine: Int? = null,
    val dependencyLeaveEnabled: Boolean? = null,
  )

  @Serializable
  data class BreakpointListResult(
    val breakpoints: List<BreakpointInfo>,
    val total: Int,
  )

  @Serializable
  data class GroupInfo(
    val name: String,
    val active: Boolean,
    val breakpointCount: Int,
    val bookmarkCount: Int,
  )

  @Serializable
  data class GroupListResult(
    val groups: List<GroupInfo>,
    val total: Int,
  )

}
