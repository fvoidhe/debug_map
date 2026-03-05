package com.example.intelligent_debug.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.BottomEnd
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.intelligent_debug.DebugMapService
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.foundation.lazy.tree.rememberTreeState
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IntelliJIconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

private val COLOR_ACTIVE = Color(0xFFFF6B6B.toInt())
private val COLOR_INACTIVE = Color(0xFF808080.toInt())

private data class BreakpointIcons(val normal: IntelliJIconKey, val noSuspend: IntelliJIconKey)

private val BREAKPOINT_ICON_MAP: Map<String, BreakpointIcons> = mapOf(
  "java-line"            to BreakpointIcons(AllIconsKeys.Debugger.Db_set_breakpoint,    AllIconsKeys.Debugger.Db_no_suspend_breakpoint),
  "java-method"          to BreakpointIcons(AllIconsKeys.Debugger.Db_method_breakpoint, AllIconsKeys.Debugger.Db_no_suspend_method_breakpoint),
  "java-wildcard-method" to BreakpointIcons(AllIconsKeys.Debugger.Db_method_breakpoint, AllIconsKeys.Debugger.Db_no_suspend_method_breakpoint),
  "java-field"           to BreakpointIcons(AllIconsKeys.Debugger.Db_field_breakpoint,  AllIconsKeys.Debugger.Db_no_suspend_field_breakpoint),
  "java-collection"      to BreakpointIcons(AllIconsKeys.Debugger.Db_field_breakpoint,  AllIconsKeys.Debugger.Db_no_suspend_field_breakpoint),
  "kotlin-line"          to BreakpointIcons(AllIconsKeys.Debugger.Db_set_breakpoint,    AllIconsKeys.Debugger.Db_no_suspend_breakpoint),
  "kotlin-function"      to BreakpointIcons(AllIconsKeys.Debugger.Db_method_breakpoint, AllIconsKeys.Debugger.Db_no_suspend_method_breakpoint),
  "kotlin-field"         to BreakpointIcons(AllIconsKeys.Debugger.Db_field_breakpoint,  AllIconsKeys.Debugger.Db_no_suspend_field_breakpoint),
)

