package com.udata.harness.common.request;

/** 修改当前用户工作区级沙箱开关。 */
public class SandboxSettingsRequest {
    private boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
