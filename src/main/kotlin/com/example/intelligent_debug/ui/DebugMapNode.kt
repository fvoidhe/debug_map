package com.example.intelligent_debug.ui

import com.example.intelligent_debug.model.BreakpointDef

internal sealed class DebugMapNode {
  data class Group(val id: Int, val name: String, val isActive: Boolean, val breakpointCount: Int) : DebugMapNode()
  data class BreakpointItem(val def: BreakpointDef) : DebugMapNode()
}