private val DEFAULT_BREAKPOINT_ICONS = BreakpointIcons(
  normal = AllIconsKeys.Debugger.Db_set_breakpoint,
  noSuspend = AllIconsKeys.Debugger.Db_no_suspend_breakpoint,
)

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun DebugMapToolWindow(project: Project) {
  val service = remember(project) { DebugMapService.getInstance(project) }
  val groups by service.groups.collectAsState()
  val activeGroupId by service.activeGroupId.collectAsState()
  var selectedNode by remember { mutableStateOf<DebugMapNode?>(null) }
  val treeState = rememberTreeState()

  val tree = remember(groups, activeGroupId) {
    buildTree {
      for (group in groups) {
        addNode(
          data = DebugMapNode.Group(group.id, group.name, group.id == activeGroupId, group.breakpoints.size),
          id = "group-${group.id}",
        ) {
          for (bp in group.breakpoints) {
            addLeaf(
              data = DebugMapNode.BreakpointItem(bp),
              id = "bp-${group.id}-${bp.fileUrl}-${bp.line}",
            )
          }
        }
      }
    }
  }

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
          val name = Messages.showInputDialog(project, "Group name:", "New Debug Group", null, defaultName, null)
                     ?: return@IconActionButton
          WriteAction.run<Exception> { service.createGroup(name) }
        },
      )
      IconActionButton(
        key = AllIconsKeys.General.Remove,
        contentDescription = "Delete Group",
        enabled = selectedNode is DebugMapNode.Group && (selectedNode as DebugMapNode.Group).id != activeGroupId,
        onClick = {
          val gId = (selectedNode as? DebugMapNode.Group)?.id ?: return@IconActionButton
          WriteAction.run<Exception> { service.deleteGroup(gId) }
          selectedNode = null
        },
      )
      IconActionButton(
        key = AllIconsKeys.Actions.CheckOut,
        contentDescription = "Checkout Group",
        enabled = selectedNode is DebugMapNode.Group && (selectedNode as DebugMapNode.Group).id != activeGroupId,
        onClick = {
          val gId = (selectedNode as? DebugMapNode.Group)?.id ?: return@IconActionButton
          WriteAction.run<Exception> { service.checkout(gId) }
        },
      )
      IconActionButton(
        key = AllIconsKeys.Actions.Edit,
        contentDescription = "Rename",
        enabled = selectedNode != null,
        onClick = {
          when (val node = selectedNode) {
            is DebugMapNode.Group -> {
              val current = groups.find { it.id == node.id }?.name ?: return@IconActionButton
              val name = Messages.showInputDialog(project, "Group name:", "Rename Group", null, current, null)
                         ?: return@IconActionButton
              if (name.isNotBlank()) service.renameGroup(node.id, name)
            }
            is DebugMapNode.BreakpointItem -> {
              val current = node.def.name ?: ""
              val name = Messages.showInputDialog(project, "Breakpoint name:", "Rename Breakpoint", null, current, null)
                         ?: return@IconActionButton
              service.renameBreakpoint(node.def, name)
            }
            null -> return@IconActionButton
          }
        },
      )
    }

    Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())

    LazyTree(
      tree = tree,
      modifier = Modifier.fillMaxSize(),
      treeState = treeState,
      onSelectionChange = { elements ->
        selectedNode = elements.firstOrNull()?.data
      },
      onElementDoubleClick = { element ->
        val node = element.data
        if (node is DebugMapNode.BreakpointItem) {
          val file = VirtualFileManager.getInstance().findFileByUrl(node.def.fileUrl)
          if (file != null) {
            OpenFileDescriptor(project, file, node.def.line, 0).navigate(true)
          }
        }
      },
    ) { element ->
      when (val node = element.data) {
        is DebugMapNode.Group -> GroupRow(node)
        is DebugMapNode.BreakpointItem -> BreakpointRow(node)
      }
    }
  }
}

@Composable
private fun GroupRow(node: DebugMapNode.Group) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Box(
      modifier = Modifier
        .size(16.dp)
        .clip(CircleShape)
        .background(if (node.isActive) COLOR_ACTIVE else COLOR_INACTIVE),
    )
    Text(
      text = node.name,
      fontWeight = if (node.isActive) FontWeight.Bold else FontWeight.Normal,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Text(
      text = node.breakpointCount.toString(),
      color = COLOR_INACTIVE,
      maxLines = 1,
    )
  }
}

@Composable
private fun BreakpointRow(node: DebugMapNode.BreakpointItem) {
  val def = node.def
  val icons = BREAKPOINT_ICON_MAP.getOrDefault(def.typeId, DEFAULT_BREAKPOINT_ICONS)
  val baseIconKey = if (!def.logExpression.isNullOrBlank()) icons.noSuspend else icons.normal
  val hasCondition = !def.condition.isNullOrBlank()
  val fileName = def.fileUrl.substringAfterLast('/')
  val lineNumber = def.line + 1
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    // Reserve space equivalent to the tree chevron (16dp icon + 2dp gap) so that
    // breakpoint content aligns with group content, matching standard IntelliJ tree behavior.
    Spacer(Modifier.width(18.dp))
    Box(modifier = Modifier.size(16.dp)) {
      Icon(key = baseIconKey, contentDescription = null, modifier = Modifier.size(16.dp))
      if (hasCondition) {
        Icon(
          key = AllIconsKeys.Debugger.Question_badge,
          contentDescription = null,
          modifier = Modifier.size(7.dp, 9.dp).align(BottomEnd),
        )
      }
    }
    if (!def.name.isNullOrBlank()) {
      Text(
        text = def.name,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = "$fileName:$lineNumber",
        color = COLOR_INACTIVE,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
    }
    else {
      Text(
        text = "$fileName:$lineNumber",
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
    }
  }
}
