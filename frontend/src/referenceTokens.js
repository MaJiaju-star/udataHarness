export function splitReferenceTokens(value, references) {
    const known = [...new Set(references.map(text => text.trim()).filter(text => text.startsWith("@")))]
        .sort((a, b) => b.length - a.length);
    const tokens = [];
    let start = 0, index = 0;
    while (index < value.length) {
        const reference = value[index] === "@" && known.find(text => value.startsWith(text, index)
            && (!value[index + text.length] || /[\s，。；,;()]/.test(value[index + text.length])));
        if (!reference) { index++; continue; }
        if (index > start) tokens.push({text: value.slice(start, index)});
        tokens.push({text: reference, reference: true});
        index += reference.length;
        start = index;
    }
    if (start < value.length) tokens.push({text: value.slice(start)});
    return tokens;
}

export function readReferenceInput(node) {
    if (node.nodeType === 3) return node.nodeValue || "";
    if (node.dataset?.caretSentinel) return "";
    if (node.dataset?.reference) return node.dataset.reference;
    if (node.nodeName === "BR") return "\n";
    return [...node.childNodes].map(readReferenceInput).join("");
}

export function referenceSelectionOffset(root, target, offset) {
    let total = 0;
    function walk(node) {
        if (node === target) {
            total += node.nodeType === 3 ? offset
                : [...node.childNodes].slice(0, offset).reduce((sum, child) => sum + readReferenceInput(child).length, 0);
            return true;
        }
        if (node.dataset?.reference && node.contains(target)) {
            total += offset > 0 ? readReferenceInput(node).length : 0;
            return true;
        }
        if (node.contains?.(target)) {
            for (const child of node.childNodes) if (walk(child)) return true;
        } else total += readReferenceInput(node).length;
        return false;
    }
    walk(root);
    return total;
}

export function referenceSelectionPoint(root, position) {
    let remaining = Math.max(0, position);
    for (let index = 0; index < root.childNodes.length; index++) {
        const node = root.childNodes[index], length = readReferenceInput(node).length;
        if (remaining <= length) {
            if (node.nodeType === 3) return {node, offset: remaining};
            return {node: root, offset: index + (remaining > 0 ? 1 : 0)};
        }
        remaining -= length;
    }
    return {node: root, offset: root.childNodes.length};
}
