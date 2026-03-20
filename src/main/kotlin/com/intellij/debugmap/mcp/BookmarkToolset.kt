@file:Suppress("FunctionName", "unused")

package com.intellij.debugmap.mcp

import com.intellij.debugmap.DebugMapBundle
import com.intellij.debugmap.DebugMapService
import com.intellij.debugmap.model.BookmarkDef
import com.intellij.ide.bookmark.BookmarkType
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.mcpserver.reportToolActivity
import com.intellij.mcpserver.toolsets.Constants
import com.intellij.mcpserver.util.resolveInProject
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

class BookmarkToolset : McpToolset {

  @McpTool(name = "debug_bookmark_list")
  @McpDescription("""
        |Lists bookmarks in the project, optionally filtered by topic and/or path substring.
    """)
  suspend fun list_bookmarks(
    @McpDescription("Filter by topic name. Omit to include all topics.")
    topic: String? = null,
    @McpDescription("Filter by path substring. Omit to include all files.")
    path: String? = null,
  ): BookmarkListResult {
    currentCoroutineContext().reportToolActivity(DebugMapBundle.message("tool.activity.listing.bookmarks"))
    val project = currentCoroutineContext().project

    val service = DebugMapService.getInstance(project)

    val items = mutableListOf<BookmarkInfo>()
    val activeTopicId = service.getActiveTopicId()
    for (t in service.getTopics()) {
      if (topic != null && t.name != topic) continue
      val isActive = t.id == activeTopicId
      for (bookmark in t.bookmarks) {
        if (path != null && !bookmark.fileUrl.contains(path)) continue
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(project.resolveInProject(bookmark.fileUrl))
        items.add(BookmarkInfo(
          id = bookmark.id,
          path = bookmark.fileUrl,
          line = bookmark.line + 1,
          topic = t.name,
          active = isActive,
          name = bookmark.name,
          mnemonic = bookmark.type.takeIf { it != BookmarkType.DEFAULT }?.mnemonic?.toString(),
          content = file?.let { lineContent(it, bookmark.line) }
        ))
      }
    }

    return BookmarkListResult(bookmarks = items, total = items.size)
  }

