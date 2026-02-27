@file:Suppress("FunctionName", "unused")

package com.example.intelligent_debug.toolsets

import com.intellij.ide.bookmark.Bookmark
import com.intellij.ide.bookmark.BookmarkGroup
import com.intellij.ide.bookmark.BookmarkProvider
import com.intellij.ide.bookmark.BookmarkType
import com.intellij.ide.bookmark.BookmarksManager
import com.intellij.ide.bookmark.FileBookmark
import com.intellij.ide.bookmark.LineBookmark
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.mcpserver.reportToolActivity
import com.intellij.mcpserver.util.relativizeIfPossible
import com.intellij.mcpserver.util.resolveInProject
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

class BookmarkToolset : McpToolset {

  @McpTool(name = "bookmark_list")
  @McpDescription("""
    |Lists all bookmarks in the project, grouped by bookmark group.
  """)
  suspend fun list_bookmarks(): BookmarkListResult {
    currentCoroutineContext().reportToolActivity("Listing bookmarks")
    val project = currentCoroutineContext().project

    return readAction {
      val manager = BookmarksManager.getInstance(project)
                    ?: return@readAction BookmarkListResult(emptyList(), 0)
      val projectPath = project.basePath?.let { Path.of(it) }

      val items = mutableListOf<BookmarkInfo>()
      for (group in manager.getGroups()) {
        for (bookmark in group.getBookmarks()) {
          val type = manager.getType(bookmark)
          val description = group.getDescription(bookmark)

          when (bookmark) {
            is LineBookmark -> {
              items.add(BookmarkInfo(
                path = projectPath?.relativizeIfPossible(bookmark.file) ?: bookmark.file.path,
                line = bookmark.line + 1,
                group = group.name,
                description = description?.takeIf { it.isNotBlank() },
                mnemonic = type?.mnemonic?.takeIf { it != BookmarkType.DEFAULT.mnemonic }?.toString(),
                content = lineContent(bookmark.file, bookmark.line),
              ))
            }
            is FileBookmark -> {
              items.add(BookmarkInfo(
                path = projectPath?.relativizeIfPossible(bookmark.file) ?: bookmark.file.path,
                line = null,
                group = group.name,
                description = description?.takeIf { it.isNotBlank() },
                mnemonic = type?.mnemonic?.takeIf { it != BookmarkType.DEFAULT.mnemonic }?.toString(),
                content = null,
              ))
            }
          }
        }
      }

      BookmarkListResult(bookmarks = items, total = items.size)
    }
  }

  @McpTool(name = "bookmark_add")
  @McpDescription("""
    |Adds a line bookmark at the specified file and line.
    |If a bookmark already exists in the target group, reports it without creating a duplicate.
  """)
  suspend fun add_bookmark(
    @McpDescription("Path relative to the project root")
    path: String,
    @McpDescription("1-based line number where the bookmark should be added")
    line: Int,
    @McpDescription("Source text of the line to bookmark")
    content: String,
    @McpDescription("Bookmark group name. Created automatically if it does not exist.")
    group: String,
    @McpDescription("Optional description text for the bookmark")
    description: String? = null,
    @McpDescription("Optional single-character mnemonic ('0'-'9' or 'A'-'Z'). Leave empty for a plain bookmark.")
    mnemonic: String? = null,
  ): BookmarkResult {
    currentCoroutineContext().reportToolActivity("Adding bookmark at $path:$line")
    val project = currentCoroutineContext().project

    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(project.resolveInProject(path))
               ?: mcpFail("File not found: $path")

    val lineZeroBased = line - 1

    data class AddContext(val manager: BookmarksManager, val bookmark: Bookmark, val bookmarkType: BookmarkType)

    val ctx = readAction {
      val actual = lineContent(file, lineZeroBased)
      if (actual == null || actual.trim() != content.trim()) {
        mcpFail("Line $line contains '${actual ?: ""}', not '$content'. Re-read the file and pass the exact source text of the target line.")
      }

      val (manager, bookmark) = resolveBookmark(project, file, lineZeroBased, path, line)

      if (manager.getGroups(bookmark).any { it.name == group }) return@readAction null

      AddContext(manager, bookmark, resolveBookmarkType(mnemonic))
    } ?: return BookmarkResult(path = path, line = line, status = "already_exists")

    writeAction {
      val targetGroup = ctx.manager.getGroup(group) ?: ctx.manager.addGroup(group, false)
                        ?: mcpFail("Cannot create bookmark group '$group'")
      targetGroup.add(ctx.bookmark, ctx.bookmarkType, description?.takeIf { it.isNotBlank() })
    }

    return BookmarkResult(path = path, line = line, status = "created")
  }

