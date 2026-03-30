package com.intellij.debugmap.sync

import com.intellij.debugmap.DebugMapService
import com.intellij.debugmap.model.BookmarkDef
import com.intellij.debugmap.model.BreakpointDef
import com.intellij.debugmap.model.LocationDef
import com.intellij.openapi.Disposable
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Tracks inactive-topic breakpoints and bookmarks via [RangeMarker]s on open documents.
 *
 * IntelliJ's interval tree shifts marker offsets automatically on every edit.
 * A [DocumentListener] is registered per tracked file to sync updated line
 * numbers back to [DebugMapService] (and thus the UI) whenever line count changes.
 *
 * All document/marker access must happen on the EDT (which has implicit read access).
 */
class BreakpointMarkerTracker(private val service: DebugMapService) {

  private class Entry(var def: LocationDef, val marker: RangeMarker)
  private data class FileState(val listenerDisposable: Disposable)

  private val lock = ReentrantLock()
  private val entries = mutableListOf<Entry>()
  private val fileStates = mutableMapOf<String, FileState>()
  private val project get() = service.project

  fun onFileOpened(file: VirtualFile) {
    val document = FileDocumentManager.getInstance().getDocument(file) ?: return
    val fileUrl = file.url
    val activeTopicId = service.getActiveTopicId()

    val locationDefList = mutableListOf<LocationDef>()
    locationDefList.addAll(service.getBreakpointsByFile(fileUrl))
    locationDefList.addAll(service.getBookmarksByFile(fileUrl))

    lock.withLock {
      var added = false
      for (def in locationDefList) {
        if (def.topicId == activeTopicId) continue
        if (def.isStale || def.line >= document.lineCount) continue

        val start = document.getLineStartOffset(def.line)
        val end = document.getLineEndOffset(def.line)
        entries.add(Entry(def, document.createRangeMarker(start, end)))
        added = true
      }

      if (added && fileUrl !in fileStates) {
        val disposable = Disposer.newDisposable(service)
        document.addDocumentListener(createDocumentListener(fileUrl), disposable)
        fileStates[fileUrl] = FileState(disposable)
      }
    }
  }

  fun onFileClosed(file: VirtualFile): Unit = flushByUrl(file.url)

  /**
   * Adds a single [RangeMarker] for [def] if the file is currently open and the def is eligible
   * (non-stale, inactive topic, valid line). A no-op if a marker already exists for this def.
   */
  @RequiresReadLock
  fun trackDef(def: LocationDef) {
    if (def.isStale) return
    if (def.topicId == service.getActiveTopicId()) return
    val file = com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(def.fileUrl) ?: return
    val document = FileDocumentManager.getInstance().getDocument(file) ?: return
    if (def.line >= document.lineCount) return
    val fileUrl = def.fileUrl
    lock.withLock {
      val alreadyTracked = entries.any {
        it.def.id == def.id
      }
      if (alreadyTracked) return
      val start = document.getLineStartOffset(def.line)
      val end = document.getLineEndOffset(def.line)
      entries.add(Entry(def, document.createRangeMarker(start, end)))
      if (fileUrl !in fileStates) {
        val disposable = Disposer.newDisposable(service)
        document.addDocumentListener(createDocumentListener(fileUrl), disposable)
        fileStates[fileUrl] = FileState(disposable)
      }
    }
  }

  /** Syncs all marker positions to the service and releases all markers. */
  fun flushAll() {
    val urls = lock.withLock { entries.map { it.def.fileUrl }.toSet() }
    urls.forEach(::flushByUrl)
  }

  /** Releases all markers and re-creates them for all currently open files. */
  fun initForOpenFiles() {
    lock.withLock {
      entries.clear()
      fileStates.values.forEach { Disposer.dispose(it.listenerDisposable) }
      fileStates.clear()
    }
    FileEditorManager.getInstance(project).openFiles.forEach(::onFileOpened)
  }

  private fun createDocumentListener(fileUrl: String) = object : DocumentListener {
    override fun documentChanged(event: DocumentEvent) {
      val oldLineCount = event.oldFragment.count { it == '\n' }
      val newLineCount = event.newFragment.count { it == '\n' }
      if (oldLineCount != newLineCount) syncToService(fileUrl)
    }
  }

  // Called on line-count changes and at flush time. Reads marker positions and
  // updates the service. entry.def is kept in sync so subsequent lookups are correct.
  private fun syncToService(fileUrl: String) = lock.withLock {
    val toRemove = mutableListOf<Entry>()
    for (entry in entries.filter { it.def.fileUrl == fileUrl }) {
      if (!entry.marker.isValid) {
        when (val def = entry.def) {
          is BreakpointDef -> service.removeBreakpointByIde(def.topicId, def.fileUrl, def.line, def.column)
          is BookmarkDef -> service.removeBookmarkByIde(def.topicId, def.fileUrl, def.line)
        }
        toRemove.add(entry)
        continue
      }
      val newLine = entry.marker.document.getLineNumber(entry.marker.startOffset)
      if (newLine != entry.def.line) {
        when (val def = entry.def) {
          is BreakpointDef -> service.moveAndActiveBreakpointLine(def, newLine)
          is BookmarkDef -> service.moveAndActiveBookmarkLine(def, newLine)
        }
        entry.def = entry.def.copyWithLine(newLine)
      }
    }
    entries.removeAll(toRemove)
  }

  /** Drops markers/listener for [fileUrl] without syncing to service. */
  fun dropFileEntries(fileUrl: String) {
    val stateToDispose = lock.withLock {
      entries.removeIf { it.def.fileUrl == fileUrl }
      fileStates.remove(fileUrl)
    }
    stateToDispose?.let { Disposer.dispose(it.listenerDisposable) }
  }

  private fun flushByUrl(fileUrl: String) {
    syncToService(fileUrl)
    dropFileEntries(fileUrl)
  }
}
