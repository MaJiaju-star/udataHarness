import {create} from "zustand";
import {persist} from "zustand/middleware";

const fileName = path => path?.split(/[\\/]/).pop() || path;

export const useWorkspaceStore = create(persist((set, get) => ({
    leftTab: "files",
    leftWidth: 260,
    editorWidth: 420,
    leftCollapsed: false,
    mobilePane: "chat",
    openedPaths: [],
    activePath: null,
    buffers: {},
    selections: {},
    referenceEnabled: true,

    setLeftTab: leftTab => set({leftTab, leftCollapsed: false}),
    setLeftWidth: leftWidth => set({leftWidth}),
    setEditorWidth: editorWidth => set({editorWidth}),
    toggleLeft: () => set(state => ({leftCollapsed: !state.leftCollapsed})),
    setMobilePane: mobilePane => set({mobilePane}),
    toggleReference: () => set(state => ({referenceEnabled: !state.referenceEnabled})),
    setEditorSelection: (path, selection) => set(state => ({
        selections: {...state.selections, [path]: selection}
    })),

    openFile: file => set(state => ({
        openedPaths: state.openedPaths.includes(file.path)
            ? state.openedPaths
            : [...state.openedPaths, file.path],
        activePath: file.path,
        buffers: {
            ...state.buffers,
            [file.path]: {
                path: file.path,
                name: file.name || fileName(file.path),
                content: file.content || "",
                savedContent: file.content || "",
                modifiedAt: file.modifiedAt,
                dirty: false
            }
        },
        mobilePane: "editor"
    })),

    activateFile: activePath => set({activePath, mobilePane: "editor"}),

    updateBuffer: (path, content) => set(state => ({
        buffers: {
            ...state.buffers,
            [path]: {
                ...state.buffers[path],
                content,
                dirty: content !== state.buffers[path]?.savedContent
            }
        }
    })),

    markSaved: (path, file) => set(state => ({
        buffers: {
            ...state.buffers,
            [path]: {
                ...state.buffers[path],
                content: file.content,
                savedContent: file.content,
                modifiedAt: file.modifiedAt,
                dirty: false
            }
        }
    })),

    closeFile: path => set(state => {
        const openedPaths = state.openedPaths.filter(item => item !== path);
        const buffers = {...state.buffers};
        const selections = {...state.selections};
        delete buffers[path];
        delete selections[path];
        let activePath = state.activePath;
        if (activePath === path) {
            const index = state.openedPaths.indexOf(path);
            activePath = openedPaths[Math.min(index, openedPaths.length - 1)] || null;
        }
        return {openedPaths, buffers, selections, activePath};
    }),

    resetEditor: () => set({openedPaths: [], activePath: null, buffers: {}, selections: {}}),
    hasDirtyFiles: () => Object.values(get().buffers).some(buffer => buffer.dirty)
}), {
    name: "udata-workbench",
    partialize: state => ({
        leftTab: state.leftTab,
        leftWidth: state.leftWidth,
        editorWidth: state.editorWidth,
        leftCollapsed: state.leftCollapsed,
        referenceEnabled: state.referenceEnabled
    })
}));
