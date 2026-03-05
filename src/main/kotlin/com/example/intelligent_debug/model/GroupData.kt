package com.example.intelligent_debug.model

data class GroupData(
  val id: Int,
  val name: String,
  val createdAt: Long,
  val lastActivatedAt: Long = createdAt,
  val breakpoints: List<BreakpointDef> = emptyList(),
)
