package com.intellij.debugmap.ui.tree

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.intellij.debugmap.DebugMapBundle
import com.intellij.debugmap.DebugMapService
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.keymap.KeymapUtil
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.MenuScope
import org.jetbrains.jewel.ui.theme.menuStyle
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.ui.component.styling.MenuColors
import org.jetbrains.jewel.ui.component.styling.MenuItemColors
import org.jetbrains.jewel.ui.component.styling.MenuStyle
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.datatransfer.StringSelection

internal fun shortcutHint(actionId: String): Set<String>? {
  val shortcuts = KeymapUtil.getActiveKeymapShortcuts(actionId).shortcuts
  val keystroke = shortcuts.filterIsInstance<KeyboardShortcut>().firstOrNull()?.firstKeyStroke
    ?: return null
  return setOf(KeymapUtil.getKeystrokeText(keystroke))
}

internal fun copyToClipboard(text: String) {
  CopyPasteManager.getInstance().setContents(StringSelection(text))
}

internal fun buildCopyText(type: String, reference: String, name: String?, id: Long): String =
  buildJsonObject {
    put("type", JsonPrimitive(type))
    put("ref", JsonPrimitive(reference))
    put("id", JsonPrimitive(id))
    if (!name.isNullOrBlank()) put("name", JsonPrimitive(name))
  }.toString()

internal fun MenuScope.copyReferenceItem(
  referenceText: String,
  keybinding: Set<String>?,
  onDismiss: () -> Unit,
  enabled: Boolean = true,
) {
  selectableItem(
    selected = false,
    iconKey = AllIconsKeys.Actions.Copy,
    keybinding = keybinding,
    enabled = enabled,
    onClick = {
      onDismiss()
      copyToClipboard(referenceText)
    },
  ) { Text(DebugMapBundle.message("action.copy.reference")) }
  separator()
}

internal fun MenuScope.checkoutItem(
  topicId: Int,
  service: DebugMapService,
  onDismiss: () -> Unit,
  enabled: Boolean = true,
) {
  selectableItem(
    selected = false,
    iconKey = AllIconsKeys.Actions.CheckOut,
    enabled = enabled,
    onClick = {
      onDismiss()
      WriteAction.run<Exception> { service.checkout(topicId) }
    },
  ) { Text(DebugMapBundle.message("action.checkout.topic")) }
  separator()
}

@Composable
internal fun rememberMenuStyle(): MenuStyle {
  val base = JewelTheme.menuStyle
  return remember(base) {
    val c = base.colors.itemColors
    MenuStyle(
      isDark = base.isDark,
      colors = MenuColors(
        background = base.colors.background,
        border = base.colors.border,
        shadow = base.colors.shadow,
        itemColors = MenuItemColors(
          background = c.background,
          backgroundDisabled = Color.Transparent,
          backgroundFocused = c.backgroundFocused,
          backgroundPressed = c.backgroundPressed,
          backgroundHovered = c.backgroundHovered,
          content = c.content,
          contentDisabled = c.contentDisabled,
          contentFocused = c.contentFocused,
          contentPressed = c.contentPressed,
          contentHovered = c.contentHovered,
          iconTint = c.iconTint,
          iconTintDisabled = c.iconTintDisabled,
          iconTintFocused = c.iconTintFocused,
          iconTintPressed = c.iconTintPressed,
          iconTintHovered = c.iconTintHovered,
          keybindingTint = c.keybindingTint,
          keybindingTintDisabled = c.keybindingTintDisabled,
          keybindingTintFocused = c.keybindingTintFocused,
          keybindingTintPressed = c.keybindingTintPressed,
          keybindingTintHovered = c.keybindingTintHovered,
          separator = c.separator,
        ),
      ),
      metrics = base.metrics,
      icons = base.icons,
    )
  }
}
