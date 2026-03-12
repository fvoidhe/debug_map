// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.debugmap.manager

import com.intellij.debugmap.DebugMapService
import com.intellij.debugmap.model.BookmarkDef
import com.intellij.debugmap.model.BreakpointDef
import com.intellij.ide.bookmark.BookmarkGroup
import com.intellij.ide.bookmark.BookmarkProvider
import com.intellij.ide.bookmark.BookmarksManager
import com.intellij.ide.bookmark.LineBookmark
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.impl.breakpoints.XBreakpointBase
import com.intellij.xdebugger.impl.breakpoints.XBreakpointManagerImpl
import com.intellij.xdebugger.impl.breakpoints.highlightRange

/**
 * Centralizes all interactions with [com.intellij.xdebugger.breakpoints.XBreakpointManager]
 * and [BookmarksManager].
 *
 * [XBreakpointManagerImpl] uses its own [java.util.concurrent.locks.ReentrantLock]
 * for all collection access, so callers do not need to wrap these calls in read/write actions.
 */
class BreakpointIdeManager(private val project: Project) {

  private val bpManager get() = XDebuggerManager.getInstance(project).breakpointManager

  private val depManager get() = (bpManager as XBreakpointManagerImpl).dependentBreakpointManager

  // ── Read operations ────────────────────────────────────────────────────────

  fun allLineBookmarks(): List<Pair<BookmarkGroup, LineBookmark>> {
    val manager = BookmarksManager.getInstance(project) ?: return emptyList()
    return manager.getGroups().flatMap { group ->
      group.getBookmarks().filterIsInstance<LineBookmark>().map { group to it }
    }
  }

  fun allLineBreakpoints(): List<XLineBreakpoint<*>> = ReadAction.nonBlocking<List<XLineBreakpoint<*>>> {
    bpManager.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>()
  }.executeSynchronously()

  fun findLineBreakpoint(fileUrl: String, lineZeroBased: Int, column: Int = 0): XLineBreakpoint<*>? {
    return allLineBreakpoints().firstOrNull { it.fileUrl == fileUrl && it.line == lineZeroBased && it.column(this) == column }
  }

  fun canPutAt(file: VirtualFile, lineZeroBased: Int): Boolean = ReadAction.nonBlocking<Boolean> {
    XDebuggerUtil.getInstance().getLineBreakpointTypes().any { it.canPutAt(file, lineZeroBased, project) }
  }.executeSynchronously()

  // ── Write operations ───────────────────────────────────────────────────────

  /**
   * Adds a line breakpoint, applying optional condition/logExpression from [def].
   * For inline (lambda) breakpoints ([BreakpointDef.column] > 0) the matching variant is selected;
   * falls back to whole-line if no variant matches the stored column.
   * Returns the created breakpoint, or null if no suitable type exists for this location.
   */
  fun addLineBreakpoint(file: VirtualFile, lineZeroBased: Int, def: BreakpointDef? = null): XLineBreakpoint<*>? =
    ReadAction.nonBlocking<XLineBreakpoint<*>?> {
      @Suppress("UNCHECKED_CAST")
      val type = XDebuggerUtil.getInstance().getLineBreakpointTypes()
                   .filter { it.canPutAt(file, lineZeroBased, project) }
                   .maxByOrNull { it.priority }
                   as? XLineBreakpointType<XBreakpointProperties<*>>
                 ?: return@nonBlocking null

      val properties = if (def != null && def.column > 0) {
        val position = XDebuggerUtil.getInstance().createPosition(file, lineZeroBased)
        val document = FileDocumentManager.getInstance().getDocument(file)
        val lineStart = if (document != null && position != null) document.getLineStartOffset(lineZeroBased) else -1
        type.computeVariants(project, position ?: XDebuggerUtil.getInstance().createPosition(file, lineZeroBased)!!)
          .filterNot { it.isMultiVariant }
          .firstOrNull { v -> v.highlightRange?.let { it.startOffset - lineStart == def.column } == true }
          ?.createProperties()
        ?: type.createBreakpointProperties(file, lineZeroBased)
      }
      else {
        type.createBreakpointProperties(file, lineZeroBased)
      }

      val bp = bpManager.addLineBreakpoint(type, file.url, lineZeroBased, properties)
      def?.condition?.let { bp.setCondition(it) }
      def?.logExpression?.let { bp.setLogExpression(it) }
      def?.name?.let { (bp as? XBreakpointBase<*, *, *>)?.userDescription = it }
      bp
    }.executeSynchronously()

  fun removeBreakpoint(bp: XLineBreakpoint<*>): Unit? = ReadAction.nonBlocking<Unit> {
    bpManager.removeBreakpoint(bp)
  }.executeSynchronously()

  fun renameBookmark(def: BookmarkDef, name: String) {
    val manager = BookmarksManager.getInstance(project) ?: return
    val provider = bookmarkProvider ?: return
    val bookmark = provider.createBookmark(mapOf("url" to def.fileUrl, "line" to "${def.line}")) ?: return
    val group = manager.getDefaultGroup() ?: return
    group.setDescription(bookmark, name)
  }

  fun renameGroup(oldName: String, newName: String): Unit? = ReadAction.nonBlocking<Unit> {
    BookmarksManager.getInstance(project)?.getGroup(oldName)?.name = newName
    (bpManager as? XBreakpointManagerImpl)?.defaultGroup = newName
  }.executeSynchronously()

  // ── Batch operations (used by checkout) ───────────────────────────────────

