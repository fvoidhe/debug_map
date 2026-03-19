package com.intellij.debugmap

import com.intellij.debugmap.manager.BreakpointManager
import com.intellij.debugmap.model.BookmarkDef
import com.intellij.debugmap.model.BreakpointDef
import com.intellij.debugmap.model.TopicData
import com.intellij.debugmap.model.TopicStatus
import com.intellij.debugmap.model.RecentLocationTracker
import com.intellij.debugmap.model.PersistedBookmark
import com.intellij.debugmap.model.PersistedBreakpoint
import com.intellij.debugmap.model.PersistedTopic
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

  private val _topics = MutableStateFlow<List<TopicData>>(emptyList())
  val topics: StateFlow<List<TopicData>> = _topics.asStateFlow()

  private val _activeTopicId = MutableStateFlow<Int?>(null)
  val activeTopicId: StateFlow<Int?> = _activeTopicId.asStateFlow()

  private val recentBreakpointTracker = RecentLocationTracker<BreakpointDef>(
    isSame = { a, b -> a.topicId == b.topicId && a.fileUrl == b.fileUrl && a.line == b.line && a.column == b.column },
  )
  val recentBreakpoints: StateFlow<List<BreakpointDef>> = recentBreakpointTracker.recent

  private val recentBookmarkTracker = RecentLocationTracker<BookmarkDef>(
    isSame = { a, b -> a.topicId == b.topicId && a.fileUrl == b.fileUrl && a.line == b.line },
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
   * preventing the async bookmarkRemoved callbacks from corrupting the old topic's data.
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
    _topics.value = breakpointManager.getTopics()
    _activeTopicId.value = breakpointManager.activeTopicId
  }

  /**
   * Sets the active topic on the in-memory manager and updates both IDE default groups
   * (breakpoint and bookmark) so that add/remove events during checkout phases are
   * routed to the right topic.
   */
  internal fun setActiveTopicId(topicId: Int?) {
    breakpointManager.activeTopicId = topicId
    val topicName = topicId?.let { breakpointManager.getTopic(it)?.name }
    cs.launch { ideManager.setDefaultGroup(topicName) }
  }

  override fun getState(): PersistedState = PersistedState().also { state ->
    state.nextTopicId = breakpointManager.nextTopicId
    state.activeTopicId = breakpointManager.activeTopicId ?: -1
    state.topics = breakpointManager.getTopicsSnapshot().map { topic ->
      PersistedTopic().also { pg ->
        pg.id = topic.id
        pg.name = topic.name
        pg.description = topic.description
        pg.status = topic.status.name
        pg.breakpoints = topic.breakpoints.map { def ->
          PersistedBreakpoint().also { pb ->
            pb.fileUrl = def.fileUrl
            pb.line = markerTracker.getCurrentLine(topic.id, def)
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
        pg.bookmarks = topic.bookmarks.map { def ->
          PersistedBookmark().also { pb ->
            pb.fileUrl = def.fileUrl
            pb.line = def.line
            pb.name = def.name?.ifEmpty { null }
            pb.bookmarkType = def.type.name
            pb.id = def.id
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
    val topicsSnapshot = state.topics.map { pg ->
      TopicData(
        id = pg.id,
        name = pg.name,
        description = pg.description,
        status = runCatching { TopicStatus.valueOf(pg.status) }.getOrDefault(TopicStatus.OPEN),
        breakpoints = pg.breakpoints.map { pb ->
          BreakpointDef(
            topicId = pg.id,
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
            topicId = pg.id,
            fileUrl = pb.fileUrl,
            line = pb.line,
            name = pb.name,
            type = runCatching { BookmarkType.valueOf(pb.bookmarkType) }.getOrDefault(BookmarkType.DEFAULT),
            id = if (pb.id != 0L) pb.id else kotlin.random.Random.nextLong(),
          )
        },
      )
    }
    val activeTopicId = if (state.activeTopicId == -1) null else state.activeTopicId
    breakpointManager.restore(topicsSnapshot, state.nextTopicId, activeTopicId)
    syncState()
  }

  val nextTopicId: Int get() = breakpointManager.nextTopicId

  fun createTopic(name: String = ""): Int {
    val id = breakpointManager.createTopic(name)
    syncState()
    return id
  }

  fun renameTopic(topicId: Int, name: String) {
    val oldName = breakpointManager.getTopic(topicId)?.name
    breakpointManager.renameTopic(topicId, name)
    if (topicId == breakpointManager.activeTopicId && oldName != null) {
      cs.launch { ideManager.renameGroup(oldName, name) }
    }
    syncState()
  }

  fun updateTopicDescription(topicId: Int, description: String) {
    breakpointManager.updateTopicDescription(topicId, description)
    syncState()
  }

  fun updateTopicStatus(topicId: Int, status: TopicStatus) {
    breakpointManager.updateTopicStatus(topicId, status)
    syncState()
  }

  fun renameBreakpoint(def: BreakpointDef, name: String) {
    breakpointManager.upsertBreakpointInTopic(def.topicId, def.copy(name = name.trim()))
    syncState()
  }

  fun renameBookmark(def: BookmarkDef, name: String) {
    val trimmed = name.trim()
    breakpointManager.upsertBookmarkInTopic(def.topicId, def.copy(name = trimmed))
    if (def.topicId == breakpointManager.activeTopicId) {
      ideManager.renameBookmark(def, trimmed)
    }
    syncState()
  }

  fun getTopics(): List<TopicData> = _topics.value
  fun topicExists(topicId: Int): Boolean = breakpointManager.topicExists(topicId)
  fun getActiveTopicId(): Int? = breakpointManager.activeTopicId
  fun getTopicIdByName(name: String): Int? = breakpointManager.getTopicIdByName(name)

  /**
   * Deletes a topic and its breakpoint definitions.
   * The active topic cannot be deleted; callers must checkout a different topic first.
   * Must be called within a writeAction.
   */
  fun deleteTopic(topicId: Int) {
    check(breakpointManager.activeTopicId != topicId) { "Cannot delete the active topic; checkout another topic first" }
    breakpointManager.deleteTopic(topicId)
    syncState()
  }

  fun getTopicBreakpoints(topicId: Int): List<BreakpointDef> =
    breakpointManager.getTopicBreakpoints(topicId)

  fun findBreakpointById(id: Long): BreakpointDef? =
    breakpointManager.findBreakpointById(id)

  fun findBookmarkById(id: Long): BookmarkDef? =
    breakpointManager.findBookmarkById(id)

  fun getTopicBookmarks(topicId: Int): List<BookmarkDef> =
    breakpointManager.getTopicBookmarks(topicId)

  fun getBreakpointsByFile(fileUrl: String): List<BreakpointDef> =
    breakpointManager.getBreakpointsByFile(fileUrl)

  fun breakpointExists(fileUrl: String, line: Int, column: Int): Boolean =
    breakpointManager.breakpointExists(fileUrl, line, column)

  /** Called from the IDE listener: updates in-memory state and notifies tool windows. Does NOT push to IDE. */
  fun upsertBreakpointByIde(topicId: Int, def: BreakpointDef) {
    breakpointManager.upsertBreakpointInTopic(topicId, def)
    syncState()
  }

  /** Called from the IDE listener: updates in-memory state and notifies tool windows. Does NOT push to IDE. */
  fun removeBreakpointByIde(topicId: Int, fileUrl: String, line: Int, column: Int = 0) {
    breakpointManager.removeBreakpointFromTopic(topicId, fileUrl, line, column)
    syncState()
  }

  /** Called from the tool window: updates in-memory state, pushes to IDE, and notifies tool windows. */
  fun removeBreakpointByToolWindow(topicId: Int, fileUrl: String, line: Int, column: Int = 0) {
    breakpointManager.removeBreakpointFromTopic(topicId, fileUrl, line, column)
    if (topicId == breakpointManager.activeTopicId) {
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

  fun getBookmarkTopicId(fileUrl: String, line: Int): Int? =
    breakpointManager.getBookmarkTopicId(fileUrl, line)

  /** Called from the IDE listener: updates in-memory state and notifies tool windows. Does NOT push to IDE. */
  fun upsertBookmarkByIde(topicId: Int, def: BookmarkDef) {
    breakpointManager.upsertBookmarkInTopic(topicId, def)
    syncState()
  }

  /** Called from the IDE listener: updates in-memory state and notifies tool windows. Does NOT push to IDE. */
  fun removeBookmarkByIde(topicId: Int, fileUrl: String, line: Int) {
    breakpointManager.removeBookmarkFromTopic(topicId, fileUrl, line)
    syncState()
  }

  /** Called from the tool window or MCP: replaces the def in-memory and applies all properties to the IDE breakpoint if the topic is active. */
  fun updateBreakpointByToolWindow(updatedDef: BreakpointDef) {
    val existingDef = breakpointManager.findBreakpointById(updatedDef.id)
    breakpointManager.replaceBreakpointDef(updatedDef)
    if (updatedDef.topicId == breakpointManager.activeTopicId) {
      if (existingDef != null && existingDef.line != updatedDef.line) {
        // Line changed: the IDE breakpoint is still at the old line, so applyBreakpointProperties
        // (which looks up by new line) would miss it. Remove the old one and add the new one instead.
        cs.launch {
          ideManager.findLineBreakpoint(existingDef.fileUrl, existingDef.line, existingDef.column)
            ?.let { ideManager.removeBreakpoint(it) }
          ideManager.addBreakpointDefs(listOf(updatedDef))
          val masterDef = updatedDef.masterBreakpointId?.let { breakpointManager.findBreakpointById(it) }
          ideManager.applyBreakpointProperties(updatedDef, masterDef)
        }
      }
      else {
        val masterDef = updatedDef.masterBreakpointId?.let { breakpointManager.findBreakpointById(it) }
        cs.launch { ideManager.applyBreakpointProperties(updatedDef, masterDef) }
      }
    }
    syncState()
  }

  /** Called from the tool window or MCP: updates in-memory state, pushes to IDE if active, and notifies tool windows. Must be called within a writeAction. */
  fun addBreakpointByToolWindow(topicId: Int, def: BreakpointDef) {
    breakpointManager.upsertBreakpointInTopic(topicId, def)
    if (topicId == breakpointManager.activeTopicId) {
      ideManager.addBreakpointDefs(listOf(def))
    }
    syncState()
  }

  /**
   * Called from MCP: updates a bookmark identified by its id within the same topic.
   * [existingDef] is the current state; [newDef] has the desired state (same fileUrl, topicId, and id).
   */
  fun updateBookmarkByToolWindow(existingDef: BookmarkDef, newDef: BookmarkDef) {
    require(existingDef.fileUrl == newDef.fileUrl) { "fileUrl must not change" }
    require(existingDef.id == newDef.id) { "id must not change" }
    require(existingDef.topicId == newDef.topicId) { "topicId must not change" }
    val isActive = existingDef.topicId == breakpointManager.activeTopicId

    if (isActive) {
      ideManager.removeBookmarkDefs(listOf(existingDef))
    }
    breakpointManager.replaceBookmarkDef(newDef)
    if (isActive) {
      ideManager.addBookmarkDefs(listOf(newDef))
    }
    syncState()
  }

  /** Called from the tool window or MCP: updates in-memory state, pushes to IDE if active, and notifies tool windows. */
  fun addBookmarkByToolWindow(topicId: Int, def: BookmarkDef) {
    breakpointManager.upsertBookmarkInTopic(topicId, def)
    if (topicId == breakpointManager.activeTopicId) {
      ideManager.addBookmarkDefs(listOf(def))
    }
    syncState()
  }

  /** Called from the tool window: updates in-memory state, pushes to IDE, and notifies tool windows. */
  fun removeBookmarkByToolWindow(topicId: Int, fileUrl: String, line: Int) {
    breakpointManager.removeBookmarkFromTopic(topicId, fileUrl, line)
    if (topicId == breakpointManager.activeTopicId) {
      ideManager.removeBookmarkDefs(listOf(BookmarkDef(topicId, fileUrl, line, null, BookmarkType.DEFAULT)))
    }
    syncState()
  }

  fun moveBookmarkLine(def: BookmarkDef, newLine: Int) {
    breakpointManager.moveBookmarkLine(def, newLine)
    syncState()
  }

  fun reorderTopic(id: Int, delta: Int) {
    breakpointManager.reorderTopic(id, delta)
    syncState()
  }

  fun reorderBookmark(topicId: Int, def: BookmarkDef, delta: Int) {
    breakpointManager.reorderBookmark(topicId, def, delta)
    syncState()
  }

  fun reorderBreakpoint(topicId: Int, def: BreakpointDef, delta: Int) {
    breakpointManager.reorderBreakpoint(topicId, def, delta)
    syncState()
  }

  fun buildReference(fileUrl: String, line: Int): String = ideManager.buildReference(fileUrl, line)

  /** Must be called within a writeAction. Switches active topic and syncs IDE breakpoints. */
  fun checkout(targetTopicId: Int?) {
    markerTracker.flushAll()
    val currentTopicId = getActiveTopicId()
    // Null out first so breakpointRemoved events (synchronous) are ignored.
    setActiveTopicId(null)
    // Harvest floating items: save untracked IDE items to appropriate topics, then remove them
    // from the IDE so they don't leak into the target view. Safe to run here because
    // activeTopicId is null, so synchronous breakpointRemoved events are ignored.
    harvestFloatingItems(currentTopicId)
    if (currentTopicId != null) {
      val bookmarksToRemove = getTopicBookmarks(currentTopicId)
      // Register suppressions before removing: BookmarksManager fires bookmarkRemoved via
      // invokeLater (async), so the events arrive after this method returns. The listener will
      // consume each entry instead of mirroring it back into the in-memory store.
      suppressBookmarkRemovals(bookmarksToRemove)
      ideManager.removeBreakpointDefs(getTopicBreakpoints(currentTopicId))
      ideManager.removeBookmarkDefs(bookmarksToRemove)
    }
    // Set target before adding so breakpointAdded/bookmarkAdded events sync to the right topic.
    setActiveTopicId(targetTopicId)
    if (targetTopicId != null) {
      // Checking out a closed topic reopens it automatically.
      if (breakpointManager.getTopic(targetTopicId)?.status == TopicStatus.CLOSE) {
        breakpointManager.updateTopicStatus(targetTopicId, TopicStatus.OPEN)
      }
      val targetTopicName = breakpointManager.getTopic(targetTopicId)?.name
      ideManager.addBreakpointDefs(getTopicBreakpoints(targetTopicId))
      ideManager.addBookmarkDefs(getTopicBookmarks(targetTopicId), targetTopicName)
    }
    syncState()
    markerTracker.initForOpenFiles()
  }

  /**
   * Saves IDE breakpoints/bookmarks not tracked by any debug-map topic ("floating") into the
   * appropriate topic, then removes them from the IDE so they don't persist into the next view.
   *
   * - Floating breakpoints go into [currentTopicId] (skipped if no current topic).
   * - Floating bookmarks are routed by IDE bookmark group name to a matching debug-map topic,
   *   falling back to [currentTopicId].
   * - If the destination topic already has an entry at the same (file, line), the floating item
   *   is a duplicate: it is removed from the IDE without being saved.
   *
   * Must be called after [setActiveTopicId] has been nulled out so that synchronous
   * breakpointRemoved events are suppressed. Bookmark removals (async via invokeLater) are
   * registered for suppression here before being fired, for the same reason.
   */
  private fun harvestFloatingItems(currentTopicId: Int?) {
    // 1. Floating breakpoints → current topic
    val floatingBreakpoints = ideManager.allLineBreakpoints()
      .filter { !breakpointManager.breakpointExists(it.fileUrl, it.line, it.column(ideManager)) }
    for (bp in floatingBreakpoints) {
      val fileUrl = bp.fileUrl
      val line = bp.line
      val column = bp.column(ideManager)
      if (currentTopicId != null) {
        val alreadyInTopic = breakpointManager.getTopicBreakpoints(currentTopicId)
          .any { it.fileUrl == fileUrl && it.line == line && it.column == column }
        if (!alreadyInTopic) {
          breakpointManager.upsertBreakpointInTopic(
            currentTopicId,
            BreakpointDef(
              topicId = currentTopicId,
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
      // Always remove from IDE; breakpointRemoved event is ignored (activeTopicId is null).
      ideManager.removeBreakpointDefs(listOf(BreakpointDef(topicId = 0, fileUrl = fileUrl, line = line, column = column)))
    }

    // 2. Floating bookmarks → name-matched topic or current topic
    val manager = BookmarksManager.getInstance(project) ?: return
    val floatingBookmarks = ideManager.allLineBookmarks()
      .filter { (_, bm) -> breakpointManager.getBookmarkTopicId(bm.file.url, bm.line) == null }
    if (floatingBookmarks.isEmpty()) return

    // Suppress async bookmarkRemoved events: invokeLater fires after checkout() returns,
    // at which point activeTopicId is restored to targetTopicId.
    suppressBookmarkRemovals(floatingBookmarks.map { (_, bm) ->
      BookmarkDef(topicId = 0, fileUrl = bm.file.url, line = bm.line, name = null, type = BookmarkType.DEFAULT)
    })
    for ((ideGroup, bookmark) in floatingBookmarks) {
      val fileUrl = bookmark.file.url
      val line = bookmark.line
      val destTopicId: Int = if (ideGroup.name.isBlank()) {
        currentTopicId ?: continue
      }
      else {
        breakpointManager.getTopicIdByName(ideGroup.name) ?: currentTopicId ?: continue
      }
      val alreadyInTopic = breakpointManager.getTopicBookmarks(destTopicId)
        .any { it.fileUrl == fileUrl && it.line == line }
      if (!alreadyInTopic) {
        val type = manager.getType(bookmark) ?: BookmarkType.DEFAULT
        val name = ideGroup.getDescription(bookmark)
        breakpointManager.upsertBookmarkInTopic(
          destTopicId,
          BookmarkDef(
            topicId = destTopicId,
            fileUrl = fileUrl,
            line = line,
            name = name?.ifEmpty { null },
            type = type,
          )
        )
      }
      // Always remove from IDE; suppression above handles the async bookmarkRemoved callback.
      ideManager.removeBookmarkDefs(listOf(BookmarkDef(topicId = destTopicId, fileUrl = fileUrl, line = line, name = null, type = BookmarkType.DEFAULT)))
    }
  }

  internal fun onFileOpened(file: VirtualFile) = markerTracker.onFileOpened(file)
  internal fun onFileClosed(file: VirtualFile) = markerTracker.onFileClosed(file)
  internal fun getCurrentLine(topicId: Int, def: BreakpointDef) = markerTracker.getCurrentLine(topicId, def)
  internal fun dropFileEntries(fileUrl: String) = markerTracker.dropFileEntries(fileUrl)

  /**
   * Ensures there is always at least one topic and an active topic.
   */
  private fun ensureDefaultTopic() {
    val activeTopicId = breakpointManager.activeTopicId

    if (breakpointManager.getTopics().isEmpty()
        || (activeTopicId == null)
        || (breakpointManager.getTopic(activeTopicId) == null)) {
      val id = breakpointManager.createTopic("Default")
      breakpointManager.activeTopicId = id
    }
  }

  /**
   * Imports IDE line breakpoints and bookmarks that are not yet assigned to any topic.
   * - Floating breakpoints (not in any debug-map topic) go into the active topic.
   * - Bookmarks are imported preserving the IDE bookmark group structure: named IDE bookmark
   *   groups are matched to existing debug-map topics by name, or a new topic is created.
   *   Bookmarks in unnamed groups fall back to the active topic.
   *
   * Must be called after the project is fully opened so that [XBreakpointManagerImpl] and
   * [BookmarksManager] have loaded their state from disk.
   */
  internal fun importFloatingItemsAtStartup() {
    ensureDefaultTopic()
    val activeTopicId = breakpointManager.activeTopicId ?: return

    // 1. Import floating breakpoints → active topic
    ideManager.allLineBreakpoints()
      .filter { !breakpointManager.isActiveTopicBreakpoint(it.fileUrl, it.line, it.column(ideManager)) }
      .forEach { bp ->
        breakpointManager.upsertBreakpointInTopic(
          activeTopicId,
          BreakpointDef(
            topicId = activeTopicId,
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
        if (breakpointManager.getBookmarkTopicId(fileUrl, line) != null) continue

        val targetTopicId: Int = if (ideGroup.name.isBlank()) {
          activeTopicId
        }
        else {
          breakpointManager.getTopicIdByName(ideGroup.name)
          ?: breakpointManager.createTopic(ideGroup.name)
        }

        val type = manager.getType(bookmark) ?: BookmarkType.DEFAULT
        val name = ideGroup.getDescription(bookmark)
        breakpointManager.upsertBookmarkInTopic(
          targetTopicId,
          BookmarkDef(
            topicId = targetTopicId,
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