  @McpTool(name = "bookmark_remove")
  @McpDescription("""
    |Removes the line bookmark at the specified file and line.
    |If no bookmark exists at that location, reports accordingly.
    |Removes the bookmark from all groups it belongs to.
  """)
  suspend fun remove_bookmark(
    @McpDescription("Path relative to the project root")
    path: String,
    @McpDescription("1-based line number of the bookmark to remove")
    line: Int,
  ): BookmarkResult {
    currentCoroutineContext().reportToolActivity("Removing bookmark at $path:$line")
    val project = currentCoroutineContext().project

    val resolvedPath = project.resolveInProject(path)
    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolvedPath)
               ?: mcpFail("File not found: $path")

    val lineZeroBased = line - 1

    data class RemoveContext(val manager: BookmarksManager, val bookmark: Bookmark)

    val ctx = readAction {
      val (manager, bookmark) = resolveBookmark(project, file, lineZeroBased, path, line)

      if (manager.getType(bookmark) == null) return@readAction null

      RemoveContext(manager, bookmark)
    } ?: return BookmarkResult(path = path, line = line, status = "not_found")

    writeAction {
      ctx.manager.remove(ctx.bookmark)
    }

    return BookmarkResult(path = path, line = line, status = "removed")
  }

  @McpTool(name = "bookmark_update_description")
  @McpDescription("""
    |Updates the description of an existing line bookmark.
    |Reports not_found if no bookmark exists at that location.
  """)
  suspend fun update_bookmark_description(
    @McpDescription("Path relative to the project root")
    path: String,
    @McpDescription("1-based line number of the bookmark to update")
    line: Int,
    @McpDescription("New description text. Pass empty string to clear.")
    description: String,
  ): BookmarkResult {
    currentCoroutineContext().reportToolActivity("Updating bookmark at $path:$line")
    val project = currentCoroutineContext().project

    val resolvedPath = project.resolveInProject(path)
    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolvedPath)
               ?: mcpFail("File not found: $path")

    val lineZeroBased = line - 1

    data class UpdateContext(val bookmark: Bookmark, val groups: List<BookmarkGroup>)

    val ctx = readAction {
      val (manager, bookmark) = resolveBookmark(project, file, lineZeroBased, path, line)

      val groups = manager.getGroups(bookmark)
      if (groups.isEmpty()) return@readAction null

      UpdateContext(bookmark, groups)
    } ?: return BookmarkResult(path = path, line = line, status = "not_found")

    writeAction {
      ctx.groups.forEach { group: BookmarkGroup ->
        group.setDescription(ctx.bookmark, description)
      }
    }

    return BookmarkResult(path = path, line = line, status = "updated")
  }

  // ----- helpers -----

  private fun resolveBookmarkType(mnemonic: String?): BookmarkType {
    val ch = mnemonic?.trim()?.firstOrNull()?.uppercaseChar() ?: return BookmarkType.DEFAULT
    return BookmarkType.get(ch)
  }

  /** Must be called inside a read action. */
  private fun resolveBookmark(
    project: Project, file: VirtualFile, lineZeroBased: Int, path: String, line: Int,
  ): Pair<BookmarksManager, Bookmark> {
    val manager = BookmarksManager.getInstance(project)
                  ?: mcpFail("BookmarksManager is not available for this project")
    val provider = BookmarkProvider.EP.getExtensions(project).minByOrNull { it.weight }
                   ?: mcpFail("LineBookmarkProvider is not available for this project")
    val bookmark = provider.createBookmark(mapOf("url" to file.url, "line" to "$lineZeroBased"))
                   ?: mcpFail("Cannot create bookmark at $path:$line")
    return manager to bookmark
  }

  // ----- data classes -----

  @Serializable
  data class BookmarkInfo(
    val path: String,
    /** 1-based line number, null for file-level bookmarks */
    val line: Int?,
    val group: String,
    val description: String? = null,
    /** Single-character mnemonic ('0'-'9' or 'A'-'Z'), null for plain bookmarks */
    val mnemonic: String? = null,
    /** Source text of the bookmarked line, null for file-level bookmarks */
    val content: String? = null,
  )

  @Serializable
  data class BookmarkResult(
    val path: String,
    val line: Int,
    /** "created" | "already_exists" | "removed" | "not_found" | "updated" */
    val status: String,
  )

  @Serializable
  data class BookmarkListResult(
    val bookmarks: List<BookmarkInfo>,
    val total: Int,
  )
}
