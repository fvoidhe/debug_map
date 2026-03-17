package com.intellij.debugmap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.intellij.debugmap.DebugMapBundle
import com.intellij.debugmap.DebugMapService
import com.intellij.debugmap.model.GroupData
import com.intellij.debugmap.ui.tree.BookmarkContextMenu
import com.intellij.debugmap.ui.tree.BookmarkRow
import com.intellij.debugmap.ui.tree.BreakpointContextMenu
import com.intellij.debugmap.ui.tree.BreakpointRow
import com.intellij.debugmap.ui.tree.GroupContextMenu
import com.intellij.debugmap.ui.tree.GroupRow
import com.intellij.debugmap.ui.tree.buildCopyText
import com.intellij.debugmap.ui.tree.copyToClipboard
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.foundation.lazy.tree.rememberTreeState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.styling.LazyTreeMetrics
import org.jetbrains.jewel.ui.component.styling.LazyTreeStyle
import org.jetbrains.jewel.ui.component.styling.SimpleListItemMetrics
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.treeStyle

internal enum class SelectionKind { NONE, GROUPS, BOOKMARKS, BREAKPOINTS }

internal fun computeSelectionKind(nodes: List<DebugMapNode>): SelectionKind = when {
  nodes.isEmpty() -> SelectionKind.NONE
  nodes.all { it is DebugMapNode.Group } -> SelectionKind.GROUPS
  nodes.all { it is DebugMapNode.BookmarkItem } -> SelectionKind.BOOKMARKS
  nodes.all { it is DebugMapNode.BreakpointItem } -> SelectionKind.BREAKPOINTS
  else -> SelectionKind.NONE
}

