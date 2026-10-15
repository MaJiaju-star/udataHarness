package com.udata.harness.common.context;

/** Immutable limits for the local, model-independent tool-result projection. */
public final class ContextManagementOptions {
    public final int singleResultChars;
    public final int batchResultChars;
    public final int previewChars;
    public final int restoreMaxChars;

    public ContextManagementOptions(int singleResultChars, int batchResultChars,
                                    int previewChars, int restoreMaxChars) {
        if (singleResultChars < 1 || batchResultChars < 1 || previewChars < 1
                || previewChars >= singleResultChars || restoreMaxChars < 1) {
            throw new IllegalArgumentException("Invalid context management limits");
        }
        this.singleResultChars = singleResultChars;
        this.batchResultChars = batchResultChars;
        this.previewChars = previewChars;
        this.restoreMaxChars = restoreMaxChars;
    }
}
