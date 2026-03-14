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
internal fun GroupContextMenu(
  nodes: List<DebugMapNode.Group>,
  project: Project,
  service: DebugMapService,
  groups: List<GroupData>,
  activeGroupId: Int?,
  offset: Offset,
  onDismiss: () -> Unit,
) {
  val isSingle = nodes.size == 1
  val node = nodes.firstOrNull() ?: return
  val deletable = nodes.filter { it.id != activeGroupId }
  val moveUpKeybinding = remember { shortcutHint("PreviousOccurence") }
  val moveDownKeybinding = remember { shortcutHint("NextOccurence") }
  val renameKeybinding = remember { shortcutHint("Tree-startEditing") }
  val deleteKeybinding = remember { shortcutHint("\$Delete") }
  val copyNameKeybinding = remember { shortcutHint("\$Copy") }
  val groupIndex = if (isSingle) groups.indexOfFirst { it.id == node.id } else -1

  PopupMenu(
    onDismissRequest = { onDismiss(); true },
    popupPositionProvider = rememberPopupPositionProviderAtPosition(offset),
    adContent = null,
  ) {
    if (isSingle && groupIndex > 0) {
      selectableItem(
        selected = false,
        iconKey = AllIconsKeys.Actions.MoveUp,
        keybinding = moveUpKeybinding,
        onClick = {
          onDismiss()
          service.reorderGroup(node.id, -1)
        },
      ) { Text(DebugMapBundle.message("action.move.up")) }
    }
    if (isSingle && groupIndex >= 0 && groupIndex < groups.size - 1) {
      selectableItem(
        selected = false,
        iconKey = AllIconsKeys.Actions.MoveDown,
        keybinding = moveDownKeybinding,
        onClick = {
          onDismiss()
          service.reorderGroup(node.id, 1)
        },
      ) { Text(DebugMapBundle.message("action.move.down")) }
    }
    if (isSingle && node.id != activeGroupId) {
      selectableItem(
        selected = false,
        iconKey = AllIconsKeys.Actions.CheckOut,
        onClick = {
          onDismiss()
          WriteAction.run<Exception> { service.checkout(node.id) }
        },
      ) { Text(DebugMapBundle.message("action.checkout.group")) }
    }
    if (isSingle) {
      selectableItem(
        selected = false,
        iconKey = AllIconsKeys.Actions.Copy,
        keybinding = copyNameKeybinding,
        onClick = {
          onDismiss()
          copyToClipboard(node.name)
        },
      ) { Text(DebugMapBundle.message("action.copy.name")) }
    }
    if (isSingle) {
      selectableItem(
        selected = false,
        iconKey = AllIconsKeys.Actions.Edit,
        keybinding = renameKeybinding,
        onClick = {
          onDismiss()
          val current = groups.find { it.id == node.id }?.name ?: return@selectableItem
          val name = Messages.showInputDialog(
            project,
            DebugMapBundle.message("dialog.rename.group.label"),
            DebugMapBundle.message("dialog.rename.group.title"),
            null, current, null,
          ) ?: return@selectableItem
          if (name.isNotBlank()) service.renameGroup(node.id, name)
        },
      ) { Text(DebugMapBundle.message("action.rename.group")) }
    }
    if (deletable.isNotEmpty()) {
      selectableItem(
        selected = false,
        iconKey = AllIconsKeys.General.Remove,
        keybinding = deleteKeybinding,
        onClick = {
          onDismiss()
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
        },
      ) {
        val key = if (deletable.size == 1) "action.delete.group" else "action.delete.groups"
        Text(DebugMapBundle.message(key))
      }
    }
  }
}
