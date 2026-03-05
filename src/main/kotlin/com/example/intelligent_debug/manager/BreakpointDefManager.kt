package com.example.intelligent_debug.manager

import com.example.intelligent_debug.model.BreakpointDef
import java.util.TreeSet
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class BreakpointDefManager {

  private val lock = ReentrantLock()
  private val groupBreakpoints = mutableMapOf<Int, TreeSet<BreakpointDef>>()

  /** Secondary index: fileUrl → all BreakpointDefs across all groups for fast file-based lookups. */
  private val fileMap = mutableMapOf<String, MutableSet<BreakpointDef>>()

  fun initGroup(groupId: Int): TreeSet<BreakpointDef> = lock.withLock {
    groupBreakpoints.getOrPut(groupId) { TreeSet() }
  }

  fun getGroupBreakpoints(groupId: Int): List<BreakpointDef> = lock.withLock {
    groupBreakpoints[groupId]?.toList() ?: emptyList()
  }

  /**
   * Adds or replaces a breakpoint definition in [groupId].
   * Uniqueness key is (fileUrl, line). If [def.name] is null, the existing entry's name is preserved.
   */
  fun upsertBreakpointInGroup(groupId: Int, def: BreakpointDef): Boolean = lock.withLock {
    val set = groupBreakpoints.getOrPut(groupId) { TreeSet() }
    val existing = set.floor(def)?.takeIf { it.fileUrl == def.fileUrl && it.line == def.line }
    if (existing != null) set.remove(existing)
    val storedDef = if (existing != null && def.name == null) def.copy(name = existing.name) else def
    set.add(storedDef).also {
      fileMap.getOrPut(storedDef.fileUrl) { mutableSetOf() }.apply {
        removeIf { it.groupId == groupId && it.line == storedDef.line }
        add(storedDef)
      }
    }
  }

  fun removeBreakpointFromGroup(groupId: Int, fileUrl: String, line: Int): Boolean? = lock.withLock {
    groupBreakpoints[groupId]?.removeIf { it.fileUrl == fileUrl && it.line == line }?.also { removed ->
      if (removed) fileMap[fileUrl]?.removeIf { it.groupId == groupId && it.line == line }
    }
  }

  /**
   * Moves [def] to [newLine] within its group atomically, preserving [name].
   * Equivalent to remove(oldLine) + upsert(newLine) but in a single lock acquisition.
   */
  fun moveBreakpointLine(def: BreakpointDef, newLine: Int): Unit = lock.withLock {
    val groupId = def.groupId
    val set = groupBreakpoints[groupId] ?: return@withLock
    set.removeIf { it.fileUrl == def.fileUrl && it.line == def.line }
    fileMap[def.fileUrl]?.removeIf { it.groupId == groupId && it.line == def.line }
    val movedDef = def.copy(line = newLine)
    set.add(movedDef)
    fileMap.getOrPut(movedDef.fileUrl) { mutableSetOf() }.apply {
      removeIf { it.groupId == groupId && it.line == newLine }
      add(movedDef)
    }
  }

  fun getBreakpointsByFile(fileUrl: String): List<BreakpointDef> = lock.withLock {
    fileMap[fileUrl]?.toList() ?: emptyList()
  }

  fun isGroupBreakpoint(fileUrl: String, line: Int): Boolean = lock.withLock {
    fileMap[fileUrl]?.any { it.line == line } ?: false
  }

  fun getBreakpointGroupId(fileUrl: String, line: Int): Int? = lock.withLock {
    fileMap[fileUrl]?.firstOrNull { it.line == line }?.groupId
  }

  fun removeGroup(groupId: Int): TreeSet<BreakpointDef>? = lock.withLock {
    groupBreakpoints.remove(groupId)?.also { removed ->
      removed.forEach { def -> fileMap[def.fileUrl]?.remove(def) }
      fileMap.entries.removeIf { (_, set) -> set.isEmpty() }
    }
  }

  fun getAllGroupBreakpoints(): Map<Int, List<BreakpointDef>> = lock.withLock {
    groupBreakpoints.mapValues { it.value.toList() }
  }

  fun restore(snapshot: Map<Int, List<BreakpointDef>>): Unit = lock.withLock {
    groupBreakpoints.clear()
    fileMap.clear()
    snapshot.forEach { (groupId, defs) ->
      groupBreakpoints[groupId] = TreeSet<BreakpointDef>().also { it.addAll(defs) }
      defs.forEach { def -> fileMap.getOrPut(def.fileUrl) { mutableSetOf() }.add(def) }
    }
  }
}
