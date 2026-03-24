package com.intellij.debugmap

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner

/**
 * Builds semantic anchor data for the given (fileUrl, line) by inspecting the document and PSI.
 * Returns a pair of (structuralPath, content), or null if the file or document cannot be resolved.
 */
internal suspend fun buildSemanticAnchor(project: Project, fileUrl: String, line: Int): Pair<String?, String>? =
  runCatching { readAction { computeAnchor(project, fileUrl, line) } }.getOrNull()

private fun computeAnchor(project: Project, fileUrl: String, line: Int): Pair<String?, String>? {
  val vFile = VirtualFileManager.getInstance().findFileByUrl(fileUrl) ?: return null
  val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return null
  if (line < 0 || line >= document.lineCount) return null

  fun lineText(l: Int): String {
    val s = document.getLineStartOffset(l)
    val e = document.getLineEndOffset(l)
    return document.getText(TextRange(s, e)).trim()
  }

  val content = lineText(line)

  var structuralPath: List<PsiNameIdentifierOwner>? = null

  val psiFile = PsiManager.getInstance(project).findFile(vFile)
  if (psiFile != null) {
    val lineStart = document.getLineStartOffset(line)
    structuralPath = buildStructuralPath(psiFile, lineStart)
  }

  return Pair(
    structuralPath?.takeIf { it.isNotEmpty() }?.joinToString(":") { it.name!! },
    content,
  )
}

private fun buildStructuralPath(psiFile: PsiFile, startOffset: Int): List<PsiNameIdentifierOwner> {
  var current = psiFile.findElementAt(startOffset)
  if (current == null) return emptyList()


  val psiElements: MutableList<PsiNameIdentifierOwner> = mutableListOf()
  while (current != null && current !is PsiFile) {
    if (current is PsiNameIdentifierOwner && !current.name.isNullOrBlank()) {
      psiElements.add(current)
    }
    current = current.parent
  }

  return psiElements.reversed()
}

