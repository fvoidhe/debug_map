package com.intellij.debugmap

import com.intellij.debugmap.manager.BreakpointManager
import com.intellij.debugmap.model.BookmarkDef
import com.intellij.debugmap.model.BreakpointDef
import com.intellij.debugmap.model.GroupData
import com.intellij.debugmap.model.PersistedBookmark
import com.intellij.debugmap.model.PersistedBreakpoint
import com.intellij.debugmap.model.PersistedGroup
import com.intellij.debugmap.model.PersistedState
import com.intellij.ide.bookmark.BookmarkType
import com.intellij.ide.bookmark.BookmarksManager
import com.intellij.debugmap.manager.BreakpointIdeManager
import com.intellij.debugmap.manager.column
import com.intellij.debugmap.sync.BreakpointMarkerTracker
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Service(Service.Level.PROJECT)
@State(name = "DebugMap", storages = [Storage(StoragePathMacros.PRODUCT_WORKSPACE_FILE)])
class DebugMapService(val project: Project) : PersistentStateComponent<PersistedState>, Disposable {

  private val _groups = MutableStateFlow<List<GroupData>>(emptyList())
  val groups: StateFlow<List<GroupData>> = _groups.asStateFlow()

  private val _activeGroupId = MutableStateFlow<Int?>(null)
  val activeGroupId: StateFlow<Int?> = _activeGroupId.asStateFlow()

  private val _recentBreakpoints = MutableStateFlow<List<BreakpointDef>>(emptyList())

  @Volatile
  private var isSessionStop = false
  val recentBreakpoints: StateFlow<List<BreakpointDef>> = _recentBreakpoints.asStateFlow()

  companion object {
    fun getInstance(project: Project): DebugMapService =
      project.getService(DebugMapService::class.java)
  }

  internal val breakpointManager = BreakpointManager()
  internal val ideManager = BreakpointIdeManager(project)
  private val markerTracker = BreakpointMarkerTracker(this)

  /**
   * Bookmark (fileUrl, line) pairs that were removed from the IDE by checkout itself.
   * The listener consumes entries here instead of mirroring them back into the in-memory store,
   * preventing the async bookmarkRemoved callbacks from corrupting the old group's data.
   */
  private val suppressedBookmarkRemovals = mutableSetOf<Pair<String, Int>>()

  internal fun suppressBookmarkRemovals(defs: List<BookmarkDef>) {
    defs.forEach { suppressedBookmarkRemovals.add(it.fileUrl to it.line) }
  }

  internal fun consumeSuppressedBookmarkRemoval(fileUrl: String, line: Int): Boolean =
    suppressedBookmarkRemovals.remove(fileUrl to line)


  fun addRecentBreakpoint(def: BreakpointDef) {
    val current = _recentBreakpoints.value.toMutableList()
    if (isSessionStop) {
      isSessionStop = false
      current.clear()
    }
    else {
      current.removeAll { it.groupId == def.groupId && it.fileUrl == def.fileUrl && it.line == def.line && it.column == def.column }
    }
    current.add(0, def)
    if (current.size > 10) {
      current.removeAt(current.size - 1)
    }
    _recentBreakpoints.value = current
  }

  fun stopRecentBreakpoints() {
    isSessionStop = true
  }

  override fun dispose() {
  }

  private fun syncState() {
    _groups.value = breakpointManager.getGroups()
    _activeGroupId.value = breakpointManager.activeGroupId
  }

  /**
   * Sets the active group on the in-memory manager and updates both IDE default groups
   * (breakpoint and bookmark) so that add/remove events during checkout phases are
   * routed to the right group.
   */
  internal fun setActiveGroupId(groupId: Int?) {
    breakpointManager.activeGroupId = groupId
    val groupName = groupId?.let { breakpointManager.getGroup(it)?.name }
    ideManager.setDefaultGroup(groupName)
  }

