package com.intellij.debugmap.ui.tree

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment.Companion.BottomEnd
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intellij.debugmap.ui.DebugMapNode
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@Composable
internal fun BreakpointRow(node: DebugMapNode.BreakpointItem) {
  val def = node.def
  val resolved = resolveBreakpointIcon(def)
  val fileName = def.fileUrl.substringAfterLast('/')
  val position = if (def.column > 0) "${def.line + 1}:${def.column}" else "${def.line + 1}"
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    if (node.recentIndex != null) {
      Box(modifier = Modifier.width(18.dp), contentAlignment = Alignment.CenterStart) {
        if (node.recentIndex == 0) {
          Icon(key = AllIconsKeys.Debugger.ThreadCurrent, contentDescription = "Current", modifier = Modifier.size(16.dp))
        } else {
          Text(text = "${node.recentIndex}", color = COLOR_INACTIVE)
        }
      }
    }
 else {
      // Reserve space equivalent to the tree chevron (16dp icon + 2dp gap) so that
      // breakpoint content aligns with group content, matching standard IntelliJ tree behavior.
      Spacer(Modifier.width(18.dp))
    }
    
    Box(modifier = Modifier.size(16.dp)) {
      Icon(key = resolved.base, contentDescription = null, modifier = Modifier.size(16.dp))
      if (resolved.badge != null) {
        Icon(
          key = resolved.badge,
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
        text = "$fileName:$position",
        color = COLOR_INACTIVE,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
    }
    else {
      Text(
        text = "$fileName:$position",
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
    }
  }
}
