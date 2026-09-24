package com.udata.harness.common.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 按文件分组的全局检索结果：一个文件对应一个 item，matches 保存其内部所有命中。
 */
public class GlobalSearchItem {
    /**
     * 相对于工作区根目录的文件路径。
     */
    private final String path;

    /**
     * 文件名。
     */
    private final String name;

    /**
     * 该文件内的命中列表。
     */
    private final List<SearchMatch> matches = new ArrayList<>();

    /**
     * 创建一个检索结果项。
     *
     * @param path 文件相对路径
     * @param name 文件名
     */
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
