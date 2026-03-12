package com.intellij.debugmap.listener

import com.intellij.debugmap.DebugMapService
import com.intellij.debugmap.model.BookmarkDef
import com.intellij.debugmap.model.BreakpointDef
import com.intellij.ide.bookmark.Bookmark
import com.intellij.ide.bookmark.BookmarkGroup
import com.intellij.ide.bookmark.BookmarkType
import com.intellij.ide.bookmark.BookmarksListener
import com.intellij.ide.bookmark.BookmarksManager
import com.intellij.ide.bookmark.LineBookmark
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.debugmap.manager.column
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointListener
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.intellij.xdebugger.impl.breakpoints.XBreakpointBase

/** Keeps [DebugMapService] in sync with IDE breakpoint and bookmark lifecycle events. */
class DebugMapBreakpointListener(private val project: Project) : XBreakpointListener<XBreakpoint<*>>, BookmarksListener {

  private val service get() = DebugMapService.getInstance(project)

  override fun breakpointAdded(breakpoint: XBreakpoint<*>) {
    if (breakpoint !is XLineBreakpoint<*>) return
    val activeGroupId = service.getActiveGroupId() ?: return
    service.upsertBreakpointByIde(activeGroupId, breakpoint.toDef(activeGroupId))
  }

  override fun breakpointRemoved(breakpoint: XBreakpoint<*>) {
    if (breakpoint !is XLineBreakpoint<*>) return
    val activeGroupId = service.getActiveGroupId() ?: return
    service.removeBreakpointByIde(activeGroupId, breakpoint.fileUrl, breakpoint.line, breakpoint.column(service.ideManager))
  }

  override fun breakpointChanged(breakpoint: XBreakpoint<*>) {
    if (breakpoint !is XLineBreakpoint<*>) return
    val activeGroupId = service.getActiveGroupId() ?: return

    // Fast path: position unchanged, only properties (condition, log, etc.) changed.
    if (service.breakpointExists(breakpoint.fileUrl, breakpoint.line, breakpoint.column(service.ideManager))) {
      service.upsertBreakpointByIde(activeGroupId, breakpoint.toDef(activeGroupId))
      return
    }

    // Line changed (code inserted/deleted): the stored def still has the old line.
    // Find it by checking which stored (line, column) in this file is no longer in the IDE.
    val idePositionsInFile = XDebuggerManager.getInstance(project).breakpointManager
      .allBreakpoints
      .filterIsInstance<XLineBreakpoint<*>>()
      .filter { it.fileUrl == breakpoint.fileUrl }
      .mapTo(HashSet()) { it.line to it.column(service.ideManager) }
    val staleDef = service.getGroupBreakpoints(activeGroupId)
                     .firstOrNull { it.fileUrl == breakpoint.fileUrl && (it.line to it.column) !in idePositionsInFile }
                   ?: return
    service.removeBreakpointByIde(activeGroupId, staleDef.fileUrl, staleDef.line, staleDef.column)
    service.upsertBreakpointByIde(activeGroupId, breakpoint.toDef(activeGroupId))
  }


  fun XLineBreakpoint<*>.toDef(groupId: Int): BreakpointDef {
    val master = service.ideManager.getMasterBreakpoint(this)
    return BreakpointDef(
      groupId = groupId,
      fileUrl = fileUrl,
      line = line,
      column = column(service.ideManager),
      typeId = type.id,
      condition = conditionExpression?.expression,
      logExpression = logExpressionObject?.expression,
      name = (this as? XBreakpointBase<*, *, *>)?.getUserDescription(),
      enabled = isEnabled,
      logMessage = isLogMessage,
      suspendPolicy = suspendPolicy.name,
      masterFileUrl = master?.fileUrl,
      masterLine = master?.line,
      masterLeaveEnabled = master?.let { service.ideManager.isLeaveEnabled(this) },
    )
  }

  // region BookmarksListener

  override fun bookmarkAdded(group: BookmarkGroup, bookmark: Bookmark) {
    if (bookmark !is LineBookmark) return
    service.getActiveGroupId() ?: return

    val groupId = service.getGroupIdByName(group.name) ?: service.createGroup(group.name)
    val def = bookmark.toDef(groupId, group)
    service.upsertBookmarkByIde(groupId, def)
  }

  override fun bookmarkRemoved(group: BookmarkGroup, bookmark: Bookmark) {
    if (bookmark !is LineBookmark) return
    if (service.consumeSuppressedBookmarkRemoval(bookmark.file.url, bookmark.line)) return
    service.getActiveGroupId() ?: return
    val groupId = service.getGroupIdByName(group.name) ?: return

    service.removeBookmarkByIde(groupId, bookmark.file.url, bookmark.line)
  }

  override fun bookmarkChanged(group: BookmarkGroup, bookmark: Bookmark) {
    if (bookmark !is LineBookmark) return
    service.getActiveGroupId() ?: return

    val groupId = service.getGroupIdByName(group.name) ?: service.createGroup(group.name)
    val def = bookmark.toDef(groupId, group)
    service.upsertBookmarkByIde(groupId, def)
  }

  override fun bookmarkTypeChanged(bookmark: Bookmark) {
    if (bookmark !is LineBookmark) return
    service.getActiveGroupId() ?: return
    val groupId = service.getBookmarkGroupId(bookmark.file.url, bookmark.line) ?: return
    val existing = service.getGroupBookmarks(groupId)
                     .firstOrNull { it.fileUrl == bookmark.file.url && it.line == bookmark.line } ?: return

    val newType = BookmarksManager.getInstance(project)?.getType(bookmark) ?: BookmarkType.DEFAULT
    val def = existing.copy(type = newType)
    service.upsertBookmarkByIde(groupId, def)
  }

  private fun LineBookmark.toDef(groupId: Int, bookmarkGroup: BookmarkGroup) = BookmarkDef(
    groupId = groupId,
    fileUrl = file.url,
    line = line,
    name = bookmarkGroup.getDescription(this),
    type = BookmarksManager.getInstance(project)?.getType(this) ?: BookmarkType.DEFAULT,
  )

  // endregion
}
