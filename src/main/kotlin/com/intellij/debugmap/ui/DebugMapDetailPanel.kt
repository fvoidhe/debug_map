package com.intellij.debugmap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellij.debugmap.model.GroupData
import com.intellij.debugmap.ui.tree.COLOR_INACTIVE
import com.intellij.debugmap.ui.tree.copyToClipboard
import com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@Composable
internal fun DebugMapDetailPanel(node: DebugMapNode, groups: List<GroupData>) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    when (node) {
      is DebugMapNode.BookmarkItem -> BookmarkDetail(node, groups)
      is DebugMapNode.BreakpointItem -> BreakpointDetail(node, groups)
      is DebugMapNode.Group -> Unit
    }
  }
}

@Composable
private fun BookmarkDetail(node: DebugMapNode.BookmarkItem, groups: List<GroupData>) {
  val def = node.def
  val path = remember(def.fileUrl) {
    VirtualFileManager.getInstance().findFileByUrl(def.fileUrl)?.path ?: def.fileUrl.removePrefix("file://")
  }
  val fileText = "$path:${def.line + 1}"
  val groupName = remember(def.groupId, groups) { groups.find { it.id == def.groupId }?.name }

  if (!def.name.isNullOrBlank()) DetailRow("Name", def.name, copyValue = def.name)
  DetailRow("File", fileText, copyValue = fileText)
  if (groupName != null) DetailRow("Group", groupName)
}

@Composable
private fun BreakpointDetail(node: DebugMapNode.BreakpointItem, groups: List<GroupData>) {
  val def = node.def
  val path = remember(def.fileUrl) {
    VirtualFileManager.getInstance().findFileByUrl(def.fileUrl)?.path ?: def.fileUrl.removePrefix("file://")
  }
  val position = if (def.column > 0) "${def.line + 1}:${def.column}" else "${def.line + 1}"
  val fileText = "$path:$position"
  val groupName = remember(def.groupId, groups) { groups.find { it.id == def.groupId }?.name }

  if (!def.name.isNullOrBlank()) DetailRow("Name", def.name, copyValue = def.name)
  DetailRow("File", fileText, copyValue = fileText)
  if (groupName != null) DetailRow("Group", groupName)
  if (!def.condition.isNullOrBlank()) DetailRow("Condition", def.condition)
  if (!def.logExpression.isNullOrBlank()) DetailRow("Log", def.logExpression)
  if (def.enabled == false) DetailRow("Enabled", "No")
  if (def.suspendPolicy != null && def.suspendPolicy != "ALL") {
    DetailRow("Suspend", def.suspendPolicy.lowercase().replaceFirstChar { it.uppercase() })
  }
  val masterDef = remember(def.masterBreakpointId, groups) {
    def.masterBreakpointId?.let { id -> groups.flatMap { it.breakpoints }.firstOrNull { it.id == id } }
  }
  if (masterDef != null) {
    val masterPath = remember(masterDef.fileUrl) {
      VirtualFileManager.getInstance().findFileByUrl(masterDef.fileUrl)?.path
        ?: masterDef.fileUrl.removePrefix("file://")
    }
    val leaveEnabled = if (def.masterLeaveEnabled == true) " (keep enabled)" else ""
    DetailRow("Master", "$masterPath:${masterDef.line + 1}$leaveEnabled")
  }
}

@Composable
private fun DetailRow(label: String, value: String, copyValue: String? = null) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      color = COLOR_INACTIVE,
      modifier = Modifier.width(72.dp),
      maxLines = 1,
    )
    Text(text = value, modifier = Modifier.weight(1f))
    if (copyValue != null) {
      IconActionButton(
        key = AllIconsKeys.Actions.Copy,
        contentDescription = "Copy",
        modifier = Modifier.size(16.dp),
        onClick = { copyToClipboard(copyValue) },
      )
    }
  }
}
