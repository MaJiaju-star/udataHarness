package com.udata.harness.idea

import com.intellij.openapi.components.Service

@Service(Service.Level.PROJECT)
class HarnessProjectService { var panel: HarnessPanel? = null }

// Preserve the old action ID for saved shortcuts, using the same text reference path.
class SendSelectionAction : SendReferenceAction()
