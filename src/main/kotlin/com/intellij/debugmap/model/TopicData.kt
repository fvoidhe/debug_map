package com.intellij.debugmap.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class TopicData(
  val id: Int,
  val name: String,
  val status: TopicStatus = TopicStatus.OPEN,
  val breakpoints: List<BreakpointDef> = emptyList(),
  val bookmarks: List<BookmarkDef> = emptyList(),
) {

  fun toJson(): JsonObject = buildJsonObject {
    put("name", JsonPrimitive(name))
    put("status", JsonPrimitive(status.name))
    put("breakpoints", buildJsonArray { for (bp in breakpoints) add(bp.toJson()) })
    put("bookmarks", buildJsonArray { for (bm in bookmarks) add(bm.toJson()) })
  }

  companion object {
    /**
     * Parses a topic from JSON. Throws [IllegalArgumentException] if the topic itself is invalid
     * (e.g. blank name). Non-fatal item-level errors are appended to [errors].
     */
    fun fromJson(obj: JsonObject, errors: MutableList<String>): TopicData {
      val name = obj["name"]?.jsonPrimitive?.contentOrNull
      require(!name.isNullOrBlank()) { "missing or blank name" }
      val status = runCatching {
        TopicStatus.valueOf(obj["status"]?.jsonPrimitive?.contentOrNull ?: "OPEN")
      }.getOrDefault(TopicStatus.OPEN)
      val breakpoints = mutableListOf<BreakpointDef>()
      obj["breakpoints"]?.jsonArray?.forEachIndexed { i, el ->
        runCatching { breakpoints.add(BreakpointDef.fromJson(el.jsonObject)) }
          .onFailure { errors.add("Topic '$name' breakpoint #${i + 1}: ${it.message}") }
      }
      val bookmarks = mutableListOf<BookmarkDef>()
      obj["bookmarks"]?.jsonArray?.forEachIndexed { i, el ->
        runCatching { bookmarks.add(BookmarkDef.fromJson(el.jsonObject)) }
          .onFailure { errors.add("Topic '$name' bookmark #${i + 1}: ${it.message}") }
      }
      return TopicData(id = 0, name = name, status = status,
                       breakpoints = breakpoints, bookmarks = bookmarks)
    }
  }
}
