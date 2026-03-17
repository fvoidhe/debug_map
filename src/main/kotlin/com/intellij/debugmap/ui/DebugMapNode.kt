package com.intellij.debugmap.ui

import com.intellij.debugmap.model.BookmarkDef
import com.intellij.debugmap.model.BreakpointDef

internal sealed class DebugMapNode {
  data class Group(val id: Int, val name: String, val isActive: Boolean, val bookmarkCount: Int, val breakpointCount: Int) : DebugMapNode()
  data class BookmarkItem(val def: BookmarkDef, val recentIndex: Int? = null) : DebugMapNode()
  data class BreakpointItem(val def: BreakpointDef, val recentIndex: Int? = null, val isInActiveGroup: Boolean = true) : DebugMapNode()
}
