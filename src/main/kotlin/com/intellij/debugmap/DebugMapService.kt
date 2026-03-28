package com.intellij.debugmap

import com.intellij.debugmap.manager.BreakpointManager
import com.intellij.debugmap.manager.ParsedImport
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Service(Service.Level.PROJECT)
@State(name = "DebugMap", storages = [Storage(StoragePathMacros.PRODUCT_WORKSPACE_FILE)])
class DebugMapService(val project: Project) : PersistentStateComponent<PersistedState>, Disposable {

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
   * Bookmark (fileUrl, line) pairs that were removed from the IDE by checkout or file reload.
   * The listener consumes entries here instead of mirroring them back into the in-memory store,
   * preventing the async bookmarkRemoved callbacks from corrupting the old topic's data.
   */
  private val suppressedBookmarkRemovals: MutableSet<Pair<String, Int>> = ConcurrentHashMap.newKeySet()

  internal fun suppressBookmarkRemovals(defs: List<BookmarkDef>) {
    defs.forEach { suppressedBookmarkRemovals.add(it.fileUrl to it.line) }
  }

  internal fun consumeSuppressedBookmarkRemoval(fileUrl: String, line: Int): Boolean =
    suppressedBookmarkRemovals.remove(fileUrl to line)

  /**
   * Breakpoint (fileUrl, line, column) triples that were removed from the IDE by file reload.
   * The listener consumes entries here to prevent synchronous breakpointRemoved callbacks from
   * corrupting the in-memory store while the reload correction is in progress.
   */
  private val suppressedBreakpointRemovals: MutableSet<Triple<String, Int, Int>> = ConcurrentHashMap.newKeySet()

  internal fun suppressBreakpointRemovals(defs: List<BreakpointDef>) {
    defs.forEach { suppressedBreakpointRemovals.add(Triple(it.fileUrl, it.line, it.column)) }
  }

  internal fun consumeSuppressedBreakpointRemoval(fileUrl: String, line: Int, column: Int): Boolean =
    suppressedBreakpointRemovals.remove(Triple(fileUrl, line, column))

  fun addRecentBreakpoint(def: BreakpointDef) {
    if (isSessionStop) {
      isSessionStop = false
      recentBreakpointTracker.clear()
    }
    recentBreakpointTracker.add(def)
  }

  fun addRecentBookmark(def: BookmarkDef) {
    if (def.isStale) {
      return
    }
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
    ideManager.setDefaultGroup(topicName)
  }

