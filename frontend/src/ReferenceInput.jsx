import {forwardRef, useCallback, useImperativeHandle, useLayoutEffect, useRef} from "react";
import {useWorkspaceStore} from "./workspaceStore.js";
import {readReferenceInput, referenceSelectionOffset, referenceSelectionPoint, splitReferenceTokens} from "./referenceTokens.js";

const ReferenceInput = forwardRef(function ReferenceInput({value, disabled, placeholder, onChange, onSelect, onBlur, onClick, onKeyDown}, ref) {
    const rootRef = useRef(null);
    const selectionRef = useRef({start: 0, end: 0});
    const composing = useRef(false);
    const references = useWorkspaceStore(state => state.promptReferences);

    const selection = useCallback(() => {
        const root = rootRef.current, selected = window.getSelection();
        if (root && selected?.anchorNode && root.contains(selected.anchorNode) && root.contains(selected.focusNode)) {
            const anchor = referenceSelectionOffset(root, selected.anchorNode, selected.anchorOffset);
            const focus = referenceSelectionOffset(root, selected.focusNode, selected.focusOffset);
            selectionRef.current = {start: Math.min(anchor, focus), end: Math.max(anchor, focus)};
        }
        return selectionRef.current;
    }, []);

    const setSelectionRange = useCallback((start, end) => {
        const root = rootRef.current;
        if (!root) return;
        const from = referenceSelectionPoint(root, start), to = referenceSelectionPoint(root, end);
        const range = document.createRange();
        range.setStart(from.node, from.offset); range.setEnd(to.node, to.offset);
        const selected = window.getSelection();
        selected.removeAllRanges(); selected.addRange(range);
        selectionRef.current = {start, end};
    }, []);

    const adapter = {
        get value() { return rootRef.current ? readReferenceInput(rootRef.current) : value; },
        get selectionStart() { return selection().start; },
        get selectionEnd() { return selection().end; },
        focus() { rootRef.current?.focus(); }, setSelectionRange,
    };
    useImperativeHandle(ref, () => adapter);

    function eventFor(event) {
        return {...event, target: adapter, currentTarget: adapter,
            preventDefault: () => event.preventDefault(), stopPropagation: () => event.stopPropagation()};
    }

    function changed(event) {
        selection();
        if (!composing.current) onChange?.(eventFor(event));
    }

    function replaceSelection(text, event) {
        const root = rootRef.current, selected = selection();
        setSelectionRange(selected.start, selected.end);
        const range = window.getSelection().getRangeAt(0);
        range.deleteContents();
        range.insertNode(document.createTextNode(text));
        root.normalize();
        setSelectionRange(selected.start + text.length, selected.start + text.length);
        changed(event);
    }

    useLayoutEffect(() => {
        const root = rootRef.current;
        if (!root || composing.current) return;
        const active = document.activeElement === root, selected = selection();
        const tokens = splitReferenceTokens(value, references);
        const expected = tokens.filter(token => token.reference).map(token => token.text);
        const actual = [...root.querySelectorAll("[data-reference]")].map(tag => tag.dataset.reference);
        // Leave normal browser edits in place so caret and native editing history survive.
        if (readReferenceInput(root) !== value || JSON.stringify(actual) !== JSON.stringify(expected)) {
        const nodes = tokens.map(token => {
            if (!token.reference) return document.createTextNode(token.text);
            const tag = document.createElement("span");
            tag.className = "prompt-reference-tag";
            tag.dataset.reference = token.text;
            tag.contentEditable = "false";
            tag.title = token.text;
            tag.textContent = token.text;
            return tag;
        });
        root.replaceChildren(...nodes);
        }
        // Chromium needs a trailing empty line to place the caret after a final newline.
        const sentinel = root.querySelector("[data-caret-sentinel]");
        if (value.endsWith("\n") && !sentinel) {
            const br = document.createElement("br");
            br.dataset.caretSentinel = "true";
            root.append(br);
        } else if (!value.endsWith("\n")) sentinel?.remove();
        if (active) setSelectionRange(Math.min(selected.start, value.length), Math.min(selected.end, value.length));
    }, [value, references, selection, setSelectionRange]);

    function copy(event, cut = false) {
        const selected = selection();
        event.preventDefault();
        event.clipboardData.setData("text/plain", adapter.value.slice(selected.start, selected.end));
        if (cut && !disabled) replaceSelection("", event);
    }

    return <div ref={rootRef} className="reference-input" role="textbox" aria-multiline="true"
                aria-label={placeholder} aria-disabled={disabled} data-placeholder={placeholder} data-empty={!value}
                contentEditable={!disabled} suppressContentEditableWarning
                onInput={changed}
                onSelect={event => { selection(); onSelect?.(eventFor(event)); }}
                onBlur={event => { selection(); onBlur?.(eventFor(event)); }}
                onClick={event => { selection(); onClick?.(eventFor(event)); }}
                onCompositionStart={() => { composing.current = true; }}
                onCompositionEnd={event => { composing.current = false; changed(event); }}
                onKeyDown={event => {
                    if (composing.current || event.nativeEvent.isComposing) return;
                    onKeyDown?.(eventFor(event));
                    if (event.key === "Enter" && !event.defaultPrevented) {
                        event.preventDefault(); replaceSelection("\n", event);
                    }
                }}
                onCopy={event => copy(event)} onCut={event => copy(event, true)}
                onPaste={event => {
                    event.preventDefault();
                    if (!disabled) replaceSelection(event.clipboardData.getData("text/plain").replace(/\r\n?/g, "\n"), event);
                }}/>
});

export default ReferenceInput;
