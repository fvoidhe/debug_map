package com.intellij.debugmap.mcp

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile

internal suspend fun lineContent(file: VirtualFile, lineZeroBased: Int): String? {
  return readAction {
    val doc = FileDocumentManager.getInstance().getDocument(file) ?: return@readAction null
    if (lineZeroBased < 0 || lineZeroBased >= doc.lineCount) return@readAction null
    val start = doc.getLineStartOffset(lineZeroBased)
    val end = doc.getLineEndOffset(lineZeroBased)
    doc.getText(TextRange(start, end)).trim()
  }
}
