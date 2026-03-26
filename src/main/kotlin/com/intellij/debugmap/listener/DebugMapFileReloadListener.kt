package com.intellij.debugmap.listener

import com.intellij.debugmap.DebugMapService
import com.intellij.debugmap.StructuralPathEntry
import com.intellij.debugmap.buildStructuralPathIndexBlocking
import com.intellij.debugmap.listener.DebugMapFileReloadListener.Companion.PATH_BONUS
import com.intellij.debugmap.model.BookmarkDef
import com.intellij.debugmap.model.BreakpointDef
import com.intellij.debugmap.model.LocationDef
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.endOffset
import com.intellij.psi.util.startOffset
import com.intellij.util.diff.Diff
import com.intellij.util.diff.Diff.Change
import com.intellij.util.diff.FilesTooBigForDiffException

class DebugMapFileReloadListener(private val project: Project) : FileDocumentManagerListener {

  private val service get() = DebugMapService.getInstance(project)

  private data class PendingReload(
    val oldContent: String,
    val breakpoints: List<Pair<BreakpointDef, Int>>, // def (carries topicId), currentLine
    val bookmarks: List<Pair<BookmarkDef, Int>>,      // def (carries topicId), currentLine
  )

  private val pendingReloads = mutableMapOf<String, PendingReload>()

  override fun beforeFileContentReload(file: VirtualFile, document: Document) {
    val fileUrl = file.url
    val breakpoints = service.getBreakpointsByFile(fileUrl)
      .map { def -> def to service.getCurrentLine(def.topicId, def) }
    val bookmarks = service.getBookmarksByFile(fileUrl)
      .map { def -> def to def.line }
    if (breakpoints.isEmpty() && bookmarks.isEmpty()) return
    pendingReloads[fileUrl] = PendingReload(document.text, breakpoints, bookmarks)
    // Drop markers now so Document.setText() won't trigger syncToService with invalid markers.
    service.dropFileEntries(fileUrl)
    // Remove active-topic IDE entries before content changes while line numbers are still known.
    // Suppress the callbacks that fire after removal to prevent corrupting the in-memory store.
    val activeTopicId = service.getActiveTopicId()
    val activeBreakpoints = breakpoints
      .filter { (def, _) -> def.topicId == activeTopicId }
      .map { (def, currentLine) -> def.copy(line = currentLine) }
    if (activeBreakpoints.isNotEmpty()) {
      service.suppressBreakpointRemovals(activeBreakpoints)
      service.ideManager.removeBreakpointDefs(activeBreakpoints)
    }
    val activeBookmarks = bookmarks
      .filter { (def, _) -> def.topicId == activeTopicId }
      .map { (def, _) -> def }
    if (activeBookmarks.isNotEmpty()) {
      service.suppressBookmarkRemovals(activeBookmarks)
      service.ideManager.removeBookmarkDefs(activeBookmarks)
    }
  }

