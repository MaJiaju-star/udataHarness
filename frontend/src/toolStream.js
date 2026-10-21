// Match provider call identity before transport stream identity. Some providers
// omit tool names on deltas or change index formatting between stream phases.
export function findToolStreamIndex(tools, event) {
    if (event.callId) {
        const index = tools.findIndex(tool => tool.providerCallId === event.callId || tool.callId === event.callId);
        if (index >= 0) return index;
    }
    return event.streamId ? tools.findIndex(tool => tool.streamId === event.streamId
        && (!event.callId || !tool.providerCallId || tool.providerCallId === event.callId)) : -1;
}
