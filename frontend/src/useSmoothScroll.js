import {useEffect, useLayoutEffect, useRef, useState} from "react";
import {createSmoothScroll} from "./smoothScroll.js";

export default function useSmoothScroll(elementRef, canFollow) {
    const allowed = useRef(canFollow);
    useLayoutEffect(() => { allowed.current = canFollow; }, [canFollow]);
    const [scroller] = useState(() => createSmoothScroll({
        getElement: () => elementRef.current, canFollow: () => allowed.current(),
        instant: () => document.hidden || window.matchMedia("(prefers-reduced-motion: reduce)").matches,
        now: () => performance.now(), requestFrame: callback => requestAnimationFrame(callback), cancelFrame: id => cancelAnimationFrame(id)
    }));
    useEffect(() => {
        const hide = () => { if (document.hidden) scroller.snap(); };
        document.addEventListener("visibilitychange", hide);
        return () => { document.removeEventListener("visibilitychange", hide); scroller.stop(); };
    }, [scroller]);
    return scroller;
}
