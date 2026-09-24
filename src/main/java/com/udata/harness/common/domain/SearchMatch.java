package com.udata.harness.common.domain;

/**
 * 文件内容中的一处检索命中，记录行列位置与上下文预览。
 */
public class SearchMatch {
    /**
     * 行号（1 起）。
     */
    private int line;

    /**
     * 起始列号（1 起）。
     */
    private int column;

    /**
     * 结束列号（1 起）。
     */
    private int endColumn;

    /**
     * 围绕命中位置的单行摘要。
     */
    private String preview;

    /**
     * 创建一处命中。
     *
     * @param line 行号
     * @param column 起始列号
     * @param endColumn 结束列号
     * @param preview 上下文预览
     */
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
