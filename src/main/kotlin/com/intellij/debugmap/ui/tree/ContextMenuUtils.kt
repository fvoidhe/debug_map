package com.intellij.debugmap.ui.tree

import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapUtil

internal fun shortcutHint(actionId: String): Set<String>? {
  val shortcuts = KeymapUtil.getActiveKeymapShortcuts(actionId).shortcuts
  val keystroke = shortcuts.filterIsInstance<KeyboardShortcut>().firstOrNull()?.firstKeyStroke
    ?: return null
  return setOf(KeymapUtil.getKeystrokeText(keystroke))
}
