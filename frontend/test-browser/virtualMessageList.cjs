// Run with Node and Playwright installed, or set PLAYWRIGHT_MODULE / PLAYWRIGHT_CHROMIUM_EXECUTABLE.
// Uses the real ChatView and CSS; all model/API traffic is replaced by local fixtures.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const esbuild = require('esbuild');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const root = path.resolve(__dirname, '..');
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'udata-scroll-regression-'));
const output = path.join(temporary, 'fixture');
const fixture = `
import React, {useEffect, useState} from 'react';
import {createRoot} from 'react-dom/client';
import {flushSync} from 'react-dom';
import {ChatView, reduceAssistantEvent} from './src/App.jsx';
import './src/styles.css';
const noop = () => {};
const api = async url => url.includes('/automations') ? {tasks: [], runs: []} : {};
const timer = {snapshot: () => ({status: 'idle', elapsedMs: 0}), subscribe: () => noop};
const initial = Array.from({length: 100}, (_, i) => ({
    id: 'qa-' + i, role: i % 2 ? 'assistant' : 'user', finished: true,
    content: ('消息 ' + i + '：验证长正文换行、窗口回收和滚动定位。\\n\\n').repeat(i % 11 === 0 ? 250 : i % 5 === 0 ? 70 : 5),
    tools: [], activities: []
}));
function Demo() {
    const [messages, setMessages] = useState(initial), [running, setRunning] = useState(true);
    const [session, setSession] = useState('scroll-a');
    useEffect(() => {
        let thinkingMessage, embedded = false;
        window.scrollFixture = {
            mixedTail: streaming => {
                setSession('mixed-tail-' + streaming);
                setRunning(streaming);
                setMessages([...initial, {id: 'qa-penultimate', role: 'assistant', finished: true,
                    content: '倒数第二条完整回复：长正文和换行。\\n\\n'.repeat(700), tools: [], activities: []},
                    {id: 'qa-last-user', role: 'user', finished: true, content: '继续输出', tools: [], activities: []},
                    {id: 'qa-live', role: 'assistant', finished: !streaming, content: '最新回复：第一段。', tools: [], activities: []}]);
            },
            longTail: streaming => {
                setRunning(streaming);
                setMessages([...initial, {id: 'qa-live', role: 'assistant', finished: !streaming,
                    content: '最新回复：长正文滚动回归。\\n\\n'.repeat(350), tools: [], activities: []}]);
            },
            commit: streaming => flushSync(() => setMessages(old => old.map((message, i) => i === old.length - 1
                ? {...message, content: message.content + (streaming ? 'x' : '')} : message))),
            prepend: () => flushSync(() => setMessages(old => [{id: 'prepended-' + old.length, role: 'user', finished: true,
                content: '前部几何变化：校正必须基于最新滚动位置。', tools: [], activities: []}, ...old])),
            append: () => setMessages(old => old.map((message, i) => i === old.length - 1
                ? {...message, finished: false, content: message.content + '\\n\\n新增流式正文。'.repeat(15)} : message)),
            finish: () => { setRunning(false); setMessages(old => old.map(m => ({...m, finished: true}))); },
            switchSession: () => { setSession('scroll-b'); setMessages(initial); },
            think: (useEmbedded = false) => {
                embedded = useEmbedded;
                thinkingMessage = reduceAssistantEvent({id: 'thinking-qa', role: 'assistant', content: '', finished: false},
                    {type: embedded ? 'text' : 'thinking', reasonId: 'thinking-test', content: (embedded ? '<think>' : '') + '正在分析项目结构。'});
                setRunning(true); setMessages([thinkingMessage]);
            },
            appendThinking: () => {
                thinkingMessage = reduceAssistantEvent(thinkingMessage, {type: embedded ? 'text' : 'thinking',
                    reasonId: 'thinking-test', content: '继续核实最新实现。'});
                setMessages([thinkingMessage]);
            },
            hideThinking: () => setMessages(initial),
            restoreThinking: () => setMessages([thinkingMessage]),
            finishThinking: () => {
                thinkingMessage = reduceAssistantEvent(thinkingMessage, {type: 'done'});
                setRunning(false); setMessages([thinkingMessage]);
            }
        };
    }, []);
    return <div style={{height: '100vh'}}><ChatView api={api} current={{sessionId: session, permissionMode: 'standard'}}
        messages={messages} running={running} models={[]} selectedModel="" tokenUsage={{}} conversationTimer={timer}
        onSend={noop} onStop={noop} onDecide={noop} onOpenFile={noop}/></div>;
}
createRoot(document.getElementById('root')).render(<React.StrictMode><Demo/></React.StrictMode>);
`;

