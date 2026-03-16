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
      enabled = isSingle && groupIndex > 0,
      onClick = {
        onDismiss()
        service.reorderGroup(node.id, -1)
      },
    ) { Text(DebugMapBundle.message("action.move.up")) }
    selectableItem(
      selected = false,
      iconKey = AllIconsKeys.Actions.MoveDown,
      keybinding = moveDownKeybinding,
      enabled = isSingle && groupIndex >= 0 && groupIndex < groups.size - 1,
      onClick = {
        onDismiss()
        service.reorderGroup(node.id, 1)
      },
    ) { Text(DebugMapBundle.message("action.move.down")) }
    selectableItem(
      selected = false,
      iconKey = AllIconsKeys.Actions.CheckOut,
      enabled = isSingle && node.id != activeGroupId,
      onClick = {
        onDismiss()
        WriteAction.run<Exception> { service.checkout(node.id) }
      },
    ) { Text(DebugMapBundle.message("action.checkout.group")) }
    selectableItem(
      selected = false,
      iconKey = AllIconsKeys.Actions.Copy,
      keybinding = copyNameKeybinding,
      enabled = isSingle,
      onClick = {
        onDismiss()
        copyToClipboard(node.name)
      },
    ) { Text(DebugMapBundle.message("action.copy.name")) }
    selectableItem(
      selected = false,
      iconKey = AllIconsKeys.Actions.Edit,
      keybinding = renameKeybinding,
      enabled = isSingle,
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
    selectableItem(
      selected = false,
      iconKey = AllIconsKeys.General.Remove,
      keybinding = deleteKeybinding,
      enabled = deletable.isNotEmpty(),
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
