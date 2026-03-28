package com.intellij.debugmap.manager

import com.intellij.debugmap.generateNanoId
import com.intellij.debugmap.model.BookmarkDef
import com.intellij.debugmap.model.BreakpointDef
import com.intellij.debugmap.model.LocationDef
import com.intellij.debugmap.model.TopicData
import com.intellij.debugmap.model.TopicStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class ParsedImport(
  val topics: List<TopicData>,
  val errors: List<String>,
)

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
  private val breakpointIdMap = mutableMapOf<String, BreakpointDef>()

  /** Secondary index: bookmark id → BookmarkDef for O(1) id-based lookup. */
  private val bookmarkIdMap = mutableMapOf<String, BookmarkDef>()

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
      breakpointIdMap.remove(def.id)
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
    breakpointIdMap.clear()
    bookmarkIdMap.clear()
    snapshot.forEach { topic ->
      topicMap[topic.id] = topic
      topicOrder.addLast(topic.id)
      nameToId[topic.name] = topic.id
      topic.breakpoints.forEach { def ->
        fileMap.getOrPut(def.fileUrl) { mutableListOf() }.add(def)
        breakpointIdMap[def.id] = def
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

  fun addBreakpointToTopic(topicId: Int, def: BreakpointDef): BreakpointDef? = lock.withLock {
    val topic = topicMap[topicId] ?: return@withLock null
    val duplicate = fileMap[def.fileUrl]?.any {
      it is BreakpointDef && it.topicId == topicId && it.line == def.line && it.column == def.column && !it.isStale
    } ?: false
    if (duplicate) return@withLock null
    val storedDef = def.copy(topicId = topicId, id = generateNanoId())
    topicMap[topicId] = topic.copy(breakpoints = topic.breakpoints + storedDef)
    breakpointIdMap[storedDef.id] = storedDef
    fileMap.getOrPut(storedDef.fileUrl) { mutableListOf() }.add(storedDef)
    storedDef
  }

  fun removeBreakpointFromTopic(topicId: Int, fileUrl: String, line: Int, column: Int = 0): Unit = lock.withLock {
    val topic = topicMap[topicId] ?: return@withLock
    val removed = topic.breakpoints.filter { it.fileUrl == fileUrl && it.line == line && it.column == column }
    removed.forEach { breakpointIdMap.remove(it.id) }
    topicMap[topicId] = topic.copy(breakpoints = topic.breakpoints - removed.toSet())
    fileMap[fileUrl]?.removeIf { it.topicId == topicId && it.line == line && it is BreakpointDef && it.column == column }
  }

  /** Finds a breakpoint by its stable primary key across all topics. */
  fun findBreakpointById(id: String): BreakpointDef? = lock.withLock { breakpointIdMap[id] }

  /** Finds the breakpoint at the given file position across all topics. Only non-stale entries are returned by default. */
  fun findBreakpointByLocation(fileUrl: String, line: Int, column: Int, isStale: Boolean = false): BreakpointDef? = lock.withLock {
    fileMap[fileUrl]?.filterIsInstance<BreakpointDef>()?.firstOrNull { it.line == line && it.column == column && it.isStale == isStale }
  }

  /** Finds the breakpoint at the given file position within a specific topic. Only non-stale entries are returned by default. */
  fun findBreakpointByLocation(fileUrl: String, line: Int, column: Int, topicId: Int, isStale: Boolean = false): BreakpointDef? = lock.withLock {
    fileMap[fileUrl]?.filterIsInstance<BreakpointDef>()?.firstOrNull { it.line == line && it.column == column && it.topicId == topicId && it.isStale == isStale }
  }

  /**
   * Generic update helper (must be called while [lock] is held).
   * Looks up [def] by id in [idMap], enforces that [LocationDef.fileUrl] and [LocationDef.topicId]
   * are immutable, replaces the entry in both the topic list and the secondary indexes, and returns
   * the stored def. Returns null if the id is not found or the topic is missing.
   */
  private fun <T : LocationDef> updateDefInTopic(
    def: T,
    idMap: MutableMap<String, T>,
    getList: (TopicData) -> List<T>,
    copyTopic: (TopicData, List<T>) -> TopicData,
  ): T? {
    val existing = idMap[def.id] ?: return null
    require(def.fileUrl == existing.fileUrl) { "fileUrl must not change: ${existing.fileUrl} → ${def.fileUrl}" }
    require(def.topicId == existing.topicId) { "topicId must not change: ${existing.topicId} → ${def.topicId}" }
    if (!def.isStale && (def.line != existing.line || existing.isStale)) {
      val wouldDuplicate = fileMap[def.fileUrl]?.any { other ->
        other.id != def.id && other.topicId == def.topicId && other.line == def.line && !other.isStale
      } ?: false
      if (wouldDuplicate) return null
    }
    val topic = topicMap[existing.topicId] ?: return null
    val list = getList(topic).toMutableList()
    val idx = list.indexOfFirst { it.id == def.id }
    if (idx < 0) return null
    list[idx] = def
    topicMap[existing.topicId] = copyTopic(topic, list)
    idMap[def.id] = def
    fileMap[existing.fileUrl]?.apply {
      val fIdx = indexOfFirst { it.id == def.id }
      if (fIdx >= 0) set(fIdx, def)
    }
    return def
  }

  /** Replaces the stored def for a breakpoint identified by [def.id]. Returns the stored def, or null if the id is not found. */
  fun updateBreakpoint(def: BreakpointDef): BreakpointDef? = lock.withLock {
    updateDefInTopic(def, breakpointIdMap, { it.breakpoints }, { topic, list -> topic.copy(breakpoints = list) })
  }


  fun getBreakpointsByFile(fileUrl: String): List<BreakpointDef> = lock.withLock {
    fileMap[fileUrl]?.filterIsInstance<BreakpointDef>() ?: emptyList()
  }

  fun isActiveTopicBreakpoint(fileUrl: String, line: Int, column: Int): Boolean = lock.withLock {
    fileMap[fileUrl]?.any { it is BreakpointDef && it.line == line && it.topicId == activeTopicId && it.column == column } ?: false
  }

  fun breakpointExists(fileUrl: String, line: Int, column: Int, isStale: Boolean = false): Boolean = lock.withLock {
    fileMap[fileUrl]?.any { it is BreakpointDef && it.line == line && it.column == column && it.isStale == isStale } ?: false
  }

  fun getTopicBookmarks(topicId: Int): List<BookmarkDef> = lock.withLock {
    topicMap[topicId]?.bookmarks ?: emptyList()
  }

  fun addBookmarkToTopic(topicId: Int, def: BookmarkDef): BookmarkDef? = lock.withLock {
    val topic = topicMap[topicId] ?: return@withLock null
    val duplicate = fileMap[def.fileUrl]?.any {
      it is BookmarkDef && it.topicId == topicId && it.line == def.line && !it.isStale
    } ?: false
    if (duplicate) return@withLock null
    val storedDef = def.copy(topicId = topicId, id = generateNanoId())
    topicMap[topicId] = topic.copy(bookmarks = topic.bookmarks + storedDef)
    bookmarkIdMap[storedDef.id] = storedDef
    fileMap.getOrPut(storedDef.fileUrl) { mutableListOf() }.add(storedDef)
    storedDef
  }

  fun removeBookmarkFromTopic(topicId: Int, fileUrl: String, line: Int): Unit = lock.withLock {
    val topic = topicMap[topicId] ?: return@withLock
    val removed = topic.bookmarks.filter { it.fileUrl == fileUrl && it.line == line }
    removed.forEach { bookmarkIdMap.remove(it.id) }
    topicMap[topicId] = topic.copy(bookmarks = topic.bookmarks - removed.toSet())
    fileMap[fileUrl]?.removeIf { it.topicId == topicId && it.line == line && it is BookmarkDef }
  }

  /** Finds a bookmark by its stable primary key across all topics. */
  fun findBookmarkById(id: String): BookmarkDef? = lock.withLock { bookmarkIdMap[id] }

  /** Finds the bookmark at the given file position across all topics. Only non-stale entries are returned by default. */
  fun findBookmarkByLocation(fileUrl: String, line: Int, isStale: Boolean = false): BookmarkDef? = lock.withLock {
    fileMap[fileUrl]?.filterIsInstance<BookmarkDef>()?.firstOrNull { it.line == line && it.isStale == isStale }
  }

  /** Finds the bookmark at the given file position within a specific topic. Only non-stale entries are returned by default. */
  fun findBookmarkByLocation(fileUrl: String, line: Int, topicId: Int, isStale: Boolean = false): BookmarkDef? = lock.withLock {
    fileMap[fileUrl]?.filterIsInstance<BookmarkDef>()?.firstOrNull { it.line == line && it.topicId == topicId && it.isStale == isStale }
  }

  /** Replaces the stored def for a bookmark identified by [def.id]. Returns the stored def, or null if the id is not found. */
  fun updateBookmark(def: BookmarkDef): BookmarkDef? = lock.withLock {
    updateDefInTopic(def, bookmarkIdMap, { it.bookmarks }, { topic, list -> topic.copy(bookmarks = list) })
  }

  fun getBookmarksByFile(fileUrl: String): List<BookmarkDef> = lock.withLock {
    fileMap[fileUrl]?.filterIsInstance<BookmarkDef>() ?: emptyList()
  }

  fun getBookmarkTopicId(fileUrl: String, line: Int): Int? = lock.withLock {
    fileMap[fileUrl]?.firstOrNull { it is BookmarkDef && it.line == line }?.topicId
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

  /** Sorts topics within each status section (PIN, OPEN, CLOSE) alphabetically by name. */
  fun sortTopicsByName(): Unit = lock.withLock {
    TopicStatus.entries.forEach { status ->
      val positions = topicOrder.indices.filter { topicMap[topicOrder[it]]?.status == status }
      val sorted = positions.map { topicOrder[it] }.sortedBy { topicMap[it]?.name?.lowercase() ?: "" }
      sorted.forEachIndexed { i, id -> topicOrder[positions[i]] = id }
    }
  }

  /** Sorts bookmarks in the given topic by name (name-less items sorted by file/line). */
  fun sortBookmarksByName(topicId: Int): Unit = lock.withLock {
    val topic = topicMap[topicId] ?: return@withLock
    val sorted = topic.bookmarks.sortedWith(Comparator { a, b ->
      val aHasName = !a.name.isNullOrBlank()
      val bHasName = !b.name.isNullOrBlank()
      when {
        aHasName && bHasName -> (a.name.lowercase().compareTo(b.name.lowercase())).takeIf { it != 0 } ?: a.compareTo(b)
        aHasName -> -1
        bHasName -> 1
        else -> a.compareTo(b)
      }
    })
    topicMap[topicId] = topic.copy(bookmarks = sorted)
  }

  /** Sorts bookmarks in the given topic by file (filename, full url, line). */
  fun sortBookmarksByFile(topicId: Int): Unit = lock.withLock {
    val topic = topicMap[topicId] ?: return@withLock
    topicMap[topicId] = topic.copy(bookmarks = topic.bookmarks.sorted())
  }

  /** Sorts breakpoints in the given topic by name (name-less items sorted by file/line). */
  fun sortBreakpointsByName(topicId: Int): Unit = lock.withLock {
    val topic = topicMap[topicId] ?: return@withLock
    val sorted = topic.breakpoints.sortedWith(Comparator { a, b ->
      val aHasName = !a.name.isNullOrBlank()
      val bHasName = !b.name.isNullOrBlank()
      when {
        aHasName && bHasName -> (a.name!!.lowercase().compareTo(b.name!!.lowercase())).takeIf { it != 0 } ?: a.compareTo(b)
        aHasName -> -1
        bHasName -> 1
        else -> a.compareTo(b)
      }
    })
    topicMap[topicId] = topic.copy(breakpoints = sorted)
  }

  /** Sorts breakpoints in the given topic by file (filename, full url, line). */
  fun sortBreakpointsByFile(topicId: Int): Unit = lock.withLock {
    val topic = topicMap[topicId] ?: return@withLock
    topicMap[topicId] = topic.copy(breakpoints = topic.breakpoints.sorted())
  }

  /** Moves [topicId] to the front of its status section. No-op if the topic does not exist. */
  fun promoteTopicToTop(topicId: Int): Unit = lock.withLock {
    val status = topicMap[topicId]?.status ?: return@withLock
    topicOrder.remove(topicId)
    insertAtStartOfSection(topicId, status)
  }

  /** Inserts [id] at the start of the [status] section in [topicOrder], maintaining PIN→OPEN→CLOSE order. */
  private fun insertAtStartOfSection(id: Int, status: TopicStatus) {
    val idx = topicOrder.indexOfFirst { (topicMap[it]?.status?.ordinal ?: Int.MAX_VALUE) >= status.ordinal }
    if (idx < 0) topicOrder.addLast(id) else topicOrder.add(idx, id)
  }

  companion object {

    fun exportTopicsToJson(topics: List<TopicData>): String = buildJsonObject {
      put("version", JsonPrimitive(1))
      put("topics", buildJsonArray { for (topic in topics) add(topic.toJson()) })
    }.toString()

    fun parseImportJson(json: String): ParsedImport {
      val errors = mutableListOf<String>()
      return try {
        val root = Json.parseToJsonElement(json).jsonObject
        val version = root["version"]?.jsonPrimitive?.intOrNull ?: 1
        if (version > 1) errors.add("File was saved with a newer format version ($version); some fields may not be imported.")
        val topicsArray = root["topics"]?.jsonArray
                          ?: return ParsedImport(emptyList(), listOf("Missing 'topics' field in JSON"))
        val topics = mutableListOf<TopicData>()
        topicsArray.forEachIndexed { i, el ->
          runCatching { topics.add(TopicData.fromJson(el.jsonObject, errors)) }
            .onFailure { errors.add("Topic #${i + 1}: ${it.message}") }
        }
        ParsedImport(topics, errors)
      }
      catch (e: Exception) {
        ParsedImport(emptyList(), listOf("Failed to parse JSON: ${e.message}"))
      }
    }
  }
}
