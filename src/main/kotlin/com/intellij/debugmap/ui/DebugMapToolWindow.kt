package com.intellij.debugmap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.intellij.debugmap.DebugMapBundle
import com.intellij.debugmap.DebugMapService
import com.intellij.debugmap.model.TopicData
import com.intellij.debugmap.ui.tree.BookmarkContextMenu
import com.intellij.debugmap.ui.tree.BookmarkRow
import com.intellij.debugmap.ui.tree.BreakpointContextMenu
import com.intellij.debugmap.ui.tree.BreakpointRow
import com.intellij.debugmap.ui.tree.TopicContextMenu
import com.intellij.debugmap.ui.tree.TopicRow
import com.intellij.debugmap.ui.tree.buildCopyText
import com.intellij.debugmap.ui.tree.copyToClipboard
import com.intellij.ide.FileSelectInContext
import com.intellij.ide.SelectInManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.ToolWindowId
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.foundation.lazy.tree.rememberTreeState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.SelectableIconActionButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.styling.LazyTreeMetrics
import org.jetbrains.jewel.ui.component.styling.LazyTreeStyle
import org.jetbrains.jewel.ui.component.styling.SimpleListItemMetrics
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.treeStyle

internal enum class SelectionKind { NONE, TOPICS, BOOKMARKS, BREAKPOINTS }

internal fun computeSelectionKind(nodes: List<DebugMapNode>): SelectionKind = when {
  nodes.isEmpty() -> SelectionKind.NONE
  nodes.all { it is DebugMapNode.Topic } -> SelectionKind.TOPICS
  nodes.all { it is DebugMapNode.BookmarkItem } -> SelectionKind.BOOKMARKS
  nodes.all { it is DebugMapNode.BreakpointItem } -> SelectionKind.BREAKPOINTS
  else -> SelectionKind.NONE
}

