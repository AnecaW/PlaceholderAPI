// All expansions use the same validated server download and activation workflow.
document.addEventListener('DOMContentLoaded', () => {
    const list = document.getElementById('list');
    const search = document.getElementById('search');
    const filter = document.getElementById('filter');
    const refresh = document.getElementById('refresh');
    let entries = [];
    let moduleId = '';
    let states = {};
    let pollTimer;
    let polling = false;
    const keyOf = name => name.toLowerCase().replace(/ /g, '_');
    const pending = state => state === 'starting' || state === 'downloading' || state === 'installing' || state === 'downloaded; registering';
    const escapeHtml = value => String(value || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    const base = () => `/api/modules/${encodeURIComponent(moduleId)}/ecloud`;

    async function request(url, options) {
        const response = await fetch(url, options);
        if (!response.ok) throw new Error((await response.text()) || `HTTP ${response.status}`);
        return response.json();
    }
    function render() {
        const query = search.value.toLowerCase().trim();
        const items = entries.filter(entry => {
            const installed = !!entry.hasExpansion;
            return (filter.value !== 'installed' || installed) && (filter.value !== 'updates' || entry.shouldUpdate) && `${entry.name} ${entry.author || ''} ${entry.description || ''}`.toLowerCase().includes(query);
        }).sort((a, b) => a.name.localeCompare(b.name));
        const busy = Object.values(states).some(pending);
        list.innerHTML = items.length ? items.map(entry => {
            const key = keyOf(entry.name);
            const state = states[key] || '';
            const installed = !!entry.hasExpansion;
            const progress = state === 'downloading' ? 'Downloading…' : pending(state) ? 'Installing…' : '';
            const error = state.startsWith('failed:') ? state.substring(7).trim() : '';
            const status = error ? `Installation failed: ${error}` : state === 'done' && installed ? 'Installed successfully' : installed ? 'Installed' : progress;
            const action = installed && !entry.shouldUpdate && !pending(state)
                ? '<span class="installed-status">Installed</span>'
                : `<button class="btn install-expansion" data-name="${escapeHtml(entry.name)}" ${busy ? 'disabled' : ''}>${progress || (installed ? 'Update' : 'Download &amp; Install')}</button>`;
            return `<div class="item"><div class="meta"><div class="title">${escapeHtml(entry.name)} <span class="author">by ${escapeHtml(entry.author)}</span></div><div class="desc">${escapeHtml(entry.description)}</div></div><div class="actions">${action}<div class="installation-status ${error ? 'installation-error' : ''}" role="status">${escapeHtml(status)}</div></div></div>`;
        }).join('') : '<div class="empty">Geen resultaten</div>';
    }
    async function load() {
        try {
            if (!moduleId) {
                const modules = await request('/api/modules');
                const module = modules.find(m => (m.name || '').toLowerCase() === 'placeholderapi' || (m.internalId || '').toLowerCase() === 'placeholderapi');
                if (!module || module.state.toUpperCase() !== 'ENABLED') throw new Error('Enable the PlaceholderAPI module first.');
                moduleId = module.internalId;
            }
            const [catalogue, statuses, metadata] = await Promise.all([request(`${base()}/list`), request(`${base()}/status`), request(`${base()}/meta`)]);
            if (!metadata.enabled) throw new Error('Automatic installation is disabled. Enable cloud_enabled in the PlaceholderAPI configuration.');
            entries = Array.isArray(catalogue) ? catalogue : Object.values(catalogue);
            // Do not drop locally pending requests while their POST response is still in flight.
            states = { ...states, ...statuses };
            render(); ensurePolling();
        } catch (error) { list.innerHTML = `<div class="error" role="alert">${escapeHtml(error.message)}</div>`; }
    }
    function ensurePolling() {
        if (!pollTimer && Object.values(states).some(pending)) pollTimer = setTimeout(poll, 1000);
    }
    async function poll() {
        pollTimer = undefined;
        if (polling) return;
        polling = true;
        try {
            const statuses = await request(`${base()}/status`);
            const completed = Object.entries(statuses).some(([key, state]) => pending(states[key]) && !pending(state));
            if (completed) {
                const catalogue = await request(`${base()}/list`);
                entries = Array.isArray(catalogue) ? catalogue : Object.values(catalogue);
            }
            states = { ...states, ...statuses };
            render();
        } catch (error) {
            // A lost status response does not establish failure or permit another installation.
            list.querySelectorAll('.installation-status').forEach(status => { status.textContent = `Waiting for installation status: ${error.message}`; });
        } finally { polling = false; ensurePolling(); }
    }
    list.addEventListener('click', async event => {
        const button = event.target.closest('.install-expansion');
        if (!button || button.disabled) return;
        const name = button.dataset.name;
        const key = keyOf(name);
        if (Object.values(states).some(pending)) return;
        states[key] = 'starting'; render();
        try {
            await request(`${base()}/install`, { method: 'POST', body: new URLSearchParams({ name }) });
            states[key] = 'downloading'; render(); ensurePolling();
        } catch (error) { states[key] = `failed: ${error.message}`; render(); }
    });
    refresh.addEventListener('click', async () => {
        refresh.disabled = true;
        try {
            await request(`${base()}/refresh`, { method: 'POST' });
            let metadata;
            for (let attempt = 0; attempt < 40; attempt++) {
                await new Promise(resolve => setTimeout(resolve, 1000));
                metadata = await request(`${base()}/meta`);
                if (!metadata.refreshing) break;
            }
            if (metadata.refreshing) throw new Error('Catalogue refresh is still pending. Try refreshing again shortly.');
            if (metadata.error) throw new Error(metadata.error);
            await load();
        } catch (error) { alert(`Catalogue refresh failed: ${error.message}`); }
        finally { refresh.disabled = false; }
    });
    search.addEventListener('input', render);
    filter.addEventListener('change', render);
    window.addEventListener('pagehide', () => clearTimeout(pollTimer));
    load();
});