  override fun getState(): PersistedState = PersistedState().also { state ->
    state.nextGroupId = breakpointManager.nextGroupId
    state.activeGroupId = breakpointManager.activeGroupId ?: -1
    state.groups = breakpointManager.getGroupsSnapshot().map { group ->
      PersistedGroup().also { pg ->
        pg.id = group.id
        pg.name = group.name
        pg.breakpoints = group.breakpoints.map { def ->
          PersistedBreakpoint().also { pb ->
            pb.fileUrl = def.fileUrl
            pb.line = markerTracker.getCurrentLine(group.id, def)
            pb.column = def.column
            pb.typeId = def.typeId
            pb.condition = def.condition
            pb.logExpression = def.logExpression
            pb.name = def.name?.ifEmpty { null }
            pb.enabled = def.enabled
            pb.logMessage = def.logMessage
            pb.suspendPolicy = def.suspendPolicy
            pb.masterFileUrl = def.masterFileUrl
            pb.masterLine = def.masterLine
            pb.masterLeaveEnabled = def.masterLeaveEnabled
          }
        }.toMutableList()
        pg.bookmarks = group.bookmarks.map { def ->
          PersistedBookmark().also { pb ->
            pb.fileUrl = def.fileUrl
            pb.line = def.line
            pb.name = def.name?.ifEmpty { null }
            pb.bookmarkType = def.type.name
          }
        }.toMutableList()
      }
    }.toMutableList()
  }

  /** Called by IntelliJ when there is no previously saved state (new project). */
  override fun noStateLoaded() {
    syncState()
  }

  override fun loadState(state: PersistedState) {
    val groupsSnapshot = state.groups.map { pg ->
      GroupData(
        id = pg.id,
        name = pg.name,
        breakpoints = pg.breakpoints.map { pb ->
          BreakpointDef(
            groupId = pg.id,
            fileUrl = pb.fileUrl,
            line = pb.line,
            column = pb.column,
            typeId = pb.typeId,
            condition = pb.condition,
            logExpression = pb.logExpression,
            name = pb.name,
            enabled = pb.enabled,
            logMessage = pb.logMessage,
            suspendPolicy = pb.suspendPolicy,
            masterFileUrl = pb.masterFileUrl,
            masterLine = pb.masterLine,
            masterLeaveEnabled = pb.masterLeaveEnabled,
          )
        },
        bookmarks = pg.bookmarks.map { pb ->
          BookmarkDef(
            groupId = pg.id,
            fileUrl = pb.fileUrl,
            line = pb.line,
            name = pb.name,
            type = runCatching { BookmarkType.valueOf(pb.bookmarkType) }.getOrDefault(BookmarkType.DEFAULT),
          )
        },
      )
    }
    val activeGroupId = if (state.activeGroupId == -1) null else state.activeGroupId
    breakpointManager.restore(groupsSnapshot, state.nextGroupId, activeGroupId)
    syncState()
  }

  val nextGroupId: Int get() = breakpointManager.nextGroupId

  fun createGroup(name: String): Int {
    val id = breakpointManager.createGroup(name.ifBlank { "Group ${breakpointManager.nextGroupId}" })
    syncState()
    return id
  }

  fun renameGroup(groupId: Int, name: String) {
    val oldName = breakpointManager.getGroup(groupId)?.name
    breakpointManager.renameGroup(groupId, name)
    if (groupId == breakpointManager.activeGroupId && oldName != null) {
      ideManager.renameGroup(oldName, name)
    }
    syncState()
  }

  fun renameBreakpoint(def: BreakpointDef, name: String) {
    breakpointManager.upsertBreakpointInGroup(def.groupId, def.copy(name = name.trim()))
    syncState()
  }

  fun renameBookmark(def: BookmarkDef, name: String) {
    val trimmed = name.trim()
    breakpointManager.upsertBookmarkInGroup(def.groupId, def.copy(name = trimmed))
    if (def.groupId == breakpointManager.activeGroupId) {
      ideManager.renameBookmark(def, trimmed)
    }
    syncState()
  }

  fun getGroups(): List<GroupData> = _groups.value
  fun groupExists(groupId: Int): Boolean = breakpointManager.groupExists(groupId)
  fun getActiveGroupId(): Int? = breakpointManager.activeGroupId
  fun getGroupIdByName(name: String): Int? = breakpointManager.getGroupIdByName(name)