  fun addBreakpointDefs(breakpointDefs: List<BreakpointDef>) {
    val vfManager = VirtualFileManager.getInstance()
    for (breakpointDef in breakpointDefs) {
      val file = vfManager.findFileByUrl(breakpointDef.fileUrl) ?: continue
      if (findLineBreakpoint(breakpointDef.fileUrl, breakpointDef.line, breakpointDef.column) != null) continue
      addLineBreakpoint(file, breakpointDef.line, breakpointDef)
    }
  }

  fun removeBreakpointDefs(breakpointDefs: List<BreakpointDef>) {
    val existing = allLineBreakpoints()
    for (breakpointDef in breakpointDefs) {
      existing.firstOrNull {
        it.fileUrl == breakpointDef.fileUrl && it.line == breakpointDef.line
        && it.column(this) == breakpointDef.column
      }
        ?.let { removeBreakpoint(it) }
    }
  }

  fun addBookmarkDefs(bookmarkDefs: List<BookmarkDef>) {
    val manager = BookmarksManager.getInstance(project) ?: return
    val provider = bookmarkProvider ?: return
    for (def in bookmarkDefs) {
      val bookmark = provider.createBookmark(mapOf("url" to def.fileUrl, "line" to "${def.line}")) ?: continue
      val group = manager.getDefaultGroup()
      if (group != null) {
        group.add(bookmark, def.type, def.name?.takeIf { it.isNotBlank() })
      }
      else {
        manager.add(bookmark, def.type)
      }
    }
  }

  fun removeBookmarkDefs(bookmarkDefs: List<BookmarkDef>) {
    val manager = BookmarksManager.getInstance(project) ?: return
    val provider = bookmarkProvider ?: return
    for (def in bookmarkDefs) {
      val bookmark = provider.createBookmark(mapOf("url" to def.fileUrl, "line" to "${def.line}", "name" to "&is_checkout&")) ?: continue
      manager.remove(bookmark)
    }
  }

  private val bookmarkProvider get() = BookmarkProvider.EP.getExtensions(project).minByOrNull { it.weight }

  fun getMasterBreakpoint(bp: XLineBreakpoint<*>): XLineBreakpoint<*>? {
    return depManager?.getMasterBreakpoint(bp) as? XLineBreakpoint<*>
  }

  fun isLeaveEnabled(bp: XLineBreakpoint<*>): Boolean {
    return depManager?.isLeaveEnabled(bp) ?: true
  }

  fun setMasterBreakpoint(slave: XLineBreakpoint<*>, master: XLineBreakpoint<*>, leaveEnabled: Boolean): Unit? =
    ReadAction.nonBlocking<Unit> {
      depManager?.setMasterBreakpoint(slave, master, leaveEnabled)
    }.executeSynchronously()

  fun clearMasterBreakpoint(bp: XLineBreakpoint<*>): Unit? = ReadAction.nonBlocking<Unit> {
    depManager?.clearMasterBreakpoint(bp)
  }.executeSynchronously()

  fun setDefaultGroup(groupName: String?): Unit? = ReadAction.nonBlocking<Unit> {
    (bpManager as? XBreakpointManagerImpl)?.defaultGroup = groupName
    val bmManager = BookmarksManager.getInstance(project) ?: return@nonBlocking
    if (groupName == null) {
      bmManager.getDefaultGroup()?.isDefault = false
      return@nonBlocking
    }
    val group = bmManager.getGroup(groupName) ?: bmManager.addGroup(groupName, false) ?: return@nonBlocking
    group.isDefault = true
  }.executeSynchronously()

  /** Switches active group and syncs IDE breakpoints. Must be called on EDT inside a write action. */
  fun checkout(targetGroupId: Int?, service: DebugMapService) {
    val currentGroupId = service.getActiveGroupId()
    // Null out first so breakpointRemoved events (synchronous) are ignored.
    service.setActiveGroupId(null)
    if (currentGroupId != null) {
      val bookmarksToRemove = service.getGroupBookmarks(currentGroupId)
      // Register suppressions before removing: BookmarksManager fires bookmarkRemoved via
      // invokeLater (async), so the events arrive after this method returns. The listener will
      // consume each entry instead of mirroring it back into the in-memory store.
      service.suppressBookmarkRemovals(bookmarksToRemove)
      removeBreakpointDefs(service.getGroupBreakpoints(currentGroupId))
      removeBookmarkDefs(bookmarksToRemove)
    }

    // Set target before adding so breakpointAdded/bookmarkAdded events sync to the right group.
    service.setActiveGroupId(targetGroupId)
    if (targetGroupId != null) {
      addBreakpointDefs(service.getGroupBreakpoints(targetGroupId))
      addBookmarkDefs(service.getGroupBookmarks(targetGroupId))
    }
  }
}


internal fun XLineBreakpoint<*>.column(breakpointIdeaManager: BreakpointIdeManager): Int {
  return ReadAction.nonBlocking<Int> {
    val position = sourcePosition ?: return@nonBlocking 0
    highlightRange ?: return@nonBlocking 0

    val firstBreakpointOffset =
      breakpointIdeaManager.allLineBreakpoints().filter { it.fileUrl == position.file.url && it.line == position.line }.minByOrNull {
        it.sourcePosition?.offset ?: Int.MAX_VALUE
      }?.sourcePosition?.offset ?: return@nonBlocking 0

    position.offset - firstBreakpointOffset + 1
  }.executeSynchronously()
}
