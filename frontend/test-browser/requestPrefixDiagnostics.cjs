// Local browser regression for the real context dashboard; no model/network credentials.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const esbuild = require('esbuild');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = path.resolve(__dirname, '..');
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'udata-prefix-ui-'));
const output = path.join(temporary, 'fixture');
const base = {agentName: 'main', format: 'chat_completions', status: 'success', baselineAvailable: true,
    previousMessageCount: 6, messageCount: 8, messagePrefixCount: 6, runId: 'run-test', reasonId: 'reason-test',
    previousInputTokens: 6000, previousCacheReadTokens: 5000, previousCacheHitRate: .833,
    usage: {inputTokens: 10000, cacheReadTokens: 6000, cacheHitRate: .6}, intervalMs: 3000, requestHash: 'abcdef0123456789'};
const data = {limit: 200, observations: [], actions: [], tasks: [], compactions: [], requestDiagnostics: [
    {...base, id: 'fixed', timestamp: 4, assessment: 'fixed_prefix_changed', changedSections: ['system', 'tools'],
        firstSystemDifference: {previousSection: 'talent:TerminalTalent', section: 'talent:TerminalTalent', previousChars: 600, chars: 620},
        changedTools: [{name: 'read', fields: ['parameters']}], addedTools: ['mcp_' + 'a'.repeat(110)]},
    {...base, id: 'lower', timestamp: 3, assessment: 'stable_prefix_lower_cache_read', appendOnly: true,
        changedSections: ['messages'], usage: {inputTokens: 7000, cacheReadTokens: 1024, cacheHitRate: .146}},
    {...base, id: 'unknown', timestamp: 2, assessment: 'cache_usage_unknown', changedSections: [], usage: {}},
    {...base, id: 'growth', timestamp: 1, assessment: 'appended_input_growth', changedSections: ['messages'], appendOnly: true}
]};
const fixture = `import React from 'react'; import {createRoot} from 'react-dom/client';
import ContextPanel from './src/ContextPanel.jsx'; import './src/styles.css';
const data = ${JSON.stringify(data)}; const api = async () => data;
createRoot(document.getElementById('root')).render(<ContextPanel api={api} sessionId="test" running={false} onClose={() => {}}/>);`;

async function main() {
    let server, browser;
    try {
        await esbuild.build({stdin: {contents: fixture, resolveDir: root, loader: 'jsx'}, bundle: true,
            outfile: output + '.js', jsx: 'automatic', minify: true, define: {'process.env.NODE_ENV': '"production"'}});
        server = http.createServer((request, response) => {
            const extension = request.url.endsWith('.js') ? '.js' : request.url.endsWith('.css') ? '.css' : null;
            response.setHeader('Content-Type', extension === '.js' ? 'application/javascript' : extension === '.css' ? 'text/css' : 'text/html; charset=utf-8');
            response.end(extension ? fs.readFileSync(output + extension) : '<!doctype html><meta charset="utf-8"><link rel="stylesheet" href="/fixture.css"><div id="root"></div><script src="/fixture.js"></script>');
        });
        await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
        browser = await chromium.launch({headless: true, ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ? {executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE} : {})});
        for (const width of [1200, 360]) {
            const page = await browser.newPage({viewport: {width, height: 820}}), errors = [];
            page.on('pageerror', error => errors.push(error.message));
            await page.goto(`http://127.0.0.1:${server.address().port}/`);
            await page.getByRole('button', {name: '请求前缀', exact: true}).click();
            const cards = page.locator('.context-action'); await cards.first().waitFor();
            assert.equal(await cards.count(), 4);
            await cards.filter({hasText: '固定前缀发生变化'}).locator('summary').click();
            await page.getByText('工具 read：parameters 变化。', {exact: true}).waitFor();
            await page.getByText('前缀保持稳定，但缓存读取下降', {exact: true}).waitFor();
            const unknown = cards.filter({hasText: '上游缓存数据未知'});
            assert.ok((await unknown.innerText()).includes('→ 未知'));
            assert.ok(!(await unknown.innerText()).includes('→ 0.0%'));
            assert.ok((await cards.filter({hasText: '新增输入使命中率下降'}).innerText()).includes('历史仅追加'));
            const bounds = await page.locator('.context-panel').evaluate(element => ({width: element.clientWidth, scroll: element.scrollWidth}));
            assert.ok(bounds.scroll <= bounds.width + 1, `Narrow panel overflow: ${JSON.stringify(bounds)}`);
            if (process.env.BROWSER_ARTIFACT_DIR) {
                fs.mkdirSync(process.env.BROWSER_ARTIFACT_DIR, {recursive: true});
                await page.screenshot({path: path.join(process.env.BROWSER_ARTIFACT_DIR, `request-prefix-${width}.png`)});
            }
            assert.deepEqual(errors, []); console.log(`Request prefix diagnostics ${width}px: changes, details, unknown cache values and layout passed`);
            await page.close();
        }
    } finally {
        if (browser) await browser.close();
        if (server) await new Promise(resolve => server.close(resolve));
        assert.equal(path.dirname(fs.realpathSync(temporary)), fs.realpathSync(os.tmpdir()));
        assert.ok(path.basename(temporary).startsWith('udata-prefix-ui-'));
        fs.rmSync(temporary, {recursive: true, force: true});
    }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
