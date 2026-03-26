package com.intellij.debugmap

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.startOffset

/**
 * An entry in the structural path index of a file.
 * [path] is the colon-joined structural path (e.g. "MyClass:myMethod");
 * [element] is the [PsiNameIdentifierOwner] that owns that path.
 */
internal data class StructuralPathEntry(val path: String, val element: PsiNameIdentifierOwner)

/** Semantic anchor data for a source location. */
internal data class SemanticAnchor(
  /** Structural PSI path (e.g. "MyClass:myMethod"), null if unavailable. */
  val structuralPath: String?,
  /** Trimmed source text of the anchor line. */
  val content: String,
  /** PSI token texts on the anchor line, excluding whitespace and comments. */
  val linePsiStrings: List<String>,
)

/**
 * Builds semantic anchor data for the given (fileUrl, line) by inspecting the document and PSI.
 * Returns null if the file or document cannot be resolved.
 */
internal fun buildSemanticAnchor(project: Project, fileUrl: String, line: Int): SemanticAnchor? =
  runCatching { ReadAction.compute<SemanticAnchor?, Throwable> { computeAnchor(project, fileUrl, line) } }.getOrNull()

/** Synchronous variant for use on the EDT (e.g. document-reload listener callbacks). */
internal fun buildStructuralPathIndexBlocking(project: Project, fileUrl: String): List<StructuralPathEntry> =
  runCatching {
    ReadAction.compute<List<StructuralPathEntry>, Throwable> {
      val vFile = VirtualFileManager.getInstance().findFileByUrl(fileUrl) ?: return@compute emptyList()
      val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: return@compute emptyList()
      collectStructuralPathIndex(psiFile)
    }
  }.getOrDefault(emptyList())

private fun computeAnchor(project: Project, fileUrl: String, line: Int): SemanticAnchor? {
  val vFile = VirtualFileManager.getInstance().findFileByUrl(fileUrl) ?: return null
  val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return null
  if (line < 0 || line >= document.lineCount) return null

  val lineStart = document.getLineStartOffset(line)
  val lineEnd = document.getLineEndOffset(line)
  val content = document.getText(TextRange(lineStart, lineEnd)).trim()

  var structuralPath: List<PsiNameIdentifierOwner>? = null
  var linePsiStrings: List<String> = emptyList()

  val psiFile = PsiManager.getInstance(project).findFile(vFile)
  if (psiFile != null) {
    val firstElement = psiFile.findElementAt(lineStart)
    if (firstElement != null) {
      structuralPath = buildStructuralPath(firstElement)
      linePsiStrings = buildLinePsiStrings(firstElement, lineEnd, psiFile)
    }
  }

  return SemanticAnchor(
    structuralPath = structuralPath?.takeIf { it.isNotEmpty() }?.joinToString(":") { it.name!! },
    content = content,
    linePsiStrings = linePsiStrings,
  )
}

private fun collectStructuralPathIndex(psiFile: PsiFile): List<StructuralPathEntry> {
  val result = mutableListOf<StructuralPathEntry>()
  val pathStack = mutableListOf<String>()

  fun visit(element: PsiElement) {
    val isNamed = element is PsiNameIdentifierOwner && !element.name.isNullOrBlank()
    if (isNamed) {
      pathStack.add(element.name!!)
      result.add(StructuralPathEntry(pathStack.joinToString(":"), element))
    }
    var child = element.firstChild
    while (child != null) {
      visit(child)
      child = child.nextSibling
    }
    if (isNamed) pathStack.removeLast()
  }

  visit(psiFile)
  return result
}

private fun buildStructuralPath(firstElement: PsiElement): List<PsiNameIdentifierOwner> {
  var current: PsiElement? = firstElement

  val psiElements: MutableList<PsiNameIdentifierOwner> = mutableListOf()
  while (current != null && current !is PsiFile) {
    if (current is PsiNameIdentifierOwner && !current.name.isNullOrBlank()) {
      psiElements.add(current)
    }
    current = current.parent
  }

  return psiElements.reversed()
}


internal fun buildLinePsiStrings(firstElement: PsiElement, endOffset: Int, psiFile: PsiFile): List<String> {
  val result = mutableListOf<String>()
  var current: PsiElement? = firstElement
  while (current != null && current.startOffset < endOffset) {
    collectLeafTexts(current, endOffset, result)
    if (current.nextSibling != null) {
      current = current.nextSibling
    } else {
      // Sibling chain exhausted before reaching end of line (e.g. `} else {` where `}`
      // is the last child of its block). Jump to the next PSI element in the file.
      val nextOffset = current.textRange.endOffset
      if (nextOffset >= endOffset) break
      current = psiFile.findElementAt(nextOffset)
    }
  }
  return result
}

private fun collectLeafTexts(element: PsiElement, endOffset: Int, result: MutableList<String>) {
  if (element is PsiWhiteSpace || element is PsiComment) return
  if (element.startOffset >= endOffset) return
  if (element.firstChild == null) {
    val text = element.text
    if (text.isNotEmpty()) result.add(text)
  } else {
    var child = element.firstChild
    while (child != null) {
      collectLeafTexts(child, endOffset, result)
      child = child.nextSibling
    }
  }
}