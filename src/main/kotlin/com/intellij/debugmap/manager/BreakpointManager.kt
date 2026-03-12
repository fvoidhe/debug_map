package com.intellij.debugmap.manager

import com.intellij.debugmap.model.BookmarkDef
import com.intellij.debugmap.model.BreakpointDef
import com.intellij.debugmap.model.GroupData
import com.intellij.debugmap.model.LocationDef
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class BreakpointManager {

  private val lock = ReentrantLock()

  /** Ordered list of group IDs; front (index 0) = most recently created. */
  private val groupOrder = ArrayDeque<Int>()

  /** Primary store: id → GroupData for O(1) lookup. */
  private val groupMap = mutableMapOf<Int, GroupData>()
  private var _nextGroupId: Int = 1
  private var _activeGroupId: Int? = null

  /** Secondary index: fileUrl → all LocationDefs across all groups for fast file-based lookups. */
  private val fileMap = mutableMapOf<String, MutableList<LocationDef>>()

  /** Secondary index: group name → group id. Enforces name uniqueness. */
  private val nameToId = mutableMapOf<String, Int>()

  val nextGroupId: Int get() = lock.withLock { _nextGroupId }
  var activeGroupId: Int?
    get() = lock.withLock { _activeGroupId }
    set(value) {
      lock.withLock {
        _activeGroupId = value
      }
    }

  // region Groups

  fun createGroup(name: String): Int = lock.withLock {
    require(!nameToId.containsKey(name)) { "A group named '$name' already exists" }
    val id = _nextGroupId++
    groupMap[id] = GroupData(id = id, name = name)
    groupOrder.addFirst(id)
    nameToId[name] = id
    id
  }

  fun renameGroup(id: Int, name: String): Unit = lock.withLock {
    val group = groupMap[id] ?: return@withLock
    require(nameToId[name] == null || nameToId[name] == id) { "A group named '$name' already exists" }
    nameToId.remove(group.name)
    nameToId[name] = id
    groupMap[id] = group.copy(name = name)
  }

  fun getGroup(id: Int): GroupData? = lock.withLock {
    groupMap[id]
  }

  fun getGroupIdByName(name: String): Int? = lock.withLock {
    nameToId[name]
  }

  fun getGroups(): List<GroupData> = lock.withLock {
    groupOrder.map { groupMap[it]!! }
  }

  fun groupExists(groupId: Int): Boolean = lock.withLock { groupMap.containsKey(groupId) }

  fun deleteGroup(groupId: Int): Unit = lock.withLock {
    val group = groupMap.remove(groupId) ?: return@withLock
    groupOrder.remove(groupId)
    nameToId.remove(group.name)
    group.breakpoints.forEach { def -> fileMap[def.fileUrl]?.remove(def) }
    group.bookmarks.forEach { def -> fileMap[def.fileUrl]?.remove(def) }
    fileMap.entries.removeIf { (_, list) -> list.isEmpty() }
  }

  /** Returns groups in display order (most recently created first). */
  fun getGroupsSnapshot(): List<GroupData> = lock.withLock { groupOrder.map { groupMap[it]!! } }

  fun restore(snapshot: List<GroupData>, nextGroupId: Int, activeGroupId: Int?): Unit = lock.withLock {
    groupMap.clear()
    groupOrder.clear()
    fileMap.clear()
    nameToId.clear()
    snapshot.forEach { group ->
      groupMap[group.id] = group
      groupOrder.addLast(group.id)
      nameToId[group.name] = group.id
      group.breakpoints.forEach { def -> fileMap.getOrPut(def.fileUrl) { mutableListOf() }.add(def) }
      group.bookmarks.forEach { def -> fileMap.getOrPut(def.fileUrl) { mutableListOf() }.add(def) }
    }
    _nextGroupId = nextGroupId
    _activeGroupId = activeGroupId
  }

  fun getGroupBreakpoints(groupId: Int): List<BreakpointDef> = lock.withLock {
    groupMap[groupId]?.breakpoints ?: emptyList()
  }

  fun upsertBreakpointInGroup(groupId: Int, def: BreakpointDef): Unit =
    upsertInGroup(groupId, def, { it.breakpoints }, { g, l -> g.copy(breakpoints = l) }) { copy(name = it) }

  fun removeBreakpointFromGroup(groupId: Int, fileUrl: String, line: Int, column: Int = 0): Unit = lock.withLock {
    val group = groupMap[groupId] ?: return@withLock
    groupMap[groupId] = group.copy(breakpoints = group.breakpoints.filter {
      !(it.fileUrl == fileUrl && it.line == line && it.column == column)
    })
    fileMap[fileUrl]?.removeIf { it.groupId == groupId && it.line == line && it is BreakpointDef && it.column == column }
  }

  /**
   * Moves [def] to [newLine] within its group atomically, preserving [name].
   */
  fun moveBreakpointLine(def: BreakpointDef, newLine: Int): Unit =
    moveLocationLine(def, newLine, { it.breakpoints }, { g, l -> g.copy(breakpoints = l) }) { copy(line = it) }

  fun getBreakpointsByFile(fileUrl: String): List<BreakpointDef> = lock.withLock {
    fileMap[fileUrl]?.filterIsInstance<BreakpointDef>() ?: emptyList()
  }

  fun isActiveGroupBreakpoint(fileUrl: String, line: Int, column: Int): Boolean = lock.withLock {
    fileMap[fileUrl]?.any { it is BreakpointDef && it.line == line && it.groupId == activeGroupId && it.column == column } ?: false
  }

  fun breakpointExists(fileUrl: String, line: Int, column: Int): Boolean = lock.withLock {
    fileMap[fileUrl]?.any { it is BreakpointDef && it.line == line && it.column == column } ?: false
  }

  fun getGroupBookmarks(groupId: Int): List<BookmarkDef> = lock.withLock {
    groupMap[groupId]?.bookmarks ?: emptyList()
  }

  /**
   * Adds or replaces a bookmark in [groupId].
   * Uniqueness key is (fileUrl, line). If [def.name] is null, the existing entry's name is preserved.
   */
  fun upsertBookmarkInGroup(groupId: Int, def: BookmarkDef): Unit =
    upsertInGroup(groupId, def, { it.bookmarks }, { g, l -> g.copy(bookmarks = l) }) { copy(name = it) }

  private inline fun <reified T : LocationDef> upsertInGroup(
    groupId: Int,
    def: T,
    getList: (GroupData) -> List<T>,
    updateGroup: (GroupData, List<T>) -> GroupData,
    withName: T.(String?) -> T,
  ): Unit = lock.withLock {
    val group = groupMap[groupId] ?: return@withLock
    val list = getList(group).toMutableList()
    val idx = list.indexOfFirst { def.sameLocation(it) }
    val existing = list.getOrNull(idx)
    val storedDef = if (existing != null && def.name == null) def.withName(existing.name) else def
    if (idx >= 0) list[idx] = storedDef else list.add(storedDef)
    groupMap[groupId] = updateGroup(group, list)
    fileMap.getOrPut(storedDef.fileUrl) { mutableListOf() }.apply {
      val fIdx = indexOfFirst { it.groupId == groupId && storedDef.sameLocation(it) && it is T }
      if (fIdx >= 0) set(fIdx, storedDef) else add(storedDef)
    }
  }

  fun removeBookmarkFromGroup(groupId: Int, fileUrl: String, line: Int): Unit = lock.withLock {
    val group = groupMap[groupId] ?: return@withLock
    groupMap[groupId] = group.copy(bookmarks = group.bookmarks.filter { !(it.fileUrl == fileUrl && it.line == line) })
    fileMap[fileUrl]?.removeIf { it.groupId == groupId && it.line == line && it is BookmarkDef }
  }

  fun getBookmarksByFile(fileUrl: String): List<BookmarkDef> = lock.withLock {
    fileMap[fileUrl]?.filterIsInstance<BookmarkDef>() ?: emptyList()
  }

  fun getBookmarkGroupId(fileUrl: String, line: Int): Int? = lock.withLock {
    fileMap[fileUrl]?.firstOrNull { it is BookmarkDef && it.line == line }?.groupId
  }

  fun moveBookmarkLine(def: BookmarkDef, newLine: Int): Unit =
    moveLocationLine(def, newLine, { it.bookmarks }, { g, l -> g.copy(bookmarks = l) }) { copy(line = it) }

  private inline fun <reified T : LocationDef> moveLocationLine(
    def: T,
    newLine: Int,
    getList: (GroupData) -> List<T>,
    updateGroup: (GroupData, List<T>) -> GroupData,
    copyWithLine: T.(Int) -> T,
  ): Unit = lock.withLock {
    val group = groupMap[def.groupId] ?: return@withLock
    val list = getList(group).toMutableList()
    val idx = list.indexOfFirst { def.sameLocation(it) }
    if (idx < 0) return@withLock
    val movedDef = def.copyWithLine(newLine)
    list[idx] = movedDef
    groupMap[def.groupId] = updateGroup(group, list)
    fileMap[def.fileUrl]?.apply {
      val fIdx = indexOfFirst { it.groupId == def.groupId && def.sameLocation(it) && it is T }
      if (fIdx >= 0) set(fIdx, movedDef)
    }
  }

  fun getLocationsByFile(fileUrl: String): List<LocationDef> = lock.withLock {
    fileMap[fileUrl]?.toList() ?: emptyList()
  }
}
