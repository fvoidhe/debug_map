package com.intellij.debugmap.ui.tree

import androidx.compose.ui.graphics.Color
import com.intellij.debugmap.model.BreakpointDef
import org.jetbrains.jewel.ui.icon.IntelliJIconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

internal val COLOR_ACTIVE = Color(0xFFFF6B6B.toInt())
internal val COLOR_INACTIVE = Color(0xFF808080.toInt())

internal data class BreakpointIcons(
  val normal: IntelliJIconKey,
  val disabled: IntelliJIconKey,
  val noSuspend: IntelliJIconKey,
  val dependent: IntelliJIconKey,
)

internal val BREAKPOINT_ICON_MAP: Map<String, BreakpointIcons> = mapOf(
  "java-line" to BreakpointIcons(AllIconsKeys.Debugger.Db_set_breakpoint, AllIconsKeys.Debugger.Db_disabled_breakpoint, AllIconsKeys.Debugger.Db_no_suspend_breakpoint, AllIconsKeys.Debugger.Db_dep_line_breakpoint),
  "java-method" to BreakpointIcons(AllIconsKeys.Debugger.Db_method_breakpoint, AllIconsKeys.Debugger.Db_disabled_method_breakpoint, AllIconsKeys.Debugger.Db_no_suspend_method_breakpoint, AllIconsKeys.Debugger.Db_dep_method_breakpoint),
  "java-wildcard-method" to BreakpointIcons(AllIconsKeys.Debugger.Db_method_breakpoint, AllIconsKeys.Debugger.Db_disabled_method_breakpoint, AllIconsKeys.Debugger.Db_no_suspend_method_breakpoint, AllIconsKeys.Debugger.Db_dep_method_breakpoint),
  "java-field" to BreakpointIcons(AllIconsKeys.Debugger.Db_field_breakpoint, AllIconsKeys.Debugger.Db_disabled_field_breakpoint, AllIconsKeys.Debugger.Db_no_suspend_field_breakpoint, AllIconsKeys.Debugger.Db_dep_field_breakpoint),
  "java-collection" to BreakpointIcons(AllIconsKeys.Debugger.Db_field_breakpoint, AllIconsKeys.Debugger.Db_disabled_field_breakpoint, AllIconsKeys.Debugger.Db_no_suspend_field_breakpoint, AllIconsKeys.Debugger.Db_dep_field_breakpoint),
  "kotlin-line" to BreakpointIcons(AllIconsKeys.Debugger.Db_set_breakpoint, AllIconsKeys.Debugger.Db_disabled_breakpoint, AllIconsKeys.Debugger.Db_no_suspend_breakpoint, AllIconsKeys.Debugger.Db_dep_line_breakpoint),
  "kotlin-function" to BreakpointIcons(AllIconsKeys.Debugger.Db_method_breakpoint, AllIconsKeys.Debugger.Db_disabled_method_breakpoint, AllIconsKeys.Debugger.Db_no_suspend_method_breakpoint, AllIconsKeys.Debugger.Db_dep_method_breakpoint),
  "kotlin-field" to BreakpointIcons(AllIconsKeys.Debugger.Db_field_breakpoint, AllIconsKeys.Debugger.Db_disabled_field_breakpoint, AllIconsKeys.Debugger.Db_no_suspend_field_breakpoint, AllIconsKeys.Debugger.Db_dep_field_breakpoint),
)

internal val DEFAULT_BREAKPOINT_ICONS = BreakpointIcons(
  normal = AllIconsKeys.Debugger.Db_set_breakpoint,
  disabled = AllIconsKeys.Debugger.Db_disabled_breakpoint,
  noSuspend = AllIconsKeys.Debugger.Db_no_suspend_breakpoint,
  dependent = AllIconsKeys.Debugger.Db_dep_line_breakpoint,
)

internal data class ResolvedBreakpointIcon(
  val base: IntelliJIconKey,
  val badge: IntelliJIconKey? = null,
)

internal fun resolveBreakpointIcon(def: BreakpointDef): ResolvedBreakpointIcon {
  val icons = BREAKPOINT_ICON_MAP.getOrDefault(def.typeId, DEFAULT_BREAKPOINT_ICONS)
  val base = when {
    def.enabled == false -> icons.disabled
    def.masterFileUrl != null -> icons.dependent
    !def.logExpression.isNullOrBlank() || def.suspendPolicy == "NONE" -> icons.noSuspend
    else -> icons.normal
  }
  val badge = if (!def.condition.isNullOrBlank()) AllIconsKeys.Debugger.Question_badge else null
  return ResolvedBreakpointIcon(base, badge)
}
