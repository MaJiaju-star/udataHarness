import {useEffect, useId, useLayoutEffect, useRef, useState} from "react";
import {createPortal} from "react-dom";
import {X} from "lucide-react";

// Portals keep panels above the composer without clipping its rounded input shell.
export default function ComposerPopover({label, trigger, className = "", children}) {
    const [open, setOpen] = useState(false);
    const [position, setPosition] = useState({});
    const button = useRef(null), panel = useRef(null);
    const id = useId();
    useLayoutEffect(() => {
        if (!open) return;
        const place = () => {
            const rect = button.current.getBoundingClientRect();
            const width = Math.min(340, window.innerWidth - 24);
            setPosition({width, left: Math.max(12, Math.min(rect.right - width, window.innerWidth - width - 12)),
                bottom: Math.max(12, window.innerHeight - rect.top + 8), maxHeight: Math.max(120, rect.top - 20)});
        };
        place();
        window.addEventListener("resize", place);
        window.addEventListener("scroll", place, true);
        return () => { window.removeEventListener("resize", place); window.removeEventListener("scroll", place, true); };
    }, [open]);
    useEffect(() => {
        if (!open) return;
        const previous = button.current;
        panel.current.focus();
        const close = event => {
            if (!panel.current?.contains(event.target) && !button.current?.contains(event.target)) setOpen(false);
        };
        document.addEventListener("pointerdown", close);
        return () => { document.removeEventListener("pointerdown", close); if (document.activeElement === document.body) previous?.focus(); };
    }, [open]);
    const dismiss = () => { setOpen(false); button.current?.focus(); };
    return <>
        <button ref={button} type="button" className={className} aria-label={label} aria-expanded={open}
            aria-haspopup="dialog" aria-controls={open ? id : undefined} onClick={() => setOpen(value => !value)}>{trigger}</button>
        {open && createPortal(<section ref={panel} id={id} role="dialog" aria-label={label} tabIndex={-1}
            className="composer-popover" style={position} onKeyDown={event => {
                if (event.key === "Escape") { event.stopPropagation(); dismiss(); }
            }}>
            <header><b>{label}</b><button type="button" aria-label={`关闭${label}`} onClick={dismiss}><X size={16}/></button></header>
            {typeof children === "function" ? children(dismiss) : children}
        </section>, document.body)}
    </>;
}
