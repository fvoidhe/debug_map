// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.debugmap.manager

import com.intellij.debugmap.model.BookmarkDef
import com.intellij.debugmap.model.BreakpointDef
import com.intellij.ide.bookmark.BookmarkGroup
import com.intellij.ide.bookmark.BookmarkProvider
import com.intellij.ide.bookmark.BookmarksManager
import com.intellij.ide.bookmark.LineBookmark
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.BaseProjectDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
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

  fun buildReference(fileUrl: String, line: Int): String = runReadActionBlocking {
    val vFile = VirtualFileManager.getInstance().findFileByUrl(fileUrl)
    val relativePath = if (vFile != null) {
      val index = ProjectRootManager.getInstance(project).fileIndex
      val root = index.getContentRootForFile(vFile)
                 ?: index.getSourceRootForFile(vFile)
                 ?: BaseProjectDirectories.getInstance(project).getBaseDirectoryFor(vFile)
      if (root != null) VfsUtil.getRelativePath(vFile, root) else null
    } else null
    "${relativePath ?: vFile?.name ?: fileUrl}:${line + 1}"
  }

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

  suspend fun removeBreakpoint(bp: XLineBreakpoint<*>): Unit = writeAction {
    bpManager.removeBreakpoint(bp)
  }

  fun renameBookmark(def: BookmarkDef, name: String) {
    val manager = BookmarksManager.getInstance(project) ?: return
    val provider = bookmarkProvider ?: return
    val bookmark = provider.createBookmark(mapOf("url" to def.fileUrl, "line" to "${def.line}")) ?: return
    val group = manager.getDefaultGroup() ?: return
    group.setDescription(bookmark, name)
  }

  suspend fun renameGroup(oldName: String, newName: String): Unit = writeAction {
    BookmarksManager.getInstance(project)?.getGroup(oldName)?.name = newName
    (bpManager as? XBreakpointManagerImpl)?.defaultGroup = newName
  }

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
        ?.let { bpManager.removeBreakpoint(it) }
    }
  }

  fun addBookmarkDefs(bookmarkDefs: List<BookmarkDef>, groupName: String? = null) {
    val manager = BookmarksManager.getInstance(project) ?: return
    val provider = bookmarkProvider ?: return
    for (def in bookmarkDefs) {
      val bookmark = provider.createBookmark(mapOf("url" to def.fileUrl, "line" to "${def.line}")) ?: continue
      val group = if (groupName != null) {
        manager.getGroup(groupName) ?: manager.addGroup(groupName, false)
      }
      else {
        manager.getDefaultGroup()
      }
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

  /**
   * Applies all properties from [def] to the matching IDE breakpoint.
   * Used by the update path to keep the IDE in sync after the manager state has already been updated.
   * No-op if the breakpoint is not currently active in the IDE.
   */
  suspend fun applyBreakpointProperties(def: BreakpointDef, masterDef: BreakpointDef?) {
    val bp = findLineBreakpoint(def.fileUrl, def.line, def.column) ?: return
    val masterBp = masterDef?.let { findLineBreakpoint(it.fileUrl, it.line) }
    writeAction {
      def.enabled?.let { bp.setEnabled(it) }
      (bp as? XBreakpointBase<*, *, *>)?.userDescription = def.name?.ifBlank { null }
      bp.setCondition(def.condition)
      bp.setLogExpression(def.logExpression)
      def.logMessage?.let { bp.setLogMessage(it) }
      def.logStack?.let { bp.setLogStack(it) }
      def.suspendPolicy?.let {
        bp.setSuspendPolicy(when (it.uppercase()) {
                              "ALL" -> SuspendPolicy.ALL
                              "THREAD" -> SuspendPolicy.THREAD
                              "NONE" -> SuspendPolicy.NONE
                              else -> return@let
                            })
      }
      if (def.masterBreakpointId == null) {
        depManager?.clearMasterBreakpoint(bp)
      }
      else if (masterBp != null) {
        depManager?.setMasterBreakpoint(bp, masterBp, def.masterLeaveEnabled ?: true)
      }
    }
  }

  fun getMasterBreakpoint(bp: XLineBreakpoint<*>): XLineBreakpoint<*>? {
    return depManager?.getMasterBreakpoint(bp) as? XLineBreakpoint<*>
  }

  fun isLeaveEnabled(bp: XLineBreakpoint<*>): Boolean {
    return depManager?.isLeaveEnabled(bp) ?: true
  }


  suspend fun setMasterBreakpoint(slave: XLineBreakpoint<*>, master: XLineBreakpoint<*>, leaveEnabled: Boolean): Unit? = writeAction {
    depManager?.setMasterBreakpoint(slave, master, leaveEnabled)
  }

  suspend fun clearMasterBreakpoint(bp: XLineBreakpoint<*>): Unit? = writeAction {
    depManager?.clearMasterBreakpoint(bp)
  }

  suspend fun setDefaultGroup(groupName: String?): Unit = writeAction {
    (bpManager as? XBreakpointManagerImpl)?.defaultGroup = groupName
    val bmManager = BookmarksManager.getInstance(project) ?: return@writeAction
    if (groupName == null) {
      bmManager.getDefaultGroup()?.isDefault = false
      return@writeAction
    }
    val group = bmManager.getGroup(groupName) ?: bmManager.addGroup(groupName, false) ?: return@writeAction
    group.isDefault = true
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
