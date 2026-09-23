package com.udata.harness.common.domain;

/** 文件内容中的一处检索命中。 */
public class SearchMatch {
    private int line;
    private int column;
    private int endColumn;
    private String preview;

    public SearchMatch(int line, int column, int endColumn, String preview) {
        this.line = line;
        this.column = column;
        this.endColumn = endColumn;
        this.preview = preview;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }

    public int getEndColumn() {
        return endColumn;
    }

    public String getPreview() {
        return preview;
    }
}
