package com.udata.harness.common.request;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 语言服务器的服务级配置。
 */
public class LspConfigRequest {
    private String name;
    private List<String> command = new ArrayList<>();
    private List<String> extensions = new ArrayList<>();
    private Map<String, String> env = new HashMap<>();
    private boolean enabled = true;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<String> getCommand() { return command; }
    public void setCommand(List<String> command) { this.command = command; }
    public List<String> getExtensions() { return extensions; }
    public void setExtensions(List<String> extensions) { this.extensions = extensions; }
    public Map<String, String> getEnv() { return env; }
    public void setEnv(Map<String, String> env) { this.env = env; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
