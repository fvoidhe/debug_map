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
        |Lists bookmarks in the project, optionally filtered by group and/or path substring.
    """)
  suspend fun list_bookmarks(
    @McpDescription("Filter by group name. Omit to include all groups.")
    group: String? = null,
    @McpDescription("Filter by path substring. Omit to include all files.")
    path: String? = null,
  ): BookmarkListResult {
    currentCoroutineContext().reportToolActivity(DebugMapBundle.message("tool.activity.listing.bookmarks"))
    val project = currentCoroutineContext().project

    val service = DebugMapService.getInstance(project)

    val items = mutableListOf<BookmarkInfo>()
    val activeGroupId = service.getActiveGroupId()
    for (g in service.getGroups()) {
      if (group != null && g.name != group) continue
      val isActive = g.id == activeGroupId
      for (bookmark in g.bookmarks) {
        if (path != null && !bookmark.fileUrl.contains(path)) continue
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(project.resolveInProject(bookmark.fileUrl))
        items.add(BookmarkInfo(
          path = bookmark.fileUrl,
          line = bookmark.line + 1,
          group = g.name,
          active = isActive,
          name = bookmark.name,
          mnemonic = bookmark.type.takeIf { it != BookmarkType.DEFAULT }?.mnemonic?.toString(),
          content = file?.let { lineContent(it, bookmark.line) }
        ))
      }
    }

    return BookmarkListResult(bookmarks = items, total = items.size)
  }

  @McpTool(name = "debug_bookmark_upsert")
  @McpDescription("""
        |Creates or updates a line bookmark at the specified file and line within a group.
        |If the bookmark already exists in the group, updates its description and mnemonic.
        |If it does not exist, creates it. The group is created automatically if needed.
    """)
  suspend fun upsert_bookmark(
    @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION)
    path: String,
    @McpDescription("1-based line number where the bookmark should be placed")
    line: Int,
    @McpDescription("Source text of the line to bookmark")
    content: String,
    @McpDescription("Bookmark group name. Created automatically if it does not exist.")
    group: String,
    @McpDescription("Optional description text for the bookmark. Pass empty string to clear.")
    description: String? = null,
    @McpDescription("Optional single-character mnemonic ('0'-'9' or 'A'-'Z'). Leave empty for a plain bookmark.")
    mnemonic: String? = null,
  ): BookmarkResult {
    currentCoroutineContext().reportToolActivity(DebugMapBundle.message("tool.activity.upserting.bookmark", path, line))
    val project = currentCoroutineContext().project
    val service = DebugMapService.getInstance(project)

    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(project.resolveInProject(path))
               ?: mcpFail("File not found: $path")

    val lineZeroBased = resolveLineByContent(file, line - 1, content) ?: run {
      val actual = lineContent(file, line - 1)
      mcpFail("Line $line contains '${actual ?: ""}', not '$content'. Re-read the file and pass the exact source text of the target line.")
    }

    val groupId = service.getGroupIdByName(group) ?: service.createGroup(group)
    val existing = service.getGroupBookmarks(groupId).firstOrNull { it.fileUrl == file.url && it.line == lineZeroBased }

    if (existing != null) {
      service.renameBookmark(existing, description ?: "")
      return BookmarkResult(path = path, line = line, status = "updated")
    }

    service.addBookmarkByToolWindow(groupId, BookmarkDef(
      groupId = groupId,
      fileUrl = file.url,
      line = lineZeroBased,
      name = description?.takeIf { it.isNotBlank() },
      type = resolveBookmarkType(mnemonic),
    ))

    return BookmarkResult(path = path, line = line, status = "created")
  }

  @McpTool(name = "debug_bookmark_remove")
  @McpDescription("""
        |Removes the line bookmark at the specified file and line.
        |If group is specified, removes only from that group; otherwise removes from the active group.
        |Reports not_found if no matching bookmark exists.
    """)
  suspend fun remove_bookmark(
    @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION)
    path: String,
    @McpDescription("1-based line number of the bookmark to remove")
    line: Int,
    @McpDescription("Bookmark group name. Defaults to the currently active group if omitted.")
    group: String? = null,
  ): BookmarkResult {
    currentCoroutineContext().reportToolActivity(DebugMapBundle.message("tool.activity.removing.bookmark", path, line))
    val project = currentCoroutineContext().project
    val service = DebugMapService.getInstance(project)

    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(project.resolveInProject(path))
               ?: mcpFail("File not found: $path")

    val lineZeroBased = line - 1

    val groupId = if (group != null) {
      service.getGroupIdByName(group) ?: mcpFail("Bookmark group not found: $group")
    }
    else {
      service.getActiveGroupId() ?: mcpFail("No active group")
    }

    val exists = service.getGroupBookmarks(groupId).any { it.fileUrl == file.url && it.line == lineZeroBased }
    if (!exists) return BookmarkResult(path = path, line = line, status = "not_found")

    service.removeBookmarkByToolWindow(groupId, file.url, lineZeroBased)

    return BookmarkResult(path = path, line = line, status = "removed")
  }

  // ----- helpers -----

  private fun resolveBookmarkType(mnemonic: String?): BookmarkType {
    val ch = mnemonic?.trim()?.firstOrNull()?.uppercaseChar() ?: return BookmarkType.DEFAULT
    return BookmarkType.get(ch)
  }

  // ----- data classes -----

  @Serializable
  data class BookmarkInfo(
    val path: String,
    val line: Int?,
    val group: String,
    val active: Boolean,
    val name: String? = null,
    val mnemonic: String? = null,
    val content: String? = null,
  )

  @Serializable
  data class BookmarkResult(
    val path: String,
    val line: Int,
    /** "created" | "updated" | "removed" | "not_found" */
    val status: String,
  )

  @Serializable
  data class BookmarkListResult(
    val bookmarks: List<BookmarkInfo>,
    val total: Int,
  )
}
