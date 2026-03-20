package com.intellij.debugmap.ui.tree

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.window.rememberPopupPositionProviderAtPosition
import com.intellij.debugmap.DebugMapBundle
import com.intellij.debugmap.DebugMapService
import com.intellij.debugmap.model.TopicData
import com.intellij.debugmap.model.TopicStatus
import com.intellij.debugmap.ui.DebugMapNode
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TopicContextMenu(
  nodes: List<DebugMapNode.Topic>,
  project: Project,
  service: DebugMapService,
  topics: List<TopicData>,
  activeTopicId: Int?,
  offset: Offset,
  onDismiss: () -> Unit,
) {
  val isSingle = nodes.size == 1
  val node = nodes.firstOrNull() ?: return
  val deletable = nodes.filter { it.id != activeTopicId }
  val moveUpKeybinding = remember { shortcutHint("PreviousOccurence") }
  val moveDownKeybinding = remember { shortcutHint("NextOccurence") }
  val renameKeybinding = remember { shortcutHint("Tree-startEditing") }
  val deleteKeybinding = remember { shortcutHint("\$Delete") }
  val copyNameKeybinding = remember { shortcutHint("\$Copy") }
  val topicIndex = if (isSingle) topics.indexOfFirst { it.id == node.id } else -1

  val nodeStatus = if (isSingle) node.status else null

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
      enabled = isSingle && topicIndex > 0 && topics.getOrNull(topicIndex - 1)?.status == nodeStatus,
      onClick = {
        onDismiss()
        service.reorderTopic(node.id, -1)
      },
    ) { Text(DebugMapBundle.message("action.move.up")) }
    selectableItem(
      selected = false,
      iconKey = AllIconsKeys.Actions.MoveDown,
      keybinding = moveDownKeybinding,
      enabled = isSingle && topicIndex >= 0 && topicIndex < topics.size - 1 && topics.getOrNull(topicIndex + 1)?.status == nodeStatus,
      onClick = {
        onDismiss()
        service.reorderTopic(node.id, 1)
      },
    ) { Text(DebugMapBundle.message("action.move.down")) }
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
    separator()
    checkoutItem(node.id, service, onDismiss, enabled = isSingle && node.id != activeTopicId)
    selectableItem(
      selected = false,
      iconKey = AllIconsKeys.Actions.Edit,
      keybinding = renameKeybinding,
      enabled = isSingle,
      onClick = {
        onDismiss()
        val current = topics.find { it.id == node.id }?.name ?: return@selectableItem
        val name = Messages.showInputDialog(
          project,
          DebugMapBundle.message("dialog.rename.topic.label"),
          DebugMapBundle.message("dialog.rename.topic.title"),
          null, current, null,
        ) ?: return@selectableItem
        if (name.isNotBlank()) service.renameTopic(node.id, name)
      },
    ) { Text(DebugMapBundle.message("action.rename.topic")) }
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
            DebugMapBundle.message("dialog.delete.topic.message", deletable.first().name)
          else
            DebugMapBundle.message("dialog.delete.topics.message", deletable.size),
          DebugMapBundle.message("dialog.delete.topic.title"),
          Messages.getWarningIcon(),
        ) == Messages.YES
        if (confirmed) WriteAction.run<Exception> {
          deletable.forEach { service.deleteTopic(it.id) }
        }
      },
    ) {
      val key = if (deletable.size == 1) "action.delete.topic" else "action.delete.topics"
      Text(DebugMapBundle.message(key))
    }
    separator()
    if (isSingle) {
      if (nodeStatus == TopicStatus.PIN) {
        selectableItem(
          selected = false,
          iconKey = AllIconsKeys.Actions.PinTab,
          onClick = {
            onDismiss()
            service.updateTopicStatus(node.id, TopicStatus.OPEN)
          },
        ) { Text(DebugMapBundle.message("action.unpin.topic")) }
      } else {
        selectableItem(
          selected = false,
          iconKey = AllIconsKeys.Actions.PinTab,
          onClick = {
            onDismiss()
            service.updateTopicStatus(node.id, TopicStatus.PIN)
          },
        ) { Text(DebugMapBundle.message("action.pin.topic")) }
      }
      if (nodeStatus == TopicStatus.CLOSE) {
        selectableItem(
          selected = false,
          iconKey = AllIconsKeys.Actions.Show,
          onClick = {
            onDismiss()
            service.updateTopicStatus(node.id, TopicStatus.OPEN)
          },
        ) { Text(DebugMapBundle.message("action.open.topic")) }
      } else {
        selectableItem(
          selected = false,
          iconKey = AllIconsKeys.FileTypes.Archive,
          onClick = {
            onDismiss()
            if (node.id == activeTopicId) {
              WriteAction.run<Exception> {
                service.updateTopicStatus(node.id, TopicStatus.CLOSE)
                val newId = service.createTopic()
                service.checkout(newId)
              }
            } else {
              service.updateTopicStatus(node.id, TopicStatus.CLOSE)
            }
          },
        ) { Text(DebugMapBundle.message("action.close.topic")) }
      }
    }
    exportImportItems(nodes.map { it.id }, project, service, onDismiss)
  }
}
