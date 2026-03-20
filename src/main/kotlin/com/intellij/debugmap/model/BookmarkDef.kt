package com.intellij.debugmap.model

import com.intellij.ide.bookmark.BookmarkType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

data class BookmarkDef(
  override val topicId: Int,
  override val fileUrl: String,
  override val line: Int,
  override val name: String? = null,
  val type: BookmarkType = BookmarkType.DEFAULT,
  override val id: String = java.util.UUID.randomUUID().toString(),
) : LocationDef(topicId, fileUrl, line, name, id) {

  fun toJson(): JsonObject = buildJsonObject {
    put("fileUrl", JsonPrimitive(fileUrl))
    put("line", JsonPrimitive(line))
    name?.let { put("name", JsonPrimitive(it)) }
    put("bookmarkType", JsonPrimitive(type.name))
  }

  companion object {
    /** Parses a bookmark from JSON. Throws [IllegalArgumentException] if required fields are missing. */
    fun fromJson(obj: JsonObject): BookmarkDef {
      val fileUrl = obj["fileUrl"]?.jsonPrimitive?.contentOrNull
        ?: throw IllegalArgumentException("missing fileUrl")
      val line = obj["line"]?.jsonPrimitive?.intOrNull
        ?: throw IllegalArgumentException("missing line")
      return BookmarkDef(
        topicId = 0,
        fileUrl = fileUrl,
        line = line,
        name = obj["name"]?.jsonPrimitive?.contentOrNull,
        type = runCatching {
          BookmarkType.valueOf(obj["bookmarkType"]?.jsonPrimitive?.content ?: "DEFAULT")
        }.getOrDefault(BookmarkType.DEFAULT),
      )
    }
  }
}
