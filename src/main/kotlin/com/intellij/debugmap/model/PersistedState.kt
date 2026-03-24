package com.intellij.debugmap.model

import com.intellij.util.xmlb.annotations.OptionTag
import com.intellij.util.xmlb.annotations.Tag

/** XML-serializable breakpoint bean. */
class PersistedBreakpoint {
  var fileUrl: String = ""
  var line: Int = 0

  /** Zero-based column; 0 = whole-line, positive = inline (lambda) breakpoint. */
  var column: Int = 0
  var typeId: String = "java-line"
  var condition: String? = null
  var logExpression: String? = null
  var name: String? = null
  var enabled: Boolean? = null
  var logMessage: Boolean? = null
  var logStack: Boolean? = null

  /** "ALL", "THREAD", or "NONE". null = IDE default. */
  var suspendPolicy: String? = null
  var masterBreakpointId: String? = null
  var masterLeaveEnabled: Boolean? = null

  /** Stable primary key; blank means unset (older persisted state) — a new random id is assigned on load. */
  var id: String = ""
  var anchorStructuralPath: String? = null
  var anchorContent: String? = null
  /** "NORMAL" | "STALE". Unknown values default to NORMAL on load. */
  var status: String = "NORMAL"
}

/** XML-serializable bookmark bean. */
class PersistedBookmark {
  var fileUrl: String = ""
  var line: Int = 0
  var name: String? = null
  var bookmarkType: String = "DEFAULT"

  /** Stable primary key; blank means unset (older persisted state) — a new random id is assigned on load. */
  var id: String = ""
  var anchorStructuralPath: String? = null
  var anchorContent: String? = null
  /** "NORMAL" | "STALE". Unknown values default to NORMAL on load. */
  var status: String = "NORMAL"
}

/** XML-serializable topic bean (includes its breakpoints and bookmarks). */
class PersistedTopic {
  var id: Int = 0
  var name: String = ""
  var status: String = "OPEN"
  var breakpoints: MutableList<PersistedBreakpoint> = mutableListOf()
  var bookmarks: MutableList<PersistedBookmark> = mutableListOf()
}

/** Root persisted state written to workspace.xml. */
class PersistedState {
  // XML keys kept as legacy names for backward compatibility with existing workspace.xml files.
  var nextTopicId: Int = 1
  var activeTopicId: Int = -1   // -1 means no active topic
  var topics: MutableList<PersistedTopic> = mutableListOf()
}
