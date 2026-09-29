import {Moon, Sun} from "lucide-react";
import {useWorkspaceStore} from "./workspaceStore.js";

export default function ThemeToggle({dark}) {
    const themeMode = useWorkspaceStore(state => state.themeMode);
    const setThemeMode = useWorkspaceStore(state => state.setThemeMode);
    return <div className="theme-toggle">
        <button type="button" role="switch" aria-label="黑夜模式" aria-checked={dark}
            title={dark ? "切换到浅色模式" : "切换到黑夜模式"}
            onClick={() => setThemeMode(dark ? "light" : "dark")}>
            {dark ? <Moon size={14}/> : <Sun size={14}/>}<span>黑夜模式 {dark ? "开" : "关"}</span>
        </button>
        <select aria-label="明暗模式" value={themeMode} onChange={event => setThemeMode(event.target.value)}>
            <option value="system">跟随系统</option>
            <option value="light">浅色</option>
            <option value="dark">黑夜</option>
        </select>
    </div>;
}
