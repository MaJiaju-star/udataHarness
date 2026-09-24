package com.udata.harness.common.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作区全局检索响应。
 *
 * <p>items 按文件分组；truncated 表示命中数已达到上限，提示前端结果被截断。</p>
 */
public class GlobalSearchResponse {
    /**
     * 本次检索使用的关键字。
     */
    private String keyword;

    /**
     * 检索模式：name 按文件名，content 按文件内容。
     */
    private String mode;

    /**
     * 命中的文件数。
     */
    private int totalFiles;

    /**
     * 命中的总匹配数。
     */
    private int totalMatches;

    /**
     * 是否因达到上限而截断。
     */
    private boolean truncated;

    /**
     * 按文件分组的命中列表。
     */
    private final List<GlobalSearchItem> items = new ArrayList<>();

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public int getTotalFiles() {
        return totalFiles;
    }

    public void setTotalFiles(int totalFiles) {
        this.totalFiles = totalFiles;
    }

    public int getTotalMatches() {
        return totalMatches;
    }

    public void setTotalMatches(int totalMatches) {
        this.totalMatches = totalMatches;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public List<GlobalSearchItem> getItems() {
        return items;
    }
}
