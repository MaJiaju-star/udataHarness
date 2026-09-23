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
    revealLocations: {},
    externalChanges: [],
    treeRefreshVersion: 0,
    referenceEnabled: true,
    themeMode: "system",
    colorTheme: "emerald",
    resolvedTheme: "light",

    setLeftTab: leftTab => set({leftTab, leftCollapsed: false}),
    setLeftWidth: leftWidth => set({leftWidth}),
    setEditorWidth: editorWidth => set({editorWidth}),
    toggleLeft: () => set(state => ({leftCollapsed: !state.leftCollapsed})),
    setMobilePane: mobilePane => set({mobilePane}),
    toggleReference: () => set(state => ({referenceEnabled: !state.referenceEnabled})),
    enableReference: () => set({referenceEnabled: true}),
    setThemeMode: themeMode => set({themeMode}),
    setColorTheme: colorTheme => set({colorTheme}),
    setResolvedTheme: resolvedTheme => set({resolvedTheme}),
    requestTreeRefresh: () => set(state => ({treeRefreshVersion: state.treeRefreshVersion + 1})),
    setEditorSelection: (path, selection) => set(state => ({
        selections: {...state.selections, [path]: selection}
    })),
    revealFileLocation: (path, location) => set(state => ({
        revealLocations: {
            ...state.revealLocations,
            [path]: {...location, requestId: Date.now()}
        }
    })),

    openFile: file => set(state => {
        const existing = state.buffers[file.path];
        const buffer = existing?.dirty
            ? existing
            : {
                path: file.path,
                name: file.name || fileName(file.path),
                content: file.content || "",
                savedContent: file.content || "",
                modifiedAt: file.modifiedAt,
                size: file.size,
                contentType: file.contentType,
                previewType: file.previewType || "text",
                viewMode: ["image", "video", "html", "markdown"].includes(file.previewType)
                    ? "preview" : "edit",
                revision: `${file.modifiedAt || 0}-${file.size || 0}`,
                dirty: false
            };
        return {
            openedPaths: state.openedPaths.includes(file.path)
                ? state.openedPaths
                : [...state.openedPaths, file.path],
            activePath: file.path,
            buffers: {...state.buffers, [file.path]: buffer},
            mobilePane: "editor"
        };
    }),

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
                size: file.size,
                revision: `${file.modifiedAt || 0}-${file.size || 0}`,
                dirty: false
            }
        }
    })),

    setFileViewMode: (path, viewMode) => set(state => ({
        buffers: {
            ...state.buffers,
            [path]: {...state.buffers[path], viewMode}
        }
    })),

    applyExternalFile: file => set(state => ({
        buffers: {
            ...state.buffers,
            [file.path]: {
                ...state.buffers[file.path],
                ...file,
                content: file.content ?? state.buffers[file.path]?.content ?? "",
                savedContent: file.content ?? state.buffers[file.path]?.savedContent ?? "",
                revision: `${file.modifiedAt || 0}-${file.size || 0}`,
                dirty: false
            }
        }
    })),

    queueExternalChanges: changes => set(state => ({
        externalChanges: [
            ...state.externalChanges.filter(current =>
                !changes.some(change => change.path === current.path)),
            ...changes
        ]
    })),

    resolveExternalChange: (path, content) => set(state => {
        const change = state.externalChanges.find(item => item.path === path);
        if (!change || !state.buffers[path]) return state;
        const diskContent = change.disk.content || "";
        return {
            buffers: {
                ...state.buffers,
                [path]: {
                    ...state.buffers[path],
                    ...change.disk,
                    content,
                    savedContent: diskContent,
                    revision: `${change.disk.modifiedAt || 0}-${change.disk.size || 0}`,
                    dirty: content !== diskContent
                }
            },
            externalChanges: state.externalChanges.filter(item => item.path !== path)
        };
    }),

    closeFile: path => set(state => {
        const openedPaths = state.openedPaths.filter(item => item !== path);
        const buffers = {...state.buffers};
        const selections = {...state.selections};
        const revealLocations = {...state.revealLocations};
        delete buffers[path];
        delete selections[path];
        delete revealLocations[path];
        let activePath = state.activePath;
        if (activePath === path) {
            const index = state.openedPaths.indexOf(path);
            activePath = openedPaths[Math.min(index, openedPaths.length - 1)] || null;
        }
        const externalChanges = state.externalChanges.filter(item => item.path !== path);
        return {openedPaths, buffers, selections, revealLocations, activePath, externalChanges};
    }),

    removePath: path => set(state => {
        const targetPath = path.replace(/\\/g, "/");
        const prefix = `${targetPath}/`;
        const affected = item => {
            const normalized = item.replace(/\\/g, "/");
            return normalized === targetPath || normalized.startsWith(prefix);
        };
        const openedPaths = state.openedPaths.filter(item => !affected(item));
        const buffers = {...state.buffers};
        const selections = {...state.selections};
        const revealLocations = {...state.revealLocations};
        Object.keys(buffers).filter(affected).forEach(item => delete buffers[item]);
        Object.keys(selections).filter(affected).forEach(item => delete selections[item]);
        Object.keys(revealLocations).filter(affected).forEach(item => delete revealLocations[item]);
        const activePath = state.activePath && affected(state.activePath)
            ? openedPaths.at(-1) || null
            : state.activePath;
        const externalChanges = state.externalChanges.filter(item => !affected(item.path));
        return {openedPaths, buffers, selections, revealLocations, activePath, externalChanges};
    }),

    resetEditor: () => set({openedPaths: [], activePath: null, buffers: {}, selections: {}, revealLocations: {}, externalChanges: []}),
    hasDirtyFiles: () => Object.values(get().buffers).some(buffer => buffer.dirty)
}), {
    name: "udata-workbench",
    partialize: state => ({
        leftTab: state.leftTab,
        leftWidth: state.leftWidth,
        editorWidth: state.editorWidth,
        leftCollapsed: state.leftCollapsed,
        referenceEnabled: state.referenceEnabled,
        themeMode: state.themeMode,
        colorTheme: state.colorTheme
    })
}));
