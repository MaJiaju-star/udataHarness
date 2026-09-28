(() => {
  // Repeated load notifications must preserve calls already awaiting native replies.
  if (typeof window.udataNative !== 'function') {
    const pending = new Map();
    const prefix = `${Date.now()}-${Math.random()}-`;
    let next = 0;
    window.udataReceive = r => {
      const p = pending.get(r.requestId);
      if (!p) return;
      pending.delete(r.requestId);
      clearTimeout(p.timer);
      r.error ? p.fail(500, r.error) : p.ok(JSON.stringify(r.result));
    };
    window.udataNative = (raw, ok, fail) => {
      const m = JSON.parse(raw);
      m.requestId = prefix + String(++next);
      const rejectQuery = (code, message) => {
        const p = pending.get(m.requestId);
        if (!p) return;
        pending.delete(m.requestId);
        clearTimeout(p.timer);
        p.fail(code, message);
      };
      const timer = setTimeout(() => rejectQuery(408, 'IDEA 操作超时'), 120000);
      pending.set(m.requestId, {ok, fail, timer});
      try {
        /*__NATIVE_QUERY__*/
      } catch (error) {
        rejectQuery(500, error.message || 'IDEA 桥接调用失败');
      }
    };
  }
  window.dispatchEvent(new Event('udata:ready'));
  // Verify the native channel even if React has not installed its ready listener yet.
  window.udataNative(JSON.stringify({method: 'ready', params: {}}), () => {}, () => {});
})();
