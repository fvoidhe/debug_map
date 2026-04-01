package com.intellij.debugmap.ui.tree

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intellij.debugmap.model.TopicStatus
import com.intellij.debugmap.ui.DebugMapNode
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@Composable
internal fun TopicRow(node: DebugMapNode.Topic, searchText: String = "") {
  val isClosed = node.status == TopicStatus.CLOSE
  val isPinned = node.status == TopicStatus.PIN
  val textColor = if (isClosed) COLOR_INACTIVE else Color.Unspecified

  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    if (isClosed) {
      Icon(key = AllIconsKeys.FileTypes.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
    } else {
      Box(
        modifier = Modifier
          .size(16.dp)
          .clip(CircleShape)
          .background(when {
            node.isActive -> COLOR_ACTIVE
            node.isMcpModified -> COLOR_MCP_MODIFIED
            else -> COLOR_INACTIVE
          }),
      )
    }
    Column(modifier = Modifier.weight(1f, fill = false)) {
      Text(
        text = buildAnnotatedString { appendHighlighted(node.name, searchText, SpanStyle()) },
        fontWeight = if (node.isActive || isPinned) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = textColor,
      )
    }
    if (isPinned) {
      Icon(key = AllIconsKeys.Actions.PinTab, contentDescription = null, modifier = Modifier.size(12.dp))
    }
    Icon(key = AllIconsKeys.Nodes.Bookmark, contentDescription = null, modifier = Modifier.size(14.dp))
    Text(
      text = node.bookmarkCount.toString(),
      color = COLOR_INACTIVE,
      maxLines = 1,
    )
    Icon(key = AllIconsKeys.Debugger.Db_set_breakpoint, contentDescription = null, modifier = Modifier.size(14.dp))
    Text(
      text = node.breakpointCount.toString(),
      color = COLOR_INACTIVE,
      maxLines = 1,
    )
  }
}
