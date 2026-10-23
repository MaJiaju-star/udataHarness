/** One moving target per scroll container; new output never restarts an animation. */
export function createSmoothScroll({getElement, canFollow, instant, now, requestFrame, cancelFrame}) {
    let frame = null, previous = 0;
    const stop = () => { if (frame !== null) cancelFrame(frame); frame = null; };
    const bottom = element => Math.max(0, element.scrollHeight - element.clientHeight);
    const snap = () => {
        stop();
        const element = getElement();
        if (element && canFollow()) element.scrollTop = bottom(element);
    };
    function tick() {
        frame = null;
        const element = getElement();
        if (!element || !canFollow()) return;
        if (instant()) { snap(); return; }
        const time = now(), elapsed = Math.min(64, Math.max(1, time - previous)); previous = time;
        const target = bottom(element), gap = target - element.scrollTop;
        if (Math.abs(gap) <= 1) { element.scrollTop = target; return; }
        // Time-based damping behaves consistently on 60/120Hz displays and catches up on large bursts.
        const before = element.scrollTop;
        element.scrollTop = before + gap * (1 - Math.exp(-elapsed / 65));
        // Integer scroll implementations must not stall forever on a fractional final step.
        if (Math.abs(element.scrollTop - before) < .01) { element.scrollTop = target; return; }
        frame = requestFrame(tick);
    }
    return {
        follow() {
            if (!canFollow() || !getElement()) { stop(); return; }
            if (instant()) { snap(); return; }
            if (frame === null) { previous = now(); frame = requestFrame(tick); }
        },
        snap,
        stop
    };
}
