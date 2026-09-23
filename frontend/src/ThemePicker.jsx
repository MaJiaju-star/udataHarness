import {useEffect, useRef, useState} from "react";
import {Check, Monitor, Moon, Palette, Sun} from "lucide-react";
import {useWorkspaceStore} from "./workspaceStore.js";

const modes = [
    {id: "system", label: "跟随系统", icon: Monitor},
    {id: "light", label: "浅色", icon: Sun},
    {id: "dark", label: "深色", icon: Moon}
];

const palettes = [
    {id: "emerald", label: "翡翠", colors: ["#10a37f", "#55c8a3", "#e9f7f2"]},
    {id: "ocean", label: "海洋", colors: ["#2f7de1", "#65a6ff", "#eaf3ff"]},
    {id: "violet", label: "暮光", colors: ["#7c5ce7", "#a38cff", "#f0edff"]},
    {id: "amber", label: "暖沙", colors: ["#c47a19", "#e5a449", "#fff4df"]}
];

export default function ThemePicker({expanded}) {
    const [open, setOpen] = useState(false);
    const rootRef = useRef(null);
    const themeMode = useWorkspaceStore(state => state.themeMode);
    const colorTheme = useWorkspaceStore(state => state.colorTheme);
    const setThemeMode = useWorkspaceStore(state => state.setThemeMode);
    const setColorTheme = useWorkspaceStore(state => state.setColorTheme);

    useEffect(() => {
        if (!open) return undefined;
        const close = event => {
            if (event.type === "keydown" && event.key !== "Escape") return;
            if (event.type === "mousedown" && rootRef.current?.contains(event.target)) return;
            setOpen(false);
        };
        window.addEventListener("mousedown", close);
        window.addEventListener("keydown", close);
        return () => {
            window.removeEventListener("mousedown", close);
            window.removeEventListener("keydown", close);
        };
    }, [open]);

    return <div className="theme-picker-root" ref={rootRef}>
        <button className={`sidebar-tool ${open ? "active" : ""}`} onClick={() => setOpen(value => !value)}
                aria-expanded={open} title="外观主题">
            <Palette size={17}/>{expanded && <span>外观主题</span>}
        </button>
        {open && <section className="theme-picker" role="dialog" aria-label="外观主题">
            <header><div><b>外观主题</b><small>选择界面明暗与强调色</small></div><Palette size={17}/></header>
            <div className="theme-mode-options" role="radiogroup" aria-label="明暗模式">
                {modes.map(item => {
                    const Icon = item.icon;
                    return <button key={item.id} className={themeMode === item.id ? "active" : ""}
                                   role="radio" aria-checked={themeMode === item.id}
                                   onClick={() => setThemeMode(item.id)}>
                        <Icon size={14}/><span>{item.label}</span>
                    </button>;
                })}
            </div>
            <div className="theme-palette-label">主题色</div>
            <div className="theme-palette-grid" role="radiogroup" aria-label="主题色">
                {palettes.map(item => <button key={item.id}
                    className={colorTheme === item.id ? "active" : ""}
                    role="radio" aria-checked={colorTheme === item.id}
                    onClick={() => setColorTheme(item.id)}>
                    <span className="theme-swatches">{item.colors.map(color =>
                        <i key={color} style={{background: color}}/>)}</span>
                    <b>{item.label}</b>{colorTheme === item.id && <Check size={13}/>}
                </button>)}
            </div>
        </section>}
    </div>;
}
