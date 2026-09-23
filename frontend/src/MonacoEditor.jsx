import {useEffect, useRef} from "react";
import Editor, {DiffEditor, loader} from "@monaco-editor/react";
import * as monaco from "monaco-editor";
import editorWorker from "../node_modules/monaco-editor/esm/vs/editor/editor.worker.js?worker";
import jsonWorker from "../node_modules/monaco-editor/esm/vs/language/json/json.worker.js?worker";
import cssWorker from "../node_modules/monaco-editor/esm/vs/language/css/css.worker.js?worker";
import htmlWorker from "../node_modules/monaco-editor/esm/vs/language/html/html.worker.js?worker";
import tsWorker from "../node_modules/monaco-editor/esm/vs/language/typescript/ts.worker.js?worker";

self.MonacoEnvironment = {
    getWorker(_, label) {
        if (label === "json") return new jsonWorker();
        if (label === "css" || label === "scss" || label === "less") return new cssWorker();
        if (label === "html" || label === "handlebars" || label === "razor") return new htmlWorker();
        if (label === "typescript" || label === "javascript") return new tsWorker();
        return new editorWorker();
    }
};
loader.config({monaco});

function applyEditorLocation(editor, location, appliedLocation) {
    if (!editor || !location || appliedLocation.current === location.requestId) return;
    editor.setSelection({
        startLineNumber: location.line,
        startColumn: location.column,
        endLineNumber: location.line,
        endColumn: location.endColumn || location.column
    });
    editor.revealLineInCenter(location.line);
    editor.focus();
    appliedLocation.current = location.requestId;
}

export default function MonacoEditor({path, value, language, location, onChange, onSelectionChange, onEditorReady}) {
    const selectionListener = useRef(onSelectionChange);
    const editorInstance = useRef(null);
    const appliedLocation = useRef(null);
    useEffect(() => {
        selectionListener.current = onSelectionChange;
    }, [onSelectionChange]);
    useEffect(() => {
        applyEditorLocation(editorInstance.current, location, appliedLocation);
    }, [location]);

    return <Editor
        path={path}
        value={value}
        language={language}
        theme="vs"
        onChange={onChange}
        onMount={editor => {
            editorInstance.current = editor;
            onEditorReady?.(editor);
            applyEditorLocation(editor, location, appliedLocation);
            editor.onDidChangeCursorSelection(event => {
                const selection = event.selection;
                let endLine = selection.endLineNumber;
                if (!selection.isEmpty() && selection.endColumn === 1
                        && endLine > selection.startLineNumber) {
                    endLine--;
                }
                selectionListener.current?.({
                    empty: selection.isEmpty(),
                    startLine: selection.startLineNumber,
                    endLine
                });
            });
        }}
        options={{
            fontSize: 13,
            minimap: {enabled: false},
            wordWrap: "on",
            automaticLayout: true,
            padding: {top: 14},
            scrollBeyondLastLine: false
        }}/>
}

export function MonacoDiffEditor({path, original, modified, language, onChange}) {
    return <DiffEditor
        original={original}
        modified={modified}
        language={language}
        originalModelPath={`browser://${path}`}
        modifiedModelPath={`disk://${path}`}
        theme="vs"
        onMount={editor => {
            const modifiedEditor = editor.getModifiedEditor();
            modifiedEditor.onDidChangeModelContent(() => onChange(modifiedEditor.getValue()));
        }}
        options={{
            fontSize: 13,
            minimap: {enabled: false},
            automaticLayout: true,
            renderSideBySide: true,
            originalEditable: false,
            scrollBeyondLastLine: false
        }}/>
}