  override fun fileContentReloaded(file: VirtualFile, document: Document) {
    val fileUrl = file.url
    val pending = pendingReloads.remove(fileUrl) ?: return
    val newContent = document.text
    val activeTopicId = service.getActiveTopicId()

    // Diff computation is pure string work — done eagerly before PSI is committed.
    val change = try {
      Diff.buildChanges(pending.oldContent, newContent)
    }
    catch (_: FilesTooBigForDiffException) {
      null
    }

    // All PSI-dependent work (structural path index, offset→line mapping, canPutAt) is
    // deferred until the document is committed to PSI, so we always see the new tree.
    PsiDocumentManager.getInstance(project).performForCommittedDocument(document) {
      val lastLine = (document.lineCount - 1).coerceAtLeast(0)

      // Structural path index of the new file content — used by the relocation algorithms.
      val pathIndex = buildStructuralPathIndexBlocking(project, fileUrl)

      // Merge breakpoints and bookmarks, sorted by current line, for unified relocation.
      val allAnchors: List<Pair<LocationDef, Int>> =
        (pending.breakpoints + pending.bookmarks).sortedBy { it.second }

      val correctedActiveBreakpoints = mutableListOf<BreakpointDef>()
      val correctedActiveBookmarks = mutableListOf<BookmarkDef>()

      var lastComputedLine: Int? = null
      var lastComputedTarget: Int = -1

      for ((def, currentLine) in allAnchors) {
        val targetLine = if (currentLine == lastComputedLine) {
          lastComputedTarget
        }
        else if (def.isStale) {
          relocateStaleLine(change, currentLine, lastLine, pathIndex).also {
            lastComputedLine = currentLine; lastComputedTarget = it
          }
        }
        else {
          relocateNormalLine(change, def, lastLine, pathIndex, document).also {
            lastComputedLine = currentLine; lastComputedTarget = it
          }
        }

        if (targetLine < 0) {
          when (def) {
            is BreakpointDef -> service.markBreakpointStale(def.id)
            is BookmarkDef -> service.markBookmarkStale(def.id)
          }
        }
        else {
          when (def) {
            is BreakpointDef -> {
              if (targetLine != def.line) service.moveBreakpointLine(def, targetLine)
              if (def.topicId == activeTopicId) correctedActiveBreakpoints.add(def.copy(line = targetLine))
            }
            is BookmarkDef -> {
              if (targetLine != def.line) service.moveBookmarkLine(def, targetLine)
              if (def.topicId == activeTopicId) correctedActiveBookmarks.add(def.copy(line = targetLine))
            }
          }
        }
      }

      if (correctedActiveBreakpoints.isNotEmpty()) {
        service.ideManager.addBreakpointDefs(correctedActiveBreakpoints)
      }
      if (correctedActiveBookmarks.isNotEmpty()) {
        val topicName = service.getTopics().find { it.id == activeTopicId }?.name
        service.ideManager.addBookmarkDefs(correctedActiveBookmarks, topicName)
      }

      service.onFileOpened(file)
    }
  }

  private fun relocateNormalLine(
    change: Change?,
    locationDef: LocationDef,
    lastLine: Int,
    pathIndex: List<StructuralPathEntry>,
    document: Document,
  ): Int {
    var newLine: Int = locationDef.line
    val currentLine = locationDef.line
    var currentChange = change

    while (currentChange != null) {
      if (currentLine < currentChange.line0) {
        newLine = currentLine + currentChange.line1 - currentChange.line0
        break
      }

      if (currentLine >= currentChange.line0 + currentChange.deleted) {
        currentChange = currentChange.link
        continue
      }
      else {
        // Each candidate carries its line number, token list, and the structural path of its
        // enclosing scope — the path is used as an extra confidence signal in findBestMatchLine.
        val frozenChange = currentChange
        val candidates = ReadAction.compute<List<Triple<Int, List<String>, String>>, RuntimeException> {
          buildCandidates(getPathIndexByChange(frozenChange, pathIndex, document, lastLine), document)
        }
        newLine = findBestMatchLine(candidates, locationDef.linePsiStrings, locationDef.logicalLocation, frozenChange.line1)
        return newLine
      }
    }

    if (newLine !in 0..<lastLine + 1) return -1
    return newLine
  }

  private fun relocateStaleLine(
    change: Change?,
    currentLine: Int,
    lastLine: Int,
    pathIndex: List<StructuralPathEntry>,
  ): Int {
    // TODO: implement recovery algorithm for stale anchors
    return -1
  }

  private fun collectLeafsByLine(
    element: PsiElement,
    document: Document,
    lineToPath: Map<Int, String>,
    result: MutableMap<Int, Pair<MutableList<String>, String>>,
  ) {
    if (element is PsiWhiteSpace || element is PsiComment) return
    if (element.firstChild == null) {
      val text = element.text
      if (text.isNotEmpty()) {
        val line = document.getLineNumber(element.startOffset)
        val path = lineToPath[line] ?: return
        result.getOrPut(line) { Pair(mutableListOf(), path) }.first.add(text)
      }
    }
    else {
      var child = element.firstChild
      while (child != null) {
        collectLeafsByLine(child, document, lineToPath, result)
        child = child.nextSibling
      }
    }
  }

