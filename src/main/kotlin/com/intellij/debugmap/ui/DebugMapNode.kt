package com.intellij.debugmap.ui

import com.intellij.debugmap.model.BookmarkDef
import com.intellij.debugmap.model.BreakpointDef
import com.intellij.debugmap.model.TopicStatus

internal sealed class DebugMapNode {
  data class Topic(
    val id: Int,
    val name: String,
    val isActive: Boolean,
    val status: TopicStatus,
    val bookmarkCount: Int,
    val breakpointCount: Int,
  ) : DebugMapNode()
  data class BookmarkItem(val def: BookmarkDef, val recentIndex: Int? = null) : DebugMapNode()
  data class BreakpointItem(val def: BreakpointDef, val recentIndex: Int? = null, val isInActiveTopic: Boolean = true) : DebugMapNode()
}