  @McpTool(name = "debug_bookmark_add")
  @McpDescription("""
        |Creates a new line bookmark at the specified file and line within a topic.
        |The topic is created automatically if it does not exist.
    """)
  suspend fun add_bookmark(
    @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION)
    path: String,
    @McpDescription("1-based line number where the bookmark should be placed")
    line: Int,
    @McpDescription("Source text of the line to bookmark")
    content: String,
    @McpDescription("Bookmark topic name. Created automatically if it does not exist.")
    topic: String,
    @McpDescription("Description text for the bookmark.")
    description: String,
    @McpDescription("Optional single-character mnemonic ('0'-'9' or 'A'-'Z'). Leave empty for a plain bookmark.")
    mnemonic: String? = null,
  ): BookmarkResult {
    currentCoroutineContext().reportToolActivity(DebugMapBundle.message("tool.activity.adding.bookmark", path, line))
    val project = currentCoroutineContext().project
    val service = DebugMapService.getInstance(project)

    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(project.resolveInProject(path))
               ?: mcpFail("File not found: $path")

    val lineZeroBased = resolveLineByContent(file, line - 1, content) ?: run {
      val actual = lineContent(file, line - 1)
      mcpFail("Line $line contains '${actual ?: ""}', not '$content'. Re-read the file and pass the exact source text of the target line.")
    }

    val topicId = service.getTopicIdByName(topic) ?: service.createTopic(topic)
    val def = BookmarkDef(
      topicId = topicId,
      fileUrl = file.url,
      line = lineZeroBased,
      name = description.takeIf { it.isNotBlank() },
      type = resolveBookmarkType(mnemonic),
    )
    service.addBookmarkByToolWindow(topicId, def)

    return BookmarkResult(id = def.id, path = path, line = line, status = "created")
  }

  @McpTool(name = "debug_bookmark_update")
  @McpDescription("""
        |Updates an existing bookmark identified by its id.
        |Only the fields you supply are changed; omitted fields keep their current values.
        |To change the line, you must also supply content (the exact source text of the new line) for validation.
        |The path and topic cannot be changed.
    """)
  suspend fun update_bookmark(
    @McpDescription("Stable id of the bookmark to update (returned by list and add)")
    id: String,
    @McpDescription("New description text. Pass empty string to clear. Omit to keep unchanged.")
    description: String? = null,
    @McpDescription("New single-character mnemonic ('0'-'9' or 'A'-'Z'). Pass empty string to clear. Omit to keep unchanged.")
    mnemonic: String? = null,
    @McpDescription("New 1-based line number. Requires content for validation. Omit to keep unchanged.")
    line: Int? = null,
    @McpDescription("Source text of the new line. Required when line is provided.")
    content: String? = null,
  ): BookmarkResult {
    currentCoroutineContext().reportToolActivity(DebugMapBundle.message("tool.activity.updating.bookmark", id))
    val project = currentCoroutineContext().project
    val service = DebugMapService.getInstance(project)

    val existing = service.findBookmarkById(id) ?: mcpFail("Bookmark not found: $id")

    if (line != null && content == null) {
      mcpFail("content is required when changing line")
    }

    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(project.resolveInProject(existing.fileUrl))
               ?: mcpFail("File not found: ${existing.fileUrl}")

    val newLineZeroBased: Int = if (line != null) {
      resolveLineByContent(file, line - 1, content!!) ?: run {
        val actual = lineContent(file, line - 1)
        mcpFail("Line $line contains '${actual ?: ""}', not '$content'. Re-read the file and pass the exact source text of the target line.")
      }
    }
    else {
      existing.line
    }

    val newName: String? = when {
      description == null -> existing.name
      description.isBlank() -> null
      else -> description
    }

    val newType: BookmarkType = when {
      mnemonic == null -> existing.type
      mnemonic.isBlank() -> BookmarkType.DEFAULT
      else -> resolveBookmarkType(mnemonic)
    }

    val newDef = existing.copy(
      line = newLineZeroBased,
      name = newName,
      type = newType,
    )
    service.updateBookmarkByToolWindow(existing, newDef)

    val resultLine = newLineZeroBased + 1
    return BookmarkResult(id = id, path = existing.fileUrl, line = resultLine, status = "updated")
  }

  @McpTool(name = "debug_bookmark_remove")
  @McpDescription("""
        |Removes a bookmark by its stable id, or by file and line.
        |When id is provided, path/line/topic are ignored.
        |If topic is specified (path+line mode), removes only from that topic; otherwise removes from the active topic.
    """)
  suspend fun remove_bookmark(
    @McpDescription("Stable bookmark id. When provided, path/line/topic are ignored.")
    id: String? = null,
    @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION)
    path: String = "",
    @McpDescription("1-based line number of the bookmark to remove")
    line: Int = -1,
    @McpDescription("Bookmark topic name. Defaults to the currently active topic if omitted.")
    topic: String? = null,
  ): BookmarkResult {
    val project = currentCoroutineContext().project
    val service = DebugMapService.getInstance(project)

    if (id != null) {
      currentCoroutineContext().reportToolActivity(DebugMapBundle.message("tool.activity.removing.bookmark.id", id))
      val def = service.findBookmarkById(id) ?: mcpFail("Bookmark not found: $id")
      service.removeBookmarkByToolWindow(def.topicId, def.fileUrl, def.line)
      return BookmarkResult(id = id, path = def.fileUrl, line = def.line + 1, status = "removed")
    }

    currentCoroutineContext().reportToolActivity(DebugMapBundle.message("tool.activity.removing.bookmark", path, line))

    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(project.resolveInProject(path))
               ?: mcpFail("File not found: $path")

    val lineZeroBased = line - 1

    val topicId = if (topic != null) {
      service.getTopicIdByName(topic) ?: mcpFail("Bookmark topic not found: $topic")
    }
    else {
      service.getActiveTopicId() ?: mcpFail("No active topic")
    }

    val existing = service.getTopicBookmarks(topicId).firstOrNull { it.fileUrl == file.url && it.line == lineZeroBased }
                   ?: mcpFail("Bookmark not found at $path:$line")

    service.removeBookmarkByToolWindow(topicId, file.url, lineZeroBased)

    return BookmarkResult(id = existing.id, path = path, line = line, status = "removed")
  }

  // ----- helpers -----

  private fun resolveBookmarkType(mnemonic: String?): BookmarkType {
    val ch = mnemonic?.trim()?.firstOrNull()?.uppercaseChar() ?: return BookmarkType.DEFAULT
    return BookmarkType.get(ch)
  }

  // ----- data classes -----

  @Serializable
  data class BookmarkInfo(
    val id: String,
    val path: String,
    val line: Int?,
    val topic: String,
    val active: Boolean,
    val name: String? = null,
    val mnemonic: String? = null,
    val content: String? = null,
  )

  @Serializable
  data class BookmarkResult(
    val id: String,
    val path: String,
    val line: Int,
    /** "created" | "updated" | "removed" */
    val status: String,
  )

  @Serializable
  data class BookmarkListResult(
    val bookmarks: List<BookmarkInfo>,
    val total: Int,
  )
}
