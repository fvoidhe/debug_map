package com.intellij.debugmap.model

/**
 * Validity status of a [LocationDef] (bookmark or breakpoint).
 *
 * - [NORMAL] — the item is stored and its line number is considered valid.
 * - [STALE]  — the stored line number no longer accurately identifies the original location
 *              (e.g. the file was edited externally and line-translation failed). Stale items
 *              are sorted to the bottom of their list and displayed with reduced opacity.
 *              Only program logic may set this status; MCP can only read it.
 */
enum class LocationStatus { NORMAL, STALE }