async function state(page) {
    return page.locator('.message-scroll').evaluate(element => {
        const box = element.getBoundingClientRect();
        const visible = [...element.querySelectorAll('.virtual-message-row')].map(row => {
            const bounds = row.getBoundingClientRect();
            return {id: row.dataset.messageId, top: bounds.top - box.top, bottom: bounds.bottom - box.top};
        }).filter(row => row.bottom > 2 && row.top < box.height - 2);
        return {top: element.scrollTop, max: element.scrollHeight - element.clientHeight,
            paused: element.dataset.streamPaused, height: box.height, visible};
    });
}
async function visible(page) {
    const snapshot = await state(page);
    assert.ok(snapshot.visible.length > 0, `Viewport contains only spacers: ${JSON.stringify(snapshot)}`);
    assert.ok(snapshot.visible[0].top < 120, `Blank space above messages: ${JSON.stringify(snapshot)}`);
    assert.ok(snapshot.visible.at(-1).bottom > snapshot.height - 120, `Blank space below messages: ${JSON.stringify(snapshot)}`);
    return snapshot;
}
async function home(page) {
    await page.locator('.message-scroll').evaluate(element => {
        // A native scrollbar jump avoids keyboard smooth-scroll timing while rows are measured.
        element.dispatchEvent(new WheelEvent('wheel', {deltaY: -1000, bubbles: true}));
        element.scrollTop = 0;
        element.dispatchEvent(new Event('scroll'));
    });
    await page.waitForTimeout(180);
    const snapshot = await visible(page);
    assert.equal(snapshot.visible[0].id, 'qa-0');
    assert.equal(snapshot.paused, 'true');
}
async function descend(page) {
    await page.locator('.message-scroll').hover();
    let previous = await visible(page);
    for (let i = 0; i < 24; i++) {
        await page.mouse.wheel(0, 450);
        await page.waitForTimeout(50);
        const next = await visible(page);
        assert.ok(next.top >= previous.top - 2, `Downward scroll jumped backward: ${previous.top} -> ${next.top}`);
        previous = next;
    }
}
async function main() {
    let browser, server;
    try {
        await esbuild.build({stdin: {contents: fixture, resolveDir: root, loader: 'jsx'}, bundle: true,
            jsx: 'automatic', outfile: output + '.js', minify: true, loader: {'.ttf': 'dataurl'},
            define: {'process.env.NODE_ENV': '"production"'}, plugins: [{name: 'chat-fixture', setup(build) {
                build.onLoad({filter: /[\\/]App\.jsx$/}, args => ({contents: fs.readFileSync(args.path, 'utf8') + '\nexport {ChatView, reduceAssistantEvent};', loader: 'jsx', resolveDir: path.dirname(args.path)}));
                build.onResolve({filter: /\?worker$/}, args => ({path: args.path, namespace: 'unused-worker'}));
                build.onLoad({filter: /.*/, namespace: 'unused-worker'}, () => ({contents: 'export default class UnusedWorker {}', loader: 'js'}));
            }}]});
        server = http.createServer((request, response) => {
            const ext = request.url.endsWith('.js') ? '.js' : request.url.endsWith('.css') ? '.css' : null;
            response.setHeader('Content-Type', ext === '.js' ? 'application/javascript' : ext === '.css' ? 'text/css' : 'text/html; charset=utf-8');
            response.end(ext ? fs.readFileSync(output + ext) : '<!doctype html><meta charset="utf-8"><link rel="stylesheet" href="/fixture.css"><div id="root"></div><script src="/fixture.js"></script>');
        });
        await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
        browser = await chromium.launch({headless: true, ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ? {executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE} : {})});
        for (const host of ['', '?host=idea']) {
            const page = await browser.newPage({viewport: {width: 420, height: 820}}), errors = [];
            page.on('pageerror', error => errors.push(error.message));
            await page.goto(`http://127.0.0.1:${server.address().port}/${host}`);
            await page.waitForTimeout(800);
            for (const streaming of [false, true]) {
                await page.evaluate(streaming => window.scrollFixture.mixedTail(streaming), streaming);
                await page.waitForTimeout(700);
                const tail = page.locator('[data-message-id="qa-live"]');
                await tail.evaluate(element => { element.dataset.scrollInstance = 'stable-tail'; });
                await home(page);
                assert.equal(await tail.count(), 1, 'Latest reply was replaced by a spacer while reading history');
                if (streaming) {
                    const reading = await state(page);
                    for (let i = 0; i < 6; i++) { await page.evaluate(() => window.scrollFixture.append()); await page.waitForTimeout(35); }
                    const held = await visible(page);
                    assert.equal(held.visible[0].id, reading.visible[0].id);
                    assert.ok(Math.abs(held.top - reading.top) < 3, 'Offscreen SSE pulled the reader down');
                }
                await page.locator('.message-scroll').hover();
                for (let i = 0; i < 8; i++) {
                    await page.mouse.wheel(0, 100000);
                    await page.waitForTimeout(100);
                    const bottom = await state(page);
                    if (bottom.max - bottom.top <= 3) break;
                }
                await page.waitForTimeout(500);
                assert.equal(await tail.getAttribute('data-scroll-instance'), 'stable-tail', 'Tail row remounted and can flash');
                if (streaming) {
                    for (let i = 0; i < 8; i++) { await page.evaluate(() => window.scrollFixture.append()); await page.waitForTimeout(65); }
                    await page.evaluate(() => window.scrollFixture.finish());
                    await page.waitForTimeout(700);
                }
                const samples = await page.locator('.message-scroll').evaluate(async element => {
                    const result = [];
                    for (let i = 0; i < 35; i++) {
                        await new Promise(requestAnimationFrame);
                        const tail = element.querySelector('[data-message-id="qa-live"]');
                        result.push({gap: element.scrollHeight - element.clientHeight - element.scrollTop,
                            instance: tail?.dataset.scrollInstance, visible: tail?.getBoundingClientRect().bottom > element.getBoundingClientRect().top});
                    }
                    return result;
                });
                assert.ok(samples.every(sample => sample.gap <= 3 && sample.instance === 'stable-tail' && sample.visible),
                    `Bottom oscillated back to the penultimate reply (${host || 'web'}, streaming=${streaming}): ${JSON.stringify(samples)}`);
            }
            for (const streaming of [false, true]) {
                await page.evaluate(streaming => window.scrollFixture.longTail(streaming), streaming);
                await page.waitForTimeout(600);
                const scroller = page.locator('.message-scroll');
                // Move the DOM before its asynchronous scroll event, then immediately commit SSE/state.
                // This reproduces scrollbar / fast wheel input racing a virtual-list layout commit.
                for (let attempt = 0; attempt < 5; attempt++) {
                    await scroller.evaluate(element => {
                        element.dispatchEvent(new WheelEvent('wheel', {deltaY: -400, bubbles: true}));
                        element.scrollTop = Math.max(0, element.scrollHeight - element.clientHeight - 1500);
                        element.dispatchEvent(new Event('scroll'));
                    });
                    await page.waitForTimeout(60);
                    await scroller.evaluate(element => {
                        element.dispatchEvent(new WheelEvent('wheel', {deltaY: 1500, bubbles: true}));
                        element.scrollTop = element.scrollHeight - element.clientHeight;
                        window.scrollFixture.commit(false);
                    });
                    await page.waitForTimeout(450);
                    const tail = await visible(page);
                    assert.ok(tail.max - tail.top <= 3,
                        `Returning to bottom jumped to an older completed reply (${host || 'web'}, streaming=${streaming}): ${JSON.stringify(tail)}`);
                    assert.equal(tail.visible.at(-1).id, 'qa-live');
                }
                await scroller.evaluate(element => {
                    element.dispatchEvent(new WheelEvent('wheel', {deltaY: -400, bubbles: true}));
                    element.scrollTop = Math.max(0, element.scrollHeight - element.clientHeight - 1500);
                    element.dispatchEvent(new Event('scroll'));
                });
                await page.waitForTimeout(60);
                const target = await scroller.evaluate(element => {
                    element.dispatchEvent(new WheelEvent('wheel', {deltaY: 1500, bubbles: true}));
                    element.scrollTop = element.scrollHeight - element.clientHeight;
                    const target = element.scrollTop;
                    window.scrollFixture.prepend();
                    return target;
                });
                await page.waitForTimeout(500);
                const corrected = await visible(page);
                assert.ok(corrected.top >= target - 2,
                    `Geometry correction replayed the old scroll position: ${target} -> ${JSON.stringify(corrected)}`);
                if (streaming) {
                    await page.evaluate(() => window.scrollFixture.append());
                    await page.waitForTimeout(900);
                    const growing = await visible(page);
                    assert.ok(growing.max - growing.top <= 3, `Resumed following lost the growing tail: ${JSON.stringify(growing)}`);
                }
                if (process.env.BROWSER_ARTIFACT_DIR) {
                    fs.mkdirSync(process.env.BROWSER_ARTIFACT_DIR, {recursive: true});
                    await page.screenshot({path: path.join(process.env.BROWSER_ARTIFACT_DIR,
                        `scroll-bottom-${host ? 'idea' : 'web'}-${streaming ? 'streaming' : 'finished'}.png`)});
                }
            }
            await page.evaluate(() => window.scrollFixture.switchSession());
            await page.waitForTimeout(400);
            await home(page); // Parent-owned ref must be available when subscriptions are installed.
            await descend(page);
            const before = await visible(page), anchor = before.visible[0];
            for (let i = 0; i < 8; i++) {
                await page.evaluate(() => window.scrollFixture.append());
                await page.waitForTimeout(40);
            }
            const held = await visible(page);
            assert.equal(held.paused, 'true');
            assert.equal(held.visible[0].id, anchor.id);
            assert.ok(Math.abs(held.visible[0].top - anchor.top) <= 3, 'Output moved the reading position');
            for (const width of [900, 360, 640, 420]) {
                await page.setViewportSize({width, height: 820});
                await page.waitForTimeout(150);
                await visible(page);
            }
            await page.evaluate(() => window.scrollFixture.finish());
            await page.waitForTimeout(150);
            await home(page);
            await descend(page); // The same listener must work without incoming SSE rerenders.
            // Native scroll position changes (e.g. scrollbar dragging) must update the
            // rendered window without needing a parent state change or another wheel.
            await page.locator('.message-scroll').evaluate(element => {
                element.scrollTop = (element.scrollHeight - element.clientHeight) * 0.7;
            });
            await page.waitForTimeout(180);
            await visible(page);
            await page.getByRole('button', {name: '回到底部', exact: true}).click();
            await page.waitForTimeout(1000);
            const bottom = await visible(page);
            assert.ok(bottom.max - bottom.top <= 2);
            await page.evaluate(() => window.scrollFixture.switchSession());
            await page.waitForTimeout(500);
            await home(page); // A session change remounts the virtual list.
            for (const embedded of [false, true]) {
                await page.setViewportSize({width: embedded ? 900 : 360, height: 820});
                await page.evaluate(embedded => window.scrollFixture.think(embedded), embedded);
                const card = page.locator('.thinking-card'), elapsed = card.locator('.thinking-elapsed');
                await elapsed.waitFor();
                const seconds = async () => parseInt(await elapsed.innerText(), 10);
                await page.waitForTimeout(1150);
                assert.ok(await seconds() >= 1);
                assert.equal(await card.locator('.stream-wait, .stream-wait-track').count(), 0);
                assert.ok((await card.locator('.thinking-preview').innerText()).includes('正在分析项目结构'));
                await page.evaluate(() => window.scrollFixture.appendThinking());
                await page.waitForTimeout(120);
                assert.ok(await seconds() >= 1, 'SSE updates reset the thinking clock');
                await card.locator(':scope > button').click();
                await page.waitForTimeout(1000);
                assert.ok(await seconds() >= 2);
                assert.equal(await card.locator('.stream-wait, .stream-wait-track').count(), 0);
                const bounds = await elapsed.evaluate(element => {
                    const timer = element.getBoundingClientRect(), button = element.closest('button').getBoundingClientRect();
                    return {right: timer.right <= button.right, left: timer.left >= button.left, height: button.height};
                });
                assert.ok(bounds.right && bounds.left && bounds.height < 70, JSON.stringify(bounds));
                if (process.env.BROWSER_ARTIFACT_DIR) {
                    fs.mkdirSync(process.env.BROWSER_ARTIFACT_DIR, {recursive: true});
                    await page.screenshot({path: path.join(process.env.BROWSER_ARTIFACT_DIR,
                        `thinking-clock-${host ? 'idea' : 'web'}-${embedded ? 'embedded-900' : 'native-360'}.png`)});
                }
                await page.evaluate(() => window.scrollFixture.hideThinking());
                await page.waitForTimeout(80);
                await page.evaluate(() => window.scrollFixture.restoreThinking());
                await elapsed.waitFor();
                assert.ok(await seconds() >= 2, 'Virtual remount reset the thinking clock');
                await page.evaluate(() => window.scrollFixture.finishThinking());
                await page.waitForTimeout(350);
                assert.equal(await elapsed.count(), 0);
                assert.ok((await card.locator(':scope > button').innerText()).includes('已完成思考'));
            }
            assert.deepEqual(errors, []);
            console.log(`${host ? 'IDEA H5 mode' : 'Browser'}: long-tail bottom returns, pending-scroll geometry races, manual reading, resize, remount and thinking checks passed`);
            await page.close();
        }
    } finally {
        if (browser) await browser.close();
        if (server) await new Promise(resolve => server.close(resolve));
        assert.equal(path.dirname(fs.realpathSync(temporary)), fs.realpathSync(os.tmpdir()));
        assert.ok(path.basename(temporary).startsWith('udata-scroll-regression-'));
        fs.rmSync(temporary, {recursive: true, force: true});
    }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
