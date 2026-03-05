package com.example.intelligent_debug.model

/** XML-serializable breakpoint bean. */
class PersistedBreakpoint {
  var fileUrl: String = ""
  var line: Int = 0
  var typeId: String = "java-line"
  var condition: String? = null
  var logExpression: String? = null
  var name: String? = null
}

/** XML-serializable group bean (includes its breakpoints). */
class PersistedGroup {
  var id: Int = 0
  var name: String = ""
  var createdAt: Long = 0L
  var lastActivatedAt: Long = 0L
  var breakpoints: MutableList<PersistedBreakpoint> = mutableListOf()
}

/** Root persisted state written to workspace.xml. */
class PersistedState {
  var nextGroupId: Int = 1
  var activeGroupId: Int = -1   // -1 means no active group
  var groups: MutableList<PersistedGroup> = mutableListOf()
}