private data class RightClickInfo(val key: Any, val offset: Offset)

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun DebugMapToolWindow(project: Project) {
  val service = remember(project) { DebugMapService.getInstance(project) }
  val groups by service.groups.collectAsState()
  val activeGroupId by service.activeGroupId.collectAsState()
  val recentBreakpoints by service.recentBreakpoints.collectAsState()
  val recentBookmarks by service.recentBookmarks.collectAsState()
  var selectedNodes by remember { mutableStateOf<List<DebugMapNode>>(emptyList()) }
  val treeState = rememberTreeState()

  val selectionKind = computeSelectionKind(selectedNodes)
  val isSingle = selectedNodes.size == 1

  val baseStyle = JewelTheme.treeStyle
  val treeStyle = remember(baseStyle) {
    LazyTreeStyle(
      colors = baseStyle.colors,
      metrics = LazyTreeMetrics(
        indentSize = baseStyle.metrics.indentSize,
        elementMinHeight = baseStyle.metrics.elementMinHeight,
        chevronContentGap = baseStyle.metrics.chevronContentGap,
        simpleListItemMetrics = SimpleListItemMetrics(
          innerPadding = baseStyle.metrics.simpleListItemMetrics.innerPadding,
          outerPadding = PaddingValues(horizontal = 4.dp),
          selectionBackgroundCornerSize = baseStyle.metrics.simpleListItemMetrics.selectionBackgroundCornerSize,
          iconTextGap = baseStyle.metrics.simpleListItemMetrics.iconTextGap,
        ),
      ),
      icons = baseStyle.icons,
    )
  }

  val tree = remember(groups, activeGroupId, recentBreakpoints, recentBookmarks) {
    buildTree {
      for (group in groups) {
        addNode(
          data = DebugMapNode.Group(group.id, group.name, group.id == activeGroupId,
                                    group.bookmarks.size, group.breakpoints.size),
          id = "group-${group.id}",
        ) {
          for (bm in group.bookmarks) {
            val bmIndex = recentBookmarks.indexOfFirst { it.groupId == bm.groupId && it.fileUrl == bm.fileUrl && it.line == bm.line }
            addLeaf(
              data = DebugMapNode.BookmarkItem(bm, if (bmIndex != -1) bmIndex else null),
              id = "bm-${group.id}-${bm.fileUrl}-${bm.line}",
            )
          }
          for (bp in group.breakpoints) {
            val index = recentBreakpoints.indexOfFirst { it.groupId == bp.groupId && it.fileUrl == bp.fileUrl && it.line == bp.line && it.column == bp.column }
            val recentIndex = if (index != -1) index else null
            addLeaf(
              data = DebugMapNode.BreakpointItem(bp, recentIndex, group.id == activeGroupId),
              id = "bp-${group.id}-${bp.fileUrl}-${bp.line}-${bp.column}",
            )
          }
        }
      }
    }
  }

  val rightClickInfoState = remember { mutableStateOf<RightClickInfo?>(null) }
  val rightClickInfo by rightClickInfoState

  Column(modifier = Modifier.fillMaxSize()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      IconActionButton(
        key = AllIconsKeys.General.Add,
        contentDescription = "New Group",
        onClick = {
          val defaultName = "Group ${service.nextGroupId}"
          WriteAction.run<Exception> {
            val id = service.createGroup(defaultName)
            service.checkout(id)
          }
        },
      )
      IconActionButton(
        key = AllIconsKeys.General.Remove,
        contentDescription = "Delete",
        enabled = selectionKind != SelectionKind.NONE &&
                  !(selectionKind == SelectionKind.GROUPS &&
                    selectedNodes.any { (it as DebugMapNode.Group).id == activeGroupId }),
        onClick = {
          doDelete(selectedNodes, selectionKind, service, project, activeGroupId)
          selectedNodes = emptyList()
        },
      )
      IconActionButton(
        key = AllIconsKeys.Actions.CheckOut,
        contentDescription = "Checkout Group",
        enabled = selectionKind == SelectionKind.GROUPS && isSingle &&
                  (selectedNodes.firstOrNull() as? DebugMapNode.Group)?.id != activeGroupId,
        onClick = {
          val gId = (selectedNodes.firstOrNull() as? DebugMapNode.Group)?.id ?: return@IconActionButton
          WriteAction.run<Exception> { service.checkout(gId) }
        },
      )
      IconActionButton(
        key = AllIconsKeys.Actions.Edit,
        contentDescription = "Rename",
        enabled = selectionKind != SelectionKind.NONE && isSingle,
        onClick = { doRename(selectedNodes.firstOrNull(), project, service, groups) },
      )
    }

    Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())

    DebugMapLazyTree(
      tree = tree,
      modifier = Modifier.weight(1f).fillMaxWidth().onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (event.key) {
          Key.F2 -> {
            if (isSingle && selectionKind != SelectionKind.NONE) {
              doRename(selectedNodes.firstOrNull(), project, service, groups)
              true
            } else false
          }
          Key.Delete, Key.Backspace -> {
            val canDelete = selectionKind != SelectionKind.NONE &&
                            !(selectionKind == SelectionKind.GROUPS &&
                              selectedNodes.any { (it as DebugMapNode.Group).id == activeGroupId })
            if (canDelete) {
              doDelete(selectedNodes, selectionKind, service, project, activeGroupId)
              selectedNodes = emptyList()
              true
            } else false
          }
          Key.C -> {
            if ((event.isMetaPressed || event.isCtrlPressed) && isSingle) {
              val text = when (val node = selectedNodes.firstOrNull()) {
                is DebugMapNode.Group -> node.name
                is DebugMapNode.BookmarkItem -> buildCopyText("bookmark", service.buildReference(node.def.fileUrl, node.def.line), node.def.name)
                is DebugMapNode.BreakpointItem -> buildCopyText("breakpoint", service.buildReference(node.def.fileUrl, node.def.line), node.def.name)
                else -> null
              }
              if (text != null) { copyToClipboard(text); true } else false
            } else false
          }
          else -> false
        }
      },
      treeState = treeState,
      style = treeStyle,
      onRightClick = { key, offset -> rightClickInfoState.value = RightClickInfo(key, offset) },
      onSelectionChange = { elements ->
        selectedNodes = elements.map { it.data }
      },
      onElementDoubleClick = { element ->
        when (val node = element.data) {
          is DebugMapNode.BookmarkItem -> {
            service.addRecentBookmark(node.def)
            val file = VirtualFileManager.getInstance().findFileByUrl(node.def.fileUrl)
            if (file != null) OpenFileDescriptor(project, file, node.def.line, 0).navigate(true)
          }
          is DebugMapNode.BreakpointItem -> {
            val file = VirtualFileManager.getInstance().findFileByUrl(node.def.fileUrl)
            if (file != null) OpenFileDescriptor(project, file, node.def.line, 0).navigate(true)
          }
          else -> Unit
        }
      },
    ) { element ->
      val node = element.data
      Box(modifier = Modifier.fillMaxWidth()) {
        when (node) {
          is DebugMapNode.Group -> GroupRow(node)
          is DebugMapNode.BookmarkItem -> BookmarkRow(node, isSelected = isSelected)
          is DebugMapNode.BreakpointItem -> BreakpointRow(node, isSelected = isSelected)
        }
        val info = rightClickInfo
        if (info != null && info.key == element.id) {
          val effectiveNodes = if (selectedNodes.contains(node)) selectedNodes else listOf(node)
          val effectiveKind = computeSelectionKind(effectiveNodes)
          when (effectiveKind) {
            SelectionKind.GROUPS -> GroupContextMenu(
              nodes = effectiveNodes.filterIsInstance<DebugMapNode.Group>(),
              project = project,
              service = service,
              groups = groups,
              activeGroupId = activeGroupId,
              offset = info.offset,
              onDismiss = { rightClickInfoState.value = null },
            )
            SelectionKind.BOOKMARKS -> BookmarkContextMenu(
              nodes = effectiveNodes.filterIsInstance<DebugMapNode.BookmarkItem>(),
              project = project,
              service = service,
              groups = groups,
              activeGroupId = activeGroupId,
              offset = info.offset,
              onDismiss = { rightClickInfoState.value = null },
            )
            SelectionKind.BREAKPOINTS -> BreakpointContextMenu(
              nodes = effectiveNodes.filterIsInstance<DebugMapNode.BreakpointItem>(),
              project = project,
              service = service,
              groups = groups,
              activeGroupId = activeGroupId,
              offset = info.offset,
              onDismiss = { rightClickInfoState.value = null },
            )
            SelectionKind.NONE -> rightClickInfoState.value = null
          }
        }
      }
    }

    val singleNode = selectedNodes.firstOrNull()
    if (isSingle && singleNode != null && (selectionKind == SelectionKind.BOOKMARKS || selectionKind == SelectionKind.BREAKPOINTS)) {
      Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
      DebugMapDetailPanel(node = singleNode, groups = groups)
    }
  }
}

