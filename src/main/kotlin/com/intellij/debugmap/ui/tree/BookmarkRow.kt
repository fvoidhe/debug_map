package com.intellij.debugmap.ui.tree

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.intellij.debugmap.ui.DebugMapNode
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@Composable
internal fun BookmarkRow(node: DebugMapNode.BookmarkItem, isSelected: Boolean = false) {
  val def = node.def
  val fileName = def.fileUrl.substringAfterLast('/')
  val lineNumber = def.line + 1
  val hasName = !def.name.isNullOrBlank()
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp),
    verticalAlignment = if (isSelected && hasName) Alignment.Top else Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Spacer(Modifier.width(18.dp))
    Icon(key = AllIconsKeys.Nodes.Bookmark, contentDescription = null, modifier = Modifier.size(16.dp))
    if (hasName) {
      if (isSelected) {
        Text(
          text = buildAnnotatedString {
            append(def.name)
            withStyle(SpanStyle(color = COLOR_INACTIVE)) { append("  $fileName:$lineNumber") }
          },
          modifier = Modifier.weight(1f),
        )
      } else {
        Text(text = def.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
          text = "$fileName:$lineNumber",
          color = COLOR_INACTIVE,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
      }
    } else {
      Text(
        text = "$fileName:$lineNumber",
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
    }
  }
}
