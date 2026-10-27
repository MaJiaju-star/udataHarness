import {memo, useEffect, useState} from "react";
import {useDisclosure} from "./HistoryView.jsx";

/** Only this small label ticks; SSE updates and virtual row remounts keep its origin. */
export default memo(function ThinkingElapsed({id, startedAt}) {
    const [fallbackStart] = useDisclosure(`thinking-clock:${id}`, Date.now());
    const [now, setNow] = useState(Date.now);
    useEffect(() => {
        const timer = window.setInterval(() => setNow(Date.now()), 1000);
        return () => window.clearInterval(timer);
    }, []);
    const seconds = Math.max(0, Math.floor((now - (startedAt ?? fallbackStart)) / 1000));
    return <span className="thinking-elapsed" aria-live="off" title="本次思考耗时，停顿期间继续计时">{seconds} 秒</span>;
});
