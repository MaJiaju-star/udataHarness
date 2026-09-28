// Native methods exist only in the trusted JCEF page owned by the plugin.
const parameters = new URLSearchParams(window.location.search);
export const isIdea = parameters.get("host") === "idea";
export const initialUserId = parameters.get("userId") || "";
export const initialWorkspaceId = parameters.get("workspaceId") || "";

export function callIdea(method, params = {}) {
    if (!isIdea || typeof window.udataNative !== "function") {
        return Promise.reject(new Error("IDEA 桥接尚未就绪，请在插件中重新连接"));
    }
    return new Promise((resolve, reject) => {
        window.udataNative(JSON.stringify({method, params}), response => {
            try { resolve(JSON.parse(response)); } catch { reject(new Error("IDEA 返回的数据无效")); }
        }, (_code, message) => reject(new Error(message || "IDEA 操作失败")));
    });
}
