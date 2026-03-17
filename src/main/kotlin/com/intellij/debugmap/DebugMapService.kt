package com.intellij.debugmap

import com.intellij.debugmap.manager.BreakpointManager
import com.intellij.debugmap.model.BookmarkDef
import com.intellij.debugmap.model.BreakpointDef
import com.intellij.debugmap.model.GroupData
import com.intellij.debugmap.model.RecentLocationTracker
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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Service(Service.Level.PROJECT)
@State(name = "DebugMap", storages = [Storage(StoragePathMacros.PRODUCT_WORKSPACE_FILE)])
class DebugMapService(val project: Project, private val cs: CoroutineScope) : PersistentStateComponent<PersistedState>, Disposable {

  private val _groups = MutableStateFlow<List<GroupData>>(emptyList())
  val groups: StateFlow<List<GroupData>> = _groups.asStateFlow()

  private val _activeGroupId = MutableStateFlow<Int?>(null)
  val activeGroupId: StateFlow<Int?> = _activeGroupId.asStateFlow()

  private val recentBreakpointTracker = RecentLocationTracker<BreakpointDef>(
    isSame = { a, b -> a.groupId == b.groupId && a.fileUrl == b.fileUrl && a.line == b.line && a.column == b.column },
  )
  val recentBreakpoints: StateFlow<List<BreakpointDef>> = recentBreakpointTracker.recent

  private val recentBookmarkTracker = RecentLocationTracker<BookmarkDef>(
    isSame = { a, b -> a.groupId == b.groupId && a.fileUrl == b.fileUrl && a.line == b.line },
  )
  val recentBookmarks: StateFlow<List<BookmarkDef>> = recentBookmarkTracker.recent

  @Volatile
  private var isSessionStop = false

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
  private val suppressedBookmarkRemovals: MutableSet<Pair<String, Int>> = ConcurrentHashMap.newKeySet()

  internal fun suppressBookmarkRemovals(defs: List<BookmarkDef>) {
    defs.forEach { suppressedBookmarkRemovals.add(it.fileUrl to it.line) }
  }

  internal fun consumeSuppressedBookmarkRemoval(fileUrl: String, line: Int): Boolean =
    suppressedBookmarkRemovals.remove(fileUrl to line)


  fun addRecentBreakpoint(def: BreakpointDef) {
    if (isSessionStop) {
      isSessionStop = false
      recentBreakpointTracker.clear()
    }
    recentBreakpointTracker.add(def)
  }

