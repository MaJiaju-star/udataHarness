package com.udata.harness.common.domain;

import java.util.ArrayList;
import java.util.List;

/** 按文件分组的全局检索结果。 */
public class GlobalSearchItem {
    private final String path;
    private final String name;
    private final List<SearchMatch> matches = new ArrayList<>();

    public GlobalSearchItem(String path, String name) {
        this.path = path;
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public String getName() {
        return name;
    }

    public List<SearchMatch> getMatches() {
        return matches;
    }
}
