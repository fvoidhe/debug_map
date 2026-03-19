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
  topics: List<TopicData>,
  activeTopicId: Int?,
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
  val breakpoints = remember(topics, node.def.topicId) { topics.find { it.id == node.def.topicId }?.breakpoints ?: emptyList() }
  val breakpointIndex = if (isSingle) breakpoints.indexOfFirst { it.fileUrl == node.def.fileUrl && it.line == node.def.line && it.column == node.def.column } else -1

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
      enabled = isSingle && breakpointIndex > 0,
      onClick = {
        onDismiss()
        service.reorderBreakpoint(node.def.topicId, node.def, -1)
      },
    ) { Text(DebugMapBundle.message("action.move.up")) }
    selectableItem(
      selected = false,
      iconKey = AllIconsKeys.Actions.MoveDown,
      keybinding = moveDownKeybinding,
      enabled = isSingle && breakpointIndex >= 0 && breakpointIndex < breakpoints.size - 1,
      onClick = {
        onDismiss()
        service.reorderBreakpoint(node.def.topicId, node.def, 1)
      },
    ) { Text(DebugMapBundle.message("action.move.down")) }
    copyReferenceItem(buildCopyText("breakpoint", service.buildReference(node.def.fileUrl, node.def.line), node.def.name, node.def.id), copyReferenceKeybinding, onDismiss, enabled = isSingle)
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
          DebugMapBundle.message("dialog.rename.breakpoint.label"),
          DebugMapBundle.message("dialog.rename.breakpoint.title"),
          null, current, null,
        ) ?: return@selectableItem
        service.renameBreakpoint(node.def, name)
      },
    ) { Text(DebugMapBundle.message("action.rename.breakpoint")) }
    selectableItem(
      selected = false,
      iconKey = AllIconsKeys.General.Remove,
      keybinding = deleteKeybinding,
      onClick = {
        onDismiss()
        WriteAction.run<Exception> {
          nodes.forEach { service.removeBreakpointByToolWindow(it.def.topicId, it.def.fileUrl, it.def.line, it.def.column) }
        }
      },
    ) {
      val key = if (nodes.size == 1) "action.delete.breakpoint" else "action.delete.breakpoints"
      Text(DebugMapBundle.message(key))
    }
  }
}
