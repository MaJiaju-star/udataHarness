package com.udata.harness.common.request;

/**
 * 导入完整技能 ZIP 的请求，archiveBase64 为压缩包字节的 Base64 文本。
 */
public class SkillArchiveRequest {
    /**
     * 技能名称，需符合名称白名单。
     */
    private String name;

    /**
     * ZIP 压缩包的 Base64 编码文本。
     */
    private String archiveBase64;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArchiveBase64() {
        return archiveBase64;
    }

    public void setArchiveBase64(String archiveBase64) {
        this.archiveBase64 = archiveBase64;
    }
}
