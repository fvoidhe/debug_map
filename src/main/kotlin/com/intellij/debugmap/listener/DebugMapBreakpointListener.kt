package com.intellij.debugmap.listener

import com.intellij.debugmap.DebugMapService
import com.intellij.debugmap.manager.column
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
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointListener
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.impl.breakpoints.XBreakpointBase

/** Keeps [DebugMapService] in sync with IDE breakpoint and bookmark lifecycle events. */
class DebugMapBreakpointListener(private val project: Project) : XBreakpointListener<XBreakpoint<*>>, BookmarksListener {

  private val service get() = DebugMapService.getInstance(project)

  override fun breakpointAdded(breakpoint: XBreakpoint<*>) {
    if (breakpoint !is XLineBreakpoint<*>) return
    val activeTopicId = service.getActiveTopicId() ?: return
    val column = breakpoint.column(service.ideManager)
    if (service.findBreakpointByLocation(breakpoint.fileUrl, breakpoint.line, column, activeTopicId) != null) return
    service.addBreakpointByIde(activeTopicId, breakpoint.toDef(activeTopicId))
  }

  override fun breakpointRemoved(breakpoint: XBreakpoint<*>) {
    if (breakpoint !is XLineBreakpoint<*>) return
    val column = breakpoint.column(service.ideManager)
    if (service.consumeSuppressedBreakpointRemoval(breakpoint.fileUrl, breakpoint.line, column)) return
    val activeTopicId = service.getActiveTopicId() ?: return
    service.removeBreakpointByIde(activeTopicId, breakpoint.fileUrl, breakpoint.line, column)
  }

  override fun breakpointChanged(breakpoint: XBreakpoint<*>) {
    if (breakpoint !is XLineBreakpoint<*>) return
    val activeTopicId = service.getActiveTopicId() ?: return

    val column = breakpoint.column(service.ideManager)
    val existing = service.findBreakpointByLocation(breakpoint.fileUrl, breakpoint.line, column, activeTopicId)

    // Fast path: position unchanged, only properties (condition, log, etc.) changed.
    if (existing != null) {
      val rawDef = breakpoint.toDef(activeTopicId)
      val def = rawDef.copy(id = existing.id, name = rawDef.name ?: existing.name)
      service.updateBreakpointByIde(def)
      return
    }

    // Line changed: the IDE snapped the breakpoint to a different line (e.g. moved to nearest executable line).
    // Find the stored non-stale def in this file whose position is no longer present in the IDE,
    // then move it to the new line — preserving id, name, and all other properties.
    // If the target line already has a non-stale def in this topic, moving would create a duplicate;
    // mark the orphaned def as stale instead.
    val idePositionsInFile = XDebuggerManager.getInstance(project).breakpointManager
      .allBreakpoints
      .filterIsInstance<XLineBreakpoint<*>>()
      .filter { it.fileUrl == breakpoint.fileUrl }
      .mapTo(HashSet()) { it.line to it.column(service.ideManager) }
    val movedDef = service.getTopicBreakpoints(activeTopicId)
                     .firstOrNull { it.fileUrl == breakpoint.fileUrl && !it.isStale && (it.line to it.column) !in idePositionsInFile }
                   ?: return
    if (service.moveBreakpointLine(movedDef, breakpoint.line) == null) {
      service.markBreakpointStale(movedDef.id)
    }
  }


  fun XLineBreakpoint<*>.toDef(topicId: Int): BreakpointDef {
    val master = service.ideManager.getMasterBreakpoint(this)
    val masterDef = master?.let { m ->
      service.getBreakpointsByFile(m.fileUrl)
        .firstOrNull { it.line == m.line && it.column == m.column(service.ideManager) }
    }
    return BreakpointDef(
      topicId = topicId,
      fileUrl = fileUrl,
      line = line,
      column = column(service.ideManager),
      typeId = type.id,
      condition = conditionExpression?.expression,
      logExpression = logExpressionObject?.expression,
      name = (this as? XBreakpointBase<*, *, *>)?.getUserDescription(),
      enabled = isEnabled,
      logMessage = isLogMessage,
      logStack = isLogStack,
      suspendPolicy = suspendPolicy.name,
      masterBreakpointId = masterDef?.id,
      masterLeaveEnabled = master?.let { service.ideManager.isLeaveEnabled(this) },
    )
  }

  // region BookmarksListener

  override fun bookmarkAdded(group: BookmarkGroup, bookmark: Bookmark) {
    if (bookmark !is LineBookmark) return
    service.getActiveTopicId() ?: return
    val topicId = service.getTopicIdByName(group.name) ?: service.createTopic(group.name)
    if (service.findBookmarkByLocation(bookmark.file.url, bookmark.line, topicId) != null) return
    val def = bookmark.toDef(topicId, group)
    service.addBookmarkByIde(topicId, def)
  }

  override fun bookmarkRemoved(group: BookmarkGroup, bookmark: Bookmark) {
    if (bookmark !is LineBookmark) return
    if (service.consumeSuppressedBookmarkRemoval(bookmark.file.url, bookmark.line)) return
    service.getActiveTopicId() ?: return
    val topicId = service.getTopicIdByName(group.name) ?: return

    service.removeBookmarkByIde(topicId, bookmark.file.url, bookmark.line)
  }

  override fun bookmarkChanged(group: BookmarkGroup, bookmark: Bookmark) {
    if (bookmark !is LineBookmark) return
    service.getActiveTopicId() ?: return

    val topicId = service.getTopicIdByName(group.name) ?: service.createTopic(group.name)
    val def = bookmark.toDef(topicId, group)
    val existing = service.findBookmarkByLocation(bookmark.file.url, bookmark.line, topicId)
    if (existing != null) {
      service.updateBookmarkByIde(def.copy(id = existing.id))
    }
    else {
      service.addBookmarkByIde(topicId, def)
    }
  }

  override fun bookmarkTypeChanged(bookmark: Bookmark) {
    if (bookmark !is LineBookmark) return
    service.getActiveTopicId() ?: return
    val existing = service.findBookmarkByLocation(bookmark.file.url, bookmark.line) ?: return

    val newType = BookmarksManager.getInstance(project)?.getType(bookmark) ?: BookmarkType.DEFAULT
    service.updateBookmarkByIde(existing.copy(type = newType))
  }

  private fun LineBookmark.toDef(topicId: Int, bookmarkGroup: BookmarkGroup) = BookmarkDef(
    topicId = topicId,
    fileUrl = file.url,
    line = line,
    name = bookmarkGroup.getDescription(this),
    type = BookmarksManager.getInstance(project)?.getType(this) ?: BookmarkType.DEFAULT,
  )

  // endregion
}
