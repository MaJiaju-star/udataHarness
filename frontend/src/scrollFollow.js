/** Manual browsing stays paused until an explicit return to the latest reply. */
export function createScrollFollow() {
    let enabled = true;
    return {
        isFollowing: () => enabled,
        pause() { enabled = false; },
        resume() { enabled = true; },
        follow(scroll) { if (enabled) scroll(); }
    };
}

/** Compensate only for geometry changes above the anchor, never replay its old scroll position. */
export function anchorShift(anchor, messages, offsets) {
    if (!anchor) return 0;
    const index = messages.findIndex(message => message.id === anchor.id);
    return index < 0 ? 0 : offsets[index] - anchor.start;
}
