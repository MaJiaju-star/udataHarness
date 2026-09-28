import {useCallback, useEffect, useRef, useState} from "react";
import {createPortal} from "react-dom";

// Embedded IDEA browsers may suppress JavaScript's native confirm/prompt dialogs.
export function usePageDialog() {
    const [request, setRequest] = useState(null);
    const pending = useRef(null);
    const ask = useCallback((kind, message, initialValue = "") => {
        // Resolve an older request before replacing it, so callers never hang.
        pending.current?.resolve(pending.current.kind === "confirm" ? false : null);
        return new Promise(resolve => {
            pending.current = {kind, resolve};
            setRequest({kind, message, initialValue});
        });
    }, []);
    const finish = useCallback(value => {
        pending.current?.resolve(value);
        pending.current = null;
        setRequest(null);
    }, []);
    useEffect(() => () => {
        pending.current?.resolve(pending.current.kind === "confirm" ? false : null);
        pending.current = null;
    }, []);
    const confirm = useCallback(message => ask("confirm", message), [ask]);
    const prompt = useCallback((message, value) => ask("prompt", message, value), [ask]);
    return {confirm, prompt, dialog: request && createPortal(
        <PageDialog key={request.message} request={request} onFinish={finish}/>, document.body
    )};
}

function PageDialog({request, onFinish}) {
    const [value, setValue] = useState(request.initialValue);
    const panel = useRef(null);
    const cancel = useRef(null);
    const input = useRef(null);
    const isPrompt = request.kind === "prompt";
    useEffect(() => {
        const previous = document.activeElement;
        const focusTarget = input.current || cancel.current;
        focusTarget?.focus();
        input.current?.select();
        return () => { if (previous?.isConnected) previous.focus(); };
    }, []);
    function onKeyDown(event) {
        if (event.key === "Escape") {
            event.preventDefault();
            onFinish(isPrompt ? null : false);
        }
        if (event.key === "Tab") {
            const elements = [...panel.current.querySelectorAll("input, button")];
            const first = elements[0], last = elements[elements.length - 1];
            if (event.shiftKey && document.activeElement === first) {
                event.preventDefault(); last.focus();
            } else if (!event.shiftKey && document.activeElement === last) {
                event.preventDefault(); first.focus();
            }
        }
        event.stopPropagation();
    }
    return <div className="modal-backdrop page-dialog-backdrop">
        <form ref={panel} className="page-dialog" role="dialog" aria-modal="true"
              aria-labelledby="page-dialog-title" aria-describedby={isPrompt ? undefined : "page-dialog-message"}
              onKeyDown={onKeyDown} onSubmit={event => {
                  event.preventDefault();
                  onFinish(isPrompt ? value : true);
              }}>
            <h2 id="page-dialog-title">{isPrompt ? request.message : "确认操作"}</h2>
            {isPrompt ? <input ref={input} aria-label={request.message} value={value}
                onChange={event => setValue(event.target.value)}/> :
                <p id="page-dialog-message">{request.message}</p>}
            <footer>
                <button ref={cancel} type="button" onClick={() => onFinish(isPrompt ? null : false)}>取消</button>
                <button type="submit" className="primary-button">确定</button>
            </footer>
        </form>
    </div>;
}