  fun addRecentBookmark(def: BookmarkDef) {
    recentBookmarkTracker.add(def)
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
    cs.launch { ideManager.setDefaultGroup(groupName) }
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
            pb.logStack = def.logStack
            pb.suspendPolicy = def.suspendPolicy
            pb.masterBreakpointId = def.masterBreakpointId
            pb.masterLeaveEnabled = def.masterLeaveEnabled
            pb.id = def.id
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
            logStack = pb.logStack,
            suspendPolicy = pb.suspendPolicy,
            masterBreakpointId = pb.masterBreakpointId,
            masterLeaveEnabled = pb.masterLeaveEnabled,
            id = if (pb.id != 0L) pb.id else kotlin.random.Random.nextLong(),
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
      cs.launch { ideManager.renameGroup(oldName, name) }
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

  fun findBreakpointById(id: Long): BreakpointDef? =
    breakpointManager.findBreakpointById(id)

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
      cs.launch { ideManager.findLineBreakpoint(fileUrl, line, column)?.let { ideManager.removeBreakpoint(it) } }
    }
    syncState()
  }

  fun moveBreakpointLine(def: BreakpointDef, newLine: Int) {
    breakpointManager.replaceBreakpointDef(def.copy(line = newLine))
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

  /** Called from the tool window or MCP: replaces the def in-memory and applies all properties to the IDE breakpoint if the group is active. */
  fun updateBreakpointByToolWindow(updatedDef: BreakpointDef) {
    breakpointManager.replaceBreakpointDef(updatedDef)
    if (updatedDef.groupId == breakpointManager.activeGroupId) {
      val masterDef = updatedDef.masterBreakpointId?.let { breakpointManager.findBreakpointById(it) }
      cs.launch { ideManager.applyBreakpointProperties(updatedDef, masterDef) }
    }
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

  fun reorderGroup(id: Int, delta: Int) {
    breakpointManager.reorderGroup(id, delta)
    syncState()
  }

  fun reorderBookmark(groupId: Int, def: BookmarkDef, delta: Int) {
    breakpointManager.reorderBookmark(groupId, def, delta)
    syncState()
  }

  fun reorderBreakpoint(groupId: Int, def: BreakpointDef, delta: Int) {
    breakpointManager.reorderBreakpoint(groupId, def, delta)
    syncState()
  }

  fun buildReference(fileUrl: String, line: Int): String = ideManager.buildReference(fileUrl, line)

  /** Must be called within a writeAction. Switches active group and syncs IDE breakpoints. */
  fun checkout(targetGroupId: Int?) {
    markerTracker.flushAll()
    val currentGroupId = getActiveGroupId()
    // Null out first so breakpointRemoved events (synchronous) are ignored.
    setActiveGroupId(null)
    // Harvest floating items: save untracked IDE items to appropriate groups, then remove them
    // from the IDE so they don't leak into the target view. Safe to run here because
    // activeGroupId is null, so synchronous breakpointRemoved events are ignored.
    harvestFloatingItems(currentGroupId)
    if (currentGroupId != null) {
      val bookmarksToRemove = getGroupBookmarks(currentGroupId)
      // Register suppressions before removing: BookmarksManager fires bookmarkRemoved via
      // invokeLater (async), so the events arrive after this method returns. The listener will
      // consume each entry instead of mirroring it back into the in-memory store.
      suppressBookmarkRemovals(bookmarksToRemove)
      ideManager.removeBreakpointDefs(getGroupBreakpoints(currentGroupId))
      ideManager.removeBookmarkDefs(bookmarksToRemove)
    }
    // Set target before adding so breakpointAdded/bookmarkAdded events sync to the right group.
    setActiveGroupId(targetGroupId)
    if (targetGroupId != null) {
      val targetGroupName = breakpointManager.getGroup(targetGroupId)?.name
      ideManager.addBreakpointDefs(getGroupBreakpoints(targetGroupId))
      ideManager.addBookmarkDefs(getGroupBookmarks(targetGroupId), targetGroupName)
    }
    syncState()
    markerTracker.initForOpenFiles()
  }

  /**
   * Saves IDE breakpoints/bookmarks not tracked by any debug-map group ("floating") into the
   * appropriate group, then removes them from the IDE so they don't persist into the next view.
   *
   * - Floating breakpoints go into [currentGroupId] (skipped if no current group).
   * - Floating bookmarks are routed by IDE bookmark group name to a matching debug-map group,
   *   falling back to [currentGroupId].
   * - If the destination group already has an entry at the same (file, line), the floating item
   *   is a duplicate: it is removed from the IDE without being saved.
   *
   * Must be called after [setActiveGroupId] has been nulled out so that synchronous
   * breakpointRemoved events are suppressed. Bookmark removals (async via invokeLater) are
   * registered for suppression here before being fired, for the same reason.
   */
  private fun harvestFloatingItems(currentGroupId: Int?) {
    // 1. Floating breakpoints → current group
    val floatingBreakpoints = ideManager.allLineBreakpoints()
      .filter { !breakpointManager.breakpointExists(it.fileUrl, it.line, it.column(ideManager)) }
    for (bp in floatingBreakpoints) {
      val fileUrl = bp.fileUrl
      val line = bp.line
      val column = bp.column(ideManager)
      if (currentGroupId != null) {
        val alreadyInGroup = breakpointManager.getGroupBreakpoints(currentGroupId)
          .any { it.fileUrl == fileUrl && it.line == line && it.column == column }
        if (!alreadyInGroup) {
          breakpointManager.upsertBreakpointInGroup(
            currentGroupId,
            BreakpointDef(
              groupId = currentGroupId,
              fileUrl = fileUrl,
              line = line,
              typeId = bp.type.id,
              condition = bp.conditionExpression?.expression,
              logExpression = bp.logExpressionObject?.expression,
              column = column,
            )
          )
        }
      }
      // Always remove from IDE; breakpointRemoved event is ignored (activeGroupId is null).
      ideManager.removeBreakpointDefs(listOf(BreakpointDef(groupId = 0, fileUrl = fileUrl, line = line, column = column)))
    }

    // 2. Floating bookmarks → name-matched group or current group
    val manager = BookmarksManager.getInstance(project) ?: return
    val floatingBookmarks = ideManager.allLineBookmarks()
      .filter { (_, bm) -> breakpointManager.getBookmarkGroupId(bm.file.url, bm.line) == null }
    if (floatingBookmarks.isEmpty()) return

    // Suppress async bookmarkRemoved events: invokeLater fires after checkout() returns,
    // at which point activeGroupId is restored to targetGroupId.
    suppressBookmarkRemovals(floatingBookmarks.map { (_, bm) ->
      BookmarkDef(groupId = 0, fileUrl = bm.file.url, line = bm.line, name = null, type = BookmarkType.DEFAULT)
    })
    for ((ideGroup, bookmark) in floatingBookmarks) {
      val fileUrl = bookmark.file.url
      val line = bookmark.line
      val destGroupId: Int = if (ideGroup.name.isBlank()) {
        currentGroupId ?: continue
      }
      else {
        breakpointManager.getGroupIdByName(ideGroup.name) ?: currentGroupId ?: continue
      }
      val alreadyInGroup = breakpointManager.getGroupBookmarks(destGroupId)
        .any { it.fileUrl == fileUrl && it.line == line }
      if (!alreadyInGroup) {
        val type = manager.getType(bookmark) ?: BookmarkType.DEFAULT
        val name = ideGroup.getDescription(bookmark)
        breakpointManager.upsertBookmarkInGroup(
          destGroupId,
          BookmarkDef(
            groupId = destGroupId,
            fileUrl = fileUrl,
            line = line,
            name = name?.ifEmpty { null },
            type = type,
          )
        )
      }
      // Always remove from IDE; suppression above handles the async bookmarkRemoved callback.
      ideManager.removeBookmarkDefs(listOf(BookmarkDef(groupId = destGroupId, fileUrl = fileUrl, line = line, name = null, type = BookmarkType.DEFAULT)))
    }
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