  /**
   * Deletes a group and its breakpoint definitions.
   * The active group cannot be deleted; callers must checkout a different group first.
   * Must be called within a writeAction.
   */
  fun deleteGroup(groupId: Int) {
    check(breakpointManager.activeGroupId != groupId) { "Cannot delete the active group; checkout another group first" }
    breakpointManager.deleteGroup(groupId)
    syncState()
  }

  fun getGroupBreakpoints(groupId: Int): List<BreakpointDef> =
    breakpointManager.getGroupBreakpoints(groupId)

  fun getGroupBookmarks(groupId: Int): List<BookmarkDef> =
    breakpointManager.getGroupBookmarks(groupId)

  fun getBreakpointsByFile(fileUrl: String): List<BreakpointDef> =
    breakpointManager.getBreakpointsByFile(fileUrl)

  fun breakpointExists(fileUrl: String, line: Int, column: Int): Boolean =
    breakpointManager.breakpointExists(fileUrl, line, column)

  /** Called from the IDE listener: updates in-memory state and notifies tool windows. Does NOT push to IDE. */
  fun upsertBreakpointByIde(groupId: Int, def: BreakpointDef) {
    breakpointManager.upsertBreakpointInGroup(groupId, def)
    syncState()
  }

  /** Called from the IDE listener: updates in-memory state and notifies tool windows. Does NOT push to IDE. */
  fun removeBreakpointByIde(groupId: Int, fileUrl: String, line: Int, column: Int = 0) {
    breakpointManager.removeBreakpointFromGroup(groupId, fileUrl, line, column)
    syncState()
  }

  /** Called from the tool window: updates in-memory state, pushes to IDE, and notifies tool windows. */
  fun removeBreakpointByToolWindow(groupId: Int, fileUrl: String, line: Int, column: Int = 0) {
    breakpointManager.removeBreakpointFromGroup(groupId, fileUrl, line, column)
    if (groupId == breakpointManager.activeGroupId) {
      ideManager.findLineBreakpoint(fileUrl, line, column)?.let { ideManager.removeBreakpoint(it) }
    }
    syncState()
  }

  fun moveBreakpointLine(def: BreakpointDef, newLine: Int) {
    breakpointManager.moveBreakpointLine(def, newLine)
    syncState()
  }

  fun getBookmarksByFile(fileUrl: String): List<BookmarkDef> =
    breakpointManager.getBookmarksByFile(fileUrl)

  fun getBookmarkGroupId(fileUrl: String, line: Int): Int? =
    breakpointManager.getBookmarkGroupId(fileUrl, line)

  /** Called from the IDE listener: updates in-memory state and notifies tool windows. Does NOT push to IDE. */
  fun upsertBookmarkByIde(groupId: Int, def: BookmarkDef) {
    breakpointManager.upsertBookmarkInGroup(groupId, def)
    syncState()
  }

  /** Called from the IDE listener: updates in-memory state and notifies tool windows. Does NOT push to IDE. */
  fun removeBookmarkByIde(groupId: Int, fileUrl: String, line: Int) {
    breakpointManager.removeBookmarkFromGroup(groupId, fileUrl, line)
    syncState()
  }

  /** Called from the tool window or MCP: updates in-memory state, pushes to IDE if active, and notifies tool windows. Must be called within a writeAction. */
  fun addBreakpointByToolWindow(groupId: Int, def: BreakpointDef) {
    breakpointManager.upsertBreakpointInGroup(groupId, def)
    if (groupId == breakpointManager.activeGroupId) {
      ideManager.addBreakpointDefs(listOf(def))
    }
    syncState()
  }

  /** Called from the tool window or MCP: updates in-memory state, pushes to IDE if active, and notifies tool windows. */
  fun addBookmarkByToolWindow(groupId: Int, def: BookmarkDef) {
    breakpointManager.upsertBookmarkInGroup(groupId, def)
    if (groupId == breakpointManager.activeGroupId) {
      ideManager.addBookmarkDefs(listOf(def))
    }
    syncState()
  }

