const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/web/ecloud.js'), 'utf8');
const flush = async () => { for (let i = 0; i < 4; i++) await new Promise(setImmediate); };
async function harness(initial = {}, initialStatuses = {}) {
    const nodes = Object.fromEntries(['list', 'search', 'filter', 'refresh'].map(id => [id, { innerHTML: '', value: id === 'filter' ? 'all' : '', listeners: {}, addEventListener(event, fn) { this.listeners[event] = fn; }, querySelectorAll() { return []; } }]));
    let entry = { name: 'Player', author: 'clip', description: 'Player expansion', hasExpansion: false, shouldUpdate: false, ...initial };
    let statuses = initialStatuses, failStatus = false, postError = null;
    const calls = [], timers = [];
    let ready;
    const sandbox = {
        document: { getElementById: id => nodes[id], addEventListener: (event, fn) => { ready = fn; } },
        window: { addEventListener() {} }, URLSearchParams,
        setTimeout: fn => { timers.push(fn); return timers.length; }, clearTimeout() {}, alert() {},
        fetch: async (url, options = {}) => {
            calls.push({ url, options });
            if (url.endsWith('/install')) return postError ? { ok: false, status: 400, text: async () => postError } : { ok: true, json: async () => ({ key: 'player', status: 'started' }) };
            if (url.endsWith('/status') && failStatus) throw new Error('connection unavailable');
            let body = url === '/api/modules' ? [{ internalId: 'placeholderapi', name: 'PlaceholderAPI', state: 'ENABLED' }] : url.endsWith('/list') ? { player: entry } : url.endsWith('/status') ? statuses : { enabled: true, refreshing: false, error: '' };
            return { ok: true, json: async () => body };
        }
    };
    vm.runInNewContext(source, sandbox); ready(); await flush();
    return {
        nodes, calls,
        setStatus(value) { statuses = { player: value }; },
        installed(update = false) { entry.hasExpansion = true; entry.shouldUpdate = update; },
        failPoll() { failStatus = true; },
        failPost(reason) { postError = reason; },
        async poll() { assert.ok(timers.length); await timers.shift()(); await flush(); },
        async click() { await nodes.list.listeners.click({ target: { closest: () => ({ disabled: false, dataset: { name: 'Player' } }) } }); await flush(); }
    };
}
test('uninstalled expansion has one server installation action and no direct link', async () => {
    const h = await harness();
    assert.equal((h.nodes.list.innerHTML.match(/<button/g) || []).length, 1);
    assert.match(h.nodes.list.innerHTML, /Download &amp; Install/);
    assert.doesNotMatch(h.nodes.list.innerHTML, /Direct download|Server install|href=/i);
});
test('duplicate clicks and rerenders remain disabled until installation completes', async () => {
    const h = await harness(); await h.click(); await h.click();
    assert.equal(h.calls.filter(c => c.url.endsWith('/install')).length, 1);
    assert.match(h.nodes.list.innerHTML, /disabled/);
    h.nodes.search.value = 'Player'; h.nodes.search.listeners.input(); assert.match(h.nodes.list.innerHTML, /disabled/);
    h.setStatus('downloading'); await h.poll(); assert.match(h.nodes.list.innerHTML, /Downloading…/);
    h.setStatus('installing'); await h.poll(); assert.match(h.nodes.list.innerHTML, /Installing…/);
    h.installed(); h.setStatus('done'); await h.poll();
    assert.match(h.nodes.list.innerHTML, /Installed successfully/);
    assert.doesNotMatch(h.nodes.list.innerHTML, /<button/);
});
test('installed expansions show status and only available updates have an action', async () => {
    const h = await harness({ hasExpansion: true }); assert.doesNotMatch(h.nodes.list.innerHTML, /<button/);
    const update = await harness({ hasExpansion: true, shouldUpdate: true });
    assert.match(update.nodes.list.innerHTML, />Update<\/button>/);
    assert.doesNotMatch(update.nodes.list.innerHTML, /Download &amp; Install/);
});
test('installation failures show precise escaped reasons and allow a retry', async () => {
    const h = await harness(); await h.click(); h.setStatus('failed: missing <RequiredPlugin>'); await h.poll();
    assert.match(h.nodes.list.innerHTML, /Installation failed: missing &lt;RequiredPlugin&gt;/);
    assert.doesNotMatch(h.nodes.list.innerHTML, /disabled/);
    const rejected = await harness(); rejected.failPost('Source compilation unsupported'); await rejected.click();
    assert.match(rejected.nodes.list.innerHTML, /Installation failed: Source compilation unsupported/);
});
test('status connection failures preserve disabled state and continue polling', async () => {
    const h = await harness(); await h.click(); h.failPoll(); await h.poll();
    assert.match(h.nodes.list.innerHTML, /disabled/);
    await h.poll(); assert.equal(h.calls.filter(c => c.url.endsWith('/install')).length, 1);
});
test('reopening the page restores server installation progress', async () => {
    const h = await harness({}, { player: 'installing' });
    assert.match(h.nodes.list.innerHTML, /disabled/);
    assert.equal(h.calls.filter(c => c.url.endsWith('/install')).length, 0);
    h.nodes.filter.value = 'all'; h.nodes.filter.listeners.change(); assert.match(h.nodes.list.innerHTML, /Installing…/);
});
