package com.intellij.debugmap.ui.tree

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.intellij.debugmap.DebugMapBundle
import com.intellij.debugmap.DebugMapService
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.io.sanitizeFileName
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
import kotlin.io.path.writeText

internal fun shortcutHint(actionId: String): Set<String>? {
  val shortcuts = KeymapUtil.getActiveKeymapShortcuts(actionId).shortcuts
  val keystroke = shortcuts.filterIsInstance<KeyboardShortcut>().firstOrNull()?.firstKeyStroke
                  ?: return null
  return setOf(KeymapUtil.getKeystrokeText(keystroke))
}

internal fun copyToClipboard(text: String) {
  CopyPasteManager.getInstance().setContents(StringSelection(text))
}

internal fun buildCopyText(type: String, reference: String, name: String?, id: String): String =
  buildJsonObject {
    put("type", JsonPrimitive(type))
    put("ref", JsonPrimitive(reference))
    put("id", JsonPrimitive(id))
    if (!name.isNullOrBlank()) put("name", JsonPrimitive(name))
  }.toString()

internal fun buildCopyText(type: String, name: String, id: String): String =
  buildJsonObject {
    put("type", JsonPrimitive(type))
    put("id", JsonPrimitive(id))
    put("name", JsonPrimitive(name))
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

internal fun MenuScope.exportImportItems(
  topicIds: List<Int>,
  project: Project,
  service: DebugMapService,
  onDismiss: () -> Unit,
) {
  separator()
  selectableItem(
    selected = false,
    iconKey = AllIconsKeys.ToolbarDecorator.Export,
    onClick = {
      onDismiss()
      doExport(topicIds, project, service)
    },
  ) {
    val key = if (topicIds.size == 1) "action.export.topic" else "action.export.topics"
    Text(DebugMapBundle.message(key))
  }
  selectableItem(
    selected = false,
    iconKey = AllIconsKeys.ToolbarDecorator.Import,
    onClick = {
      onDismiss()
      doImport(project, service)
    },
  ) { Text(DebugMapBundle.message("action.import.topics")) }
}

internal fun doExport(topicIds: List<Int>, project: Project, service: DebugMapService) {
  val json = service.exportTopics(topicIds)
  val defaultName = if (topicIds.size == 1) {
    val name = service.getTopics().find { it.id == topicIds[0] }?.name ?: "topic"
    "${sanitizeFileName(name, replacement = "-")}.json"
  }
  else "debugmap-topics.json"
  val descriptor = FileSaverDescriptor(
    DebugMapBundle.message("dialog.export.topics.title"),
    DebugMapBundle.message("dialog.export.topics.description"),
    "json",
  )
  val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
                  .save(null as com.intellij.openapi.vfs.VirtualFile?, defaultName) ?: return
  val path = wrapper.file.toPath()
  ApplicationManager.getApplication().executeOnPooledThread {
    try {
      path.writeText(json)
    }
    catch (e: Exception) {
      ApplicationManager.getApplication().invokeLater {
        Messages.showErrorDialog(project, e.message, DebugMapBundle.message("dialog.export.error.title"))
      }
    }
  }
}

internal fun doImport(project: Project, service: DebugMapService) {
  val descriptor = FileChooserDescriptorFactory.singleFile()
    .withExtensionFilter("json")
    .withTitle(DebugMapBundle.message("dialog.import.topics.title"))
    .withDescription(DebugMapBundle.message("dialog.import.topics.description"))
  val file = FileChooser.chooseFiles(descriptor, project, null).firstOrNull() ?: return
  val content = try {
    runBlockingCancellable { String(file.contentsToByteArray()) }
  }
  catch (e: Exception) {
    Messages.showErrorDialog(project, e.message, DebugMapBundle.message("dialog.import.error.title"))
    return
  }
  val parsed = service.parseImportJson(content)
  if (parsed.topics.isEmpty()) {
    Messages.showErrorDialog(
      project,
      parsed.errors.joinToString("\n").ifEmpty { DebugMapBundle.message("dialog.import.empty") },
      DebugMapBundle.message("dialog.import.error.title"),
    )
    return
  }
  if (parsed.errors.isNotEmpty()) {
    val proceed = Messages.showYesNoDialog(
      project,
      DebugMapBundle.message("dialog.import.warnings.message", parsed.errors.joinToString("\n")),
      DebugMapBundle.message("dialog.import.warnings.title"),
      Messages.getWarningIcon(),
    ) == Messages.YES
    if (!proceed) return
  }
  val existingTopicNames = service.getTopics().map { it.name }.toSet()
  val conflicting = parsed.topics.filter { it.name in existingTopicNames }
  val overwriteExisting = if (conflicting.isEmpty()) {
    false
  }
  else {
    val conflictList = conflicting.joinToString("\n") { "• ${it.name}" }
    val result = Messages.showYesNoCancelDialog(
      project,
      DebugMapBundle.message("dialog.import.conflicts.message", conflictList),
      DebugMapBundle.message("dialog.import.conflicts.title"),
      DebugMapBundle.message("dialog.import.conflicts.overwrite"),
      DebugMapBundle.message("dialog.import.conflicts.skip"),
      Messages.getCancelButton(),
      Messages.getWarningIcon(),
    )
    when (result) {
      Messages.YES -> true
      Messages.NO -> false
      else -> return
    }
  }
  val result = service.applyImport(parsed.topics, overwriteExisting)
  @NlsSafe val successMsg = buildString {
    append(DebugMapBundle.message("dialog.import.success.message", result.count))
    if (result.skippedActiveName != null) {
      append("\n")
      append(DebugMapBundle.message("dialog.import.success.skipped.active", result.skippedActiveName))
    }
  }
  Messages.showInfoMessage(project, successMsg, DebugMapBundle.message("dialog.import.success.title"))
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
