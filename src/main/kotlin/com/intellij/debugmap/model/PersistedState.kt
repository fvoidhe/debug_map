package com.intellij.debugmap.model

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
  var masterBreakpointId: Long? = null
  var masterLeaveEnabled: Boolean? = null
  /** Stable primary key; 0 means unset (older persisted state) — a new random id is assigned on load. */
  var id: Long = 0
}

/** XML-serializable bookmark bean. */
class PersistedBookmark {
  var fileUrl: String = ""
  var line: Int = 0
  var name: String? = null
  var bookmarkType: String = "DEFAULT"
}

/** XML-serializable group bean (includes its breakpoints and bookmarks). */
class PersistedGroup {
  var id: Int = 0
  var name: String = ""
  var breakpoints: MutableList<PersistedBreakpoint> = mutableListOf()
  var bookmarks: MutableList<PersistedBookmark> = mutableListOf()
}

/** Root persisted state written to workspace.xml. */
class PersistedState {
  var nextGroupId: Int = 1
  var activeGroupId: Int = -1   // -1 means no active group
  var groups: MutableList<PersistedGroup> = mutableListOf()
}