private fun doDelete(
  nodes: List<DebugMapNode>,
  kind: SelectionKind,
  service: DebugMapService,
  project: Project,
  activeGroupId: Int?,
) {
  when (kind) {
    SelectionKind.GROUPS -> {
      val deletable = nodes.filterIsInstance<DebugMapNode.Group>().filter { it.id != activeGroupId }
      val anyNonEmpty = deletable.any { it.bookmarkCount > 0 || it.breakpointCount > 0 }
      val confirmed = !anyNonEmpty || Messages.showYesNoDialog(
        project,
        if (deletable.size == 1)
          DebugMapBundle.message("dialog.delete.group.message", deletable.first().name)
        else
          DebugMapBundle.message("dialog.delete.groups.message", deletable.size),
        DebugMapBundle.message("dialog.delete.group.title"),
        Messages.getWarningIcon(),
      ) == Messages.YES
      if (confirmed) WriteAction.run<Exception> {
        deletable.forEach { service.deleteGroup(it.id) }
      }
    }
    SelectionKind.BOOKMARKS -> WriteAction.run<Exception> {
      nodes.filterIsInstance<DebugMapNode.BookmarkItem>()
        .forEach { service.removeBookmarkByToolWindow(it.def.groupId, it.def.fileUrl, it.def.line) }
    }
    SelectionKind.BREAKPOINTS -> WriteAction.run<Exception> {
      nodes.filterIsInstance<DebugMapNode.BreakpointItem>()
        .forEach { service.removeBreakpointByToolWindow(it.def.groupId, it.def.fileUrl, it.def.line, it.def.column) }
    }
    SelectionKind.NONE -> Unit
  }
}

private fun doRename(node: DebugMapNode?, project: Project, service: DebugMapService, groups: List<GroupData>) {
  when (node) {
    is DebugMapNode.Group -> {
      val current = groups.find { it.id == node.id }?.name ?: return
      val name = Messages.showInputDialog(project,
                                          DebugMapBundle.message("dialog.rename.group.label"),
                                          DebugMapBundle.message("dialog.rename.group.title"),
                                          null,
                                          current,
                                          null) ?: return
      if (name.isNotBlank()) service.renameGroup(node.id, name)
    }
    is DebugMapNode.BookmarkItem -> {
      val current = node.def.name ?: ""
      val name = Messages.showInputDialog(project,
                                          DebugMapBundle.message("dialog.rename.bookmark.label"),
                                          DebugMapBundle.message("dialog.rename.bookmark.title"),
                                          null,
                                          current,
                                          null) ?: return
      service.renameBookmark(node.def, name)
    }
    is DebugMapNode.BreakpointItem -> {
      val current = node.def.name ?: ""
      val name = Messages.showInputDialog(project,
                                          DebugMapBundle.message("dialog.rename.breakpoint.label"),
                                          DebugMapBundle.message("dialog.rename.breakpoint.title"),
                                          null,
                                          current,
                                          null) ?: return
      service.renameBreakpoint(node.def, name)
    }
    null -> return
  }
}
