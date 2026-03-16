package com.intellij.debugmap.ui.tree

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.window.rememberPopupPositionProviderAtPosition
import com.intellij.debugmap.DebugMapBundle
import com.intellij.debugmap.DebugMapService
import com.intellij.debugmap.model.GroupData
import com.intellij.debugmap.ui.DebugMapNode
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun BookmarkContextMenu(
  nodes: List<DebugMapNode.BookmarkItem>,
  project: Project,
  service: DebugMapService,
  groups: List<GroupData>,
  activeGroupId: Int?,
  offset: Offset,
  onDismiss: () -> Unit,
) {
  val isSingle = nodes.size == 1
  val node = nodes.firstOrNull() ?: return
  val moveUpKeybinding = remember { shortcutHint("PreviousOccurence") }
  val moveDownKeybinding = remember { shortcutHint("NextOccurence") }
  val renameKeybinding = remember { shortcutHint("Tree-startEditing") }
  val deleteKeybinding = remember { shortcutHint("\$Delete") }
  val copyReferenceKeybinding = remember { shortcutHint("\$Copy") }
  val bookmarks = remember(groups, node.def.groupId) { groups.find { it.id == node.def.groupId }?.bookmarks ?: emptyList() }
  val bookmarkIndex = if (isSingle) bookmarks.indexOfFirst { it.fileUrl == node.def.fileUrl && it.line == node.def.line } else -1

  val menuStyle = rememberMenuStyle()
  PopupMenu(
    onDismissRequest = { onDismiss(); true },
    popupPositionProvider = rememberPopupPositionProviderAtPosition(offset),
    style = menuStyle,
    adContent = null,
  ) {
    selectableItem(
      selected = false,
      iconKey = AllIconsKeys.Actions.MoveUp,
      keybinding = moveUpKeybinding,
      enabled = isSingle && bookmarkIndex > 0,
      onClick = {
        onDismiss()
        service.reorderBookmark(node.def.groupId, node.def, -1)
      },
    ) { Text(DebugMapBundle.message("action.move.up")) }
    selectableItem(
      selected = false,
      iconKey = AllIconsKeys.Actions.MoveDown,
      keybinding = moveDownKeybinding,
      enabled = isSingle && bookmarkIndex >= 0 && bookmarkIndex < bookmarks.size - 1,
      onClick = {
        onDismiss()
        service.reorderBookmark(node.def.groupId, node.def, 1)
      },
    ) { Text(DebugMapBundle.message("action.move.down")) }
    copyReferenceItem(buildCopyText("bookmark", service.buildReference(node.def.fileUrl, node.def.line), node.def.name), copyReferenceKeybinding, onDismiss, enabled = isSingle)
    checkoutItem(node.def.groupId, service, onDismiss, enabled = isSingle && node.def.groupId != activeGroupId)
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
          nodes.forEach { service.removeBookmarkByToolWindow(it.def.groupId, it.def.fileUrl, it.def.line) }
        }
      },
    ) {
      val key = if (nodes.size == 1) "action.delete.bookmark" else "action.delete.bookmarks"
      Text(DebugMapBundle.message(key))
    }
  }
}