  override fun getState(): PersistedState = PersistedState().also { state ->
    state.nextTopicId = breakpointManager.nextTopicId
    state.activeTopicId = breakpointManager.activeTopicId ?: -1
    state.topics = breakpointManager.getTopicsSnapshot().map { topic ->
      PersistedTopic().also { pg ->
        pg.id = topic.id
        pg.name = topic.name
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
            pb.logicalLocation = def.logicalLocation
            pb.content = def.content
            pb.linePsiStrings = def.linePsiStrings.toMutableList()
            pb.isStale = def.isStale
          }
        }.toMutableList()
        pg.bookmarks = topic.bookmarks.map { def ->
          PersistedBookmark().also { pb ->
            pb.fileUrl = def.fileUrl
            pb.line = def.line
            pb.name = def.name?.ifEmpty { null }
            pb.bookmarkType = def.type.name
            pb.id = def.id
            pb.logicalLocation = def.logicalLocation
            pb.content = def.content
            pb.linePsiStrings = def.linePsiStrings.toMutableList()
            pb.isStale = def.isStale
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
            id = pb.id.ifEmpty { generateNanoId() },
            logicalLocation = pb.logicalLocation,
            content = pb.content,
            linePsiStrings = pb.linePsiStrings,
            isStale = pb.isStale,
          )
        },
        bookmarks = pg.bookmarks.map { pb ->
          BookmarkDef(
            topicId = pg.id,
            fileUrl = pb.fileUrl,
            line = pb.line,
            name = pb.name,
            type = runCatching { BookmarkType.valueOf(pb.bookmarkType) }.getOrDefault(BookmarkType.DEFAULT),
            id = pb.id.ifEmpty { generateNanoId() },
            logicalLocation = pb.logicalLocation,
            content = pb.content,
            linePsiStrings = pb.linePsiStrings,
            isStale = pb.isStale,
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
      ideManager.renameGroup(oldName, name)
    }
    syncState()
  }

  fun updateTopicStatus(topicId: Int, status: TopicStatus) {
    breakpointManager.updateTopicStatus(topicId, status)
    syncState()
  }

  fun renameBreakpoint(def: BreakpointDef, name: String) {
    breakpointManager.updateBreakpoint(def.copy(name = name.trim()))
    syncState()
  }

  fun renameBookmark(def: BookmarkDef, name: String) {
    val trimmed = name.trim()
    breakpointManager.updateBookmark(def.copy(name = trimmed))
    if (def.topicId == breakpointManager.activeTopicId && !def.isStale) {
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

  fun findBreakpointById(id: String): BreakpointDef? =
    breakpointManager.findBreakpointById(id)

  fun findBookmarkById(id: String): BookmarkDef? =
    breakpointManager.findBookmarkById(id)

  fun getTopicBookmarks(topicId: Int): List<BookmarkDef> =
    breakpointManager.getTopicBookmarks(topicId)

  fun getBreakpointsByFile(fileUrl: String): List<BreakpointDef> =
    breakpointManager.getBreakpointsByFile(fileUrl)

  fun breakpointExists(fileUrl: String, line: Int, column: Int, isStale: Boolean = false): Boolean =
    breakpointManager.breakpointExists(fileUrl, line, column, isStale)

  fun findBreakpointByLocation(fileUrl: String, line: Int, column: Int, topicId: Int? = null, isStale: Boolean = false): BreakpointDef? =
    if (topicId != null) breakpointManager.findBreakpointByLocation(fileUrl, line, column, topicId, isStale)
    else breakpointManager.findBreakpointByLocation(fileUrl, line, column, isStale)

  fun findBookmarkByLocation(fileUrl: String, line: Int, topicId: Int? = null, isStale: Boolean = false): BookmarkDef? =
    if (topicId != null) breakpointManager.findBookmarkByLocation(fileUrl, line, topicId, isStale)
    else breakpointManager.findBookmarkByLocation(fileUrl, line, isStale)

  /** Called from the IDE listener when a new breakpoint is added. Does NOT push to IDE. */
  fun addBreakpointByIde(topicId: Int, def: BreakpointDef) {
    val anchor = buildSemanticAnchor(project, def.fileUrl, def.line)
    val enrichedDef = if (anchor != null) def.copy(logicalLocation = anchor.structuralPath,
                                                   content = anchor.content,
                                                   linePsiStrings = anchor.linePsiStrings)
    else def
    breakpointManager.addBreakpointToTopic(topicId, enrichedDef)
    syncState()
  }

  /** Called from the IDE listener when an existing breakpoint's properties change. [def] must carry the existing id. Does NOT push to IDE. */
  fun updateBreakpointByIde(def: BreakpointDef) {
    val anchor = buildSemanticAnchor(project, def.fileUrl, def.line)
    val enrichedDef = if (anchor != null) def.copy(logicalLocation = anchor.structuralPath,
                                                   content = anchor.content,
                                                   linePsiStrings = anchor.linePsiStrings)
    else def
    breakpointManager.updateBreakpoint(enrichedDef)
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
      ideManager.findLineBreakpoint(fileUrl, line, column)?.let { ideManager.removeBreakpoint(it) }
    }
    syncState()
  }

  fun moveBreakpointLine(def: BreakpointDef, newLine: Int): BreakpointDef? {
    val anchor = buildSemanticAnchor(project, def.fileUrl, newLine)
    val updatedDef = if (anchor != null)
      def.copy(line = newLine, logicalLocation = anchor.structuralPath, content = anchor.content, linePsiStrings = anchor.linePsiStrings)
    else
      def.copy(line = newLine)
    val stored = breakpointManager.updateBreakpoint(updatedDef)
    syncState()
    return stored
  }

  fun getBookmarksByFile(fileUrl: String): List<BookmarkDef> =
    breakpointManager.getBookmarksByFile(fileUrl)

  fun getBookmarkTopicId(fileUrl: String, line: Int): Int? =
    breakpointManager.getBookmarkTopicId(fileUrl, line)

  /** Called from the IDE listener when a new bookmark is added. Does NOT push to IDE. */
  fun addBookmarkByIde(topicId: Int, def: BookmarkDef) {
    val anchor = buildSemanticAnchor(project, def.fileUrl, def.line)
    val enrichedDef = if (anchor != null) def.copy(logicalLocation = anchor.structuralPath,
                                                   content = anchor.content,
                                                   linePsiStrings = anchor.linePsiStrings)
    else def
    breakpointManager.addBookmarkToTopic(topicId, enrichedDef)
    syncState()
  }

  /** Called from the IDE listener when an existing bookmark's properties change. [def] must carry the existing id. Does NOT push to IDE. */
  fun updateBookmarkByIde(def: BookmarkDef) {
    val anchor = buildSemanticAnchor(project, def.fileUrl, def.line)
    val enrichedDef = if (anchor != null) def.copy(logicalLocation = anchor.structuralPath,
                                                   content = anchor.content,
                                                   linePsiStrings = anchor.linePsiStrings)
    else def
    breakpointManager.updateBookmark(enrichedDef)
    syncState()
  }

  /** Called from the IDE listener: updates in-memory state and notifies tool windows. Does NOT push to IDE. */
  fun removeBookmarkByIde(topicId: Int, fileUrl: String, line: Int) {
    breakpointManager.removeBookmarkFromTopic(topicId, fileUrl, line)
    syncState()
  }

  /** Called from the tool window or MCP: replaces the def in-memory and applies all properties to the IDE breakpoint if the topic is active.
   *  Returns false if the manager rejected the update (e.g. a non-stale breakpoint already occupies the target line). */
  fun updateBreakpointByToolWindow(updatedDef: BreakpointDef): Boolean {
    val existingDef = breakpointManager.findBreakpointById(updatedDef.id)
    val stored = breakpointManager.updateBreakpoint(updatedDef) ?: return false
    if (stored.topicId == breakpointManager.activeTopicId) {
      if (existingDef != null && existingDef.line != stored.line) {
        // Line changed: the IDE breakpoint is still at the old line, so applyBreakpointProperties
        // (which looks up by new line) would miss it. Remove the old one and add the new one instead.
        // Skip the remove for stale defs: they were never added to the IDE, and their unreliable
        // line number could collide with an unrelated breakpoint at the same position.
        if (!existingDef.isStale) {
          ideManager.findLineBreakpoint(existingDef.fileUrl, existingDef.line, existingDef.column)
            ?.let { ideManager.removeBreakpoint(it) }
        }
        if (!stored.isStale) {
          ideManager.addBreakpointDefs(listOf(stored))
          val masterDef = stored.masterBreakpointId?.let { breakpointManager.findBreakpointById(it) }
          ideManager.applyBreakpointProperties(stored, masterDef)
        }
      }
      else if (!stored.isStale) {
        val masterDef = stored.masterBreakpointId?.let { breakpointManager.findBreakpointById(it) }
        ideManager.applyBreakpointProperties(stored, masterDef)
      }
    }
    syncState()
    return true
  }

  /** Called from the tool window or MCP: updates in-memory state, pushes to IDE if active, and notifies tool windows. Must be called within a writeAction. */
  fun addBreakpointByToolWindow(topicId: Int, def: BreakpointDef): BreakpointDef? {
    val anchor = buildSemanticAnchor(project, def.fileUrl, def.line)
    val enrichedDef = if (anchor != null) def.copy(logicalLocation = anchor.structuralPath,
                                                   content = anchor.content,
                                                   linePsiStrings = anchor.linePsiStrings)
    else def
    val storedDef = breakpointManager.addBreakpointToTopic(topicId, enrichedDef)
    if (topicId == breakpointManager.activeTopicId && storedDef != null) {
      ideManager.addBreakpointDefs(listOf(storedDef))
    }
    syncState()
    return storedDef
  }

  /**
   * Called from the tool window or MCP: updates a bookmark identified by its id within the same topic.
   * [existingDef] is the current state; [newDef] has the desired state (same fileUrl, topicId, and id).
   * Returns false if the manager rejected the update (e.g. a non-stale bookmark already occupies the target line).
   */
  fun updateBookmarkByToolWindow(existingDef: BookmarkDef, newDef: BookmarkDef): Boolean {
    require(existingDef.fileUrl == newDef.fileUrl) { "fileUrl must not change" }
    require(existingDef.id == newDef.id) { "id must not change" }
    require(existingDef.topicId == newDef.topicId) { "topicId must not change" }
    val isActive = existingDef.topicId == breakpointManager.activeTopicId

    if (isActive) {
      ideManager.removeBookmarkDefs(listOf(existingDef))
    }
    val stored = breakpointManager.updateBookmark(newDef)
    if (stored == null) {
      // Manager rejected — restore the IDE bookmark to avoid state divergence.
      if (isActive && !existingDef.isStale) {
        ideManager.addBookmarkDefs(listOf(existingDef))
      }
      return false
    }
    if (isActive && !stored.isStale) {
      ideManager.addBookmarkDefs(listOf(stored))
    }
    syncState()
    return true
  }

  /** Called from the tool window or MCP: updates in-memory state, pushes to IDE if active, and notifies tool windows. */
  fun addBookmarkByToolWindow(topicId: Int, def: BookmarkDef): BookmarkDef? {
    val anchor = buildSemanticAnchor(project, def.fileUrl, def.line)
    val enrichedDef = if (anchor != null) def.copy(logicalLocation = anchor.structuralPath,
                                                   content = anchor.content,
                                                   linePsiStrings = anchor.linePsiStrings)
    else def
    val storedDef = breakpointManager.addBookmarkToTopic(topicId, enrichedDef)
    if (topicId == breakpointManager.activeTopicId && storedDef != null) {
      ideManager.addBookmarkDefs(listOf(storedDef))
    }
    syncState()
    return storedDef
  }

  /** Called from the tool window: updates in-memory state, pushes to IDE, and notifies tool windows. */
  fun removeBookmarkByToolWindow(topicId: Int, fileUrl: String, line: Int) {
    breakpointManager.removeBookmarkFromTopic(topicId, fileUrl, line)
    if (topicId == breakpointManager.activeTopicId) {
      ideManager.removeBookmarkDefs(listOf(BookmarkDef(topicId, fileUrl, line, null, BookmarkType.DEFAULT)))
    }
    syncState()
  }

  /** Marks the breakpoint with the given id as stale. Only program logic should call this. */
  fun markBreakpointStale(id: String) {
    val def = breakpointManager.findBreakpointById(id) ?: return
    breakpointManager.updateBreakpoint(def.copy(isStale = true))
    syncState()
  }

  /** Marks the bookmark with the given id as stale. Only program logic should call this. */
  fun markBookmarkStale(id: String) {
    val def = breakpointManager.findBookmarkById(id) ?: return
    breakpointManager.updateBookmark(def.copy(isStale = true))
    syncState()
  }

  /**
   * Reactivates a stale breakpoint: clears [BreakpointDef.isStale] and, if the topic is active,
   * adds it to the IDE. Must be called within a writeAction.
   * Returns false if a non-stale breakpoint already occupies the same location.
   */
  fun reactivateBreakpoint(def: BreakpointDef): Boolean {
    val reactivated = def.copy(isStale = false)
    val stored = breakpointManager.updateBreakpoint(reactivated) ?: return false
    if (stored.topicId == breakpointManager.activeTopicId) {
      ideManager.addBreakpointDefs(listOf(stored))
    }
    syncState()
    return true
  }

  /**
   * Reactivates a stale bookmark: clears [BookmarkDef.isStale] and, if the topic is active,
   * adds it to the IDE. Must be called within a writeAction.
   * Returns false if a non-stale bookmark already occupies the same location.
   */
  fun reactivateBookmark(def: BookmarkDef): Boolean {
    val reactivated = def.copy(isStale = false)
    val stored = breakpointManager.updateBookmark(reactivated) ?: return false
    if (stored.topicId == breakpointManager.activeTopicId) {
      val topicName = breakpointManager.getTopic(stored.topicId)?.name
      ideManager.addBookmarkDefs(listOf(stored), topicName)
    }
    syncState()
    return true
  }

  fun moveBookmarkLine(def: BookmarkDef, newLine: Int): BookmarkDef? {
    val anchor = buildSemanticAnchor(project, def.fileUrl, newLine)
    val updatedDef = if (anchor != null)
      def.copy(line = newLine, logicalLocation = anchor.structuralPath, content = anchor.content, linePsiStrings = anchor.linePsiStrings)
    else
      def.copy(line = newLine)
    val stored = breakpointManager.updateBookmark(updatedDef)
    syncState()
    return stored
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

  fun sortTopicsByName() {
    breakpointManager.sortTopicsByName()
    syncState()
  }

  fun sortBookmarksByName(topicId: Int) {
    breakpointManager.sortBookmarksByName(topicId)
    syncState()
  }

  fun sortBookmarksByFile(topicId: Int) {
    breakpointManager.sortBookmarksByFile(topicId)
    syncState()
  }

  fun sortBreakpointsByName(topicId: Int) {
    breakpointManager.sortBreakpointsByName(topicId)
    syncState()
  }

  fun sortBreakpointsByFile(topicId: Int) {
    breakpointManager.sortBreakpointsByFile(topicId)
    syncState()
  }

  fun promoteTopicToTop(topicId: Int) {
    breakpointManager.promoteTopicToTop(topicId)
    syncState()
  }

  fun buildReference(fileUrl: String, line: Int): String = ideManager.buildReference(fileUrl, line)

  fun exportTopics(topicIds: List<Int>): String {
    val topics = topicIds.mapNotNull { breakpointManager.getTopic(it) }
    return BreakpointManager.exportTopicsToJson(topics)
  }

  fun parseImportJson(json: String): ParsedImport =
    BreakpointManager.parseImportJson(json)

  data class ImportResult(val count: Int, val skippedActiveName: String?)

  /**
   * Applies parsed import data. Topics whose names conflict with existing ones are overwritten
   * if [overwriteExisting] is true, otherwise skipped. The active topic is never overwritten.
   * Returns an [ImportResult] with the number of topics actually imported and the name of the
   * active topic that was skipped due to the active-topic guard, if any.
   */
  fun applyImport(importedTopics: List<TopicData>, overwriteExisting: Boolean): ImportResult {
    var count = 0
    var skippedActiveName: String? = null
    for (topic in importedTopics) {
      val existingId = breakpointManager.getTopicIdByName(topic.name)
      if (existingId != null) {
        if (!overwriteExisting) continue
        if (existingId == breakpointManager.activeTopicId) {
          skippedActiveName = topic.name
          continue
        }
        breakpointManager.deleteTopic(existingId)
      }
      val newId = breakpointManager.createTopic(topic.name)
      if (topic.status != TopicStatus.OPEN) breakpointManager.updateTopicStatus(newId, topic.status)
      for (bp in topic.breakpoints) {
        breakpointManager.addBreakpointToTopic(newId, bp.copy(topicId = newId))
      }
      for (bm in topic.bookmarks) {
        breakpointManager.addBookmarkToTopic(newId, bm.copy(topicId = newId))
      }
      count++
    }
    syncState()
    return ImportResult(count, skippedActiveName)
  }

  /** Must be called within a writeAction. Switches active topic and syncs IDE breakpoints. */
  fun checkout(targetTopicId: Int?) {
    markerTracker.flushAll()
    val currentTopicId = getActiveTopicId()
    // Null out first so breakpointRemoved events (synchronous) are ignored.
    setActiveTopicId(null)
    if (currentTopicId != null) {
      val bookmarksToRemove = getTopicBookmarks(currentTopicId).filter { !it.isStale }
      // Register suppressions before removing: BookmarksManager fires bookmarkRemoved via
      // invokeLater (async), so the events arrive after this method returns. The listener will
      // consume each entry instead of mirroring it back into the in-memory store.
      suppressBookmarkRemovals(bookmarksToRemove)
      ideManager.removeBreakpointDefs(getTopicBreakpoints(currentTopicId).filter { !it.isStale })
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
      ideManager.addBreakpointDefs(getTopicBreakpoints(targetTopicId).filter { !it.isStale })
      ideManager.addBookmarkDefs(getTopicBookmarks(targetTopicId).filter { !it.isStale }, targetTopicName)
    }
    syncState()
    markerTracker.initForOpenFiles()
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
        val def = BreakpointDef(
          topicId = activeTopicId,
          fileUrl = bp.fileUrl,
          line = bp.line,
          typeId = bp.type.id,
          condition = bp.conditionExpression?.expression,
          logExpression = bp.logExpressionObject?.expression,
          column = bp.column(ideManager),
        )
        val anchor = buildSemanticAnchor(project, def.fileUrl, def.line)
        breakpointManager.addBreakpointToTopic(
          activeTopicId,
          if (anchor != null) def.copy(logicalLocation = anchor.structuralPath,
                                       content = anchor.content,
                                       linePsiStrings = anchor.linePsiStrings)
          else def
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
        val def = BookmarkDef(
          topicId = targetTopicId,
          fileUrl = fileUrl,
          line = line,
          name = name?.ifEmpty { null },
          type = type,
        )
        val anchor = buildSemanticAnchor(project, fileUrl, line)
        breakpointManager.addBookmarkToTopic(
          targetTopicId,
          if (anchor != null) def.copy(logicalLocation = anchor.structuralPath,
                                       content = anchor.content,
                                       linePsiStrings = anchor.linePsiStrings)
          else def
        )
      }
    }

    syncState()
  }
}
