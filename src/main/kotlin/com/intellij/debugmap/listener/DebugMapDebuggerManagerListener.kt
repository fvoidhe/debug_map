package com.intellij.debugmap.listener

import com.intellij.debugmap.DebugMapService
import com.intellij.debugmap.model.BreakpointDef
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.XSourcePosition

class DebugMapDebuggerManagerListener(private val project: Project) : XDebuggerManagerListener {
  override fun processStarted(debugProcess: XDebugProcess) {
    val session = debugProcess.session
    session.addSessionListener(object : XDebugSessionListener {
      override fun sessionPaused() {
        val position = session.currentPosition ?: return

        val service = DebugMapService.getInstance(project)
        for (breakpoint in service.getBreakpointsByFile(position.file.url)) {
          if (isSamePosition(breakpoint, position)) {
            service.addRecentBreakpoint(breakpoint)
            break
          }
        }
      }

      override fun sessionStopped() {
        val service = DebugMapService.getInstance(project)
        service.stopRecentBreakpoints()
      }

      override fun sessionResumed() {
        val service = DebugMapService.getInstance(project)
      }
    })
  }

  fun isSamePosition(breakpoint: BreakpointDef, position: XSourcePosition): Boolean {
    if (breakpoint.line == position.line) return true

    if (breakpoint.typeId == "java-method" || breakpoint.typeId == "java-wildcard-method" || breakpoint.typeId == "kotlin-function") {
      if (breakpoint.line + 1 == position.line) return true
    }

    return false
  }
}
