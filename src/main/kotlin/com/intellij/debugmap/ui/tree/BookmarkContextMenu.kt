package com.intellij.debugmap.ui.tree

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.window.rememberPopupPositionProviderAtPosition
import com.intellij.debugmap.DebugMapBundle
import com.intellij.debugmap.DebugMapService
import com.intellij.debugmap.model.TopicData
import com.intellij.debugmap.ui.DebugMapNode
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun BookmarkContextMenu(
  nodes: List<DebugMapNode.BookmarkItem>,
  project: Project,
  service: DebugMapService,
  topics: List<TopicData>,
  activeTopicId: Int?,
  offset: Offset,
  onDismiss: () -> Unit,
) {
  val isSingle = nodes.size == 1
  val node = nodes.firstOrNull() ?: return
  val moveUpKeybinding = remember { altArrowHint(up = true) }
  val moveDownKeybinding = remember { altArrowHint(up = false) }
  val renameKeybinding = remember { shortcutHint("Tree-startEditing") }
  val deleteKeybinding = remember { shortcutHint("\$Delete") }
  val copyReferenceKeybinding = remember { shortcutHint("\$Copy") }
  val bookmarks = remember(topics, node.def.topicId) { topics.find { it.id == node.def.topicId }?.bookmarks ?: emptyList() }
  val bookmarkIndex = if (isSingle) bookmarks.indexOfFirst { it.fileUrl == node.def.fileUrl && it.line == node.def.line } else -1
  val defFile = remember(node.def.fileUrl) { VirtualFileManager.getInstance().findFileByUrl(node.def.fileUrl) }
  var canReactivate = false
  runReadActionBlocking {
    val defDocument = defFile?.let { FileDocumentManager.getInstance().getDocument(it) }
    canReactivate = isSingle && node.def.isStale &&
                    (defDocument != null && node.def.line < defDocument.lineCount) &&
                    bookmarks.none { !it.isStale && it.fileUrl == node.def.fileUrl && it.line == node.def.line }
  }

  val sortTopicIds = remember(nodes) { nodes.map { it.def.topicId }.distinct() }
  val menuStyle = rememberMenuStyle()
  PopupMenu(
    onDismissRequest = { onDismiss(); true },
    popupPositionProvider = rememberPopupPositionProviderAtPosition(offset),
    style = menuStyle,
    adContent = null,
  ) {
    copyReferenceItem(
      nodes.joinToString("\n") { buildCopyText("bookmark", service.buildReference(it.def.fileUrl, it.def.line), it.def.name, it.def.id) },
      copyReferenceKeybinding,
      onDismiss,
    )
    selectableItem(
      selected = false,
      iconKey = AllIconsKeys.Actions.MoveUp,
      keybinding = moveUpKeybinding,
      enabled = isSingle && bookmarkIndex > 0,
      onClick = {
        onDismiss()
        service.reorderBookmark(node.def.topicId, node.def, -1)
      },
    ) { Text(DebugMapBundle.message("action.move.up")) }
    selectableItem(
      selected = false,
      iconKey = AllIconsKeys.Actions.MoveDown,
      keybinding = moveDownKeybinding,
      enabled = isSingle && bookmarkIndex >= 0 && bookmarkIndex < bookmarks.size - 1,
      onClick = {
        onDismiss()
        service.reorderBookmark(node.def.topicId, node.def, 1)
      },
    ) { Text(DebugMapBundle.message("action.move.down")) }
    selectableItem(
      selected = false,
      iconKey = AllIconsKeys.ObjectBrowser.Sorted,
      onClick = {
        onDismiss()
        sortTopicIds.forEach { service.sortBookmarksByName(it) }
      },
    ) { Text(DebugMapBundle.message("action.sort.by.name")) }
    selectableItem(
      selected = false,
      iconKey = AllIconsKeys.ObjectBrowser.SortByType,
      onClick = {
        onDismiss()
        sortTopicIds.forEach { service.sortBookmarksByFile(it) }
      },
    ) { Text(DebugMapBundle.message("action.sort.by.file")) }
    separator()
    selectableItem(
      selected = false,
      iconKey = AllIconsKeys.Actions.Refresh,
      enabled = canReactivate,
      onClick = {
        onDismiss()
        val ok = WriteAction.compute<Boolean, Exception> { service.reactivateBookmark(node.def) }
        if (!ok) Messages.showErrorDialog(
          project,
          DebugMapBundle.message("dialog.reactivate.bookmark.duplicate.message"),
          DebugMapBundle.message("dialog.reactivate.bookmark.failed.title"),
        )
      },
    ) { Text(DebugMapBundle.message("action.reactivate.bookmark")) }
    checkoutItem(node.def.topicId, service, onDismiss, enabled = isSingle && node.def.topicId != activeTopicId)
    selectableItem(
      selected = false,
      iconKey = AllIconsKeys.Actions.Edit,
      keybinding = renameKeybinding,
      enabled = isSingle,
      onClick = {
        onDismiss()
        val current = node.def.name ?: ""
        val name = Messages.showInputDialog(
          project,
          DebugMapBundle.message("dialog.rename.bookmark.label"),
          DebugMapBundle.message("dialog.rename.bookmark.title"),
          null, current, null,
        ) ?: return@selectableItem
        service.renameBookmark(node.def, name)
      },
    ) { Text(DebugMapBundle.message("action.rename.bookmark")) }
    selectableItem(
      selected = false,
      iconKey = AllIconsKeys.General.Remove,
      keybinding = deleteKeybinding,
      onClick = {
        onDismiss()
        WriteAction.run<Exception> {
          nodes.forEach { service.removeBookmarkByToolWindow(it.def.topicId, it.def.fileUrl, it.def.line) }
        }
      },
    ) {
      val key = if (nodes.size == 1) "action.delete.bookmark" else "action.delete.bookmarks"
      Text(DebugMapBundle.message(key))
    }
    exportImportItems(nodes.map { it.def.topicId }.distinct(), project, service, onDismiss)
  }
}
