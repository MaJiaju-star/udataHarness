package com.udata.harness.common.request;

import java.util.List;

/**
 * 工作区全局检索请求。
 *
 * <p>mode 为 name/content；extensions 可选用于按扩展名过滤；maxResults 由服务层限制上限。</p>
 */
public class GlobalSearchRequest {
    /**
     * 检索关键字。
     */
    private String keyword;

    /**
     * 检索模式：name 按文件名，content 按文件内容。
     */
    private String mode;

    /**
     * 可选扩展名过滤列表。
     */
    private List<String> extensions;

    /**
     * 结果上限；为空时使用服务端默认值。
     */
    private Integer maxResults;

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

    public List<String> getExtensions() {
        return extensions;
    }

    public void setExtensions(List<String> extensions) {
        this.extensions = extensions;
    }

    public Integer getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(Integer maxResults) {
        this.maxResults = maxResults;
    }
}
