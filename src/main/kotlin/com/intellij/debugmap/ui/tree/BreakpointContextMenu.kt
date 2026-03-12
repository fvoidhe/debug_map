package com.intellij.debugmap.ui.tree

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.window.rememberPopupPositionProviderAtPosition
import com.intellij.debugmap.DebugMapBundle
import com.intellij.debugmap.DebugMapService
import com.intellij.debugmap.ui.DebugMapNode
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun BreakpointContextMenu(
  nodes: List<DebugMapNode.BreakpointItem>,
  project: Project,
  service: DebugMapService,
  activeGroupId: Int?,
  offset: Offset,
  onDismiss: () -> Unit,
) {
  val isSingle = nodes.size == 1
  val node = nodes.firstOrNull() ?: return
  val renameKeybinding = remember { shortcutHint("Tree-startEditing") }
  val deleteKeybinding = remember { shortcutHint("\$Delete") }

  PopupMenu(
    onDismissRequest = { onDismiss(); true },
    popupPositionProvider = rememberPopupPositionProviderAtPosition(offset),
    adContent = null,
  ) {
    if (isSingle && node.def.groupId != activeGroupId) {
      selectableItem(
        selected = false,
        iconKey = AllIconsKeys.Actions.CheckOut,
        onClick = {
          onDismiss()
          WriteAction.run<Exception> { service.checkout(node.def.groupId) }
        },
      ) { Text(DebugMapBundle.message("action.checkout.group")) }
    }
    if (isSingle) {
      selectableItem(
        selected = false,
        iconKey = AllIconsKeys.Actions.Edit,
        keybinding = renameKeybinding,
        onClick = {
          onDismiss()
          val current = node.def.name ?: ""
          val name = Messages.showInputDialog(
            project,
            DebugMapBundle.message("dialog.rename.breakpoint.label"),
            DebugMapBundle.message("dialog.rename.breakpoint.title"),
            null, current, null,
          ) ?: return@selectableItem
          service.renameBreakpoint(node.def, name)
        },
      ) { Text(DebugMapBundle.message("action.rename.breakpoint")) }
    }
    selectableItem(
      selected = false,
      iconKey = AllIconsKeys.General.Remove,
      keybinding = deleteKeybinding,
      onClick = {
        onDismiss()
        WriteAction.run<Exception> {
          nodes.forEach { service.removeBreakpointByToolWindow(it.def.groupId, it.def.fileUrl, it.def.line, it.def.column) }
        }
      },
    ) {
      val key = if (nodes.size == 1) "action.delete.breakpoint" else "action.delete.breakpoints"
      Text(DebugMapBundle.message(key))
    }
  }
}
