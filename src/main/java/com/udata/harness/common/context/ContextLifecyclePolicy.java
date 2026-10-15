package com.udata.harness.common.context;

/** Conservative rules: never infer closure from age or a checked todo. */
final class ContextLifecyclePolicy {
    boolean shouldArchive(String ownerTask, String activeTask, String state, int chars) {
        if (ownerTask == null || ownerTask.isEmpty() || ownerTask.equals(activeTask)) return false;
        return ("closed".equals(state) && chars > 1000) || ("paused".equals(state) && chars > 4000);
    }
}
