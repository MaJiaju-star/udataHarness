import {createContext, memo, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState} from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import {decorateStreamTail} from "./streamTail.js";
import {createSmoothStream} from "./smoothStream.js";
import useSmoothScroll from "./useSmoothScroll.js";

export const StreamMotionContext = createContext(false);

function StreamSpan({node, children, ...props}) {
    const born = node.properties?.["data-stream-born"];
    if (born === undefined) return <span {...props}>{children}</span>;
    return <TailGlyph key={`${node.properties["data-stream-id"]}:${born}`} born={born}
                      tip={node.properties["data-stream-tip"]} moving={node.properties["data-stream-moving"]}>{children}</TailGlyph>;
}
function TailGlyph({born, tip, moving, children}) {
    // Preserve the delay of an existing DOM animation. Updating it on every SSE chunk would advance it twice.
    const [delay] = useState(() => born - Date.now());
    return <span className="stream-glyph" data-stream-tip={tip} data-stream-moving={moving} style={{animationDelay: `${delay}ms`}}>{children}</span>;
}
const components = {span: StreamSpan};

function ThinkingPre({children, follow}) {
    const ref = useRef(null);
    const scroller = useSmoothScroll(ref, () => follow);
    const initialized = useRef(false);
    useLayoutEffect(() => {
        if (!follow) scroller.stop();
        else if (!initialized.current) scroller.snap();
        else scroller.follow();
        initialized.current = true;
    }, [children, follow, scroller]);
    return <pre ref={ref}>{children}</pre>;
}

function decorateTail(tree, clock, moving) {
    decorateStreamTail(tree, clock, Date.now(), 16);
    const mark = node => {
        if (node.properties?.["data-stream-tip"]) node.properties["data-stream-moving"] = String(moving);
        for (const child of node.children || []) mark(child);
    };
    mark(tree);
}

function FlowMarkdown({content, plain = false}) {
    const paused = useContext(StreamMotionContext);
    const [reduced, setReduced] = useState(() => window.matchMedia("(prefers-reduced-motion: reduce)").matches);
    // Re-entering the virtual viewport or enabling motion must never erase/retype existing prose.
    const [visible, setVisible] = useState(content);
    const [buffer] = useState(() => createSmoothStream({initial: content, structured: !plain, publish: setVisible, now: () => performance.now(),
        requestFrame: callback => requestAnimationFrame(callback), cancelFrame: id => cancelAnimationFrame(id)}));
    useEffect(() => {
        const media = window.matchMedia("(prefers-reduced-motion: reduce)");
        const change = () => setReduced(media.matches);
        media.addEventListener("change", change); change();
        return () => media.removeEventListener("change", change);
    }, []);
    useLayoutEffect(() => { buffer.update(content, !paused && !reduced && !document.hidden); }, [buffer, content, paused, reduced]);
    useEffect(() => {
        const hide = () => { if (document.hidden) buffer.flush(); };
        document.addEventListener("visibilitychange", hide);
        return () => { document.removeEventListener("visibilitychange", hide); buffer.dispose(); };
    }, [buffer]);
    const shown = paused || reduced ? content : visible;
    const clock = useMemo(() => new Map(), []);
    const moving = shown !== content;
    const plugins = useMemo(() => !paused && !reduced ? [() => tree => decorateTail(tree, clock, moving)] : [], [paused, reduced, clock, moving]);
    const plainNodes = useMemo(() => {
        if (!plain) return null;
        const tree = {type: "root", children: [{type: "text", value: shown}]};
        if (!paused && !reduced) decorateTail(tree, clock, moving);
        return tree.children;
    }, [plain, shown, paused, reduced, clock, moving]);
    if (plain) return <ThinkingPre follow={!paused}>{plainNodes.map((node, index) => node.type === "text" ? node.value
        : <StreamSpan key={node.properties["data-stream-id"] || index} node={node}>{node.children[0].value}</StreamSpan>)}</ThinkingPre>;
    return <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={plugins} components={components}>{shown}</ReactMarkdown>;
}

export default memo(function StreamMarkdown({content, active = false, mode = "soft"}) {
    return active && mode !== "off" ? <FlowMarkdown content={content}/>
        : <ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown>;
});

export const StreamText = memo(function StreamText({content, active = false, mode = "soft"}) {
    const paused = useContext(StreamMotionContext);
    return active && mode !== "off" ? <FlowMarkdown content={content} plain/>
        : <ThinkingPre follow={active && !paused}>{content}</ThinkingPre>;
});
