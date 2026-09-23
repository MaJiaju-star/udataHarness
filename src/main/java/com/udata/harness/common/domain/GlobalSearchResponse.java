package com.udata.harness.common.domain;

import java.util.ArrayList;
import java.util.List;

/** 工作区全局检索响应。 */
public class GlobalSearchResponse {
    private String keyword;
    private String mode;
    private int totalFiles;
    private int totalMatches;
    private boolean truncated;
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