private data class RightClickInfo(val key: Any, val offset: Offset)

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun DebugMapToolWindow(project: Project) {
  val service = remember(project) { DebugMapService.getInstance(project) }
  val topics by service.topics.collectAsState()
  val activeTopicId by service.activeTopicId.collectAsState()
  val lastModifiedTopicId by service.lastModifiedTopicId.collectAsState()
  val recentBreakpoints by service.recentBreakpoints.collectAsState()
  val recentBookmarks by service.recentBookmarks.collectAsState()
  var selectedNodes by remember { mutableStateOf<List<DebugMapNode>>(emptyList()) }
  val treeState = rememberTreeState()
  var searchVisible by remember { mutableStateOf(false) }
  val searchFieldState = rememberTextFieldState()
  val searchText by remember { derivedStateOf { searchFieldState.text.toString() } }

  LaunchedEffect(searchVisible) {
    if (!searchVisible) searchFieldState.edit { delete(0, length) }
  }

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

  val tree = remember(topics, activeTopicId, lastModifiedTopicId, recentBreakpoints, recentBookmarks, searchText) {
    buildTree {
      for (topic in topics) {
        val isActive = topic.id == activeTopicId
        val isMcpModified = !isActive && topic.id == lastModifiedTopicId
        val topicNode = DebugMapNode.Topic(
          id = topic.id,
          name = topic.name,
          isActive = isActive,
          status = topic.status,
          bookmarkCount = topic.bookmarks.size,
          breakpointCount = topic.breakpoints.size,
          isMcpModified = isMcpModified,
        )
        val topicMatches = matchesSearch(searchText, topic.name)
        val filteredBookmarks = (when {
          searchText.isBlank() || topicMatches -> topic.bookmarks
          else -> topic.bookmarks.filter { bm ->
            matchesSearch(searchText, bm.name, bm.fileUrl.substringAfterLast('/'))
          }
        }).sortedBy { if (it.isStale) 1 else 0 }
        val filteredBreakpoints = (when {
          searchText.isBlank() || topicMatches -> topic.breakpoints
          else -> topic.breakpoints.filter { bp ->
            matchesSearch(searchText, bp.name, bp.fileUrl.substringAfterLast('/'), bp.condition, bp.logExpression)
          }
        }).sortedBy { if (it.isStale) 1 else 0 }
        if (searchText.isBlank() || topicMatches || filteredBookmarks.isNotEmpty() || filteredBreakpoints.isNotEmpty()) {
          addNode(data = topicNode, id = "topic-${topic.id}") {
            for (bm in filteredBookmarks) {
              val bmIndex = recentBookmarks.indexOfFirst { it.topicId == bm.topicId && it.fileUrl == bm.fileUrl && it.line == bm.line }
              addLeaf(
                data = DebugMapNode.BookmarkItem(bm, if (bmIndex != -1) bmIndex else null),
                id = "bm-${bm.id}",
              )
            }
            for (bp in filteredBreakpoints) {
              val index =
                recentBreakpoints.indexOfFirst { it.topicId == bp.topicId && it.fileUrl == bp.fileUrl && it.line == bp.line && it.column == bp.column }
              val recentIndex = if (index != -1) index else null
              addLeaf(
                data = DebugMapNode.BreakpointItem(bp, recentIndex, isActive),
                id = "bp-${bp.id}",
              )
            }
          }
        }
      }
    }
  }

  LaunchedEffect(searchText) {
    if (searchText.isNotBlank()) {
      treeState.openNodes(topics.map { "topic-${it.id}" })
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
        contentDescription = "New Topic",
        onClick = {
          WriteAction.run<Exception> {
            val id = service.createTopic()
            service.checkout(id)
          }
        },
      )
      IconActionButton(
        key = AllIconsKeys.General.Remove,
        contentDescription = "Delete",
        enabled = selectionKind != SelectionKind.NONE &&
                  !(selectionKind == SelectionKind.TOPICS &&
                    selectedNodes.any { (it as DebugMapNode.Topic).id == activeTopicId }),
        onClick = {
          doDelete(selectedNodes, selectionKind, service, project, activeTopicId)
          selectedNodes = emptyList()
        },
      )
      val checkoutTopicId: Int? = if (!isSingle) null
      else when (val node = selectedNodes.firstOrNull()) {
        is DebugMapNode.Topic -> node.id
        is DebugMapNode.BookmarkItem -> node.def.topicId
        is DebugMapNode.BreakpointItem -> node.def.topicId
        else -> null
      }
      IconActionButton(
        key = AllIconsKeys.Actions.CheckOut,
        contentDescription = "Checkout Topic",
        enabled = checkoutTopicId != null && checkoutTopicId != activeTopicId,
        onClick = {
          val tId = checkoutTopicId ?: return@IconActionButton
          WriteAction.run<Exception> { service.checkout(tId) }
        },
      )
      IconActionButton(
        key = AllIconsKeys.Actions.Edit,
        contentDescription = "Rename",
        enabled = selectionKind != SelectionKind.NONE && isSingle,
        onClick = { doRename(selectedNodes.firstOrNull(), project, service, topics) },
      )
      SelectableIconActionButton(
        key = AllIconsKeys.Actions.Find,
        contentDescription = "Search",
        selected = searchVisible,
        onClick = { searchVisible = !searchVisible },
      )
      val locateFileUrl: String? = if (!isSingle) null
      else when (val node = selectedNodes.firstOrNull()) {
        is DebugMapNode.BookmarkItem -> node.def.fileUrl
        is DebugMapNode.BreakpointItem -> node.def.fileUrl
        else -> null
      }
      IconActionButton(
        key = AllIconsKeys.General.Locate,
        contentDescription = "Select in Project View",
        enabled = locateFileUrl != null,
        onClick = {
          val fileUrl = locateFileUrl ?: return@IconActionButton
          val file = VirtualFileManager.getInstance().findFileByUrl(fileUrl) ?: return@IconActionButton
          val target = SelectInManager.findSelectInTarget(ToolWindowId.PROJECT_VIEW, project) ?: return@IconActionButton
          target.selectIn(FileSelectInContext(project, file, null), true)
        },
      )
    }

    Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())

    if (searchVisible) {
      TextField(
        state = searchFieldState,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        placeholder = { Text("Search...") },
      )
      Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
    }

    DebugMapLazyTree(
      tree = tree,
      modifier = Modifier.weight(1f).fillMaxWidth()
        .onPreviewKeyEvent { event ->
          if (event.type != KeyEventType.KeyDown || !event.isAltPressed) return@onPreviewKeyEvent false
          when (event.key) {
            Key.DirectionUp -> {
              if (isSingle && selectionKind == SelectionKind.BOOKMARKS) {
                val node = selectedNodes.firstOrNull() as? DebugMapNode.BookmarkItem ?: return@onPreviewKeyEvent false
                val bookmarks = topics.find { it.id == node.def.topicId }?.bookmarks ?: return@onPreviewKeyEvent false
                val idx = bookmarks.indexOfFirst { it.fileUrl == node.def.fileUrl && it.line == node.def.line }
                if (idx > 0) { service.reorderBookmark(node.def.topicId, node.def, -1); true } else false
              }
              else if (isSingle && selectionKind == SelectionKind.TOPICS) {
                val node = selectedNodes.firstOrNull() as? DebugMapNode.Topic ?: return@onPreviewKeyEvent false
                val idx = topics.indexOfFirst { it.id == node.id }
                val nodeStatus = topics.getOrNull(idx)?.status
                if (idx > 0 && topics.getOrNull(idx - 1)?.status == nodeStatus) { service.reorderTopic(node.id, -1); true } else false
              }
              else if (isSingle && selectionKind == SelectionKind.BREAKPOINTS) {
                val node = selectedNodes.firstOrNull() as? DebugMapNode.BreakpointItem ?: return@onPreviewKeyEvent false
                val breakpoints = topics.find { it.id == node.def.topicId }?.breakpoints ?: return@onPreviewKeyEvent false
                val idx = breakpoints.indexOfFirst { it.fileUrl == node.def.fileUrl && it.line == node.def.line && it.column == node.def.column }
                if (idx > 0) { service.reorderBreakpoint(node.def.topicId, node.def, -1); true } else false
              }
              else false
            }
            Key.DirectionDown -> {
              if (isSingle && selectionKind == SelectionKind.BOOKMARKS) {
                val node = selectedNodes.firstOrNull() as? DebugMapNode.BookmarkItem ?: return@onPreviewKeyEvent false
                val bookmarks = topics.find { it.id == node.def.topicId }?.bookmarks ?: return@onPreviewKeyEvent false
                val idx = bookmarks.indexOfFirst { it.fileUrl == node.def.fileUrl && it.line == node.def.line }
                if (idx >= 0 && idx < bookmarks.size - 1) { service.reorderBookmark(node.def.topicId, node.def, 1); true } else false
              }
              else if (isSingle && selectionKind == SelectionKind.TOPICS) {
                val node = selectedNodes.firstOrNull() as? DebugMapNode.Topic ?: return@onPreviewKeyEvent false
                val idx = topics.indexOfFirst { it.id == node.id }
                val nodeStatus = topics.getOrNull(idx)?.status
                if (idx >= 0 && idx < topics.size - 1 && topics.getOrNull(idx + 1)?.status == nodeStatus) { service.reorderTopic(node.id, 1); true } else false
              }
              else if (isSingle && selectionKind == SelectionKind.BREAKPOINTS) {
                val node = selectedNodes.firstOrNull() as? DebugMapNode.BreakpointItem ?: return@onPreviewKeyEvent false
                val breakpoints = topics.find { it.id == node.def.topicId }?.breakpoints ?: return@onPreviewKeyEvent false
                val idx = breakpoints.indexOfFirst { it.fileUrl == node.def.fileUrl && it.line == node.def.line && it.column == node.def.column }
                if (idx >= 0 && idx < breakpoints.size - 1) { service.reorderBreakpoint(node.def.topicId, node.def, 1); true } else false
              }
              else false
            }
            else -> false
          }
        }
        .onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (event.key) {
          Key.F2 -> {
            if (isSingle && selectionKind != SelectionKind.NONE) {
              doRename(selectedNodes.firstOrNull(), project, service, topics)
              true
            }
            else false
          }
          Key.Delete, Key.Backspace -> {
            val canDelete = selectionKind != SelectionKind.NONE &&
                            !(selectionKind == SelectionKind.TOPICS &&
                              selectedNodes.any { (it as DebugMapNode.Topic).id == activeTopicId })
            if (canDelete) {
              doDelete(selectedNodes, selectionKind, service, project, activeTopicId)
              selectedNodes = emptyList()
              true
            }
            else false
          }
          Key.C -> {
            if ((event.isMetaPressed || event.isCtrlPressed) && selectionKind != SelectionKind.NONE) {
              val lines = selectedNodes.map { node ->
                when (node) {
                  is DebugMapNode.Topic -> buildCopyText("topic", node.name, node.id.toString())
                  is DebugMapNode.BookmarkItem -> buildCopyText("bookmark",
                                                                service.buildReference(node.def.fileUrl, node.def.line),
                                                                node.def.name,
                                                                node.def.id)
                  is DebugMapNode.BreakpointItem -> buildCopyText("breakpoint",
                                                                  service.buildReference(node.def.fileUrl, node.def.line),
                                                                  node.def.name,
                                                                  node.def.id)
                }
              }
              if (lines.isNotEmpty()) {
                copyToClipboard(lines.joinToString("\n")); true
              }
              else false
            }
            else false
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
          is DebugMapNode.Topic -> TopicRow(node, searchText = searchText)
          is DebugMapNode.BookmarkItem -> BookmarkRow(node, isSelected = isSelected, searchText = searchText)
          is DebugMapNode.BreakpointItem -> BreakpointRow(node, isSelected = isSelected, searchText = searchText)
        }
        val info = rightClickInfo
        if (info != null && info.key == element.id) {
          val effectiveNodes = if (selectedNodes.contains(node)) selectedNodes else listOf(node)
          val effectiveKind = computeSelectionKind(effectiveNodes)
          when (effectiveKind) {
            SelectionKind.TOPICS -> TopicContextMenu(
              nodes = effectiveNodes.filterIsInstance<DebugMapNode.Topic>(),
              project = project,
              service = service,
              topics = topics,
              activeTopicId = activeTopicId,
              offset = info.offset,
              onDismiss = { rightClickInfoState.value = null },
            )
            SelectionKind.BOOKMARKS -> BookmarkContextMenu(
              nodes = effectiveNodes.filterIsInstance<DebugMapNode.BookmarkItem>(),
              project = project,
              service = service,
              topics = topics,
              activeTopicId = activeTopicId,
              offset = info.offset,
              onDismiss = { rightClickInfoState.value = null },
            )
            SelectionKind.BREAKPOINTS -> BreakpointContextMenu(
              nodes = effectiveNodes.filterIsInstance<DebugMapNode.BreakpointItem>(),
              project = project,
              service = service,
              topics = topics,
              activeTopicId = activeTopicId,
              offset = info.offset,
              onDismiss = { rightClickInfoState.value = null },
            )
            SelectionKind.NONE -> rightClickInfoState.value = null
          }
        }
      }
    }
  }
}

