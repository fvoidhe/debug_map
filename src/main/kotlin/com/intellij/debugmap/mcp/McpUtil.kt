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

/**
 * Finds the actual 0-based line number by scanning a ±[tolerance]-line window around [hintLine]
 * for a line whose trimmed text matches [content].
 *
 * Returns the matched line number, or null if no match is found in the window.
 * Prefer this over exact-line validation so that small AI line-number errors (e.g. off-by-N
 * after edits shift the file) are tolerated instead of causing hard failures.
 */
internal suspend fun resolveLineByContent(
  file: VirtualFile,
  hintLine: Int,
  content: String,
  tolerance: Int = 5,
): Int? {
  return readAction {
    val doc = FileDocumentManager.getInstance().getDocument(file) ?: return@readAction null
    val from = maxOf(0, hintLine - tolerance)
    val to = minOf(doc.lineCount - 1, hintLine + tolerance)
    val trimmed = content.trim()
    for (line in from..to) {
      val start = doc.getLineStartOffset(line)
      val end = doc.getLineEndOffset(line)
      if (doc.getText(TextRange(start, end)).trim() == trimmed) return@readAction line
    }
    null
  }
}
