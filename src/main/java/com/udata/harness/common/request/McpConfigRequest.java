package com.udata.harness.common.request;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Server 配置，包括传输方式、命令、环境变量和工具过滤规则。
 */
public class McpConfigRequest {
    /**
     * 唯一名称。
     */
    private String name;

    /**
     * 传输方式（如 stdio/sse/streamable）。
     */
    private String transport;

    /**
     * 远程服务 URL。
     */
    private String url;

    /**
     * 本地启动命令。
     */
    private String command;

    /**
     * 命令参数。
     */
    private List<String> args = new ArrayList<>();

    /**
     * 请求 Header，可能包含敏感凭据。
     */
    private Map<String, String> headers = new HashMap<>();

    /**
     * 进程环境变量，可能包含敏感凭据。
     */
    private Map<String, String> env = new HashMap<>();

    /**
     * 允许暴露的工具白名单。
     */
    private List<String> allowedTools = new ArrayList<>();

    /**
     * 禁止暴露的工具黑名单。
     */
    private List<String> disallowedTools = new ArrayList<>();

    /**
     * 是否启用该 Server。
     */
    private boolean enabled = true;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public List<String> getArgs() { return args; }
    public void setArgs(List<String> args) { this.args = args; }
    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }
    public Map<String, String> getEnv() { return env; }
    public void setEnv(Map<String, String> env) { this.env = env; }
    public List<String> getAllowedTools() { return allowedTools; }
    public void setAllowedTools(List<String> allowedTools) { this.allowedTools = allowedTools; }
    public List<String> getDisallowedTools() { return disallowedTools; }
    public void setDisallowedTools(List<String> disallowedTools) { this.disallowedTools = disallowedTools; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
