package com.intellij.debugmap.manager

import com.intellij.debugmap.model.BookmarkDef
import com.intellij.debugmap.model.BreakpointDef
import com.intellij.debugmap.model.TopicData
import com.intellij.debugmap.model.TopicStatus
import com.intellij.debugmap.model.LocationDef
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class BreakpointManager {

  private val lock = ReentrantLock()

  /** Ordered list of topic IDs; front (index 0) = most recently created. */
  private val topicOrder = ArrayDeque<Int>()

  /** Primary store: id → TopicData for O(1) lookup. */
  private val topicMap = mutableMapOf<Int, TopicData>()
  private var _nextTopicId: Int = 1
  private var _activeTopicId: Int? = null

  /** Secondary index: fileUrl → all LocationDefs across all topics for fast file-based lookups. */
  private val fileMap = mutableMapOf<String, MutableList<LocationDef>>()

  /** Secondary index: topic name → topic id. Enforces name uniqueness. */
  private val nameToId = mutableMapOf<String, Int>()

  /** Secondary index: breakpoint id → BreakpointDef for O(1) id-based lookup. */
  private val idMap = mutableMapOf<Long, BreakpointDef>()

  /** Secondary index: bookmark id → BookmarkDef for O(1) id-based lookup. */
  private val bookmarkIdMap = mutableMapOf<Long, BookmarkDef>()

  val nextTopicId: Int get() = lock.withLock { _nextTopicId }
  var activeTopicId: Int?
    get() = lock.withLock { _activeTopicId }
    set(value) {
      lock.withLock {
        _activeTopicId = value
      }
    }

  // region Topics

  fun createTopic(name: String = ""): Int = lock.withLock {
    val id = _nextTopicId++
    val resolvedName = name.ifBlank { "Topic $id" }
    require(!nameToId.containsKey(resolvedName)) { "A topic named '$resolvedName' already exists" }
    topicMap[id] = TopicData(id = id, name = resolvedName)
    insertAtStartOfSection(id, TopicStatus.OPEN)
    nameToId[resolvedName] = id
    id
  }

  fun renameTopic(id: Int, name: String): Unit = lock.withLock {
    val topic = topicMap[id] ?: return@withLock
    require(nameToId[name] == null || nameToId[name] == id) { "A topic named '$name' already exists" }
    nameToId.remove(topic.name)
    nameToId[name] = id
    topicMap[id] = topic.copy(name = name)
  }

  fun updateTopicDescription(id: Int, description: String): Unit = lock.withLock {
    val topic = topicMap[id] ?: return@withLock
    topicMap[id] = topic.copy(description = description)
  }

  fun updateTopicStatus(id: Int, status: TopicStatus): Unit = lock.withLock {
    val topic = topicMap[id] ?: return@withLock
    topicMap[id] = topic.copy(status = status)
    topicOrder.remove(id)
    insertAtStartOfSection(id, status)
  }

  fun getTopic(id: Int): TopicData? = lock.withLock {
    topicMap[id]
  }

  fun getTopicIdByName(name: String): Int? = lock.withLock {
    nameToId[name]
  }

  /** Returns topics in display order: PIN section first, then OPEN, then CLOSE; within each section in user-defined order. */
  fun getTopics(): List<TopicData> = lock.withLock {
    topicOrder.map { topicMap[it]!! }
  }

  fun topicExists(topicId: Int): Boolean = lock.withLock { topicMap.containsKey(topicId) }

  fun deleteTopic(topicId: Int): Unit = lock.withLock {
    val topic = topicMap.remove(topicId) ?: return@withLock
    topicOrder.remove(topicId)
    nameToId.remove(topic.name)
    topic.breakpoints.forEach { def ->
      fileMap[def.fileUrl]?.remove(def)
      idMap.remove(def.id)
    }
    topic.bookmarks.forEach { def ->
      fileMap[def.fileUrl]?.remove(def)
      bookmarkIdMap.remove(def.id)
    }
    fileMap.entries.removeIf { (_, list) -> list.isEmpty() }
  }

  /** Returns topics in display order (most recently created first). */
  fun getTopicsSnapshot(): List<TopicData> = lock.withLock { topicOrder.map { topicMap[it]!! } }

  fun restore(snapshot: List<TopicData>, nextTopicId: Int, activeTopicId: Int?): Unit = lock.withLock {
    topicMap.clear()
    topicOrder.clear()
    fileMap.clear()
    nameToId.clear()
    idMap.clear()
    bookmarkIdMap.clear()
    snapshot.forEach { topic ->
      topicMap[topic.id] = topic
      topicOrder.addLast(topic.id)
      nameToId[topic.name] = topic.id
      topic.breakpoints.forEach { def ->
        fileMap.getOrPut(def.fileUrl) { mutableListOf() }.add(def)
        idMap[def.id] = def
      }
      topic.bookmarks.forEach { def ->
        fileMap.getOrPut(def.fileUrl) { mutableListOf() }.add(def)
        bookmarkIdMap[def.id] = def
      }
    }
    _nextTopicId = nextTopicId
    _activeTopicId = activeTopicId
  }

  fun getTopicBreakpoints(topicId: Int): List<BreakpointDef> = lock.withLock {
    topicMap[topicId]?.breakpoints ?: emptyList()
  }

  fun upsertBreakpointInTopic(topicId: Int, def: BreakpointDef): Unit = lock.withLock {
    val topic = topicMap[topicId] ?: return@withLock
    val list = topic.breakpoints.toMutableList()
    val idx = list.indexOfFirst { def.sameLocation(it) }
    val existing = list.getOrNull(idx)
    // Same location → preserve the existing id (and name if caller omitted it). New location → add as-is.
    val storedDef = when {
      existing == null -> def.copy(topicId = topicId)
      def.name == null -> def.copy(topicId = topicId, name = existing.name, id = existing.id)
      else -> def.copy(topicId = topicId, id = existing.id)
    }
    if (idx >= 0) list[idx] = storedDef else list.add(storedDef)
    topicMap[topicId] = topic.copy(breakpoints = list)
    idMap[storedDef.id] = storedDef
    fileMap.getOrPut(storedDef.fileUrl) { mutableListOf() }.apply {
      val fIdx = indexOfFirst { it.topicId == topicId && storedDef.sameLocation(it) && it is BreakpointDef }
      if (fIdx >= 0) set(fIdx, storedDef) else add(storedDef)
    }
  }

  fun removeBreakpointFromTopic(topicId: Int, fileUrl: String, line: Int, column: Int = 0): Unit = lock.withLock {
    val topic = topicMap[topicId] ?: return@withLock
    val removed = topic.breakpoints.filter { it.fileUrl == fileUrl && it.line == line && it.column == column }
    removed.forEach { idMap.remove(it.id) }
    topicMap[topicId] = topic.copy(breakpoints = topic.breakpoints - removed.toSet())
    fileMap[fileUrl]?.removeIf { it.topicId == topicId && it.line == line && it is BreakpointDef && it.column == column }
  }

  /** Finds a breakpoint by its stable primary key across all topics. */
  fun findBreakpointById(id: Long): BreakpointDef? = lock.withLock { idMap[id] }

  /**
   * Replaces the stored def for a breakpoint identified by [def.id].
   * Unlike [upsertBreakpointInTopic], preserves only the id — all other fields (including name) are taken from [def].
   * No-op if the id is not found.
   */
  fun replaceBreakpointDef(def: BreakpointDef): Unit = lock.withLock {
    val existing = idMap[def.id] ?: return@withLock
    require(def.fileUrl == existing.fileUrl) { "fileUrl must not change: ${existing.fileUrl} → ${def.fileUrl}" }
    val topic = topicMap[existing.topicId] ?: return@withLock
    val list = topic.breakpoints.toMutableList()
    val idx = list.indexOfFirst { it is BreakpointDef && (it as BreakpointDef).id == def.id }
    if (idx < 0) return@withLock
    list[idx] = def
    topicMap[existing.topicId] = topic.copy(breakpoints = list)
    idMap[def.id] = def
    fileMap[existing.fileUrl]?.apply {
      val fIdx = indexOfFirst { it.topicId == existing.topicId && it is BreakpointDef && it.id == def.id }
      if (fIdx >= 0) set(fIdx, def)
    }
  }


  fun getBreakpointsByFile(fileUrl: String): List<BreakpointDef> = lock.withLock {
    fileMap[fileUrl]?.filterIsInstance<BreakpointDef>() ?: emptyList()
  }

  fun isActiveTopicBreakpoint(fileUrl: String, line: Int, column: Int): Boolean = lock.withLock {
    fileMap[fileUrl]?.any { it is BreakpointDef && it.line == line && it.topicId == activeTopicId && it.column == column } ?: false
  }

  fun breakpointExists(fileUrl: String, line: Int, column: Int): Boolean = lock.withLock {
    fileMap[fileUrl]?.any { it is BreakpointDef && it.line == line && it.column == column } ?: false
  }

  fun getTopicBookmarks(topicId: Int): List<BookmarkDef> = lock.withLock {
    topicMap[topicId]?.bookmarks ?: emptyList()
  }

  /**
   * Adds or replaces a bookmark in [topicId].
   * Uniqueness key is (fileUrl, line). If [def.name] is null, the existing entry's name is preserved.
   */
  fun upsertBookmarkInTopic(topicId: Int, def: BookmarkDef): Unit = lock.withLock {
    val topic = topicMap[topicId] ?: return@withLock
    val list = topic.bookmarks.toMutableList()
    val idx = list.indexOfFirst { def.sameLocation(it) }
    val existing = list.getOrNull(idx)
    val storedDef = if (existing != null) def.copy(name = def.name ?: existing.name, id = existing.id) else def
    if (idx >= 0) list[idx] = storedDef else list.add(storedDef)
    topicMap[topicId] = topic.copy(bookmarks = list)
    bookmarkIdMap[storedDef.id] = storedDef
    fileMap.getOrPut(storedDef.fileUrl) { mutableListOf() }.apply {
      val fIdx = indexOfFirst { it.topicId == topicId && storedDef.sameLocation(it) && it is BookmarkDef }
      if (fIdx >= 0) set(fIdx, storedDef) else add(storedDef)
    }
  }

  fun removeBookmarkFromTopic(topicId: Int, fileUrl: String, line: Int): Unit = lock.withLock {
    val topic = topicMap[topicId] ?: return@withLock
    val removed = topic.bookmarks.filter { it.fileUrl == fileUrl && it.line == line }
    removed.forEach { bookmarkIdMap.remove(it.id) }
    topicMap[topicId] = topic.copy(bookmarks = topic.bookmarks - removed.toSet())
    fileMap[fileUrl]?.removeIf { it.topicId == topicId && it.line == line && it is BookmarkDef }
  }

  /** Finds a bookmark by its stable primary key across all topics. */
  fun findBookmarkById(id: Long): BookmarkDef? = lock.withLock { bookmarkIdMap[id] }

  /**
   * Replaces the stored def for a bookmark identified by [def.id] within the same topic.
   * All fields except [fileUrl] and [topicId] may change. No-op if the id is not found.
   */
  fun replaceBookmarkDef(def: BookmarkDef): Unit = lock.withLock {
    val existing = bookmarkIdMap[def.id] ?: return@withLock
    require(def.fileUrl == existing.fileUrl) { "fileUrl must not change: ${existing.fileUrl} → ${def.fileUrl}" }
    require(def.topicId == existing.topicId) { "topicId must not change via replaceBookmarkDef" }
    val topic = topicMap[existing.topicId] ?: return@withLock
    val list = topic.bookmarks.toMutableList()
    val idx = list.indexOfFirst { it.id == def.id }
    if (idx < 0) return@withLock
    list[idx] = def
    topicMap[existing.topicId] = topic.copy(bookmarks = list)
    bookmarkIdMap[def.id] = def
    fileMap[existing.fileUrl]?.apply {
      val fIdx = indexOfFirst { it.topicId == existing.topicId && it is BookmarkDef && it.id == def.id }
      if (fIdx >= 0) set(fIdx, def)
    }
  }

  fun getBookmarksByFile(fileUrl: String): List<BookmarkDef> = lock.withLock {
    fileMap[fileUrl]?.filterIsInstance<BookmarkDef>() ?: emptyList()
  }

  fun getBookmarkTopicId(fileUrl: String, line: Int): Int? = lock.withLock {
    fileMap[fileUrl]?.firstOrNull { it is BookmarkDef && it.line == line }?.topicId
  }

  fun moveBookmarkLine(def: BookmarkDef, newLine: Int): Unit = lock.withLock {
    val topic = topicMap[def.topicId] ?: return@withLock
    val list = topic.bookmarks.toMutableList()
    val idx = list.indexOfFirst { def.sameLocation(it) }
    if (idx < 0) return@withLock
    val movedDef = def.copy(line = newLine)
    list[idx] = movedDef
    topicMap[def.topicId] = topic.copy(bookmarks = list)
    fileMap[def.fileUrl]?.apply {
      val fIdx = indexOfFirst { it.topicId == def.topicId && def.sameLocation(it) && it is BookmarkDef }
      if (fIdx >= 0) set(fIdx, movedDef)
    }
  }

  fun getLocationsByFile(fileUrl: String): List<LocationDef> = lock.withLock {
    fileMap[fileUrl]?.toList() ?: emptyList()
  }

  /** Moves the topic by [delta] positions within its own status section. */
  fun reorderTopic(id: Int, delta: Int): Unit = lock.withLock {
    val status = topicMap[id]?.status ?: return@withLock
    val sameStatusIndices = topicOrder.indices.filter { topicMap[topicOrder[it]]?.status == status }
    val posInSection = sameStatusIndices.indexOfFirst { topicOrder[it] == id }
    val newPosInSection = posInSection + delta
    if (posInSection < 0 || newPosInSection < 0 || newPosInSection >= sameStatusIndices.size) return@withLock
    val rawIdx1 = sameStatusIndices[posInSection]
    val rawIdx2 = sameStatusIndices[newPosInSection]
    val tmp = topicOrder[rawIdx1]
    topicOrder[rawIdx1] = topicOrder[rawIdx2]
    topicOrder[rawIdx2] = tmp
  }

  fun reorderBookmark(topicId: Int, def: BookmarkDef, delta: Int): Unit = lock.withLock {
    val topic = topicMap[topicId] ?: return@withLock
    val list = topic.bookmarks.toMutableList()
    val idx = list.indexOfFirst { def.sameLocation(it) }
    val newIdx = idx + delta
    if (idx < 0 || newIdx < 0 || newIdx >= list.size) return@withLock
    val item = list.removeAt(idx)
    list.add(newIdx, item)
    topicMap[topicId] = topic.copy(bookmarks = list)
  }

  fun reorderBreakpoint(topicId: Int, def: BreakpointDef, delta: Int): Unit = lock.withLock {
    val topic = topicMap[topicId] ?: return@withLock
    val list = topic.breakpoints.toMutableList()
    val idx = list.indexOfFirst { def.sameLocation(it) }
    val newIdx = idx + delta
    if (idx < 0 || newIdx < 0 || newIdx >= list.size) return@withLock
    val item = list.removeAt(idx)
    list.add(newIdx, item)
    topicMap[topicId] = topic.copy(breakpoints = list)
  }

  /** Inserts [id] at the start of the [status] section in [topicOrder], maintaining PIN→OPEN→CLOSE order. */
  private fun insertAtStartOfSection(id: Int, status: TopicStatus) {
    val idx = topicOrder.indexOfFirst { (topicMap[it]?.status?.ordinal ?: Int.MAX_VALUE) >= status.ordinal }
    if (idx < 0) topicOrder.addLast(id) else topicOrder.add(idx, id)
  }
}