  /**
   * Builds a line-number → structural-path map from [entries].
   *
   * For every line covered by more than one entry (due to nesting), only the narrowest
   * entry wins — the one with the smallest element offset span — so the path label
   * reflects the tightest enclosing scope (e.g. a method beats its containing class).
   *
   * Must be called inside a read action.
   */
  private fun buildLineToPath(entries: List<StructuralPathEntry>, document: Document): Map<Int, String> {
    val lineToPath = mutableMapOf<Int, String>()
    val lineToSize = mutableMapOf<Int, Int>()
    for (entry in entries) {
      val size = entry.element.endOffset - entry.element.startOffset
      val startLine = document.getLineNumber(entry.element.startOffset)
      val endLine = document.getLineNumber(entry.element.endOffset.coerceAtMost(document.textLength - 1))
      for (line in startLine..endLine) {
        val existing = lineToSize[line]
        if (existing == null || size < existing) {
          lineToPath[line] = entry.path
          lineToSize[line] = size
        }
      }
    }
    return lineToPath
  }

  private fun getPathIndexByChange(
    change: Change,
    pathIndex: List<StructuralPathEntry>,
    document: Document,
    lastLine: Int,
  ): List<StructuralPathEntry> {
    val startOffset = document.getLineStartOffset(change.line1)
    val endLine = (change.line1 + change.inserted).coerceAtMost(lastLine)
    val endOffset = document.getLineEndOffset(endLine)
    return pathIndex.filter { (it.element.endOffset >= startOffset && it.element.startOffset < endOffset) }
  }

  private fun buildCandidates(
    entries: List<StructuralPathEntry>,
    document: Document,
  ): List<Triple<Int, List<String>, String>> {
    if (entries.isEmpty()) return emptyList()
    val lineToPath = buildLineToPath(entries, document)
    val roots = entries.filter { candidate ->
      entries.none { other ->
        other !== candidate &&
        other.element.startOffset <= candidate.element.startOffset &&
        other.element.endOffset >= candidate.element.endOffset
      }
    }
    val result = mutableMapOf<Int, Pair<MutableList<String>, String>>()
    for (root in roots) collectLeafsByLine(root.element, document, lineToPath, result)
    return result.map { (line, pair) -> Triple(line, pair.first, pair.second) }.sortedBy { it.first }
  }

  /**
   * Each candidate is (lineNumber, tokenList, structuralPath).
   * Scoring: raw LCS length, plus [PATH_BONUS] if the candidate's structural path equals
   * [logicalLocation] (i.e. the anchor was originally inside the same named scope).
   * The MATCH_CONFIDENCE threshold is applied on the raw LCS ratio alone, so the path
   * bonus only influences ranking among already-plausible candidates, not the filter.
   */
  private fun findBestMatchLine(
    candidates: List<Triple<Int, List<String>, String>>,
    target: List<String>,
    logicalLocation: String?,
    preferredLine: Int,
  ): Int {
    if (candidates.isEmpty() || target.isEmpty()) return -1
    val best = candidates
                 .mapNotNull { (line, tokens, path) ->
                   val lcsScore = longestCommonSubsequence(target, tokens)
                   if (lcsScore == 0) return@mapNotNull null
                   val pathBonus = if (!logicalLocation.isNullOrBlank() && path == logicalLocation) PATH_BONUS else 0
                   val totalScore = lcsScore + pathBonus
                   Triple(line, Pair(totalScore, lcsScore), kotlin.math.abs(line - preferredLine))
                 }
                 .maxWithOrNull(compareBy({ it.second.first }, { -it.third }))
               ?: return -1
    val confidence = best.second.second.toDouble() / target.size
    return if (confidence >= MATCH_CONFIDENCE) best.first else -1
  }

  companion object {
    /** Minimum LCS/target-length ratio required to accept a line match. */
    private const val MATCH_CONFIDENCE = 0.6

    /** Bonus added to the ranking score when the candidate is in the same structural scope. */
    private const val PATH_BONUS = 2
  }

  private fun longestCommonSubsequence(a: List<String>, b: List<String>): Int {
    if (a.isEmpty() || b.isEmpty()) return 0
    val dp = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in 1..a.size) {
      for (j in 1..b.size) {
        dp[i][j] = if (a[i - 1] == b[j - 1]) {
          dp[i - 1][j - 1] + 1
        }
        else {
          maxOf(dp[i - 1][j], dp[i][j - 1])
        }
      }
    }
    return dp[a.size][b.size]
  }

}
