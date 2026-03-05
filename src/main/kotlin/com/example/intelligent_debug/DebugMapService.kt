package com.example.intelligent_debug

import com.example.intelligent_debug.manager.BreakpointDefManager
import com.example.intelligent_debug.manager.GroupManager
import com.example.intelligent_debug.model.BreakpointDef
import com.example.intelligent_debug.model.GroupData
import com.example.intelligent_debug.model.PersistedBreakpoint
import com.example.intelligent_debug.model.PersistedGroup
import com.example.intelligent_debug.model.PersistedState
import com.example.intelligent_debug.sync.BreakpointIdeManager
import com.example.intelligent_debug.sync.BreakpointIdeSyncer
import com.example.intelligent_debug.sync.BreakpointMarkerTracker
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
@State(name = "DebugMap", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class DebugMapService(val project: Project) : PersistentStateComponent<PersistedState>, Disposable {

  private val _groups = MutableStateFlow<List<GroupData>>(emptyList())
  val groups: StateFlow<List<GroupData>> = _groups.asStateFlow()

  private val _activeGroupId = MutableStateFlow<Int?>(null)
  val activeGroupId: StateFlow<Int?> = _activeGroupId.asStateFlow()

  companion object {
    fun getInstance(project: Project): DebugMapService =
      project.getService(DebugMapService::class.java)
  }

  private val groupManager = GroupManager()
  private val breakpointDefManager = BreakpointDefManager()
  private val ideManager = BreakpointIdeManager(project)
  private val ideSyncer = BreakpointIdeSyncer(project)
  private val markerTracker = BreakpointMarkerTracker(this)

  init {
    // Only initializes in-memory state; does NOT call syncState() so the StateFlow
    // keeps its emptyList() default until loadState() (or noStateLoaded) runs below.
    ensureDefaultGroup()
  }

  override fun dispose() {
  }

  private fun syncState() {
    _groups.value = groupManager.getGroups().map { group ->
      group.copy(breakpoints = breakpointDefManager.getGroupBreakpoints(group.id))
    }
    _activeGroupId.value = groupManager.activeGroupId
  }

  /**
   * Sets activeGroupId directly on the manager without touch or syncState.
   * Called by [BreakpointIdeSyncer] to drive listener behaviour during checkout phases.
   */
  internal fun setActiveGroupId(groupId: Int?) {
    groupManager.activeGroupId = groupId
  }

  override fun getState(): PersistedState = PersistedState().also { state ->
    state.nextGroupId = groupManager.nextGroupId
    state.activeGroupId = groupManager.activeGroupId ?: -1
    state.groups = groupManager.getGroupsSnapshot().values.map { group ->
      PersistedGroup().also { pg ->
        pg.id = group.id
        pg.name = group.name
        pg.createdAt = group.createdAt
        pg.lastActivatedAt = group.lastActivatedAt
        pg.breakpoints = breakpointDefManager.getGroupBreakpoints(group.id).map { def ->
          PersistedBreakpoint().also { pb ->
            pb.fileUrl = def.fileUrl
            pb.line = markerTracker.getCurrentLine(group.id, def)
            pb.typeId = def.typeId
            pb.condition = def.condition
            pb.logExpression = def.logExpression
            pb.name = def.name?.ifEmpty { null }
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
    val groupsSnapshot = state.groups.associate { pg ->
      pg.id to GroupData(
        id = pg.id,
        name = pg.name,
        createdAt = pg.createdAt,
        lastActivatedAt = pg.lastActivatedAt,
      )
    }
    val activeGroupId = if (state.activeGroupId == -1) null else state.activeGroupId
    groupManager.restore(groupsSnapshot, state.nextGroupId, activeGroupId)

    val breakpointsSnapshot = state.groups.associate { pg ->
      pg.id to pg.breakpoints.map { pb ->
        BreakpointDef(
          groupId = pg.id,
          fileUrl = pb.fileUrl,
          line = pb.line,
          typeId = pb.typeId,
          condition = pb.condition,
          logExpression = pb.logExpression,
          name = pb.name,
        )
      }
    }
    breakpointDefManager.restore(breakpointsSnapshot)
    ensureDefaultGroup()
    syncState()
  }

  val nextGroupId: Int get() = groupManager.nextGroupId

  fun createGroup(name: String): Int {
    val id = groupManager.createGroup(name.ifBlank { "Group ${groupManager.nextGroupId}" })
    breakpointDefManager.initGroup(id)
    syncState()
    return id
  }

  fun renameGroup(groupId: Int, name: String) {
    groupManager.renameGroup(groupId, name)
    syncState()
  }

  fun renameBreakpoint(def: BreakpointDef, name: String) {
    breakpointDefManager.upsertBreakpointInGroup(def.groupId, def.copy(name = name.trim()))
    syncState()
  }

  fun getGroups(): List<GroupData> = _groups.value
  fun groupExists(groupId: Int): Boolean = groupManager.groupExists(groupId)
  fun getActiveGroupId(): Int? = groupManager.activeGroupId

  /**
   * Deletes a group and its breakpoint definitions.
   * The active group cannot be deleted; callers must checkout a different group first.
   * Must be called within a writeAction.
   */
  fun deleteGroup(groupId: Int) {
    check(groupManager.activeGroupId != groupId) { "Cannot delete the active group; checkout another group first" }
    groupManager.deleteGroup(groupId)
    breakpointDefManager.removeGroup(groupId)
    syncState()
  }

  fun getGroupBreakpoints(groupId: Int): List<BreakpointDef> =
    breakpointDefManager.getGroupBreakpoints(groupId)

  fun getBreakpointsByFile(fileUrl: String): List<BreakpointDef> =
    breakpointDefManager.getBreakpointsByFile(fileUrl)

  fun upsertBreakpointInGroup(groupId: Int, def: BreakpointDef) {
    breakpointDefManager.upsertBreakpointInGroup(groupId, def)
    syncState()
  }

  fun removeBreakpointFromGroup(groupId: Int, fileUrl: String, line: Int) {
    breakpointDefManager.removeBreakpointFromGroup(groupId, fileUrl, line)
    syncState()
  }

  fun moveBreakpointLine(def: BreakpointDef, newLine: Int) {
    breakpointDefManager.moveBreakpointLine(def, newLine)
    syncState()
  }

  fun isGroupBreakpoint(fileUrl: String, line: Int): Boolean =
    breakpointDefManager.isGroupBreakpoint(fileUrl, line)

  fun getBreakpointGroupId(fileUrl: String, line: Int): Int? =
    breakpointDefManager.getBreakpointGroupId(fileUrl, line)

  /** Must be called within a writeAction. Switches active group and syncs IDE breakpoints. */
  fun checkout(targetGroupId: Int?) {
    markerTracker.flushAll()
    ideSyncer.checkout(targetGroupId)
    if (targetGroupId != null) groupManager.touchGroup(targetGroupId)
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
    if (groupManager.getGroups().isEmpty()) {
      val id = groupManager.createGroup("Default")
      breakpointDefManager.initGroup(id)
      groupManager.activeGroupId = id
    }
    else if (groupManager.activeGroupId == null) {
      groupManager.activeGroupId = groupManager.getGroups().first().id
    }
  }

  /**
   * Imports IDE line breakpoints that are not yet assigned to any group into the active group.
   * Must be called after the project is fully opened so that [XBreakpointManagerImpl] has
   * loaded its state from disk. Intended to be called from [BreakpointSyncStartupActivity].
   */
  internal fun importFloatingBreakpoints() {
    val targetGroupId = groupManager.activeGroupId ?: return
    ideManager.allLineBreakpoints()
      .filter { !breakpointDefManager.isGroupBreakpoint(it.fileUrl, it.line) }
      .forEach { bp ->
        breakpointDefManager.upsertBreakpointInGroup(
          targetGroupId,
          BreakpointDef(
            groupId = targetGroupId,
            fileUrl = bp.fileUrl,
            line = bp.line,
            typeId = bp.type.id,
            condition = bp.conditionExpression?.expression,
            logExpression = bp.logExpressionObject?.expression,
          )
        )
      }
    syncState()
  }
}