  /** Called from the tool window: updates in-memory state, pushes to IDE, and notifies tool windows. */
  fun removeBookmarkByToolWindow(groupId: Int, fileUrl: String, line: Int) {
    breakpointManager.removeBookmarkFromGroup(groupId, fileUrl, line)
    if (groupId == breakpointManager.activeGroupId) {
      ideManager.removeBookmarkDefs(listOf(BookmarkDef(groupId, fileUrl, line, null, BookmarkType.DEFAULT)))
    }
    syncState()
  }

  fun moveBookmarkLine(def: BookmarkDef, newLine: Int) {
    breakpointManager.moveBookmarkLine(def, newLine)
    syncState()
  }

  /** Must be called within a writeAction. Switches active group and syncs IDE breakpoints. */
  fun checkout(targetGroupId: Int?) {
    markerTracker.flushAll()
    ideManager.checkout(targetGroupId, this)
    syncState()
    markerTracker.initForOpenFiles()
  }

  internal fun onFileOpened(file: VirtualFile) = markerTracker.onFileOpened(file)
  internal fun onFileClosed(file: VirtualFile) = markerTracker.onFileClosed(file)
  internal fun getCurrentLine(groupId: Int, def: BreakpointDef) = markerTracker.getCurrentLine(groupId, def)
  internal fun dropFileEntries(fileUrl: String) = markerTracker.dropFileEntries(fileUrl)

  /**
   * Ensures there is always at least one group and an active group.
   */
  private fun ensureDefaultGroup() {
    val activeGroupId = breakpointManager.activeGroupId

    if (breakpointManager.getGroups().isEmpty()
        || (activeGroupId == null)
        || (breakpointManager.getGroup(activeGroupId) == null)) {
      val id = breakpointManager.createGroup("Default")
      breakpointManager.activeGroupId = id
    }
  }

  /**
   * Imports IDE line breakpoints and bookmarks that are not yet assigned to any group.
   * - Floating breakpoints (not in any debug-map group) go into the active group.
   * - Bookmarks are imported preserving the IDE bookmark group structure: named IDE bookmark
   *   groups are matched to existing debug-map groups by name, or a new group is created.
   *   Bookmarks in unnamed groups fall back to the active group.
   *
   * Must be called after the project is fully opened so that [XBreakpointManagerImpl] and
   * [BookmarksManager] have loaded their state from disk.
   */
  internal fun importFloatingItemsAtStartup() {
    ensureDefaultGroup()
    val activeGroupId = breakpointManager.activeGroupId ?: return

    // 1. Import floating breakpoints → active group
    ideManager.allLineBreakpoints()
      .filter { !breakpointManager.isActiveGroupBreakpoint(it.fileUrl, it.line, it.column(ideManager)) }
      .forEach { bp ->
        breakpointManager.upsertBreakpointInGroup(
          activeGroupId,
          BreakpointDef(
            groupId = activeGroupId,
            fileUrl = bp.fileUrl,
            line = bp.line,
            typeId = bp.type.id,
            condition = bp.conditionExpression?.expression,
            logExpression = bp.logExpressionObject?.expression,
            column = bp.column(ideManager),
          )
        )
      }

    // 2. Import bookmarks, preserving their IDE bookmark group structure
    val manager = BookmarksManager.getInstance(project)
    if (manager != null) {
      for ((ideGroup, bookmark) in ideManager.allLineBookmarks()) {
        val fileUrl = bookmark.file.url
        val line = bookmark.line
        if (breakpointManager.getBookmarkGroupId(fileUrl, line) != null) continue

        val targetGroupId: Int = if (ideGroup.name.isBlank()) {
          activeGroupId
        }
        else {
          breakpointManager.getGroupIdByName(ideGroup.name)
          ?: breakpointManager.createGroup(ideGroup.name)
        }

        val type = manager.getType(bookmark) ?: BookmarkType.DEFAULT
        val name = ideGroup.getDescription(bookmark)
        breakpointManager.upsertBookmarkInGroup(
          targetGroupId,
          BookmarkDef(
            groupId = targetGroupId,
            fileUrl = fileUrl,
            line = line,
            name = name?.ifEmpty { null },
            type = type,
          )
        )
      }
    }

    syncState()
  }
}
