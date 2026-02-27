package com.example.intelligent_debug.toolsets

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile

internal fun lineContent(file: VirtualFile, lineZeroBased: Int): String? {
  val doc = FileDocumentManager.getInstance().getDocument(file) ?: return null
  if (lineZeroBased < 0 || lineZeroBased >= doc.lineCount) return null
  val start = doc.getLineStartOffset(lineZeroBased)
  val end = doc.getLineEndOffset(lineZeroBased)
  return doc.getText(TextRange(start, end)).trim()
}
