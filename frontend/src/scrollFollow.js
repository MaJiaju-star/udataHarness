/** Only a downward user gesture can resume following at the bottom. */
export function createScrollFollow() {
    let enabled = true, returning = false, previousTop = 0;
    const atBottom = element => element.scrollHeight - element.clientHeight - element.scrollTop <= 2;
    return {
        isFollowing: () => enabled,
        pause() { enabled = false; returning = false; },
        resume() { enabled = true; returning = false; },
        input(direction, element) {
            previousTop = element.scrollTop;
            returning = direction >= 0;
            enabled = direction > 0 && atBottom(element);
        },
        scrolled(element) {
            if (!enabled && returning && element.scrollTop > previousTop && atBottom(element)) {
                enabled = true; returning = false;
            }
            previousTop = element.scrollTop;
        },
        follow(scroll) { if (enabled) scroll(); }
    };
}

/** Scrolling an inner tool/thinking pane must not change conversation following. */
export function consumesNestedScroll(target, container, direction) {
    for (let node = target; node && node !== container; node = node.parentElement) {
        if (node.scrollHeight <= node.clientHeight + 1) continue;
        if (!/(auto|scroll)/.test(getComputedStyle(node).overflowY)) continue;
        if (direction < 0 ? node.scrollTop > 0 : node.scrollTop + node.clientHeight < node.scrollHeight - 1) return true;
    }
    return false;
}

/** Compensate only for geometry changes above the anchor, never replay its old scroll position. */
export function anchorShift(anchor, messages, offsets) {
    if (!anchor) return 0;
    const index = messages.findIndex(message => message.id === anchor.id);
    return index < 0 ? 0 : offsets[index] - anchor.start;
}