private fun matchesSearch(query: String, vararg texts: String?): Boolean {
  if (query.isBlank()) return true
  val q = query.lowercase()
  return texts.any { it?.lowercase()?.contains(q) == true }
}

private fun doDelete(
  nodes: List<DebugMapNode>,
  kind: SelectionKind,
  service: DebugMapService,
  project: Project,
  activeTopicId: Int?,
) {
  when (kind) {
    SelectionKind.TOPICS -> {
      val deletable = nodes.filterIsInstance<DebugMapNode.Topic>().filter { it.id != activeTopicId }
      val anyNonEmpty = deletable.any { it.bookmarkCount > 0 || it.breakpointCount > 0 }
      val confirmed = !anyNonEmpty || Messages.showYesNoDialog(
        project,
        if (deletable.size == 1)
          DebugMapBundle.message("dialog.delete.topic.message", deletable.first().name)
        else
          DebugMapBundle.message("dialog.delete.topics.message", deletable.size),
        DebugMapBundle.message("dialog.delete.topic.title"),
        Messages.getWarningIcon(),
      ) == Messages.YES
      if (confirmed) WriteAction.run<Exception> {
        deletable.forEach { service.deleteTopic(it.id) }
      }
    }
    SelectionKind.BOOKMARKS -> WriteAction.run<Exception> {
      nodes.filterIsInstance<DebugMapNode.BookmarkItem>()
        .forEach { service.removeBookmarkByToolWindow(it.def.topicId, it.def.fileUrl, it.def.line) }
    }
    SelectionKind.BREAKPOINTS -> WriteAction.run<Exception> {
      nodes.filterIsInstance<DebugMapNode.BreakpointItem>()
        .forEach { service.removeBreakpointByToolWindow(it.def.topicId, it.def.fileUrl, it.def.line, it.def.column) }
    }
    SelectionKind.NONE -> Unit
  }
}

private fun doRename(node: DebugMapNode?, project: Project, service: DebugMapService, topics: List<TopicData>) {
  when (node) {
    is DebugMapNode.Topic -> {
      val current = topics.find { it.id == node.id }?.name ?: return
      val name = Messages.showInputDialog(project,
                                          DebugMapBundle.message("dialog.rename.topic.label"),
                                          DebugMapBundle.message("dialog.rename.topic.title"),
                                          null,
                                          current,
                                          null) ?: return
      if (name.isNotBlank()) service.renameTopic(node.id, name)
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
