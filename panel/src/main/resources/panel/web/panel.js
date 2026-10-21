(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  /** Minecraft text with § codes as HTML, like the game shows it: the 16 colours, bold, italic, underlined, struck through; §r resets, §k is left out. */
  const MC_COLORS = { 0: '#000000', 1: '#0000AA', 2: '#00AA00', 3: '#00AAAA', 4: '#AA0000', 5: '#AA00AA', 6: '#FFAA00', 7: '#AAAAAA',
    8: '#555555', 9: '#5555FF', a: '#55FF55', b: '#55FFFF', c: '#FF5555', d: '#FF55FF', e: '#FFFF55', f: '#FFFFFF' };
  const MC_STYLES = { l: 'font-weight:800', o: 'font-style:italic', n: 'text-decoration:underline', m: 'text-decoration:line-through' };
  function mcHtml(text) {
    let out = '';
    let open = 0;
    for (const part of String(text ?? '').split(/(\u00a7[0-9a-fk-or])/i)) {
      const code = /^\u00a7([0-9a-fk-or])$/i.exec(part);
      if (!code) {
        out += esc(part);
        continue;
      }
      const c = code[1].toLowerCase();
      if (MC_COLORS[c] || c === 'r') {
        // A colour (or reset) ends the formatting before it, like in the game.
        out += '</span>'.repeat(open);
        open = 0;
        if (MC_COLORS[c]) {
          out += `<span style="color:${MC_COLORS[c]}">`;
          open++;
        }
      } else if (MC_STYLES[c]) {
        out += `<span style="${MC_STYLES[c]}">`;
        open++;
      }
    }
    return out + '</span>'.repeat(open);
  }
  /** The same text without its codes (for searching). */
  const mcPlain = (text) => String(text ?? '').replace(/\u00a7[0-9a-fk-or]/gi, '');

  async function api(path, body) {
    const res = await fetch(path, body === undefined ? {} : {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-NCC': '1' }, body: JSON.stringify(body),
    });
    const data = await res.json().catch(() => ({}));
    if (res.status === 401 && path !== 'api/login') showLogin();
    if (!res.ok) {
      const err = new Error(data.error || res.statusText);
      err.data = data;
      throw err;
    }
    return data;
  }

  // ── Login ──────────────────────────────────────────────────────────────

  let loggedIn = false;
  let me = null;
  const can = (perm) => !!me && me.permissions.includes(perm);

  function showLogin() {
    loggedIn = false;
    me = null;
    $('pn-app').hidden = true;
    $('pn-user-menu').hidden = true;
    $('pn-state-pill').hidden = true;
    $('pn-power').hidden = true;
    $('pn-stats').hidden = true;
    $('pn-clock').hidden = true;
    $('pn-login').hidden = false;
    $('pn-user').focus();
    closeConsole();
    const frame = $('pn-dashboard-frame');
    frame.src = 'about:blank';
    delete frame.dataset.loaded;
  }

  async function showApp() {
    try {
      me = await api('api/me');
    } catch (e) {
      return showLogin();
    }
    loggedIn = true;
    $('pn-version').textContent = me.version;
    $('pn-login').hidden = true;
    $('pn-app').hidden = false;
    $('pn-user-menu').hidden = false;
    $('pn-user-name').textContent = me.user;
    showAvatar(false);
    if (me.theme && me.theme !== document.documentElement.dataset.theme) setTheme(me.theme, false);
    applyPermissions();
    if (can('console.read')) openConsole();
    if (can('server.view')) {
      $('pn-state-pill').hidden = false;
      loadSettings();
      pollServer();
    }
  }

  // ── Navigation ─────────────────────────────────────────────────────────
  // One sidebar entry per area; related pages are sub-tabs of it. Each sub-tab is one page (a panel
  // section, or a page of the mod's dashboard shown in the frame: "mod:<page>").

  const NAV = [
    { group: '', items: [
      { id: 'home', hue: '#7b93ff', label: 'Home', icon: 'bi-house', subs: [
        { tab: 'mod:overview', label: 'Overview', perm: 'dashboard.view', desc: 'How the server is doing right now, and anything that needs your attention.' }] },
      { id: 'console', hue: '#9fb0c8', label: 'Console', icon: 'bi-terminal', subs: [
        { tab: 'server', label: 'Console', perm: 'console.read', desc: 'The server log as it happens. Type commands below it.' },
        { tab: 'mod:chat', label: 'Chat', perm: 'dashboard.view', desc: 'What players write in game; you can answer from here.' },
        { tab: 'commands', label: 'Commands', perm: 'server.view', desc: 'Every command on the server, by where it comes from: who may use it and what it does.' }] },
      { id: 'players', hue: '#5fd68a', label: 'Players', icon: 'bi-people', subs: [
        { tab: 'mod:players', label: 'Players', perm: 'dashboard.view', desc: 'Everyone who has played: where they are, what they carry, private messages.' },
        { tab: 'players', label: 'Whitelist & bans', perm: 'players.manage', desc: 'Who may join and who may use commands; kick or ban players who are online.' }] },
      { id: 'performance', hue: '#ffb35c', label: 'Performance', icon: 'bi-speedometer2', subs: [
        { tab: 'mod:performance', label: 'Live', perm: 'dashboard.view', desc: 'What takes the server\'s time right now, tick by tick.' },
        { tab: 'stats', label: 'History', perm: 'server.view', desc: 'TPS, players, memory and disk over days and weeks.' },
        { tab: 'mod:reports', label: 'Reports', perm: 'dashboard.view', desc: 'Recordings of lag episodes, to find what caused them.' }] },
      { id: 'world', hue: '#4fd1c5', label: 'World', icon: 'bi-globe-americas', subs: [
        { tab: 'mod:world', label: 'Health', perm: 'dashboard.view', desc: 'Chunks, entities and the places that cost the most.' },
        { tab: 'world', label: 'Borders & reset', perm: 'world.trim', desc: 'Decide which part of the world is permanent, pregenerate it, and reset the rest on a schedule.' },
        { tab: 'map', label: 'Map', perm: 'map.manage', desc: 'The public web map of the world.' }] },
    ] },
    { group: 'Community', items: [
      { id: 'claims', hue: '#f2b233', label: 'Claims', icon: 'bi-bounding-box', subs: [
        { tab: 'claims', label: 'Claims', perm: 'claims.view', desc: 'Land players have claimed: who owns it, who may do what, and where.' },
        { tab: 'claimblocks', label: 'Claim blocks', perm: 'claims.view', desc: 'How many claim blocks each player has earned, bought and used.' },
        { tab: 'claimsettings', label: 'Settings', perm: 'claims.view', desc: 'Switch claims on, how players earn claim blocks, the claim tool and where waypoint hubs may go.' },
        { tab: 'claimimport', label: 'GOML import', perm: 'claims.manage', desc: 'Bring over the claims made with Get Off My Lawn.' }] },
      { id: 'market', hue: '#ffd166', label: 'Market', icon: 'bi-shop', subs: [
        { tab: 'market', label: 'Player market', perm: 'server.view', desc: 'The player market (/market): settings, what players sell and what sold.' },
        { tab: 'servershop', label: 'Server shop', perm: 'server.view', desc: 'What the server sells in /market: claim blocks, command listings and pet listings, and what players bought.' }] },
      { id: 'pets', hue: '#ff9ecf', label: 'Pets', icon: 'bi-emoji-smile', subs: [
        { tab: 'pets', label: 'Pets', perm: 'server.view', desc: 'Floating heads that follow their owner: add pets, then list them in the Server shop or give them with /pets grant.' }] },
      { id: 'discord', hue: '#7983f5', label: 'Discord', icon: 'bi-discord', subs: [
        { tab: 'discord', label: 'Discord', perm: 'discord.manage', desc: 'Chat bridge, alerts and account linking with your Discord server.' }] },
    ] },
    { group: 'Settings', items: [
      { id: 'settings', hue: '#a77bff', label: 'Server', icon: 'bi-sliders', subs: [
        { tab: 'mod:server', label: 'Game', perm: 'dashboard.view', desc: 'Game rules and server.properties.' },
        { tab: 'java', label: 'Startup & Java', perm: 'server.view', desc: 'Memory, Java and what happens when the server starts or crashes.' },
        { tab: 'serverlist', label: 'Server list', perm: 'server.view', desc: 'How the server looks in the multiplayer list: message and icon.' },
        { tab: 'maintenance', label: 'Maintenance', perm: 'server.view', desc: 'Close the server for everyone but staff while you work on it.' }] },
      { id: 'gameplay', hue: '#c4a6ff', label: 'Gameplay', icon: 'bi-magic', subs: [
        { tab: 'tweaks', label: 'Items & tools', perm: 'server.view', desc: 'Anvil, Better Mending, grindstone, spawners, tridents, dispensers, tree felling and vein mining: things vanilla does differently.' },
        { tab: 'felling', label: 'World & nature', perm: 'server.view', desc: 'Nether portals in any shape, saplings that plant themselves, bone meal on more plants, hidden ores, crops in the rain and iron golems.' },
        { tab: 'chattweaks', label: 'Chat & tab list', perm: 'server.view', desc: '@Mentions in chat, the AFK tag, and the tab list\'s header, footer and dimensions.' }] },
      { id: 'tuning', hue: '#ffb35c', label: 'Tuning', icon: 'bi-speedometer', subs: [
        { tab: 'tuning', label: 'Tuning', perm: 'dashboard.view', desc: 'Make the server lighter: tick less where nobody is, cap what piles up, step down while it lags.' }] },
      { id: 'packs', hue: '#f5c451', label: 'Packs & mods', icon: 'bi-boxes', subs: [
        { tab: 'mod:packs', label: 'Data pack settings', perm: 'dashboard.view', desc: 'Options the data packs offer.' },
        { tab: 'configs', label: 'Mod configs', permAny: 'files.config files.read', desc: 'The mods\' settings files, grouped by mod.' }] },
      { id: 'public', hue: '#ff8fa3', label: 'Public pages', icon: 'bi-globe2', subs: [
        { tab: 'info', label: 'Info page', perm: 'settings.server', desc: 'A public page where players can read up on the server\'s settings.' },
        { tab: 'appearance', label: 'Appearance', permAny: 'users.admin map.manage settings.server', desc: 'Logos for the panel and the map, and the picture of the info page banner.' }] },
    ] },
    { group: 'Server', items: [
      { id: 'schedule', hue: '#ff8fc7', label: 'Schedule', icon: 'bi-calendar-check', subs: [
        { tab: 'schedule', label: 'Schedule', perm: 'server.view', desc: 'Things that happen on their own: restarts, backups, resets, messages.' }] },
      { id: 'backups', hue: '#62b6ff', label: 'Backups', icon: 'bi-archive', subs: [
        { tab: 'backups', label: 'Backups', perm: 'backup.view', desc: 'Copies of the world and server to go back to.' }] },
      { id: 'files', hue: '#f5c451', label: 'Files', icon: 'bi-folder2-open', subs: [
        { tab: 'files', label: 'All files', perm: 'files.read', desc: 'Everything in the server folder.' }] },
      { id: 'updates', hue: '#6ee7b7', label: 'Updates', icon: 'bi-cloud-arrow-down', subs: [
        { tab: 'versions', label: 'Minecraft', perm: 'server.view', desc: 'Move the server to another Minecraft version, with the mods to match.' },
        { tab: 'mod:updates', label: 'Mods', perm: 'dashboard.view', desc: 'Newer versions of the installed mods and the resource pack.' }] },
    ] },
    { group: 'Admin', items: [
      { id: 'users', hue: '#c4a6ff', label: 'Users & roles', icon: 'bi-person-gear', subs: [
        { tab: 'users', label: 'Users & roles', perm: 'users.admin', desc: 'Who can log in to the panel and what they may do.' }] },
      { id: 'audit', hue: '#b6c8ff', label: 'Audit log', icon: 'bi-journal-text', subs: [
        { tab: 'audit', label: 'Audit log', perm: 'audit.view', desc: 'Every change made through the panel, and by whom.' }] },
    ] },
  ];
  /** Links from before the sidebar was reorganised still land on the same page. */
  const OLD_HASHES = {
    'tweaks': 'tweaks', 'tweaks/gameplay': 'tweaks', 'tweaks/trees-veins': 'felling', 'tweaks/trees-nature': 'felling', 'tweaks/market': 'market',
    'performance/tuning': 'tuning', 'settings/game': 'mod:server', 'settings/startup-java': 'java', 'settings/server-list': 'serverlist', 'settings/public-info': 'info',
    'files/all-files': 'files', 'files/mod-configs': 'configs', 'files/data-pack-settings': 'mod:packs', 'appearance': 'appearance', 'commands': 'commands',
    'players/maintenance': 'maintenance',
  };
  /** Pages outside the sidebar (the user menu). */
  const EXTRA = { account: { tab: 'account', label: 'Account', desc: 'Your picture, theme, password and SSH keys for SFTP.' } };
  const ENTRY_OF = {};
  const SUB_OF = {};
  NAV.forEach((g) => g.items.forEach((e) => e.subs.forEach((sub) => { ENTRY_OF[sub.tab] = e; SUB_OF[sub.tab] = sub; })));
  const allowed = (sub) => (sub.perm ? can(sub.perm) : sub.permAny ? sub.permAny.split(' ').some(can) : true);
  const subsOf = (entry) => entry.subs.filter(allowed);
  /** Badges by page (the mod's counts), added up on their sidebar entry. */
  const badges = {};
  /** Which badges are warnings (unread answers, updates waiting); the rest are plain counts like players online. */
  const badgeWarn = {};

  function renderSide() {
    $('pn-side-groups').innerHTML = NAV.map((g) => {
      const items = g.items.filter((e) => subsOf(e).length);
      if (!items.length) return '';
      return `<div class="pn-side-group">${g.group ? `<span class="pn-side-head">${esc(g.group)}</span>` : ''}
        ${items.map((e) => `<button type="button" class="np-tab" data-entry="${e.id}" style="--area:${e.hue}"><i class="bi ${e.icon}"></i><span>${esc(e.label)}</span><span class="pn-badge" hidden></span></button>`).join('')}</div>`;
    }).join('');
    renderBadges();
  }

  function renderBadges() {
    document.querySelectorAll('#pn-side-groups [data-entry]').forEach((b) => {
      const entry = NAV.flatMap((g) => g.items).find((e) => e.id === b.dataset.entry);
      const total = subsOf(entry).reduce((n, sub) => n + (Number(badges[sub.tab]) || 0), 0);
      const el = b.querySelector('.pn-badge');
      el.hidden = !total;
      el.textContent = total || '';
      el.classList.toggle('warn', subsOf(entry).some((sub) => badgeWarn[sub.tab] && Number(badges[sub.tab])));
    });
    document.querySelectorAll('#pn-subtabs [data-tab]').forEach((b) => {
      const el = b.querySelector('.pn-badge');
      const n = badges[b.dataset.tab];
      el.hidden = !n;
      el.textContent = n || '';
      el.classList.toggle('warn', !!badgeWarn[b.dataset.tab]);
    });
  }

  function renderPageHead(tab) {
    const entry = ENTRY_OF[tab];
    const sub = SUB_OF[tab] || EXTRA[tab];
    const subs = entry ? subsOf(entry) : [];
    $('pn-page-title').textContent = entry && subs.length > 1 ? entry.label : (sub ? sub.label : '');
    const area = entry ? entry.hue : '#b6c8ff';
    document.querySelector('.pn-content').style.setProperty('--area', area);
    $('pn-page-icon').innerHTML = `<i class="bi ${entry ? entry.icon : 'bi-person-circle'}"></i>`;
    $('pn-page-desc').textContent = sub ? sub.desc : '';
    $('pn-subtabs').hidden = subs.length < 2;
    $('pn-subtabs').innerHTML = subs.length < 2 ? '' : subs.map((x) => `<button type="button" role="tab" class="pn-subtab${x.tab === tab ? ' active' : ''}" aria-selected="${x.tab === tab}" data-tab="${x.tab}">${esc(x.label)}<span class="pn-badge" hidden></span></button>`).join('');
    renderBadges();
  }

  /** "#settings/maintenance" for a page, so links and reloads land on it. */
  function hashOf(tab) {
    const entry = ENTRY_OF[tab];
    if (!entry) return tab;
    const subs = entry.subs;
    return subs.length > 1 ? entry.id + '/' + subs.find((x) => x.tab === tab).label.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') : entry.id;
  }

  function tabFromHash(hash) {
    const key = hash.replace(/^#/, '').split(/[?]/)[0];
    if (OLD_HASHES[key] && allowed(SUB_OF[OLD_HASHES[key]] || {})) return OLD_HASHES[key];
    const [id, sub] = key.split('/');
    if (EXTRA[id]) return id;
    const entry = NAV.flatMap((g) => g.items).find((e) => e.id === id);
    if (!entry) return null;
    const subs = subsOf(entry);
    const hit = sub && subs.find((x) => hashOf(x.tab).split('/')[1] === sub);
    return (hit || subs[0] || {}).tab || null;
  }

  // ── Tabs and permissions ───────────────────────────────────────────────

  const TAB_KEY = 'pn-tab';

  function applyPermissions() {
    document.querySelectorAll('#pn-app [data-perm]').forEach((el) => { el.hidden = !can(el.dataset.perm); });
    document.querySelectorAll('#pn-app [data-perm-any]').forEach((el) => { el.hidden = !el.dataset.permAny.split(' ').some(can); });
    $('pn-power').hidden = !can('server.power');
    renderSide();
    $('pn-menu-btn').hidden = false;
    $('pn-command-form').hidden = !can('console.write');
    loadConsoleOps();
    const editable = can('settings.java');
    $('pn-settings').querySelectorAll('input, select, button').forEach((el) => { el.disabled = !editable; });
    // Links from the game (/ncc web) open ?tab=dashboard, optionally with #<dashboard tab>.
    const params = new URLSearchParams(location.search);
    let tab = params.get('tab');
    // Old links (?tab=dashboard#players) open that page of the mod's dashboard.
    if (tab === 'dashboard') tab = 'mod:' + (location.hash.slice(1).split(/[/?]/)[0] || 'overview');
    if (tab) history.replaceState(null, '', location.pathname);
    if (!tab && location.hash.length > 1) tab = tabFromHash(location.hash);
    if (!tab) {
      tab = 'mod:overview';
      try { tab = localStorage.getItem(TAB_KEY) || tab; } catch (e) { /* default */ }
    }
    selectTab(tab);
  }

  let dashboardHash = '';
  let activeTab = '';
  let homeTicks = 0;
  let memTicks = 0;

  // Backups and schedules change on their own (progress, next run): refresh the open tab.
  setInterval(() => {
    if (!loggedIn || document.hidden) return;
    if (activeTab === 'backups') loadBackups();
    if (activeTab === 'schedule') loadSchedule();
    if (activeTab === 'versions' && versionState.running) loadVersions(false);
    if (activeTab === 'world') worldTick();
    if (activeTab === 'java' && ++memTicks % 3 === 0) loadMemory();
    if (activeTab === 'tuning') loadPerfLive();
    if (activeTab === 'maintenance' && ++mtTicks % 2 === 0) refreshStaffList();
    if (activeTab === 'mod:overview' && ++homeTicks % 5 === 0) loadHome();
    if (activeTab === 'players' && !document.querySelector('#pn-pl-form :focus')) loadPlayerLists();
    if (activeTab === 'stats' && Date.now() - (statsData ? statsData.to : 0) > 60000) loadStats();
  }, 2000);

  // ── The mod's dashboard inside the panel ──
  // It loads once and keeps running; the sidebar switches its pages (its own tab bar, tiles, console
  // and schedule are hidden: the panel has those). It's as tall as its content, so the page scrolls as one.

  const EMBED_CSS = `
    html, body { min-height: 0 !important; height: auto !important; }
    body.embedded { min-height: 0 !important; }
    body.embedded > .navbar, body.embedded .np-tabs, body.embedded .np-toasts { display: none !important; }
    /* The player list runs as long as the open card (at least 640px) and scrolls inside. */
    body.embedded .np-inspector { align-items: stretch !important; }
    body.embedded .np-inspector-list { height: auto !important; min-height: 640px; contain: size; position: static !important; }
    body.embedded .np-console-log { height: 520px !important; }
    /* Kick and ban live on Players → Whitelist & bans. */
    body.embedded .np-moderation, body.embedded .np-subhead:has(+ .np-moderation) { display: none !important; }
    /* On Home the health banner is part of the panel's greeting card. */
    body.embedded.pn-overview #np-banner { display: none !important; }
    /* The panel shows the Save/Cancel bar under the frame (in here it would sit at the end of the page). */
    body.embedded .np-save-bar { display: none !important; }`;
  let frameResize = null;

  function showDashboard(page) {
    const frame = $('pn-dashboard-frame');
    if (!frame.dataset.loaded) {
      frame.dataset.loaded = '1';
      frame.onload = () => {
        try {
          const doc = frame.contentDocument;
          const style = doc.createElement('style');
          style.textContent = EMBED_CSS;
          doc.head.appendChild(style);
          if (frameResize) frameResize.disconnect();
          // Sized to its content. Pages that fill the window (the "server is offline" page) would grow
          // with every resize, so heights are measured without that, and only real changes count.
          const fit = () => {
            const h = Math.max(160, Math.ceil(doc.body.scrollHeight));
            if (Math.abs(h - frame.offsetHeight) > 2) frame.style.height = h + 'px';
          };
          frameResize = new ResizeObserver(fit);
          frameResize.observe(doc.body);
          fit();
          watchFrameSave(doc);
          pauseFrame();
        } catch (e) { /* another origin: leave it as it is */ }
      };
      frame.src = 'dashboard/#' + page;
      return;
    }
    try {
      if (frame.contentWindow.location.hash.slice(1) !== page) frame.contentWindow.location.hash = page;
    } catch (e) {
      frame.src = 'dashboard/#' + page;
    }
  }

  // The mod pages' Save/Cancel bar: the panel's own bar under the frame does what the hidden one inside would.
  let frameSaveWatch = null;

  function frameBar() {
    try {
      const doc = $('pn-dashboard-frame').contentDocument;
      // The bar itself is hidden in the frame, so its form counts as shown when no parent is hidden.
      const shown = (el) => {
        for (let e = el; e && e.nodeType === 1; e = e.parentElement) {
          if (e.hidden || e.ownerDocument.defaultView.getComputedStyle(e).display === 'none') return false;
        }
        return true;
      };
      return doc ? [...doc.querySelectorAll('.np-save-bar')].find((b) => !b.hidden && b.closest('form') && shown(b.closest('form'))) || null : null;
    } catch (e) {
      return null;
    }
  }

  function mirrorFrameSave() {
    const inner = activeTab.startsWith('mod:') ? frameBar() : null;
    $('pn-frame-save').hidden = !inner;
    if (!inner) return;
    const [save, cancel] = inner.querySelectorAll('button');
    for (const [mine, theirs] of [[$('pn-frame-save-btn'), save], [$('pn-frame-cancel-btn'), cancel]]) {
      mine.hidden = !theirs || theirs.hidden;
      mine.disabled = !!(theirs && theirs.disabled);
    }
    const msg = inner.querySelector('.np-form-msg');
    $('pn-frame-save-msg').textContent = msg ? msg.textContent : '';
  }

  function watchFrameSave(doc) {
    if (frameSaveWatch) frameSaveWatch.disconnect();
    frameSaveWatch = new MutationObserver(() => queueMicrotask(mirrorFrameSave));
    frameSaveWatch.observe(doc.body, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ['hidden', 'disabled', 'class'] });
    doc.defaultView.addEventListener('hashchange', mirrorFrameSave);
    mirrorFrameSave();
  }

  $('pn-frame-save-btn').addEventListener('click', () => {
    const inner = frameBar();
    if (inner) inner.closest('form').requestSubmit();
  });
  $('pn-frame-cancel-btn').addEventListener('click', () => {
    const inner = frameBar();
    const cancel = inner && inner.querySelectorAll('button')[1];
    if (cancel) cancel.click();
  });

  /** The mod's page shown in the frame, or null. */
  function framePage() {
    try {
      const frame = $('pn-dashboard-frame');
      return frame.dataset.loaded ? frame.contentWindow.location.hash.slice(1).split(/[/?]/)[0] || null : null;
    } catch (e) {
      return null;
    }
  }

  // Follow page changes made inside the dashboard (its own links), and copy its tab badges to the sidebar.
  setInterval(() => {
    if (!loggedIn) return;
    const page = framePage();
    if (page && activeTab.startsWith('mod:') && activeTab !== 'mod:' + page && SUB_OF['mod:' + page]) markTab('mod:' + page);
    try {
      const doc = $('pn-dashboard-frame').contentDocument;
      let changed = false;
      doc.querySelectorAll('#np-tabs .np-tab').forEach((t) => {
        const src = t.querySelector('.np-tab-badge');
        // The Game page's count (settings worth a look) says nothing next to "Server"; Home lists those.
        if (!src || t.dataset.tab === 'server') return;
        const warn = src.classList.contains('warn');
        let n = src.hidden ? '' : src.textContent.trim();
        // Players online come from the navbar's live data (the frame only counts when its page is open); unread answers win.
        if (t.dataset.tab === 'players' && !warn) n = liveOnline ? String(liveOnline) : '';
        if ((badges['mod:' + t.dataset.tab] || '') !== n || !!badgeWarn['mod:' + t.dataset.tab] !== warn) {
          badges['mod:' + t.dataset.tab] = n;
          badgeWarn['mod:' + t.dataset.tab] = warn;
          changed = true;
        }
      });
      if (changed) renderBadges();
    } catch (e) { /* not loaded */ }
    syncHeroHealth();
    // Without the frame (never opened a mod page) the online count still shows.
    if (!$('pn-dashboard-frame').dataset.loaded && (badges['mod:players'] || '') !== (liveOnline ? String(liveOnline) : '')) {
      badges['mod:players'] = liveOnline ? String(liveOnline) : '';
      renderBadges();
    }
  }, 700);

  /** The dashboard in the frame only polls while one of its pages is shown (it keeps running in the background otherwise). */
  function pauseFrame() {
    try {
      const win = $('pn-dashboard-frame').contentWindow;
      if (win) win.nccPaused = !activeTab.startsWith('mod:');
    } catch (e) { /* not loaded */ }
  }

  function markTab(tab) {
    activeTab = tab;
    pauseFrame();
    const entry = ENTRY_OF[tab];
    document.querySelectorAll('#pn-side-groups [data-entry]').forEach((b) => {
      const on = !!entry && b.dataset.entry === entry.id;
      b.classList.toggle('active', on);
      b.setAttribute('aria-current', on ? 'page' : 'false');
    });
    renderPageHead(tab);
    history.replaceState(null, '', '#' + hashOf(tab));
    try { localStorage.setItem(TAB_KEY, tab); } catch (e) { /* not remembered */ }
  }

  function selectTab(tab) {
    const visible = NAV.flatMap((g) => g.items).flatMap(subsOf).map((x) => x.tab).concat(Object.keys(EXTRA));
    if (!visible.includes(tab)) tab = visible[0];
    const panel = tab.startsWith('mod:') ? 'dashboard' : tab;
    markTab(tab);
    if (ENTRY_OF[tab]) try { localStorage.setItem('pn-sub-' + ENTRY_OF[tab].id, tab); } catch (e) { /* not remembered */ }
    $('pn-home').hidden = tab !== 'mod:overview';
    if (tab === 'mod:overview') loadHome();
    if (tab === 'tuning') loadTuning();
    if (tab === 'maintenance') loadMaintenance();
    if (tab === 'serverlist') loadServerList();
    if (tab === 'info') loadInfo();
    if (tab === 'commands') loadCommands();
    if (tab === 'claims') loadClaims();
    if (tab === 'claimblocks') loadClaimBlocks();
    if (tab === 'claimsettings') loadClaimSettings();
    if (tab === 'tweaks' || tab === 'felling' || tab === 'chattweaks') loadTweaks();
    if (tab === 'market' || tab === 'servershop' || tab === 'pets') { loadMarket(); loadPets(); }
    document.querySelectorAll('#pn-app .np-tab-panel').forEach((p) => { p.hidden = p.dataset.tab !== panel; });
    document.body.classList.remove('pn-nav-open');
    if (panel === 'dashboard') showDashboard(tab.slice(4));
    if (tab === 'backups') loadBackups();
    if (tab === 'java') loadMemory();
    if (tab === 'files') { loadFiles(); loadCleanup(); }
    if (tab === 'configs') loadConfigs();
    if (tab === 'versions') loadVersions(true);
    if (tab === 'discord') loadDiscord();
    mirrorFrameSave();
    if (tab === 'map') loadMap();
    if (tab === 'world') loadWorld();
    if (tab === 'stats') loadStats();
    if (tab === 'players') loadPlayerLists();
    if (tab === 'schedule') loadSchedule();
    if (tab === 'users') loadUsers();
    if (tab === 'audit') loadAudit(true);
    if (tab === 'account') { renderAccount(); renderThemes(); }
    if (tab === 'appearance') loadLogos();
    if (tab === 'server') { const log = $('pn-log'); log.scrollTop = log.scrollHeight; }
    window.scrollTo({ top: 0 });
  }

  // Phones: the sidebar slides in from the left.
  const sideBackdrop = document.createElement('div');
  sideBackdrop.className = 'pn-side-backdrop';
  document.body.appendChild(sideBackdrop);
  sideBackdrop.addEventListener('click', () => document.body.classList.remove('pn-nav-open'));
  $('pn-menu-btn').addEventListener('click', () => document.body.classList.toggle('pn-nav-open'));

  // The sidebar sticks below the navbar, whose height changes with the tiles.
  new ResizeObserver(() => document.documentElement.style.setProperty('--pn-nav-h', document.querySelector('.navbar').offsetHeight + 'px'))
    .observe(document.querySelector('.navbar'));

  // ── Logos (Appearance) ──

  /** fresh: after an upload, skip the browser's copy. Otherwise the cached logo is checked with its ETag. */
  function showBrandLogo(fresh) {
    const img = $('pn-brand-logo');
    const login = $('pn-login-logo');
    const remember = (on) => { try { localStorage.setItem('pn-logo', on ? '1' : '0'); } catch (e) { /* not remembered */ } };
    const shown = () => { img.hidden = false; $('pn-brand-text').hidden = true; login.src = img.src; login.hidden = false; remember(true); };
    img.onload = shown;
    img.onerror = () => { img.hidden = true; $('pn-brand-text').hidden = false; login.hidden = true; document.documentElement.classList.remove('pn-has-logo'); remember(false); };
    if (fresh) img.src = 'branding/panel?t=' + Date.now();
    else if (img.complete) (img.naturalWidth ? shown : img.onerror)();
  }
  showBrandLogo(false);

  function loadLogos() {
    document.querySelectorAll('.pn-logo-slot[data-logo]').forEach((slot) => {
      const img = slot.querySelector('img');
      const empty = slot.querySelector('.pn-logo-preview span');
      img.onload = () => { img.hidden = false; empty.hidden = true; };
      img.onerror = () => { img.hidden = true; empty.hidden = false; };
      img.src = ({ panel: 'branding/panel', map: 'map/data/logo', infobanner: 'info/banner' }[slot.dataset.logo]) + '?t=' + Date.now();
    });
  }

  /**
   * Picking the part of a picture for the info page banner: the picture in a banner-shaped frame
   * (7:1, like the banner on a wide screen), moved by dragging and zoomed by the slider or the wheel.
   * Resolves with a 2450 × 350 JPEG of that part, or null when cancelled.
   */
  function cropBanner(file) {
    return new Promise((resolve) => {
      const dlg = $('pn-crop');
      const frame = $('pn-crop-frame');
      const img = $('pn-crop-img');
      const zoom = $('pn-crop-zoom');
      const url = URL.createObjectURL(file);
      const st = { x: 0, y: 0, s: 1, base: 1 };
      const size = () => ({ fw: frame.clientWidth, fh: frame.clientHeight });
      const place = () => {
        const { fw, fh } = size();
        const w = img.naturalWidth * st.s;
        const h = img.naturalHeight * st.s;
        st.x = Math.min(0, Math.max(fw - w, st.x));
        st.y = Math.min(0, Math.max(fh - h, st.y));
        img.style.width = w + 'px';
        img.style.height = h + 'px';
        img.style.transform = `translate(${st.x}px, ${st.y}px)`;
      };
      /** Zoom to z (1 = the picture just covers the frame), keeping the point (cx, cy) of the frame still. */
      const zoomTo = (z, cx, cy) => {
        const { fw, fh } = size();
        if (cx == null) { cx = fw / 2; cy = fh / 2; }
        const next = st.base * Math.min(5, Math.max(1, z));
        st.x = cx - (cx - st.x) * (next / st.s);
        st.y = cy - (cy - st.y) * (next / st.s);
        st.s = next;
        zoom.value = String(next / st.base);
        place();
      };
      const finish = (result) => {
        dlg.close();
        URL.revokeObjectURL(url);
        frame.onpointerdown = null;
        frame.onwheel = null;
        zoom.oninput = null;
        $('pn-crop-ok').onclick = null;
        $('pn-crop-cancel').onclick = null;
        dlg.oncancel = null;
        resolve(result);
      };
      img.onerror = () => { URL.revokeObjectURL(url); formMsg('pn-logo-msg', 'That file isn\'t a picture the browser can open.', false); resolve(null); };
      img.onload = () => {
        dlg.showModal();
        const { fw, fh } = size();
        st.base = Math.max(fw / img.naturalWidth, fh / img.naturalHeight);
        st.s = st.base;
        st.x = (fw - img.naturalWidth * st.s) / 2;
        st.y = (fh - img.naturalHeight * st.s) / 2;
        zoom.value = '1';
        place();
      };
      img.src = url;
      frame.onpointerdown = (e) => {
        e.preventDefault();
        frame.setPointerCapture(e.pointerId);
        const start = { px: e.clientX, py: e.clientY, x: st.x, y: st.y };
        frame.onpointermove = (m) => { st.x = start.x + m.clientX - start.px; st.y = start.y + m.clientY - start.py; place(); };
        frame.onpointerup = () => { frame.onpointermove = null; frame.onpointerup = null; };
      };
      frame.onwheel = (e) => {
        e.preventDefault();
        const r = frame.getBoundingClientRect();
        zoomTo((st.s / st.base) * (e.deltaY < 0 ? 1.1 : 1 / 1.1), e.clientX - r.left, e.clientY - r.top);
      };
      zoom.oninput = () => zoomTo(Number(zoom.value));
      dlg.oncancel = (e) => { e.preventDefault(); finish(null); };
      $('pn-crop-cancel').onclick = () => finish(null);
      $('pn-crop-ok').onclick = () => {
        const { fw } = size();
        const out = document.createElement('canvas');
        out.width = 2450;
        out.height = 350;
        const k = out.width / fw;
        const ctx = out.getContext('2d');
        ctx.imageSmoothingQuality = 'high';
        ctx.drawImage(img, st.x * k, st.y * k, img.naturalWidth * st.s * k, img.naturalHeight * st.s * k);
        out.toBlob((blob) => finish(blob), 'image/jpeg', 0.9);
      };
    });
  }

  document.querySelectorAll('.pn-logo-slot[data-logo]').forEach((slot) => {
    const which = slot.dataset.logo;
    slot.querySelector('input[type="file"]').addEventListener('change', async (e) => {
      let file = e.target.files[0];
      e.target.value = '';
      if (!file) return;
      if (which === 'infobanner') {
        const cropped = await cropBanner(file);
        if (!cropped) return;
        file = new File([cropped], 'banner.jpg', { type: 'image/jpeg' });
      }
      const form = new FormData();
      form.append('logo', file);
      try {
        const res = await fetch('api/branding/' + which, { method: 'POST', headers: { 'X-NCC': '1' }, body: form });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(data.error || res.statusText);
        formMsg('pn-logo-msg', 'Saved.', true);
        loadLogos();
        if (which === 'panel') showBrandLogo(true);
      } catch (err) {
        formMsg('pn-logo-msg', err.message, false);
      }
    });
    slot.querySelector('[data-logo-remove]').addEventListener('click', async () => {
      try {
        await api('api/branding/' + which + '/remove', {});
        formMsg('pn-logo-msg', 'Removed.', true);
        loadLogos();
        if (which === 'panel') showBrandLogo(true);
      } catch (err) {
        formMsg('pn-logo-msg', err.message, false);
      }
    });
  });

  // ── Replies from players (/nccreply), shown on every page ──

  let repliesSeen = 0;
  try { repliesSeen = Number(localStorage.getItem('pn-replies-seen')) || 0; } catch (e) { /* none */ }

  function renderReplies(replies) {
    let box = $('pn-toasts');
    if (!box) {
      box = document.createElement('div');
      box.id = 'pn-toasts';
      box.className = 'np-toasts pn-toasts';
      box.setAttribute('aria-live', 'polite');
      document.body.appendChild(box);
      box.addEventListener('click', (e) => {
        const toast = e.target.closest('.np-toast');
        if (!toast) return;
        repliesSeen = Math.max(repliesSeen, Number(toast.dataset.time));
        try { localStorage.setItem('pn-replies-seen', String(repliesSeen)); } catch (err) { /* not remembered */ }
        toast.remove();
        if (e.target.closest('.np-toast-close')) return;
        // Open the conversation in the dashboard: its own (hidden) toast does that when clicked.
        selectTab('mod:players');
        const uuid = toast.dataset.uuid;
        setTimeout(() => {
          try { $('pn-dashboard-frame').contentDocument.querySelector(`.np-toast[data-uuid="${uuid}"]`)?.click(); } catch (err) { /* not loaded */ }
        }, 600);
      });
    }
    const open = new Set();
    for (const r of replies || []) {
      if (r.time <= repliesSeen) continue;
      open.add(r.uuid);
      let toast = box.querySelector(`.np-toast[data-uuid="${r.uuid}"]`);
      if (!toast) {
        toast = document.createElement('div');
        toast.className = 'np-toast';
        toast.dataset.uuid = r.uuid;
        toast.setAttribute('role', 'button');
        toast.title = 'Open the conversation';
        box.appendChild(toast);
      }
      toast.dataset.time = r.time;
      const html = `<img src="https://mc-heads.net/avatar/${encodeURIComponent(r.uuid)}/32" alt="" width="32" height="32"><div class="np-toast-body"><b>${esc(r.name)} answered${r.unread > 1 ? ` (${r.unread})` : ''}</b>
        <span>${esc(r.text)}</span></div><button type="button" class="np-toast-close" aria-label="Dismiss"><i class="bi bi-x"></i></button>`;
      if (toast.dataset.html !== html) { toast.innerHTML = html; toast.dataset.html = html; }
    }
    box.querySelectorAll('.np-toast').forEach((t) => { if (!open.has(t.dataset.uuid)) t.remove(); });
  }

  $('pn-tabs').addEventListener('click', (e) => {
    const b = e.target.closest('[data-entry]');
    if (!b) return;
    const entry = NAV.flatMap((g) => g.items).find((x) => x.id === b.dataset.entry);
    // Back to the sub-tab last used in this area.
    let last = null;
    try { last = localStorage.getItem('pn-sub-' + entry.id); } catch (err) { /* first one */ }
    const subs = subsOf(entry);
    selectTab((subs.find((x) => x.tab === last) || subs[0]).tab);
  });
  $('pn-subtabs').addEventListener('click', (e) => {
    const b = e.target.closest('[data-tab]');
    if (b) selectTab(b.dataset.tab);
  });
  window.addEventListener('hashchange', () => {
    const tab = tabFromHash(location.hash);
    if (loggedIn && tab && tab !== activeTab) selectTab(tab);
  });

  // User menu: account and log out.
  $('pn-user-btn').addEventListener('click', (e) => {
    e.stopPropagation();
    const pop = $('pn-user-pop');
    pop.hidden = !pop.hidden;
    $('pn-user-btn').setAttribute('aria-expanded', String(!pop.hidden));
  });
  document.addEventListener('click', (e) => { if (!e.target.closest('#pn-user-menu')) $('pn-user-pop').hidden = true; });
  $('pn-user-pop').addEventListener('click', (e) => {
    const b = e.target.closest('[data-menu]');
    if (!b) return;
    $('pn-user-pop').hidden = true;
    if (b.dataset.menu === 'account') selectTab('account');
    if (b.dataset.menu === 'theme') selectTab('account');
  });

  $('pn-login-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const msg = $('pn-login-msg');
    msg.className = 'np-form-msg';
    msg.textContent = 'Logging in…';
    try {
      await api('api/login', { user: $('pn-user').value, password: $('pn-password').value });
      $('pn-password').value = '';
      msg.textContent = '';
      showApp();
    } catch (err) {
      msg.className = 'np-form-msg error';
      msg.textContent = err.message;
    }
  });

  $('pn-logout').addEventListener('click', async () => {
    try { await api('api/logout', {}); } catch (e) { /* logged out locally anyway */ }
    showLogin();
  });

  // ── Server state ───────────────────────────────────────────────────────

  const STATES = {
    STOPPED: ['Stopped', 'info', 'off'],
    STARTING: ['Starting…', 'warn', 'paused'],
    RUNNING: ['Running', 'good', 'on'],
    STOPPING: ['Stopping…', 'warn', 'paused'],
    CRASHED: ['Crashed', 'poor', 'off'],
  };

  function duration(ms) {
    const s = Math.max(0, Math.floor(ms / 1000));
    const d = Math.floor(s / 86400), h = Math.floor(s / 3600) % 24, m = Math.floor(s / 60) % 60;
    return d ? `${d}d ${h}h` : h ? `${h}h ${m}m` : m ? `${m}m ${s % 60}s` : `${s}s`;
  }

  function renderServer(st) {
    const [label, dot, pill] = STATES[st.state] || [st.state, 'info', 'off'];
    $('pn-state-pill').className = 'np-live ' + pill;
    $('pn-state-text').textContent = label;
    // Uptime goes in the navbar badge; anything worth reading (crash, restart countdown, missing jar) in the note below it.
    const up = st.state === 'RUNNING' && st.runningSince ? duration(Date.now() - st.runningSince)
      : st.state === 'STARTING' && st.startedAt ? duration(Date.now() - st.startedAt) : '';
    $('pn-uptime').textContent = up;
    $('pn-state-pill').title = `Server ${label.toLowerCase()}${up ? ', ' + (st.state === 'RUNNING' ? 'up ' : 'starting for ') + up : ''}${st.pid ? ' · PID ' + st.pid : ''}`;
    const parts = [];
    if (st.message && st.state !== 'RUNNING') parts.push(st.message);
    if (st.nextRestartAt) parts.push('restarting in ' + duration(st.nextRestartAt - Date.now()));
    if (st.recentCrashes && st.state !== 'CRASHED') parts.push(`${st.recentCrashes} crash${st.recentCrashes === 1 ? '' : 'es'} in the last 15 minutes`);
    if (st.ready && (st.state === 'STOPPED' || st.state === 'CRASHED') && st.ready !== st.message) parts.push(st.ready);
    $('pn-sub').textContent = parts.join(' · ');
    $('pn-sub').hidden = !parts.length;
    $('pn-sub').className = 'pn-state-note ' + dot;
    serverRunning = st.state === 'RUNNING';
    if (serverRunning && !liveTimer && can('dashboard.view')) pollLive();
    if (!serverRunning) {
      liveOnline = 0;
      $('pn-pregen-pill').hidden = true;
      $('pn-stats').hidden = true;
      $('pn-clock').hidden = true;
    }
    const running = st.state === 'RUNNING' || st.state === 'STARTING';
    const enabled = {
      start: !running && st.state !== 'STOPPING' && !st.ready,
      restart: running,
      stop: running || st.state === 'CRASHED',
      kill: !!st.pid,
    };
    document.querySelectorAll('[data-action]').forEach((b) => { b.disabled = !enabled[b.dataset.action]; });
    $('pn-eula').hidden = st.eula || !can('server.power');
    $('pn-command').disabled = !running;
  }

  // ── Navbar: stat tiles and game clock (from the mod's live data) ───────

  let serverRunning = false;
  /** Players online from the last live reading: the sidebar's Players count. */
  let liveOnline = 0;
  let liveTimer = null;
  let liveAfter = 0;

  const fixed = (v, d) => (v == null || Number.isNaN(v) ? '–' : Number(v).toFixed(d));
  const num = (v) => (v == null ? '–' : Math.round(v).toLocaleString());

  async function pollLive() {
    clearTimeout(liveTimer);
    liveTimer = null;
    if (!loggedIn || !serverRunning || !can('dashboard.view')) return;
    try {
      const data = await api('dashboard/api/live?after=' + (liveAfter || Date.now() - 2000));
      if (data.points && data.points.length) liveAfter = data.points[data.points.length - 1].t;
      renderTiles(data);
      renderPregenPill(data.pregen);
      renderGameClock(data.clock);
      renderReplies(data.replies);
    } catch (e) {
      $('pn-stats').hidden = true;
    }
    liveTimer = setTimeout(pollLive, document.hidden ? 5000 : 1000);
  }

  /** The mod's stat tiles (same look as its dashboard), pinned in the panel's navbar on every tab. */
  function renderTiles(d) {
    const p = d.latest;
    const cur = d.current;
    if (!p) return;
    liveOnline = p.players || 0;
    const budget = 50;
    const tpsClass = p.tps >= 19.4 ? 'good' : p.tps >= 17 ? 'warn' : 'poor';
    const msptClass = (v) => (v <= budget * 0.7 ? 'good' : v <= budget ? 'warn' : 'poor');
    const livePct = p.heapMax ? (p.heapLive || p.heapUsed) / p.heapMax * 100 : 0;
    const heapClass = livePct < 80 ? 'good' : livePct < 90 ? 'warn' : 'poor';
    const usedPct = p.heapMax ? p.heapUsed / p.heapMax * 100 : 0;
    const usedClass = usedPct < 85 ? 'good' : usedPct < 95 ? 'warn' : 'poor';
    const cpuClass = p.cpuSystem >= 90 ? 'poor' : p.cpuSystem >= 70 ? 'warn' : 'good';
    const gcPct = cur && cur.wallMs ? cur.gcTimeMs * 100 / cur.wallMs : 0;
    const bg = cur && cur.wallMs ? Math.min(100, (cur.gcBackgroundMs || 0) * 100 / cur.wallMs) : 0;
    // Same rules as the mod's tile: background work (ZGC) only counts once the heap is filling up, and
    // the tile shows the number its color comes from.
    const pauseClass = gcPct >= 10 ? 'poor' : gcPct >= 5 ? 'warn' : 'good';
    const bgClass = bg >= 95 && livePct >= 85 ? 'poor' : bg >= 80 && livePct >= 70 ? 'warn' : 'good';
    const rank = { good: 0, warn: 1, poor: 2 };
    const showBusy = rank[bgClass] > rank[pauseClass];
    const gcClass = showBusy ? bgClass : pauseClass;
    const ms = (v) => (v >= 100 ? fixed(v, 0) : fixed(v, 1));
    // Short sizes for the tiles: "54 MB", "2.0 GB", "540 GB".
    const short = (b) => { const [v, u] = b >= 1073741824 ? [b / 1073741824, 'GB'] : [b / 1048576, 'MB']; return (v < 10 ? v.toFixed(1) : Math.round(v)) + ' ' + u; };
    const sizes = (cols) => `<div class="np-mspt">${cols.map(([l, v, cls]) => { const [n, u = ''] = short(v || 0).split(' '); return `<span class="${cls}"><b>${n}<i>${u}</i></b><small>${l}</small></span>`; }).join('')}</div>`;
    const tiles = [
      ['TPS', `<span class="${tpsClass}">${fixed(p.tps, 1)}</span>`, 'target 20'],
      ['MSPT', null, null, cur ? `<div class="np-mspt">${[['min', cur.msptMin], ['med', cur.msptMedian], ['95%ile', cur.msptP95], ['max', cur.msptMax]]
        .map(([l, v]) => `<span class="${msptClass(v)}">${ms(v)}<small>${l}</small></span>`).join('')}</div>` : ''],
      // In cores too: docker stats counts 100 % per core, this tile a share of the whole machine.
      ['CPU', `<span class="${cpuClass}">${fixed(p.cpuProcess, 0)}%</span>`, d.server && d.server.cores
        ? `≈ ${fixed(p.cpuProcess * d.server.cores / 100, 1)} of ${d.server.cores} cores` : `machine ${fixed(p.cpuSystem, 0)}%`],
      ['Memory', null, null, sizes([[p.heapLive ? 'after GC' : 'in use', p.heapLive || p.heapUsed, heapClass], ['w/ garbage', p.heapUsed, usedClass], ['max', p.heapMax, '']])],
    ];
    if (d.disk) {
      const freePct = d.disk.total ? d.disk.free / d.disk.total * 100 : 100;
      tiles.push(['Disk', null, null, sizes([['world', d.disk.world, ''], ['server', d.disk.server, ''], ['free', d.disk.free, freePct < 5 ? 'poor' : freePct < 10 ? 'warn' : 'good']])]);
    }
    tiles.push(
      ['GC', showBusy ? `<span class="${gcClass}">${fixed(bg, 0)}%</span><small> busy</small>` : `<span class="${gcClass}">${fixed(gcPct, 1)}%</span><small> paused</small>`,
        showBusy ? `paused ${fixed(gcPct, 1)}%` : `${num(cur ? cur.gcCount : 0)} pauses`],
      ['Entities', num(p.entities), 'loaded'],
      ['Block entities', num(p.blockEntities), 'ticking'],
      ['Chunks', num(p.chunks), 'loaded'],
      ['Players', num(p.players), d.server && d.server.maxPlayers ? `online of ${num(d.server.maxPlayers)}` : 'online'],
    );
    $('pn-stats').innerHTML = tiles.map(([label, value, sub, extra]) => `<div class="np-stat-card${extra && extra.includes('np-mspt') ? ' wide' : ''}">
      <span class="np-stat-label">${label}</span>${value == null ? '' : `<span class="np-stat-value">${value}</span>`}${extra || ''}${sub == null ? '' : `<span class="np-stat-sub">${esc(sub)}</span>`}</div>`).join('');
    $('pn-stats').hidden = false;
  }

  /** "Overworld: 42%" left of the game clock, only while chunks are being pregenerated. */
  function renderPregenPill(st) {
    const pill = $('pn-pregen-pill');
    if (!st || !st.running) {
      pill.hidden = true;
      return;
    }
    const dim = (worldDims.find((x) => x.id === st.dimension) || {}).name
      || String(st.dimension || '').replace(/^minecraft:/, '').replace(/^[^:]*:/, '').replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
    const pct = st.total ? Math.floor(st.done / st.total * 100) : 0;
    const status = st.counting ? 'counting…' : st.paused ? 'paused' : pct + '%';
    const html = `<i class="bi bi-grid-3x3-gap"></i><b>${esc(dim)}:</b><span>${esc(status)}</span>`;
    if (pill.dataset.html !== html) { pill.innerHTML = html; pill.dataset.html = html; }
    pill.title = `Pregenerating ${dim}: ${(st.done || 0).toLocaleString()} of ${(st.total || 0).toLocaleString()} chunks`
      + `${st.paused ? ' · paused: ' + st.paused : st.etaSeconds > 0 ? ' · about ' + duration(st.etaSeconds * 1000) + ' left' : ''}`
      + `${(st.queued || []).length ? ' · then ' + st.queued.length + ' more' : ''}`;
    pill.hidden = false;
  }
  $('pn-pregen-pill').addEventListener('click', () => selectTab('world'));

  /** The in-game clock badge, like in the mod's navbar. */
  function renderGameClock(c) {
    const box = $('pn-clock');
    if (!c) {
      box.hidden = true;
      return;
    }
    const icon = c.weather === 'thunder' ? 'bi-cloud-lightning-rain' : c.weather === 'rain' ? 'bi-cloud-rain' : c.day ? 'bi-sun' : 'bi-moon-stars';
    let time = c.time;
    if (c.minuteOfDay != null) {
      const m = ((Math.floor(c.minuteOfDay) % 1440) + 1440) % 1440;
      const h = Math.floor(m / 60);
      const mm = String(m % 60).padStart(2, '0');
      time = c.twelveHour ? `${h % 12 === 0 ? 12 : h % 12}:${mm} ${h < 12 ? 'AM' : 'PM'}` : `${String(h).padStart(2, '0')}:${mm}`;
    }
    const html = `<i class="bi ${icon}"></i><b class="np-clock-time">${esc(time || '')}</b><span class="np-clock-date">${c.weekday ? esc(c.weekday.slice(0, 3)) + ', ' : ''}${esc(c.date || '')}</span>`;
    // Rewriting the same markup makes the icon blink in some browsers.
    if (box.dataset.html !== html) { box.innerHTML = html; box.dataset.html = html; }
    box.title = [c.weekday, c.date, c.detail].filter(Boolean).join(' · ');
    box.hidden = false;
  }

  async function pollServer() {
    clearTimeout(pollServer.timer);
    if (!loggedIn) return;
    try {
      renderServer(await api('api/server'));
    } catch (e) { /* shown by the console connection state */ }
    pollServer.timer = setTimeout(pollServer, 1500);
  }

  document.querySelectorAll('[data-action]').forEach((button) => button.addEventListener('click', async () => {
    const action = button.dataset.action;
    if (action === 'kill' && !confirm('Kill the server process? Unsaved world changes are lost.')) return;
    button.disabled = true;
    try {
      await api('api/server/' + action, {});
    } catch (err) {
      alert(err.message);
    }
    pollServer();
  }));

  $('pn-eula-accept').addEventListener('click', async () => {
    try {
      await api('api/server/eula', {});
    } catch (err) {
      alert(err.message);
    }
    pollServer();
  });

  // ── Console ────────────────────────────────────────────────────────────

  let socket = null;
  let reconnect = null;
  const MAX_LINES = 2000;

  /** Panel notes and command echoes start with their time like the server's lines: "[14:03:22] [panel] …". */
  const STAMP = /^\[\d\d:\d\d:\d\d\] /;
  const unstamped = (line) => line.replace(STAMP, '');

  function lineClass(line) {
    if (unstamped(line).startsWith('[panel] ')) return 'panel';
    if (unstamped(line).startsWith('> ')) return 'cmd';
    if (/\b(ERROR|FATAL)\b|Exception|^\s+at /.test(line)) return 'poor';
    if (/\bWARN\b/.test(line)) return 'warn';
    return '';
  }

  // ── Console filters: level chips, debug lines, search ──
  let conFilter = 'all';
  let lastLevel = 'INFO';

  /** The line's level; stack trace lines ("at …", "Caused by") belong to the line above them. */
  function lineLevel(line) {
    if (unstamped(line).startsWith('[panel] ')) return 'PANEL';
    const m = line.match(/\/(ERROR|FATAL|WARN|INFO|DEBUG|TRACE)\]/);
    if (m) return (lastLevel = m[1]);
    if (/^\s+at |^\s*Caused by:|^\s*\.\.\. \d+ more/.test(line)) return lastLevel;
    return (lastLevel = /\b(ERROR|FATAL)\b|Exception/.test(line) ? 'ERROR' : /\bWARN/.test(line) ? 'WARN' : 'INFO');
  }

  function lineVisible(div) {
    const level = div.dataset.level;
    const q = $('pn-console-search').value.trim().toLowerCase();
    if (q && !div.textContent.toLowerCase().includes(q)) return false;
    if (!$('pn-console-debug').checked && (level === 'DEBUG' || level === 'TRACE')) return false;
    switch (conFilter) {
      case 'errors': return level === 'ERROR' || level === 'FATAL';
      case 'warnings': return level === 'WARN';
      // The mod's lines and the panel's own notes.
      case 'ncc': return level === 'PANEL' || /Nice Control Center|nicecontrolcenter/.test(div.textContent);
      default: return true;
    }
  }

  function refilterConsole() {
    for (const div of $('pn-log').children) div.hidden = !lineVisible(div);
    if ($('pn-console-follow').checked) $('pn-log').scrollTop = $('pn-log').scrollHeight;
  }

  $('pn-console-filters').addEventListener('click', (e) => {
    const b = e.target.closest('[data-confilter]');
    if (!b) return;
    conFilter = b.dataset.confilter;
    $('pn-console-filters').querySelectorAll('[data-confilter]').forEach((x) => x.classList.toggle('active', x === b));
    refilterConsole();
  });
  $('pn-console-debug').addEventListener('change', refilterConsole);

  /** Notes made here in the browser get the time like the rest. */
  const panelNote = (text) => `[${new Date().toTimeString().slice(0, 8)}] [panel] ${text}`;

  function addLines(lines) {
    const log = $('pn-log');
    const frag = document.createDocumentFragment();
    for (const line of lines) {
      const div = document.createElement('div');
      div.className = lineClass(line);
      div.dataset.level = lineLevel(line);
      div.textContent = line;
      div.hidden = !lineVisible(div);
      frag.appendChild(div);
    }
    log.appendChild(frag);
    while (log.childElementCount > MAX_LINES) log.firstElementChild.remove();
    if ($('pn-console-follow').checked) log.scrollTop = log.scrollHeight;
  }

  function openConsole() {
    closeConsole();
    const url = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + location.pathname.replace(/[^/]*$/, '') + 'api/console';
    socket = new WebSocket(url);
    consoleHeard = Date.now();
    socket.onmessage = (e) => {
      consoleHeard = Date.now();
      const msg = JSON.parse(e.data);
      if (msg.ping) return;
      if (msg.history) {
        $('pn-log').textContent = '';
        addLines(msg.history);
      } else if (msg.line != null) {
        addLines([msg.line]);
      }
    };
    socket.onclose = (e) => {
      socket = null;
      if (e.code === 4401) return showLogin();
      if (e.code === 4403) return;
      if (loggedIn) reconnect = setTimeout(() => { reconnect = null; openConsole(); }, 3000);
    };
  }

  // The server sends something at least every 25 s. A connection that went quiet (sleep, network
  // change, a proxy dropping it without closing) is replaced, so the console never needs a reload.
  let consoleHeard = 0;
  function checkConsole() {
    if (!loggedIn || !can('console.read')) return;
    const dead = !socket || socket.readyState === WebSocket.CLOSED || socket.readyState === WebSocket.CLOSING;
    if ((socket && socket.readyState === WebSocket.OPEN && Date.now() - consoleHeard > 70000) || (dead && !reconnect)) openConsole();
  }
  setInterval(checkConsole, 10000);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) checkConsole(); });
  window.addEventListener('online', () => checkConsole());

  function closeConsole() {
    clearTimeout(reconnect);
    reconnect = null;
    if (socket) {
      socket.onclose = null;
      socket.close();
      socket = null;
    }
  }

  $('pn-console-search').addEventListener('input', refilterConsole);

  $('pn-command-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const input = $('pn-command');
    const command = input.value.trim();
    if (!command) return;
    try {
      await api('api/server/command', { command });
      input.value = '';
    } catch (err) {
      addLines([panelNote(err.message)]);
    }
  });

  /** "Show to ops in game": commands run here appear for operators in chat. Off by default. */
  async function loadConsoleOps() {
    const wrap = $('pn-console-ops-wrap');
    wrap.hidden = !can('settings.server') || !can('server.view');
    if (wrap.hidden) return;
    try {
      $('pn-console-ops').checked = !!(await api('api/settings')).settings.consoleToOps;
    } catch (e) {
      wrap.hidden = true;
    }
  }
  $('pn-console-ops').addEventListener('change', async (e) => {
    const on = e.target.checked;
    try {
      await api('api/console/ops', { on });
      addLines([panelNote(`Commands run here are ${on ? 'now shown to operators in game' : 'no longer shown to operators in game'}.`)]);
    } catch (err) {
      e.target.checked = !on;
      addLines([panelNote(err.message)]);
    }
  });

  // ── Settings ───────────────────────────────────────────────────────────

  let presets = [];

  function fillSettings(view) {
    const form = $('pn-settings');
    presets = view.presets;
    form.preset.innerHTML = presets.map((p) => `<option value="${esc(p.id)}">${esc(p.name)}</option>`).join('');
    form.javaPath.innerHTML = view.runtimes.map((r) => `<option value="${esc(r)}">${esc(r === 'java' ? 'Built-in (Java 25)' : r)}</option>`).join('');
    const s = view.settings;
    for (const [key, value] of Object.entries(s)) {
      const field = form.elements[key];
      if (!field) continue;
      if (field.type === 'checkbox') field.checked = !!value;
      else field.value = value;
    }
    $('pn-preview').textContent = view.command;
    Dirty.reset('settings');
  }

  function readSettings() {
    const form = $('pn-settings');
    return {
      autoStart: form.autoStart.checked,
      autoRestart: form.autoRestart.checked,
      companionMod: form.companionMod.checked,
      memoryMinMb: Number(form.memoryMinMb.value),
      memoryMaxMb: Number(form.memoryMaxMb.value),
      preset: form.preset.value,
      javaPath: form.javaPath.value,
      serverJar: form.serverJar.value.trim(),
      stopTimeoutSeconds: Number(form.stopTimeoutSeconds.value),
      customArgs: form.customArgs.value.trim(),
    };
  }

  /** The preview follows the form while typing; the server builds the real one on save. */
  function previewCommand() {
    const s = readSettings();
    const preset = presets.find((p) => p.id === s.preset);
    const flags = preset && preset.flags.replace('{softMax}', Math.floor(s.memoryMaxMb / 8) * 7);
    $('pn-preview').textContent = [s.javaPath, `-Xms${s.memoryMinMb}M`, `-Xmx${s.memoryMaxMb}M`, flags, s.customArgs, '-jar', s.serverJar, 'nogui']
      .filter(Boolean).join(' ');
  }

  async function loadSettings() {
    try {
      fillSettings(await api('api/settings'));
    } catch (e) { /* the form stays empty; saving shows the error */ }
  }

  /**
   * Where the container's memory goes: the Minecraft process (its heap plus what Java and the mods use outside
   * it), the panel, and the file cache the kernel gives back when needed (Linux containers only).
   */
  async function loadMemory() {
    let m;
    try {
      m = (await api('api/server?memory=1')).memory;
    } catch (e) {
      return;
    }
    const box = $('pn-memory');
    if (!m || (!m.server && !m.container)) {
      box.hidden = true;
      return;
    }
    const gb = (b) => (b / 1073741824).toLocaleString(undefined, { maximumFractionDigits: 1 }) + ' GB';
    const heap = Number($('pn-settings').memoryMaxMb.value) * 1048576;
    const rows = [];
    if (m.server) rows.push(['Minecraft server', gb(m.server), heap ? `${gb(heap)} heap (maximum memory) plus ${gb(Math.max(0, m.server - heap))} outside it: Java itself, mods' code, network buffers and native libraries (Distant Horizons' database, C2ME…)` : '']);
    if (m.panel) rows.push(['Panel', gb(m.panel), `map, backups, website sync; heap limit ${gb(m.panelHeapMax)}`]);
    if (m.container) rows.push(['Whole container', gb(m.container), m.fileCache ? `incl. ${gb(m.fileCache)} file cache (region files, backups): Docker counts it, but the system gives it back as soon as something needs memory` : '']);
    box.innerHTML = `<p class="pn-preview-label">Memory right now</p><dl>${rows.map(([k, v, note]) => `<dt>${esc(k)}</dt><dd><b>${esc(v)}</b>${note ? ` <span class="pn-hint">${esc(note)}</span>` : ''}</dd>`).join('')}</dl>`;
    box.hidden = false;
  }

  $('pn-settings').addEventListener('input', previewCommand);
  $('pn-settings').addEventListener('submit', async (e) => {
    e.preventDefault();
    const msg = $('pn-settings-msg');
    try {
      fillSettings(await api('api/settings', readSettings()));
      msg.className = 'np-form-msg ok';
      msg.textContent = 'Saved. Applies on the next start.';
    } catch (err) {
      msg.className = 'np-form-msg error';
      msg.textContent = err.message;
    }
  });

  // ── Users and roles ────────────────────────────────────────────────────

  let accounts = null;
  const dateTime = (t) => t ? new Date(t).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' }) : 'never';

  function dbNotice(text) {
    $('pn-db-notice').hidden = !text;
    $('pn-db-notice').textContent = text || '';
    // Without the database there's nothing to list or create, so only the notice stays.
    document.querySelectorAll('#pn-app .np-tab-panel[data-tab="users"] > .np-card').forEach((c) => { c.hidden = !!text; });
  }

  async function loadUsers() {
    try {
      accounts = await api('api/users');
      dbNotice('');
      renderUsers();
      renderRoles();
    } catch (err) {
      dbNotice(err.message);
    }
  }

  function roleName(id) {
    const r = accounts.roles.find((x) => x.id === id);
    return r ? r.name : '?';
  }

  /** Profile pictures for [data-avatar] elements; the first letter stays where there's none. */
  function loadAvatars(root) {
    root.querySelectorAll('[data-avatar]').forEach((el) => {
      const img = new Image();
      img.onload = () => { el.style.backgroundImage = `url("${img.src}")`; el.classList.add('pic'); };
      img.src = `api/avatar/${encodeURIComponent(el.dataset.avatar)}`;
    });
  }

  function renderUsers() {
    $('pn-env-note').textContent = `${accounts.envAdmin} (from the container settings) can always log in and isn't listed here.`;
    $('pn-user-rows').innerHTML = accounts.users.map((u) => `<tr class="${u.disabled ? 'disabled' : ''}" data-user="${u.id}">
      <td class="name"><span class="pn-user-cell"><span class="pn-avatar small" data-avatar="${esc(u.name)}">${esc(u.name.slice(0, 1).toUpperCase())}</span><span>${esc(u.name)}${u.disabled ? '<small>disabled</small>' : ''}</span></span></td>
      <td><div class="np-chips">${u.roles.map((r) => `<span class="np-chip">${esc(roleName(r))}</span>`).join('') || '<span class="np-chip">no role</span>'}</div></td>
      <td>${esc(dateTime(u.lastLogin))}</td>
      <td><div class="pn-row-actions">
        <button type="button" class="np-btn small" data-user-act="edit">Edit</button>
        <button type="button" class="np-btn small" data-user-act="toggle">${u.disabled ? 'Enable' : 'Disable'}</button>
        <button type="button" class="np-btn small danger" data-user-act="delete">Delete</button>
      </div></td></tr>`).join('') || '<tr><td colspan="4" class="np-empty">No users yet. Create the first one below.</td></tr>';
    loadAvatars($('pn-user-rows'));
    resetUserForm();
  }

  function resetUserForm(user) {
    const form = $('pn-user-form');
    form.id.value = user ? user.id : '';
    form.name.value = user ? user.name : '';
    form.name.disabled = !!user;
    form.password.value = '';
    $('pn-user-form-title').textContent = user ? 'Edit ' + user.name : 'New user';
    $('pn-user-submit').textContent = user ? 'Save' : 'Create user';
    $('pn-user-pw-label').textContent = user ? 'New password (leave empty to keep it)' : 'Password';
    $('pn-user-cancel').hidden = !user;
    const chosen = user ? user.roles : [];
    $('pn-user-roles').innerHTML = accounts.roles.map((r) => `<label><input type="checkbox" value="${r.id}" ${chosen.includes(r.id) ? 'checked' : ''}> ${esc(r.name)}</label>`).join('');
  }

  function formMsg(id, text, ok) {
    $(id).className = 'np-form-msg ' + (ok ? 'ok' : 'error');
    $(id).textContent = text;
    Dirty.saved(id, ok);
  }

  // ── Unsaved changes ─────────────────────────────────────────────────────
  // A settings form's Save button (and a Cancel next to it) only shows once something differs from what
  // was loaded. Cancel loads the saved values again. Lists that grow and shrink count as changes too.

  const Dirty = (() => {
    const forms = new Map();
    /** The fields' values, leaving out search boxes and anything marked data-dirty-ignore. */
    const fields = (el) => JSON.stringify([...el.querySelectorAll('input, select, textarea')]
      .filter((i) => !i.closest('[data-dirty-ignore]') && !['search', 'file', 'button', 'submit'].includes(i.type))
      .map((i) => (i.type === 'checkbox' || i.type === 'radio' ? i.checked : i.value)));
    const state = (f) => fields(f.el) + (f.extra ? JSON.stringify(f.extra()) : '');

    function show(f, on) {
      f.dirty = on;
      f.el.classList.toggle('pn-dirty', on);
      for (const b of f.buttons) b.hidden = !on;
      if (f.bar) {
        // After saving, the bar stays a moment so "Saved." can be read.
        if (on) {
          clearTimeout(f.barTimer);
          f.barTimer = null;
          f.bar.hidden = false;
        } else if (!f.barTimer && !f.bar.hidden) {
          f.barTimer = setTimeout(() => { f.barTimer = null; if (!f.dirty) f.bar.hidden = true; }, f.justSaved ? 2500 : 0);
        }
      }
      f.justSaved = false;
    }
    function check(f) {
      if (f.snap === null) return;
      show(f, state(f) !== f.snap);
    }

    return {
      /** name: key; el: the form or card; saveButtons: what to show/hide; reload: what Cancel does; extra(): state kept outside the fields. */
      track(name, el, { saveButtons, reload, extra }) {
        const f = { el, reload, extra, snap: null, dirty: false, buttons: [], bar: null };
        const saves = saveButtons || [...el.querySelectorAll('button[type="submit"]')];
        for (const save of saves) {
          const cancel = document.createElement('button');
          cancel.type = 'button';
          cancel.className = 'np-btn';
          cancel.innerHTML = '<i class="bi bi-x-lg"></i> Cancel';
          cancel.addEventListener('click', () => { f.snap = null; show(f, false); reload(); });
          save.after(cancel);
          f.buttons.push(save, cancel);
          save.addEventListener('click', () => { f.saving = true; });
          if (save.parentElement.classList.contains('pn-sticky-save')) f.bar = save.parentElement;
        }
        el.addEventListener('submit', () => { f.saving = true; });
        const recheck = () => check(f);
        el.addEventListener('input', recheck);
        el.addEventListener('change', recheck);
        // Rows added or removed (rules, chips) count too.
        new MutationObserver(() => queueMicrotask(recheck)).observe(el, { childList: true, subtree: true });
        forms.set(name, f);
        show(f, false);
      },
      /** The form was just filled from the server: this is the saved state. */
      reset(name) {
        const f = forms.get(name);
        if (!f) return;
        f.snap = state(f);
        show(f, false);
      },
      /** A success message after Save: the current state is the saved one. */
      saved(msgId, ok) {
        const msg = document.getElementById(msgId);
        for (const f of forms.values()) {
          if (!f.saving || !f.el.contains(msg) && !(f.bar && f.bar.contains(msg))) continue;
          f.saving = false;
          if (ok) { f.snap = state(f); f.justSaved = true; show(f, false); }
        }
      },
      any: () => [...forms.values()].some((f) => f.dirty),
    };
  })();

  window.addEventListener('beforeunload', (e) => { if (Dirty.any()) { e.preventDefault(); e.returnValue = ''; } });

  $('pn-user-rows').addEventListener('click', async (e) => {
    const b = e.target.closest('[data-user-act]');
    if (!b) return;
    const user = accounts.users.find((u) => u.id === Number(b.closest('tr').dataset.user));
    if (!user) return;
    const act = b.dataset.userAct;
    if (act === 'edit') {
      resetUserForm(user);
      $('pn-user-form').scrollIntoView({ behavior: 'smooth', block: 'center' });
      return;
    }
    if (act === 'delete' && !confirm(`Delete ${user.name}? This can't be undone.`)) return;
    try {
      if (act === 'toggle') await api('api/users/' + user.id, { disabled: !user.disabled });
      else await api('api/users/' + user.id + '/delete', {});
      loadUsers();
    } catch (err) {
      alert(err.message);
    }
  });

  $('pn-user-cancel').addEventListener('click', () => resetUserForm());

  $('pn-user-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const form = e.target;
    const roles = [...$('pn-user-roles').querySelectorAll('input:checked')].map((i) => i.value);
    try {
      if (form.id.value) {
        const body = { roles };
        if (form.password.value) body.password = form.password.value;
        await api('api/users/' + form.id.value, body);
        formMsg('pn-user-msg', 'Saved.', true);
      } else {
        await api('api/users', { name: form.name.value.trim(), password: form.password.value, roles });
        formMsg('pn-user-msg', 'User created.', true);
      }
      loadUsers();
    } catch (err) {
      formMsg('pn-user-msg', err.message, false);
    }
  });

  function permLabel(id) {
    if (id === '*') return 'Everything';
    const p = accounts.permissions.find((x) => x.id === id);
    return p ? p.label : id;
  }

  function renderRoles() {
    $('pn-role-list').innerHTML = accounts.roles.map((r) => `<div class="pn-role" data-role="${r.id}">
      <b>${esc(r.name)}</b>
      <div class="np-chips">${r.permissions.map((p) => `<span class="np-chip">${esc(permLabel(p))}</span>`).join('') || '<span class="np-chip">nothing</span>'}</div>
      <small class="np-form-msg">${r.users} user${r.users === 1 ? '' : 's'}</small>
      <div class="pn-role-actions">
        ${r.permissions.includes('*') ? '' : '<button type="button" class="np-btn small" data-role-act="edit">Edit</button>'}
        ${r.builtin ? '' : '<button type="button" class="np-btn small danger" data-role-act="delete">Delete</button>'}
      </div></div>`).join('');
    resetRoleForm();
  }

  function resetRoleForm(role) {
    const form = $('pn-role-form');
    form.id.value = role ? role.id : '';
    form.name.value = role ? role.name : '';
    form.name.disabled = !!(role && role.builtin);
    $('pn-role-form-title').textContent = role ? 'Edit ' + role.name : 'New role';
    $('pn-role-submit').textContent = role ? 'Save' : 'Create role';
    $('pn-role-cancel').hidden = !role;
    const chosen = role ? role.permissions : [];
    $('pn-role-perms').innerHTML = accounts.permissions.map((p) => `<label><input type="checkbox" value="${esc(p.id)}" ${chosen.includes(p.id) ? 'checked' : ''}>
      <span><small>${esc(p.group)}</small>${esc(p.label)}</span></label>`).join('');
  }

  $('pn-role-list').addEventListener('click', async (e) => {
    const b = e.target.closest('[data-role-act]');
    if (!b) return;
    const role = accounts.roles.find((r) => r.id === Number(b.closest('.pn-role').dataset.role));
    if (!role) return;
    if (b.dataset.roleAct === 'edit') {
      resetRoleForm(role);
      $('pn-role-form').scrollIntoView({ behavior: 'smooth', block: 'center' });
      return;
    }
    if (!confirm(`Delete the role ${role.name}? Users keep their other roles.`)) return;
    try {
      await api('api/roles/' + role.id + '/delete', {});
      loadUsers();
    } catch (err) {
      alert(err.message);
    }
  });

  $('pn-role-cancel').addEventListener('click', () => resetRoleForm());

  $('pn-role-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const form = e.target;
    const permissions = [...$('pn-role-perms').querySelectorAll('input:checked')].map((i) => i.value);
    try {
      await api('api/roles', { id: form.id.value ? Number(form.id.value) : null, name: form.name.value.trim(), permissions });
      formMsg('pn-role-msg', form.id.value ? 'Saved.' : 'Role created.', true);
      loadUsers();
    } catch (err) {
      formMsg('pn-role-msg', err.message, false);
    }
  });

  // ── Audit log ──────────────────────────────────────────────────────────

  let auditOldest = 0;

  async function loadAudit(fresh) {
    if (fresh) auditOldest = 0;
    const q = $('pn-audit-search').value.trim();
    try {
      const data = await api(`api/audit?before=${auditOldest}&q=${encodeURIComponent(q)}`);
      const rows = data.entries.map((e) => `<tr><td>${esc(dateTime(e.time))}</td><td class="name">${esc(e.user || '–')}</td>
        <td><span class="np-chip">${esc(e.action)}</span></td><td>${esc(e.detail || '')}</td><td>${esc(e.ip || '')}</td></tr>`).join('');
      if (fresh) $('pn-audit-rows').innerHTML = rows || '<tr><td colspan="5" class="np-empty">Nothing logged yet.</td></tr>';
      else $('pn-audit-rows').insertAdjacentHTML('beforeend', rows);
      if (data.entries.length) auditOldest = data.entries[data.entries.length - 1].id;
      $('pn-audit-more').hidden = data.entries.length < 100;
    } catch (err) {
      $('pn-audit-rows').innerHTML = `<tr><td colspan="5" class="np-empty">${esc(err.message)}</td></tr>`;
      $('pn-audit-more').hidden = true;
    }
  }

  let auditTimer = null;
  $('pn-audit-search').addEventListener('input', () => {
    clearTimeout(auditTimer);
    auditTimer = setTimeout(() => loadAudit(true), 300);
  });
  $('pn-audit-more').addEventListener('click', () => loadAudit(false));

  // ── Own account ────────────────────────────────────────────────────────

  function renderAccount() {
    $('pn-account-intro').textContent = me.envAdmin
      ? `You're logged in as ${me.user}, the container's admin account. Its password is set with PANEL_ADMIN_PASSWORD in the container settings, and it can do everything.`
      : `You're logged in as ${me.user}. Changing your password logs you out everywhere.`;
    $('pn-password-form').hidden = me.envAdmin;
    loadKeys();
  }

  async function loadKeys() {
    if (me.envAdmin) {
      $('pn-keys').innerHTML = '<p class="np-card-intro">The container admin logs in to SFTP with its password (PANEL_ADMIN_PASSWORD).</p>';
      $('pn-key-form').hidden = true;
      return;
    }
    $('pn-key-form').hidden = false;
    try {
      const data = await api('api/me/keys');
      $('pn-keys').innerHTML = data.keys.map((k) => `<div class="pn-role" data-key="${k.id}"><b>${esc(k.name)}</b>
        <div class="np-chips"><span class="np-chip">${esc(k.fingerprint)}</span></div>
        <small class="np-form-msg">${k.lastUsed ? 'last used ' + esc(dateTime(k.lastUsed)) : 'not used yet'}</small>
        <div class="pn-role-actions"><button type="button" class="np-btn small danger" data-key-remove>Remove</button></div></div>`).join('')
        || '<p class="np-card-intro">No keys yet.</p>';
    } catch (err) {
      $('pn-keys').textContent = err.message;
    }
  }

  $('pn-keys').addEventListener('click', async (e) => {
    if (!e.target.closest('[data-key-remove]')) return;
    try {
      await api(`api/me/keys/${e.target.closest('[data-key]').dataset.key}/delete`, {});
      loadKeys();
    } catch (err) {
      alert(err.message);
    }
  });

  $('pn-key-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
      await api('api/me/keys', { key: e.target.key.value.trim() });
      e.target.reset();
      formMsg('pn-key-msg', 'Key added.', true);
      loadKeys();
    } catch (err) {
      formMsg('pn-key-msg', err.message, false);
    }
  });

  $('pn-password-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const form = e.target;
    try {
      await api('api/me/password', { current: form.current.value, password: form.password.value });
      form.reset();
      showLogin();
      $('pn-login-msg').className = 'np-form-msg ok';
      $('pn-login-msg').textContent = 'Password changed. Log in with the new one.';
    } catch (err) {
      formMsg('pn-password-msg', err.message, false);
    }
  });

  // ── Backups ────────────────────────────────────────────────────────────

  const bytes = (b) => b >= 1073741824 ? (b / 1073741824).toFixed(2) + ' GB' : b >= 1048576 ? (b / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(b / 1024)) + ' KB';
  let backupData = null;
  let restoreName = null;

  async function loadBackups() {
    try {
      backupData = await api('api/backups');
    } catch (err) {
      $('pn-backup-status').textContent = err.message;
      return;
    }
    const st = backupData.status;
    const parts = [];
    if (st.running) {
      const pct = st.filesTotal ? Math.round(st.filesDone / st.filesTotal * 100) : 0;
      parts.push(`<b>${esc(st.phase || 'Working…')}</b>${st.filesTotal ? ` · ${st.filesDone} of ${st.filesTotal} files` : ''}<div class="pn-bar"><span style="width:${pct}%"></span></div>`);
    } else if (st.lastError) {
      parts.push(`<span class="np-form-msg error">${esc(st.lastError)}</span>`);
    } else if (st.lastResult) {
      parts.push('Last: ' + esc(st.lastResult));
    }
    if (st.freeBytes) parts.push(`${bytes(st.freeBytes)} free on the backup disk`);
    $('pn-backup-status').innerHTML = parts.join(' · ');
    const manage = can('backup.manage');
    $('pn-backup-rows').innerHTML = backupData.backups.map((b) => `<tr data-backup="${esc(b.name)}">
      <td class="name">${esc(dateTime(b.time))}${b.pinned ? ' <i class="bi bi-pin-angle-fill" title="Pinned: never removed automatically"></i>' : ''}<small>${esc(b.name)}</small></td>
      <td>${esc(b.label)}</td><td class="num">${bytes(b.size)}</td><td>${esc(b.by)}</td>
      <td><div class="pn-row-actions">
        <a class="np-btn small" href="api/backups/${encodeURIComponent(b.name)}/download" download><i class="bi bi-download"></i></a>
        ${manage ? `<button type="button" class="np-btn small" data-backup-act="pin">${b.pinned ? 'Unpin' : 'Pin'}</button>
        <button type="button" class="np-btn small" data-backup-act="restore">Restore</button>
        <button type="button" class="np-btn small danger" data-backup-act="delete">Delete</button>` : ''}
      </div></td></tr>`).join('') || '<tr><td colspan="5" class="np-empty">No backups yet.</td></tr>';
    const form = $('pn-backup-settings');
    if (!form.dataset.filled) {
      form.dataset.filled = '1';
      form.keepLast.value = backupData.settings.keepLast;
      exSet = new Set(backupData.settings.exclude);
      renderExPatterns();
      loadExTree();
      Dirty.reset('backups');
    }
    $('pn-backup-form').querySelector('button').disabled = st.running;
  }

  $('pn-backup-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
      await api('api/backups', { label: $('pn-backup-label').value.trim() });
      $('pn-backup-label').value = '';
      formMsg('pn-backup-msg', 'Backup started.', true);
      loadBackups();
    } catch (err) {
      formMsg('pn-backup-msg', err.message, false);
    }
  });

  $('pn-backup-rows').addEventListener('click', async (e) => {
    const b = e.target.closest('[data-backup-act]');
    if (!b) return;
    const name = b.closest('tr').dataset.backup;
    const entry = backupData.backups.find((x) => x.name === name);
    const act = b.dataset.backupAct;
    try {
      if (act === 'pin') await api(`api/backups/${encodeURIComponent(name)}/pin`, { pinned: !entry.pinned });
      if (act === 'delete') {
        if (!confirm(`Delete ${name}? This can't be undone.`)) return;
        await api(`api/backups/${encodeURIComponent(name)}/delete`, {});
      }
      if (act === 'restore') return openRestore(name);
      loadBackups();
    } catch (err) {
      alert(err.message);
    }
  });

  async function openRestore(name) {
    restoreName = name;
    $('pn-restore').hidden = false;
    $('pn-restore-title').textContent = 'Restore ' + name;
    $('pn-restore-msg').textContent = '';
    $('pn-restore-items').textContent = 'Loading…';
    try {
      const data = await api(`api/backups/${encodeURIComponent(name)}/contents`);
      $('pn-restore-items').innerHTML = data.contents.map((c) => `<label><input type="checkbox" value="${esc(c.path)}" checked>
        <span>${esc(c.path)} <small>${c.files} file${c.files === 1 ? '' : 's'} · ${bytes(c.bytes)}</small></span></label>`).join('');
    } catch (err) {
      $('pn-restore-items').textContent = err.message;
    }
    $('pn-restore').scrollIntoView({ behavior: 'smooth', block: 'center' });
  }

  $('pn-restore-cancel').addEventListener('click', () => { $('pn-restore').hidden = true; });
  $('pn-restore-go').addEventListener('click', async () => {
    const only = [...$('pn-restore-items').querySelectorAll('input:checked')].map((i) => i.value);
    if (!only.length) return formMsg('pn-restore-msg', 'Choose at least one item.', false);
    const backupFirst = $('pn-restore-safety').checked;
    if (!confirm(`Restore ${only.length} item${only.length === 1 ? '' : 's'} from ${restoreName}?${backupFirst ? ' The current state is backed up first.' : ' The current state is not backed up.'}`)) return;
    try {
      await api(`api/backups/${encodeURIComponent(restoreName)}/restore`, { only, backupFirst });
      $('pn-restore').hidden = true;
      loadBackups();
    } catch (err) {
      formMsg('pn-restore-msg', err.message, false);
    }
  });

  // ── What backups leave out: a folder tree with ticks, plus free patterns ──

  let exSet = new Set();
  /** A plain path ("logs/latest.log") or a whole folder ("logs/**"); everything else is a free pattern. */
  const exIsPath = (p) => /^[^*?[\]{}]+(\/\*\*)?$/.test(p);
  const exKey = (path, dir) => (dir ? path + '/**' : path);
  const exFolderOf = (p) => (p.endsWith('/**') ? p.slice(0, -3) : null);

  function renderExPatterns() {
    const free = [...exSet].filter((p) => !exIsPath(p));
    $('pn-ex-patterns').innerHTML = free.map((p) => `<span class="np-chip">${esc(p)} <button type="button" data-ex-remove="${esc(p)}" aria-label="Remove ${esc(p)}">×</button></span>`).join('')
      || '<span class="pn-hint">none</span>';
  }

  function exRow(path, entry) {
    const dir = !!entry.dir;
    return `<div class="pn-tree-node" data-path="${esc(path)}" data-dir="${dir ? 1 : ''}">
      <div class="pn-tree-row">
        ${dir ? '<button type="button" class="pn-tree-caret" aria-label="Open folder"><i class="bi bi-chevron-right"></i></button>' : '<span class="pn-tree-caret"></span>'}
        <label><input type="checkbox" data-ex="${esc(path)}"> <i class="bi ${dir ? 'bi-folder-fill' : 'bi-file-earmark'}"></i> ${esc(entry.name)}</label>
      </div>
      ${dir ? '<div class="pn-tree-children" hidden></div>' : ''}
    </div>`;
  }

  async function exLoad(container, path) {
    try {
      const data = await api('api/files/list?path=' + encodeURIComponent(path));
      const entries = [...data.entries].sort((a, b) => (b.dir - a.dir) || a.name.localeCompare(b.name));
      container.innerHTML = entries.map((e) => exRow((path ? path + '/' : '') + e.name, e)).join('') || '<div class="pn-hint pn-tree-empty">empty</div>';
      container.dataset.loaded = '1';
      exRefresh();
    } catch (err) {
      container.innerHTML = `<div class="pn-hint">${esc(err.message)}${!can('files.read') ? ' (browsing needs the Files permission; patterns still work)' : ''}</div>`;
    }
  }

  function loadExTree() {
    exLoad($('pn-ex-tree'), '');
  }

  /** Ticks: own pattern = ticked; inside a ticked folder = ticked and greyed; something inside left out = half. */
  function exRefresh() {
    const folders = [...exSet].map(exFolderOf).filter((f) => f !== null);
    $('pn-ex-tree').querySelectorAll('[data-ex]').forEach((box) => {
      const node = box.closest('.pn-tree-node');
      const path = box.dataset.ex;
      const dir = !!node.dataset.dir;
      const inherited = folders.some((f) => path.startsWith(f + '/'));
      box.checked = inherited || exSet.has(exKey(path, dir));
      box.disabled = inherited;
      box.indeterminate = !box.checked && dir && [...exSet].some((p) => exIsPath(p) && p.startsWith(path + '/'));
      node.classList.toggle('excluded', box.checked);
    });
  }

  $('pn-ex-tree').addEventListener('click', (e) => {
    const caret = e.target.closest('button.pn-tree-caret');
    if (!caret) return;
    const node = caret.closest('.pn-tree-node');
    const kids = node.querySelector(':scope > .pn-tree-children');
    kids.hidden = !kids.hidden;
    node.classList.toggle('open', !kids.hidden);
    if (!kids.hidden && !kids.dataset.loaded) exLoad(kids, node.dataset.path);
  });
  $('pn-ex-tree').addEventListener('change', (e) => {
    const box = e.target.closest('[data-ex]');
    if (!box) return;
    const path = box.dataset.ex;
    const dir = !!box.closest('.pn-tree-node').dataset.dir;
    if (box.checked) {
      // A whole folder replaces what was ticked inside it.
      if (dir) [...exSet].forEach((p) => { if (exIsPath(p) && p.startsWith(path + '/')) exSet.delete(p); });
      exSet.add(exKey(path, dir));
    } else {
      exSet.delete(exKey(path, dir));
    }
    exRefresh();
  });
  $('pn-ex-patterns').addEventListener('click', (e) => {
    const b = e.target.closest('[data-ex-remove]');
    if (!b) return;
    exSet.delete(b.dataset.exRemove);
    renderExPatterns();
    exRefresh();
  });
  $('pn-ex-add').addEventListener('keydown', (e) => {
    if (e.key !== 'Enter') return;
    e.preventDefault();
    const v = e.target.value.trim().replace(/^\/+/, '');
    if (!v) return;
    exSet.add(v);
    e.target.value = '';
    renderExPatterns();
    exRefresh();
  });

  $('pn-backup-settings').addEventListener('submit', async (e) => {
    e.preventDefault();
    const form = e.target;
    try {
      const res = await api('api/backups/settings', {
        keepLast: Number(form.keepLast.value),
        exclude: [...exSet],
      });
      formMsg('pn-backup-settings-msg', res.deleted.length ? `Saved. ${res.deleted.length} old backup(s) removed.` : 'Saved.', true);
      loadBackups();
    } catch (err) {
      formMsg('pn-backup-settings-msg', err.message, false);
    }
  });

  // ── Scheduled tasks ────────────────────────────────────────────────────

  let scheduleData = null;
  const STEP_LABELS = { restart: 'Restart', stop: 'Stop the server', start: 'Start the server', backup: 'Backup', command: 'Console command', say: 'Chat message', wait: 'Wait', trim: 'Reset the outer wilds', pregen: 'Pregenerate',
    cleanup: 'Clean up', maintenance: 'Maintenance mode' };

  async function loadSchedule() {
    try {
      scheduleData = await api('api/schedule');
    } catch (err) {
      $('pn-task-list').textContent = err.message;
      return;
    }
    $('pn-zone').textContent = scheduleData.zone;
    loadModTasks();
    const manage = can('schedule.manage');
    $('pn-task-list').innerHTML = scheduleData.tasks.map(({ task, next, last, running }) => `<div class="pn-task ${task.enabled ? '' : 'off'}" data-task="${esc(task.id)}">
      <div><b>${esc(task.name)}</b>
        <small>${esc(whenText(task))} · ${esc(task.steps.map(stepText).join(' → '))}</small>
        <small>${running ? `<span class="good">Running: ${esc(running)}</span>` : task.enabled ? 'Next: ' + esc(next ? dateTime(next) : '–') : 'Off'}${last ? ` · last run ${esc(dateTime(last.time))}: <span class="${last.ok ? 'good' : 'bad'}">${esc(last.message)}</span>` : ''}</small>
      </div>
      ${manage ? `<div class="pn-row-actions">
        <button type="button" class="np-btn small" data-task-act="run" ${running ? 'disabled' : ''}>Run now</button>
        <button type="button" class="np-btn small" data-task-act="toggle">${task.enabled ? 'Turn off' : 'Turn on'}</button>
        <button type="button" class="np-btn small" data-task-act="edit">Edit</button>
        <button type="button" class="np-btn small danger" data-task-act="delete">Delete</button></div>` : ''}
    </div>`).join('') || '<p class="np-empty">No scheduled tasks yet.</p>';
  }

  // ── Tasks still in the mod's own schedule ──

  let modTasks = [];

  async function loadModTasks() {
    if (!can('dashboard.view')) return;
    try {
      modTasks = (await api('dashboard/api/schedule')).tasks || [];
    } catch (e) {
      modTasks = [];
    }
    $('pn-modtasks').hidden = !modTasks.length;
    const manage = can('schedule.manage');
    $('pn-modtask-list').innerHTML = modTasks.map((t) => {
      const conv = convertModTask(t);
      const when = t.repeat === 'interval' ? `every ${t.every} min` : t.repeat === 'once' ? `once on ${t.date} ${t.time}`
        : t.repeat === 'after' ? 'after another task' : t.repeat === 'weekly' ? `${t.time} on ${(t.days || []).map((d) => (scheduleData ? scheduleData.days[d - 1] : d)).join(', ')}` : `${t.time} daily`;
      return `<div class="pn-task ${t.enabled ? '' : 'off'}" data-modtask="${esc(t.id)}">
        <div><b>${esc(t.name || (t.commands || [])[0] || 'Task')}</b>
          <small>${esc(when)} · ${esc((t.commands || []).join(' → '))}</small>
          ${conv.error ? `<small>${esc(conv.error)}</small>` : ''}</div>
        ${manage && !conv.error ? '<div class="pn-row-actions"><button type="button" class="np-btn small primary" data-modtask-move>Move to the panel</button></div>' : ''}
      </div>`;
    }).join('');
  }

  /** A mod task as a panel task: commands become command steps, "wait" lines and pauses become waits. */
  function convertModTask(t) {
    if (t.repeat === 'once') return { error: 'Runs once, so it stays in the mod.' };
    if (t.repeat === 'after') return { error: 'Follows another task, so it stays in the mod.' };
    const steps = [];
    (t.commands || []).forEach((c, i) => {
      const line = c.trim().replace(/^\//, '');
      const wait = line.match(/^wait\s+(\d+)\s*([smh]?)$/i);
      if (wait) {
        const n = Number(wait[1]) * ({ s: 1, m: 60, h: 3600 }[wait[2].toLowerCase()] || 1);
        steps.push({ type: 'wait', seconds: Math.max(1, Math.min(3600, n)) });
      } else if (line) {
        if (i > 0 && t.pause > 0 && steps.length && steps[steps.length - 1].type !== 'wait') steps.push({ type: 'wait', seconds: Math.min(3600, t.pause) });
        steps.push({ type: 'command', text: line });
      }
    });
    if (!steps.length) return { error: 'Has no commands.' };
    const task = { name: (t.name || steps[0].text || 'Task').slice(0, 60), enabled: !!t.enabled, steps };
    if (t.repeat === 'interval') Object.assign(task, { when: 'interval', everyMinutes: Math.max(1, Math.min(10080, t.every || 60)) });
    else Object.assign(task, { when: 'daily', times: [t.time || '04:00'], days: t.repeat === 'weekly' ? (t.days || []) : [] });
    return { task };
  }

  $('pn-modtask-list').addEventListener('click', async (e) => {
    const b = e.target.closest('[data-modtask-move]');
    if (!b) return;
    const id = b.closest('[data-modtask]').dataset.modtask;
    const t = modTasks.find((x) => x.id === id);
    const conv = t && convertModTask(t);
    if (!conv || conv.error) return;
    b.disabled = true;
    try {
      await api('api/schedule', conv.task);
      await modPost('api/schedule/delete', { id });
      loadSchedule();
    } catch (err) {
      alert(err.message);
      b.disabled = false;
    }
  });

  function whenText(t) {
    if (t.when === 'interval') return t.everyMinutes % 60 === 0 ? `every ${t.everyMinutes / 60} h` : `every ${t.everyMinutes} min`;
    const days = t.days.length && t.days.length < 7 ? ' on ' + t.days.map((d) => scheduleData.days[d - 1]).join(', ') : ' daily';
    return t.times.join(', ') + days;
  }

  function stepText(s) {
    switch (s.type) {
      case 'restart': return s.minutes ? `restart after ${s.minutes} min countdown` : 'restart';
      case 'command': return '/' + s.text.replace(/^\//, '');
      case 'say': return `say "${s.text}"`;
      case 'wait': return `wait ${s.seconds} s`;
      case 'backup': return 'backup' + (s.text ? ` (${s.text})` : '');
      case 'trim': return (s.minutes ? `reset the outer wilds after ${s.minutes} min countdown` : 'reset the outer wilds') + (s.backup ? ' (backup first)' : '');
      case 'pregen': return s.text === 'all' ? 'pregenerate everything' : 'pregenerate the outer wilds';
      case 'cleanup': return s.text === 'excess' ? 'remove mobs over their limit' : (s.text || '').startsWith('excess') ? `remove mobs over ${s.text.split(':')[1]} per chunk` : 'clear dropped items';
      case 'maintenance': return s.text === 'off' ? 'maintenance off' : 'maintenance on';
      default: return STEP_LABELS[s.type].toLowerCase();
    }
  }

  $('pn-task-list').addEventListener('click', async (e) => {
    const b = e.target.closest('[data-task-act]');
    if (!b) return;
    const id = b.closest('.pn-task').dataset.task;
    const task = scheduleData.tasks.find((x) => x.task.id === id).task;
    try {
      switch (b.dataset.taskAct) {
        case 'run': await api(`api/schedule/${id}/run`, {}); break;
        case 'toggle': await api('api/schedule', Object.assign({}, task, { enabled: !task.enabled })); break;
        case 'edit': return openTask(task);
        case 'delete':
          if (!confirm(`Delete the task "${task.name}"?`)) return;
          await api(`api/schedule/${id}/delete`, {});
          break;
      }
      loadSchedule();
    } catch (err) {
      alert(err.message);
    }
  });

  let editingTask = null;

  function openTask(task) {
    editingTask = task;
    const form = $('pn-task-form');
    form.hidden = false;
    $('pn-task-title').textContent = task.id ? 'Edit ' + task.name : 'New task';
    $('pn-task-msg').textContent = '';
    form.name.value = task.name || '';
    form.when.value = task.when || 'daily';
    form.times.value = (task.times || []).join(', ');
    form.everyMinutes.value = task.everyMinutes || 60;
    form.enabled.checked = task.enabled !== false;
    $('pn-task-days').innerHTML = scheduleData.days.map((d, i) => `<label><input type="checkbox" value="${i + 1}" ${(task.days || []).includes(i + 1) ? 'checked' : ''}> ${esc(d)}</label>`).join('');
    renderSteps(task.steps || []);
    showWhen();
    form.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }

  function showWhen() {
    const when = $('pn-task-form').when.value;
    document.querySelectorAll('#pn-task-form .pn-when').forEach((el) => { el.hidden = el.dataset.when !== when; });
  }

  function stepRow(s) {
    const opts = Object.entries(STEP_LABELS).map(([k, l]) => `<option value="${k}" ${k === s.type ? 'selected' : ''}>${l}</option>`).join('');
    let fields = '';
    if (s.type === 'restart') fields = `<span>after a countdown of</span><input type="number" data-f="minutes" min="0" max="60" value="${s.minutes ?? 5}"><span>min, message</span>
      <input type="text" data-f="text" value="${esc(s.text || '')}" placeholder="The server restarts in {time}.">`;
    if (s.type === 'command') fields = `<input type="text" data-f="text" value="${esc(s.text || '')}" placeholder="e.g. save-all">`;
    if (s.type === 'say') fields = `<input type="text" data-f="text" value="${esc(s.text || '')}" placeholder="Message to everyone online">`;
    if (s.type === 'backup') fields = `<input type="text" data-f="text" value="${esc(s.text || '')}" placeholder="Note (optional)">`;
    if (s.type === 'trim') fields = `<span>stops the server after a countdown of</span><input type="number" data-f="minutes" min="0" max="60" value="${s.minutes ?? 5}"><span>min, trims outside the soft borders, starts it again; message</span>
      <input type="text" data-f="text" value="${esc(s.text || '')}" placeholder="The outer wilds reset in {time}.">
      <label class="np-check"><input type="checkbox" data-f="backup" ${s.backup ? 'checked' : ''}> back up first</label>`;
    if (s.type === 'pregen') fields = `<select data-f="text"><option value="wilds" ${s.text !== 'all' ? 'selected' : ''}>only the outer wilds</option><option value="all" ${s.text === 'all' ? 'selected' : ''}>everything up to the hard border</option></select>`
      + ((m) => `<select data-f="mode"><option value="slow" ${m === 'slow' ? 'selected' : ''}>slow down while players are online</option><option value="pause" ${m === 'pause' ? 'selected' : ''}>pause while players are online</option><option value="full" ${m === 'full' ? 'selected' : ''}>full speed even with players online</option></select>`)(s.mode || 'slow') + `<span>(dimensions with Pregen on, World tab)</span>`;
    if (s.type === 'wait') fields = `<input type="number" data-f="seconds" min="1" max="3600" value="${s.seconds ?? 30}"><span>seconds</span>`;
    if (s.type === 'cleanup') {
      const caps = [8, 16, 24, 32, 48, 64];
      const cur = (s.text || 'items').startsWith('excess') ? s.text : 'items';
      fields = `<select data-f="text"><option value="items" ${cur === 'items' ? 'selected' : ''}>clear dropped items and XP</option><option value="excess" ${cur === 'excess' ? 'selected' : ''}>remove mobs over their limit (Settings → Tuning)</option>${caps.map((n) => `<option value="excess:${n}" ${cur === 'excess:' + n ? 'selected' : ''}>remove mobs over ${n} of one kind per chunk</option>`).join('')}</select>
        <span>warn in chat</span><input type="number" data-f="seconds" min="0" max="600" value="${s.seconds ?? 30}"><span>seconds before (items only)</span>`;
    }
    if (s.type === 'maintenance') fields = `<select data-f="text"><option value="on" ${s.text !== 'off' ? 'selected' : ''}>switch on (staff only)</option><option value="off" ${s.text === 'off' ? 'selected' : ''}>switch off</option></select><span>(settings under Settings → Server → Maintenance)</span>`;
    // Variables for chat texts: click one to put it into the message.
    const vars = ['say', 'restart', 'trim'].includes(s.type) ? `<div class="pn-vars">${(s.type === 'say' ? [] : [['{time}', 'time left']])
      .concat([['{player}', "reader's name"], ['{online}', 'everyone online'], ['{clock}', 'time now'], ['{date}', 'date'], ['&a', 'green'], ['&c', 'red'], ['&e', 'yellow'], ['&l', 'bold'], ['&r', 'reset']])
      .map(([v, l]) => `<button type="button" class="pn-var" data-var="${v}" title="${l}">${v}<small>${l}</small></button>`).join('')}</div>` : '';
    return `<div class="pn-step"><select data-f="type">${opts}</select>${fields}<button type="button" class="np-btn small" data-step-remove title="Remove"><i class="bi bi-x-lg"></i></button>${vars}</div>`;
  }

  function renderSteps(steps) {
    $('pn-steps').innerHTML = steps.map(stepRow).join('');
  }

  function readSteps() {
    return [...$('pn-steps').children].map((row) => {
      const s = { type: row.querySelector('[data-f="type"]').value, text: '', minutes: 5, seconds: 30 };
      row.querySelectorAll('input[data-f], select[data-f]:not([data-f="type"])').forEach((i) => { s[i.dataset.f] = i.type === 'checkbox' ? i.checked : i.type === 'number' ? Number(i.value) : i.value.trim(); });
      return s;
    });
  }

  $('pn-steps').addEventListener('change', (e) => {
    if (e.target.dataset.f !== 'type') return;
    const steps = readSteps();
    renderSteps(steps);
  });
  $('pn-steps').addEventListener('click', (e) => {
    const v = e.target.closest('[data-var]');
    if (v) {
      const input = v.closest('.pn-step').querySelector('input[data-f="text"]');
      if (!input) return;
      // A countdown starts from its default text, so a variable is added to it rather than replacing it.
      if (!input.value && /\{time\}/.test(input.placeholder)) input.value = input.placeholder;
      const at = input.selectionStart ?? input.value.length;
      input.setRangeText(v.dataset.var, at, input.selectionEnd ?? at, 'end');
      input.focus();
      return;
    }
    if (!e.target.closest('[data-step-remove]')) return;
    e.target.closest('.pn-step').remove();
  });
  $('pn-step-add').addEventListener('click', () => {
    $('pn-steps').insertAdjacentHTML('beforeend', stepRow({ type: 'command', text: '' }));
  });
  $('pn-task-form').when.addEventListener('change', showWhen);
  $('pn-task-cancel').addEventListener('click', () => { $('pn-task-form').hidden = true; });
  $('pn-task-new').addEventListener('click', () => openTask({ name: '', when: 'daily', times: ['04:00'], days: [], steps: [] }));
  document.querySelectorAll('[data-preset]').forEach((b) => b.addEventListener('click', () => {
    if (b.dataset.preset === 'restart') {
      openTask({ name: 'Daily restart', when: 'daily', times: ['04:00'], days: [], steps: [
        { type: 'backup', text: 'before restart' },
        { type: 'restart', minutes: 5, text: '' },
      ] });
    } else if (b.dataset.preset === 'wilds') {
      openTask({ name: 'Weekly outer wilds reset', when: 'daily', times: ['05:00'], days: [1], steps: [
        { type: 'trim', minutes: 5, text: '' },
        { type: 'pregen', text: 'wilds' },
      ] });
    } else {
      openTask({ name: 'Backup every 6 hours', when: 'interval', everyMinutes: 360, steps: [{ type: 'backup', text: 'scheduled' }] });
    }
  }));

  $('pn-task-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const form = e.target;
    const task = {
      id: editingTask && editingTask.id ? editingTask.id : null,
      name: form.name.value.trim(),
      enabled: form.enabled.checked,
      when: form.when.value,
      times: form.times.value.split(',').map((t) => t.trim()).filter(Boolean).map((t) => t.length === 4 ? '0' + t : t),
      days: [...$('pn-task-days').querySelectorAll('input:checked')].map((i) => Number(i.value)),
      everyMinutes: Number(form.everyMinutes.value),
      steps: readSteps(),
    };
    try {
      await api('api/schedule', task);
      form.hidden = true;
      loadSchedule();
    } catch (err) {
      formMsg('pn-task-msg', err.message, false);
    }
  });

  // ── Editor window (files and configs) ──────────────────────────────────

  /** Opens a file in the editor window (editor.html); one window per file. */
  function openEditor(path) {
    const url = 'editor.html#' + encodeURIComponent(path);
    const w = window.open(url, 'ncc-edit-' + path.replace(/[^\w]/g, '_'), 'popup,width=1100,height=820');
    if (!w) window.open(url, '_blank');
    else w.focus();
  }

  // The editor window tells the panel when it saved, so the file list shows the new size and time.
  if ('BroadcastChannel' in window) {
    new BroadcastChannel('ncc-files').onmessage = () => { if (activeTab === 'files') loadFiles(); };
  }

  // ── Files ──────────────────────────────────────────────────────────────

  let cwd = '';
  let fileData = null;

  async function loadFiles(path) {
    if (path !== undefined) cwd = path;
    try {
      fileData = await api('api/files/list?path=' + encodeURIComponent(cwd));
    } catch (err) {
      $('pn-files-msg').className = 'np-form-msg error';
      $('pn-files-msg').textContent = err.message;
      return;
    }
    cwd = fileData.path;
    const parts = cwd ? cwd.split('/') : [];
    $('pn-crumbs').innerHTML = `<button type="button" data-crumb=""><i class="bi bi-hdd"></i> server</button>`
      + parts.map((p, i) => `<span>/</span><button type="button" data-crumb="${esc(parts.slice(0, i + 1).join('/'))}">${esc(p)}</button>`).join('');
    const running = $('pn-state-text').textContent === 'Running' || $('pn-state-text').textContent === 'Starting…';
    const write = can('files.write');
    $('pn-file-rows').innerHTML = (cwd ? `<tr><td></td><td class="name"><button type="button" data-open="${esc(parts.slice(0, -1).join('/'))}" data-dir="1"><i class="bi bi-arrow-90deg-up"></i>..</button></td><td></td><td></td><td></td></tr>` : '')
      + fileData.entries.map((f) => {
        const path = (cwd ? cwd + '/' : '') + f.name;
        const locked = running && (path === fileData.world || path.startsWith(fileData.world + '/'));
        const icon = f.dir ? 'bi-folder-fill' : /\.(zip|jar)$/i.test(f.name) ? 'bi-file-zip' : /\.(json5?|toml|ya?ml|properties|txt|cfg|conf|ini|mcfunction|log)$/i.test(f.name) ? 'bi-file-text' : 'bi-file-earmark';
        return `<tr data-path="${esc(path)}" data-dir="${f.dir ? 1 : ''}" class="${locked ? 'locked' : ''}">
          <td class="pn-check">${write ? `<input type="checkbox" data-select aria-label="Select ${esc(f.name)}">` : ''}</td>
          <td class="name"><button type="button" data-open="${esc(path)}" data-dir="${f.dir ? 1 : ''}"><i class="bi ${icon}"></i>${esc(f.name)}</button></td>
          <td class="num">${f.dir ? '' : bytes(f.size)}</td><td>${esc(dateTime(f.time))}</td>
          <td><div class="pn-row-actions">
            <a class="np-btn small" href="api/files/download?path=${encodeURIComponent(path)}" title="${f.dir ? 'Download as zip' : 'Download'}"><i class="bi bi-download"></i></a>
            ${write ? `<button type="button" class="np-btn small" data-file-act="rename" title="Rename or move"><i class="bi bi-pencil"></i></button>
            <button type="button" class="np-btn small" data-file-act="copy" title="Copy"><i class="bi bi-files"></i></button>
            ${/\.zip$/i.test(f.name) ? '<button type="button" class="np-btn small" data-file-act="unzip" title="Extract here"><i class="bi bi-box-arrow-up"></i></button>' : ''}
            <button type="button" class="np-btn small danger" data-file-act="delete" title="Delete"><i class="bi bi-trash"></i></button>` : ''}
          </div></td></tr>`;
      }).join('');
    $('pn-select-all').checked = false;
    updateSelection();
  }

  function selected() {
    return [...$('pn-file-rows').querySelectorAll('[data-select]:checked')].map((c) => c.closest('tr').dataset.path);
  }

  function updateSelection() {
    const n = selected().length;
    $('pn-zip-selected').disabled = !n;
    $('pn-delete-selected').disabled = !n;
  }

  function filesNote(t, ok) {
    $('pn-files-msg').className = 'np-form-msg ' + (ok ? 'ok' : 'error');
    $('pn-files-msg').textContent = t;
  }

  $('pn-crumbs').addEventListener('click', (e) => { const b = e.target.closest('[data-crumb]'); if (b) loadFiles(b.dataset.crumb); });
  $('pn-file-rows').addEventListener('change', updateSelection);
  $('pn-select-all').addEventListener('change', () => {
    $('pn-file-rows').querySelectorAll('[data-select]').forEach((c) => { c.checked = $('pn-select-all').checked; });
    updateSelection();
  });

  /** One action on one file or folder; used by the row buttons and the right-click menu. */
  async function fileAction(act, path, isDir) {
    const name = path.split('/').pop();
    try {
      switch (act) {
        case 'open':
          if (isDir) return loadFiles(path);
          return openEditor(path);
        case 'download':
          location.href = 'api/files/download?path=' + encodeURIComponent(path);
          return;
        case 'rename': {
          const to = prompt('New name or path (relative to the server folder):', path);
          if (!to || to === path) return;
          await api('api/files/move', { from: path, to });
          break;
        }
        case 'copy': {
          const to = prompt('Copy to (relative to the server folder):', path.replace(/(\.[^./]+)?$/, ' copy$1'));
          if (!to) return;
          const { job } = await api('api/files/copy', { from: path, to });
          await awaitJob(job, (t) => filesNote(t || 'Copying…', true));
          filesNote('Copied.', true);
          break;
        }
        case 'zip': {
          const to = prompt('Name of the zip file:', path + '.zip');
          if (!to) return;
          const { job } = await api('api/files/zip', { paths: [path], to });
          loadFiles();
          await awaitJob(job, (t) => filesNote(t || 'Packing…', true));
          filesNote('Zip ready.', true);
          break;
        }
        case 'unzip': {
          const { job } = await api('api/files/unzip', { path });
          const res = await awaitJob(job, (t) => filesNote(t || 'Unpacking…', true));
          filesNote(`Extracted ${res.files} files.`, true);
          break;
        }
        case 'copypath':
          await navigator.clipboard.writeText(path);
          filesNote(`Copied “${path}”.`, true);
          return;
        case 'delete':
          if (!confirm(`Delete ${name}? It stays in .panel-trash for 7 days.`)) return;
          await api('api/files/delete', { paths: [path] });
          break;
        default:
          return;
      }
      loadFiles();
    } catch (err) {
      filesNote(err.message, false);
    }
  }

  $('pn-file-rows').addEventListener('click', (e) => {
    const open = e.target.closest('[data-open]');
    if (open) return fileAction('open', open.dataset.open, !!open.dataset.dir);
    const b = e.target.closest('[data-file-act]');
    if (!b) return;
    const row = b.closest('tr');
    fileAction(b.dataset.fileAct, row.dataset.path, !!row.dataset.dir);
  });

  // ── Right-click menu on files and folders ──
  let fileMenu = null;

  function closeFileMenu() {
    if (fileMenu) fileMenu.remove();
    fileMenu = null;
  }

  $('pn-file-rows').addEventListener('contextmenu', (e) => {
    const row = e.target.closest('tr[data-path]');
    if (!row) return;
    e.preventDefault();
    closeFileMenu();
    const path = row.dataset.path;
    const isDir = !!row.dataset.dir;
    const write = can('files.write') && !row.classList.contains('locked');
    const items = [
      ['open', isDir ? 'bi-folder2-open' : 'bi-pencil-square', isDir ? 'Open' : 'Open in editor'],
      ['download', 'bi-download', isDir ? 'Download as zip' : 'Download'],
      ['copypath', 'bi-clipboard', 'Copy path'],
      null,
      ...(write ? [
        ['rename', 'bi-input-cursor-text', 'Rename or move'],
        ['copy', 'bi-files', 'Duplicate'],
        ['zip', 'bi-file-zip', 'Zip'],
        ...(/\.zip$/i.test(path) ? [['unzip', 'bi-box-arrow-up', 'Extract here']] : []),
        null,
        ['delete', 'bi-trash', 'Delete', 'danger'],
      ] : []),
    ];
    fileMenu = document.createElement('div');
    fileMenu.className = 'pn-menu';
    fileMenu.setAttribute('role', 'menu');
    fileMenu.innerHTML = `<div class="pn-menu-title">${esc(path.split('/').pop())}</div>` + items.filter((it, i, arr) => it || (i > 0 && arr[i - 1] && i < arr.length - 1))
      .map((it) => (it ? `<button type="button" role="menuitem" class="${it[3] || ''}" data-menu="${it[0]}"><i class="bi ${it[1]}"></i>${it[2]}</button>` : '<hr>')).join('');
    document.body.appendChild(fileMenu);
    const r = fileMenu.getBoundingClientRect();
    fileMenu.style.left = Math.min(e.clientX, window.innerWidth - r.width - 8) + 'px';
    fileMenu.style.top = Math.min(e.clientY, window.innerHeight - r.height - 8) + 'px';
    fileMenu.addEventListener('click', (ev) => {
      const b = ev.target.closest('[data-menu]');
      if (!b) return;
      closeFileMenu();
      fileAction(b.dataset.menu, path, isDir);
    });
    fileMenu.querySelector('button').focus();
  });
  document.addEventListener('click', (e) => { if (fileMenu && !fileMenu.contains(e.target)) closeFileMenu(); });
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeFileMenu(); });
  window.addEventListener('scroll', closeFileMenu, true);
  window.addEventListener('blur', closeFileMenu);

  $('pn-newfile').addEventListener('click', async () => {
    const name = prompt('File name (e.g. notes.txt or config/mymod.json):');
    if (!name) return;
    const path = name.includes('/') && !name.startsWith('./') ? name.replace(/^\/+/, '') : (cwd ? cwd + '/' : '') + name.replace(/^\.\//, '');
    if (fileData && fileData.entries.some((f) => (cwd ? cwd + '/' : '') + f.name === path)) return filesNote(`${path} already exists.`, false);
    try {
      // Empty files don't pass the JSON/TOML check yet; they're filled in the editor.
      await api('api/files/write', { path, text: '', force: true });
      loadFiles();
      openEditor(path);
    } catch (err) {
      filesNote(err.message, false);
    }
  });

  $('pn-mkdir').addEventListener('click', async () => {
    const name = prompt('Folder name:');
    if (!name) return;
    try {
      await api('api/files/mkdir', { path: (cwd ? cwd + '/' : '') + name });
      loadFiles();
    } catch (err) {
      filesNote(err.message, false);
    }
  });

  $('pn-delete-selected').addEventListener('click', async () => {
    const paths = selected();
    if (!confirm(`Delete ${paths.length} item${paths.length === 1 ? '' : 's'}? They stay in .panel-trash for 7 days.`)) return;
    try {
      await api('api/files/delete', { paths });
      loadFiles();
    } catch (err) {
      filesNote(err.message, false);
    }
  });

  $('pn-zip-selected').addEventListener('click', async () => {
    const paths = selected();
    const to = prompt('Name of the zip file:', (cwd ? cwd + '/' : '') + (paths.length === 1 ? paths[0].split('/').pop() : 'files') + '.zip');
    if (!to) return;
    try {
      const { job } = await api('api/files/zip', { paths, to });
      loadFiles();
      await awaitJob(job, (t) => filesNote(t || 'Packing…', true));
      filesNote('Zip ready.', true);
      loadFiles();
    } catch (err) {
      filesNote(err.message, false);
    }
  });

  /**
   * Uploads run in the background with a progress box on every tab, one batch after another, so the
   * Files tab (or any other) can be left meanwhile. Dropped folders keep their structure.
   */
  const uploadQueue = [];
  let uploading = null;

  function uploadBox() {
    let box = $('pn-uploads');
    if (!box) {
      box = document.createElement('div');
      box.id = 'pn-uploads';
      box.className = 'pn-uploads';
      box.setAttribute('role', 'status');
      document.body.appendChild(box);
    }
    return box;
  }

  function showUploads(pct) {
    const box = uploadBox();
    if (!uploading) {
      box.remove();
      return;
    }
    const n = uploading.files.length;
    const waiting = uploadQueue.reduce((sum, b) => sum + b.files.length, 0);
    box.innerHTML = `<b>Uploading ${n} file${n === 1 ? '' : 's'} to /${esc(uploading.dir)}</b> · ${pct}%
      <div class="pn-bar"><span style="width:${pct}%"></span></div>
      <small>${esc(uploading.files.slice(0, 3).map((f) => f.path).join(', '))}${n > 3 ? ` and ${n - 3} more` : ''}${waiting ? ` · ${waiting} more waiting` : ''}</small>`;
  }

  /** entries: [{ file, path }] with path relative to dir (folders included). */
  function upload(entries, overwrite, dir = cwd) {
    if (!entries.length) return;
    uploadQueue.push({ files: entries, overwrite, dir });
    if (!uploading) nextUpload();
    else showUploads(0);
  }

  function nextUpload() {
    uploading = uploadQueue.shift() || null;
    if (!uploading) {
      showUploads(0);
      return;
    }
    const batch = uploading;
    const form = new FormData();
    batch.files.forEach((f) => {
      form.append('files', f.file, f.file.name);
      form.append('paths', f.path);
    });
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `api/files/upload?path=${encodeURIComponent(batch.dir)}${batch.overwrite ? '&overwrite=1' : ''}`);
    xhr.setRequestHeader('X-NCC', '1');
    showUploads(0);
    xhr.upload.onprogress = (e) => { if (e.lengthComputable) showUploads(Math.round(e.loaded / e.total * 100)); };
    xhr.onload = () => {
      let data = {};
      try { data = JSON.parse(xhr.responseText); } catch (e) { /* not JSON */ }
      if (xhr.status === 400 && /already exists/.test(data.error || '') && !batch.overwrite && confirm(data.error + ' Replace it?')) {
        uploadQueue.unshift({ files: batch.files, overwrite: true, dir: batch.dir });
      } else if (xhr.status >= 400) {
        filesNote(data.error || 'Upload failed', false);
      } else {
        filesNote(`Uploaded ${data.saved.length} file${data.saved.length === 1 ? '' : 's'} to /${batch.dir}.`, true);
      }
      if (activeTab === 'files') loadFiles();
      nextUpload();
    };
    xhr.onerror = xhr.onabort = xhr.ontimeout = () => {
      filesNote('Upload failed (connection).', false);
      nextUpload();
    };
    xhr.send(form);
  }

  window.addEventListener('beforeunload', (e) => {
    if (uploading) {
      e.preventDefault();
      e.returnValue = '';
    }
  });

  /** Files (and the contents of dropped folders) from a drop, with their paths. */
  async function droppedEntries(dt) {
    const out = [];
    const items = [...(dt.items || [])].map((i) => (i.webkitGetAsEntry ? i.webkitGetAsEntry() : null));
    if (!items.length || items.some((i) => !i)) return [...dt.files].map((file) => ({ file, path: file.name }));
    const walk = async (entry, prefix) => {
      if (entry.isFile) {
        out.push({ file: await new Promise((res, rej) => entry.file(res, rej)), path: prefix + entry.name });
      } else if (entry.isDirectory) {
        const reader = entry.createReader();
        // readEntries returns at most 100 at a time.
        for (;;) {
          const batch = await new Promise((res, rej) => reader.readEntries(res, rej));
          if (!batch.length) break;
          for (const e of batch) await walk(e, prefix + entry.name + '/');
        }
      }
    };
    for (const entry of items) await walk(entry, '');
    return out;
  }

  $('pn-upload').addEventListener('change', (e) => { upload([...e.target.files].map((file) => ({ file, path: file.name })), false); e.target.value = ''; });
  // The whole Files tab takes drops, not just the table.
  const dropArea = document.querySelector('.np-tab-panel[data-tab="files"]');
  const drop = $('pn-drop');
  dropArea.addEventListener('dragover', (e) => {
    if (!can('files.write') || ![...e.dataTransfer.types].includes('Files')) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = 'copy';
    drop.classList.add('dragging');
  });
  dropArea.addEventListener('dragleave', (e) => { if (!dropArea.contains(e.relatedTarget)) drop.classList.remove('dragging'); });
  dropArea.addEventListener('drop', async (e) => {
    if (![...e.dataTransfer.types].includes('Files')) return;
    e.preventDefault();
    drop.classList.remove('dragging');
    if (!can('files.write')) return;
    const dir = cwd;
    upload(await droppedEntries(e.dataTransfer), false, dir);
  });
  // A file dropped next to the drop area mustn't make the browser leave the panel to show it.
  window.addEventListener('dragover', (e) => { if ([...e.dataTransfer.types].includes('Files')) e.preventDefault(); });
  window.addEventListener('drop', (e) => { if ([...e.dataTransfer.types].includes('Files')) e.preventDefault(); });

  // ── Log cleanup ────────────────────────────────────────────────────────

  function cleanupRow(r) {
    return `<div class="pn-step"><span>In</span><input type="text" data-f="folder" value="${esc(r.folder || '')}" placeholder="logs" style="flex:0 1 160px;min-width:100px">
      <span>delete</span><input type="text" data-f="pattern" value="${esc(r.pattern || '*')}" style="flex:0 1 140px;min-width:80px">
      <span>older than</span><input type="number" data-f="maxAgeDays" min="1" max="3650" value="${r.maxAgeDays || 14}"><span>days</span>
      <label class="np-check"><input type="checkbox" data-f="enabled" ${r.enabled !== false ? 'checked' : ''}> on</label>
      <button type="button" class="np-btn small" data-rule-remove title="Remove"><i class="bi bi-x-lg"></i></button></div>`;
  }

  function readCleanup() {
    return {
      daily: $('pn-cleanup-daily').checked,
      rules: [...$('pn-cleanup-rules').children].map((row) => ({
        folder: row.querySelector('[data-f="folder"]').value.trim(),
        pattern: row.querySelector('[data-f="pattern"]').value.trim() || '*',
        maxAgeDays: Number(row.querySelector('[data-f="maxAgeDays"]').value),
        enabled: row.querySelector('[data-f="enabled"]').checked,
      })),
    };
  }

  function renderCleanup(view) {
    $('pn-cleanup-daily').checked = view.settings.daily;
    $('pn-cleanup-rules').innerHTML = view.settings.rules.map(cleanupRow).join('');
    const total = view.preview.reduce((n, c) => n + c.size, 0);
    $('pn-cleanup-preview').textContent = view.preview.length
      ? `Right now this would delete ${view.preview.length} file${view.preview.length === 1 ? '' : 's'} (${bytes(total)}), e.g. ${view.preview.slice(0, 3).map((c) => c.path).join(', ')}.`
      : 'Right now there is nothing old enough to delete.';
    Dirty.reset('logs');
  }

  async function loadCleanup() {
    if (!can('files.write')) return;
    try {
      renderCleanup(await api('api/cleanup'));
    } catch (err) {
      formMsg('pn-cleanup-msg', err.message, false);
    }
  }

  $('pn-cleanup-rules').addEventListener('click', (e) => { if (e.target.closest('[data-rule-remove]')) e.target.closest('.pn-step').remove(); });
  $('pn-cleanup-add').addEventListener('click', () => $('pn-cleanup-rules').insertAdjacentHTML('beforeend', cleanupRow({ folder: '', pattern: '*', maxAgeDays: 14 })));
  $('pn-cleanup-save').addEventListener('click', async () => {
    try {
      renderCleanup(await api('api/cleanup/settings', readCleanup()));
      formMsg('pn-cleanup-msg', 'Saved.', true);
    } catch (err) {
      formMsg('pn-cleanup-msg', err.message, false);
    }
  });
  $('pn-cleanup-run').addEventListener('click', async () => {
    if (!confirm('Delete the old log files listed above now?')) return;
    try {
      const res = await api('api/cleanup/run', {});
      formMsg('pn-cleanup-msg', res.result + '.', true);
      loadCleanup();
    } catch (err) {
      formMsg('pn-cleanup-msg', err.message, false);
    }
  });

  // ── Config editor ──────────────────────────────────────────────────────

  let configFiles = [];

  async function loadConfigs() {
    try {
      configFiles = (await api('api/configs')).files;
    } catch (err) {
      $('pn-config-files').textContent = err.message;
      return;
    }
    renderConfigs();
  }

  function renderConfigs() {
    const q = $('pn-config-search').value.trim().toLowerCase();
    const groups = new Map();
    for (const f of configFiles) {
      const rel = f.path.replace(/^config\//, '');
      if (q && !rel.toLowerCase().includes(q)) continue;
      const slash = rel.indexOf('/');
      const group = slash < 0 ? '' : rel.slice(0, slash);
      if (!groups.has(group)) groups.set(group, []);
      groups.get(group).push([f, slash < 0 ? rel : rel.slice(slash + 1)]);
    }
    $('pn-config-files').innerHTML = [...groups].sort((a, b) => a[0].localeCompare(b[0])).map(([g, list]) =>
      (g ? `<div class="pn-config-group">${esc(g)}</div>` : '')
      + list.map(([f, name]) => `<button type="button" class="pn-config-file" data-config="${esc(f.path)}" title="${esc(f.path)}">${esc(name)}</button>`).join('')
    ).join('') || '<p class="np-empty">No config files found.</p>';
  }

  $('pn-config-search').addEventListener('input', renderConfigs);
  $('pn-config-files').addEventListener('click', async (e) => {
    const b = e.target.closest('[data-config]');
    if (!b) return;
    openEditor(b.dataset.config);
  });

  // ── Versions ─────────────────────────────────────────────────────────

  const versionState = { installed: null, games: [], running: false, plan: null };
  const STATUS_LABEL = { ok: 'works', update: 'update', missing: 'no version yet', unknown: 'not on Modrinth', companion: 'panel mod' };

  /** 26.1+ releases first; "26.3" > "26.1.2" > "1.21.11". */
  const versionKey = (v) => v.split(/[.\-]/).map((p) => (/^\d+$/.test(p) ? p.padStart(5, '0') : p)).join('.');

  async function loadVersions(full) {
    let data;
    try {
      data = await api('api/versions');
    } catch (err) {
      $('pn-installed').textContent = err.message;
      return;
    }
    const inst = data.installed;
    versionState.installed = inst;
    versionState.running = data.status.running;
    $('pn-installed').innerHTML = [
      `<span class="np-chip">Minecraft ${esc(inst.mc || '?')}</span>`,
      `<span class="np-chip">Fabric Loader ${esc(inst.loader || '?')}</span>`,
      inst.installer ? `<span class="np-chip">Launcher ${esc(inst.installer)}</span>` : '',
      `<span class="np-chip">${esc(inst.jar)}</span>`,
    ].join('');
    renderUpdateStatus(data.status);
    if (full) await loadAvailable();
  }

  function renderUpdateStatus(st) {
    const show = st.running || st.log.length;
    $('pn-update-progress').hidden = !show;
    $('pn-update-phase').textContent = st.running ? (st.phase || 'Working…') : (st.result || 'Last update');
    $('pn-update-log').innerHTML = stepList(st.log, st.running);
    $('pn-update-go').disabled = st.running;
    // Only the newest update can be undone, and only if it went through (a rollback already undid itself).
    const latestBackup = st.history.length && st.history[0].backup && st.history[0].result === 'Done' ? st.history[0] : null;
    $('pn-version-history').innerHTML = st.history.map((h, i) => `<div class="pn-task">
      <div><b>${esc(h.to)}</b><small>from ${esc(h.from)} · ${esc(dateTime(h.time))} · ${esc(h.by || '')}</small>
      <small class="${h.result === 'Done' ? 'good' : 'bad'}">${esc(h.result)}</small></div>
      ${can('versions.manage') && h.backup && h === latestBackup ? `<div class="pn-row-actions"><button type="button" class="np-btn small" data-undo="${esc(h.backup)}">Undo</button></div>` : ''}
    </div>`).join('') || '<p class="np-empty">No updates yet.</p>';
  }

  async function loadAvailable() {
    const inst = versionState.installed;
    try {
      const data = await api('api/versions/available?mc=' + encodeURIComponent(inst.mc || ''));
      versionState.games = data.games;
      renderGames();
      const stable = (data.loaders || []).find((l) => l.stable);
      $('pn-loader-hint').innerHTML = stable && inst.loader && stable.version !== inst.loader
        ? `Fabric Loader ${esc(stable.version)} is out for Minecraft ${esc(inst.mc)}. ${can('versions.manage') ? `<button type="button" class="np-btn small" id="pn-loader-quick">Update Fabric Loader</button>` : ''}`
        : (stable ? `Fabric Loader ${esc(inst.loader)} is the newest for Minecraft ${esc(inst.mc)}.` : '');
      await loadLoaders();
    } catch (err) {
      formMsg('pn-versions-msg', err.message, false);
    }
  }

  function renderGames() {
    const all = $('pn-mc-all').checked;
    const current = versionState.installed.mc;
    const list = versionState.games.filter((g) => all || (g.stable && /^(2[6-9]|[3-9]\d)\./.test(g.version)) || g.version === current)
      .sort((a, b) => versionKey(b.version).localeCompare(versionKey(a.version)));
    const chosen = $('pn-mc').value || current;
    $('pn-mc').innerHTML = list.map((g) => `<option value="${esc(g.version)}" ${g.version === chosen ? 'selected' : ''}>${esc(g.version)}${g.version === current ? ' (installed)' : ''}${g.stable ? '' : ' – snapshot'}</option>`).join('');
  }

  async function loadLoaders() {
    const mc = $('pn-mc').value;
    if (!mc) return;
    try {
      const data = await api('api/versions/available?mc=' + encodeURIComponent(mc));
      const stable = data.loaders.find((l) => l.stable) || data.loaders[0];
      $('pn-loader').innerHTML = data.loaders.slice(0, 25).map((l) => `<option value="${esc(l.version)}" ${stable && l.version === stable.version ? 'selected' : ''}>${esc(l.version)}${l.stable ? '' : ' – beta'}${l.version === versionState.installed.loader && mc === versionState.installed.mc ? ' (installed)' : ''}</option>`).join('');
    } catch (err) {
      formMsg('pn-versions-msg', err.message, false);
    }
    $('pn-mod-plan').hidden = true;
  }

  async function checkMods() {
    const mc = $('pn-mc').value;
    formMsg('pn-versions-msg', 'Checking the mods on Modrinth…', true);
    try {
      const data = await api('api/versions/check', { mc, loader: $('pn-loader').value });
      versionState.plan = data.mods;
      formMsg('pn-versions-msg', '', true);
    } catch (err) {
      return formMsg('pn-versions-msg', err.message, false);
    }
    $('pn-mod-rows').innerHTML = versionState.plan.map((m) => `<tr data-mod="${esc(m.file)}">
      <td class="name">${esc(m.name)}<small>${esc(m.file)}</small></td><td>${esc(m.version || '')}</td>
      <td><span class="pn-status ${esc(m.status)}">${esc(STATUS_LABEL[m.status] || m.status)}</span>${m.newVersion ? ' ' + esc(m.newVersion) : ''}
        ${m.needsLoader ? `<br><span class="pn-status missing">needs Fabric Loader ${esc(m.needsLoader)}</span>` : ''}</td>
      <td><select class="pn-plan-select">${['keep', 'update', 'disable'].filter((a) => a !== 'update' || m.status === 'update')
        .map((a) => `<option value="${a}" ${a === m.action ? 'selected' : ''}>${{ keep: 'keep as is', update: 'update', disable: 'turn off' }[a]}</option>`).join('')}</select></td></tr>`).join('')
      || '<tr><td colspan="4" class="np-empty">No mods installed.</td></tr>';
    const inst = versionState.installed;
    const loader = $('pn-loader').value;
    $('pn-update-label').textContent = mc === inst.mc ? `Update to Fabric Loader ${loader}` : `Update to Minecraft ${mc} with Fabric Loader ${loader}`;
    $('pn-mod-plan').hidden = false;
  }

  async function startUpdate() {
    const mc = $('pn-mc').value;
    const loader = $('pn-loader').value;
    const actions = {};
    $('pn-mod-rows').querySelectorAll('[data-mod]').forEach((row) => { actions[row.dataset.mod] = row.querySelector('select').value; });
    const blocked = (versionState.plan || []).filter((m) => m.needsLoader && actions[m.file] !== 'disable');
    if (blocked.length && !confirm(`${blocked.map((m) => m.name).join(', ')} need${blocked.length === 1 ? 's' : ''} a newer Fabric Loader than ${loader}. The server will most likely not start and the update will be rolled back. Continue anyway?`)) return;
    const off = Object.values(actions).filter((a) => a === 'disable').length;
    const upd = Object.values(actions).filter((a) => a === 'update').length;
    const backupFirst = $('pn-update-backup').checked;
    const steps = [backupFirst ? 'Backup' : null, 'Stop the server', `New server jar${upd ? `, ${upd} mod update${upd === 1 ? '' : 's'}` : ''}${off ? `, ${off} mod${off === 1 ? '' : 's'} turned off` : ''}`,
      `Start; if it doesn't come up, ${backupFirst ? 'the backup is restored' : 'the old mods and server jar are put back'}`].filter(Boolean);
    if (!confirm(`${$('pn-update-label').textContent}?\n\n${steps.map((t, i) => `${i + 1}. ${t}`).join('\n')}`)) return;
    try {
      await api('api/versions/update', { mc, loader, actions, backupFirst });
      $('pn-mod-plan').hidden = true;
      loadVersions(false);
    } catch (err) {
      formMsg('pn-versions-msg', err.message, false);
    }
  }

  $('pn-mc').addEventListener('change', loadLoaders);
  $('pn-mc-all').addEventListener('change', () => { renderGames(); loadLoaders(); });
  $('pn-loader').addEventListener('change', () => { $('pn-mod-plan').hidden = true; });
  $('pn-mods-check').addEventListener('click', checkMods);
  $('pn-update-go').addEventListener('click', startUpdate);
  $('pn-loader-hint').addEventListener('click', async (e) => {
    if (!e.target.closest('#pn-loader-quick')) return;
    $('pn-mc').value = versionState.installed.mc;
    await loadLoaders();
    await checkMods();
    $('pn-mod-plan').scrollIntoView({ behavior: 'smooth', block: 'center' });
  });
  $('pn-version-history').addEventListener('click', async (e) => {
    const b = e.target.closest('[data-undo]');
    if (!b || !confirm(`Undo the last update? The server stops, the backup ${b.dataset.undo} is restored (world included) and the old version starts again.`)) return;
    try {
      await api('api/versions/undo', { backup: b.dataset.undo });
      loadVersions(false);
    } catch (err) {
      alert(err.message);
    }
  });

  // ── Discord ────────────────────────────────────────────────────────────

  const EVENT_LABELS = { join: 'Join', leave: 'Leave', death: 'Death', advancement: 'Advancement', broadcast: '/tellraw @a and /say messages', start: 'Server online', stop: 'Server offline',
    maintenance: 'Maintenance on/off', crash: 'Crash', lag: 'Lag', lagOver: 'Lag over', taskFailed: 'Task failed', backupFailed: 'Backup failed',
    trimDone: 'Outer wilds reset', diskLow: 'Disk almost full', memory: 'Memory pressure', perf: 'Distances stepped down or up' };
  /** Extra placeholders some events have, shown under their name. */
  const EVENT_VARS = { crash: '{mod} {message}', taskFailed: '{name} {message}', advancement: '{player} {advancement}', death: '{message}', broadcast: '{message} {player}' };
  const EVENT_GROUPS = [
    ['Chat & server', ['join', 'leave', 'death', 'advancement', 'broadcast', 'start', 'stop', 'maintenance']],
    ['Alerts', ['crash', 'lag', 'lagOver', 'taskFailed', 'backupFailed', 'trimDone', 'diskLow', 'memory', 'perf']],
  ];
  let discordData = null;
  /** Fabled Roots' races (its fabled_roots.<race> tags), for a Discord role each. */
  const FR_RACES = ['aetherian', 'dunesworn', 'endling', 'frostborne', 'moonshroud', 'netherian', 'oakhearted', 'orebringer', 'palehearted', 'turtlekin'];

  async function loadDiscord() {
    try {
      discordData = await api('api/discord');
    } catch (err) {
      $('pn-discord-status').textContent = err.message;
      return;
    }
    const s = discordData.settings;
    const form = $('pn-discord-form');
    for (const key of ['enabled', 'requireLink', 'requireMember', 'opsBypass', 'chatToDiscord', 'chatToGame', 'presence', 'topicOn']) form[key].checked = !!s[key];
    form.topicChannelId.value = s.topicChannelId || '';
    form.topicFormat.value = s.topicFormat || '';
    form.topicOffline.value = s.topicOffline || '';
    $('pn-dc-topic-preview').textContent = discordData.topicPreview ? 'Right now: ' + discordData.topicPreview : '';
    form.token.value = '';
    form.token.placeholder = s.token ? 'saved (paste a new one to replace it)' : 'paste the bot token';
    // A token from the container settings (DISCORD_TOKEN) can't be changed here, so there's no field for it.
    $('pn-dc-token-row').hidden = discordData.tokenFromEnv;
    form.guildId.value = s.guildId || '';
    form.channelId.value = s.channelId || '';
    form.alertChannelId.value = s.alertChannelId || '';
    form.linkChannelId.value = s.linkChannelId || '';
    form.kickMessage.value = s.kickMessage || '';
    form.gameFormat.value = s.gameFormat || '';
    form.nameFormat.value = s.nameFormat || '';
    form.linkedRoleId.value = s.linkedRoleId || '';
    form.discordInvite.value = s.discordInvite || '';
    $('pn-dc-race-roles').innerHTML = FR_RACES.map((r) => `<label class="np-form-row">${r[0].toUpperCase() + r.slice(1)}<input type="text" data-race="${r}" inputmode="numeric" placeholder="role ID" value="${esc((s.raceRoles || {})[r] || '')}"></label>`).join('');
    previewGameFormat();
    // One grid: event · channel · message, so every column lines up.
    $('pn-discord-events').innerHTML = EVENT_GROUPS.map(([title, keys]) => `<h5 class="pn-dc-group">${title}</h5>
      <div class="pn-dc-head"><span>Event</span><span>Posted in</span><span>Message</span></div>
      ${keys.map((k) => `<div class="pn-dc-event" data-event="${k}">
        <label class="np-check"><input type="checkbox" ${s.events[k] ? 'checked' : ''}><span class="pn-event-name">${EVENT_LABELS[k]}${EVENT_VARS[k] ? `<small>${esc(EVENT_VARS[k])}</small>` : ''}</span></label>
        <select title="Channel"><option value="chat">Chat channel</option><option value="alerts" ${(s.eventChannels || {})[k] === 'alerts' ? 'selected' : ''}>Alerts channel</option></select>
        <input type="text" value="${esc(s.templates[k] || '')}"></div>`).join('')}`).join('');
    const st = discordData.status || '';
    const ok = st.startsWith('Connected as');
    $('pn-discord-status').className = 'pn-dc-status ' + (ok ? 'good' : /^Couldn|^Can't/.test(st) ? 'bad' : 'off');
    $('pn-discord-status').innerHTML = `<span class="pn-dc-dot"></span>Bot: ${esc(st)}${discordData.tokenFromEnv ? ' · token from the container settings' : ''}`;
    // Not on the server, channels it can't see, missing permissions; and the link that adds it with all of them.
    const problems = discordData.problems || [];
    $('pn-dc-problems').hidden = !problems.length;
    $('pn-dc-problems').innerHTML = problems.length ? `<ul>${problems.map((p) => `<li>${esc(p)}</li>`).join('')}</ul>`
      + (discordData.inviteUrl ? `<a href="${esc(discordData.inviteUrl)}" target="_blank" rel="noopener"><i class="bi bi-box-arrow-up-right"></i> Add the bot to your server (with every permission it needs)</a>` : '') : '';
    renderDiscordLinks();
    Dirty.reset('discord');
  }

  /** The in-game look of a Discord message, drawn like the game would (same tags as the panel turns into chat). */
  const GAME_COLORS = { black: '#000000', dark_blue: '#0000AA', dark_green: '#00AA00', dark_aqua: '#00AAAA', dark_red: '#AA0000', dark_purple: '#AA00AA',
    gold: '#FFAA00', gray: '#AAAAAA', grey: '#AAAAAA', dark_gray: '#555555', dark_grey: '#555555', blue: '#5555FF', green: '#55FF55', aqua: '#55FFFF',
    red: '#FF5555', light_purple: '#FF55FF', purple: '#FF55FF', pink: '#FF55FF', orange: '#FFAA00', yellow: '#FFFF55', white: '#FFFFFF' };
  function previewGameFormat() {
    const format = $('pn-discord-form').gameFormat.value || '<aqua>Discord</aqua> <dark_gray>┃</dark_gray> %user%: %msg%';
    const stack = [{ color: '#FFFFFF', deco: '' }];
    let html = '';
    const put = (t) => { const st = stack[stack.length - 1]; if (t) html += `<span style="color:${st.color};${st.deco}">${esc(t)}</span>`; };
    const re = /<(\/?)([#a-zA-Z_:0-9]+)>|%user%|%msg%|%attachments%|\{user\}|\{message\}/g;
    let at = 0;
    for (let m; (m = re.exec(format));) {
      put(format.slice(at, m.index));
      at = m.index + m[0].length;
      if (m[0] === '%user%' || m[0] === '{user}') { put('Steve'); continue; }
      if (m[0] === '%msg%' || m[0] === '{message}') { put('Hello from Discord!'); continue; }
      if (m[0] === '%attachments%') continue;
      const tag = m[2].toLowerCase().replace(/^color:/, '');
      const top = stack[stack.length - 1];
      if (tag === 'reset') { stack.length = 1; continue; }
      if (m[1]) { if (stack.length > 1) stack.pop(); continue; }
      const deco = { bold: 'font-weight:700;', b: 'font-weight:700;', italic: 'font-style:italic;', i: 'font-style:italic;', em: 'font-style:italic;',
        underlined: 'text-decoration:underline;', u: 'text-decoration:underline;', strikethrough: 'text-decoration:line-through;', st: 'text-decoration:line-through;' }[tag];
      if (/^#[0-9a-f]{6}$/.test(tag) || GAME_COLORS[tag]) stack.push({ color: GAME_COLORS[tag] || tag, deco: top.deco });
      else if (deco) stack.push({ color: top.color, deco: top.deco + deco });
      else put(m[0]);
    }
    put(format.slice(at));
    $('pn-dc-format-preview').innerHTML = html;
  }
  $('pn-discord-form').gameFormat.addEventListener('input', previewGameFormat);

  function renderDiscordLinks() {
    const q = $('pn-link-search').value.trim().toLowerCase();
    const rows = discordData.links.filter((l) => !q || l.name.toLowerCase().includes(q) || l.discordName.toLowerCase().includes(q))
      .sort((a, b) => a.name.localeCompare(b.name));
    $('pn-link-rows').innerHTML = rows.map((l) => `<tr data-link="${esc(l.uuid)}"><td class="name">${esc(l.name)}<small>${esc(l.uuid)}</small></td>
      <td>${esc(l.discordName)}</td><td>${esc(dateTime(l.linkedAt))}</td>
      <td><div class="pn-row-actions"><button type="button" class="np-btn small danger" data-unlink>Unlink</button></div></td></tr>`).join('')
      || '<tr><td colspan="4" class="np-empty">No linked accounts yet.</td></tr>';
  }

  $('pn-link-search').addEventListener('input', renderDiscordLinks);
  $('pn-link-rows').addEventListener('click', async (e) => {
    if (!e.target.closest('[data-unlink]')) return;
    const row = e.target.closest('[data-link]');
    if (!confirm('Unlink this player? They need a new code to join again.')) return;
    try {
      await api(`api/discord/links/${encodeURIComponent(row.dataset.link)}/delete`, {});
      loadDiscord();
    } catch (err) {
      alert(err.message);
    }
  });

  $('pn-discord-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const form = e.target;
    const events = {};
    const templates = {};
    const eventChannels = {};
    $('pn-discord-events').querySelectorAll('[data-event]').forEach((row) => {
      events[row.dataset.event] = row.querySelector('input[type="checkbox"]').checked;
      eventChannels[row.dataset.event] = row.querySelector('select').value;
      templates[row.dataset.event] = row.querySelector('input[type="text"]').value;
    });
    const body = {
      enabled: form.enabled.checked, token: form.token.value.trim(), guildId: form.guildId.value.trim(), channelId: form.channelId.value.trim(), alertChannelId: form.alertChannelId.value.trim(), linkChannelId: form.linkChannelId.value.trim(),
      requireLink: form.requireLink.checked, requireMember: form.requireMember.checked, opsBypass: form.opsBypass.checked,
      bypass: [], kickMessage: form.kickMessage.value,
      notMemberMessage: discordData.settings.notMemberMessage,
      chatToDiscord: form.chatToDiscord.checked, chatToGame: form.chatToGame.checked, presence: form.presence.checked, events, templates, eventChannels,
      gameFormat: form.gameFormat.value, nameFormat: form.nameFormat.value.trim(),
      linkedRoleId: form.linkedRoleId.value.trim(), discordInvite: form.discordInvite.value.trim(),
      topicOn: form.topicOn.checked, topicChannelId: form.topicChannelId.value.trim(), topicFormat: form.topicFormat.value.trim(), topicOffline: form.topicOffline.value.trim(),
      raceRoles: Object.fromEntries([...$('pn-dc-race-roles').querySelectorAll('[data-race]')].map((i) => [i.dataset.race, i.value.trim()]).filter(([, v]) => v)),
    };
    try {
      await api('api/discord/settings', body);
      formMsg('pn-discord-msg', 'Saved.', true);
      setTimeout(loadDiscord, 2500);
    } catch (err) {
      formMsg('pn-discord-msg', err.message, false);
    }
  });

  // ── Map ────────────────────────────────────────────────────────────────

  let mapData = null;

  async function loadMap() {
    try {
      mapData = await api('api/map');
    } catch (err) {
      $('pn-map-status').textContent = err.message;
      return;
    }
    const s = mapData.settings;
    const st = mapData.status;
    const form = $('pn-map-form');
    form.enabled.checked = s.enabled;
    form.title.value = s.title;
    form.showPlayers.checked = s.showPlayers;
    form.showClaims.checked = s.showClaims;
    form.showStatus.checked = s.showStatus !== false;
    form.statusNames.checked = !!s.statusNames;
    form.showClock.checked = s.showClock !== false;
    form.hubs.value = s.hubs;
    $('pn-map-dims').innerHTML = mapData.dimensions.map((d) => `<label><input type="checkbox" value="${esc(d.id)}" ${s.hidden.includes(d.id) ? '' : 'checked'}> ${esc(d.name)}</label>`).join('');
    $('pn-map-status').textContent = `${st.status} · ${st.rendered}/${st.regions} regions · colors: ${st.colors}`;
    $('pn-map-host').innerHTML = mapData.host
      ? `Public address: <a href="https://${esc(mapData.host)}/" target="_blank" rel="noopener">${esc(mapData.host)}</a>`
      : 'Set <code>MAP_HOST</code> (e.g. map.example.com) to give the map its own address.';
    Dirty.reset('map');
  }

  $('pn-map-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const form = e.target;
    const shown = [...$('pn-map-dims').querySelectorAll('input:checked')].map((i) => i.value);
    try {
      await api('api/map/settings', {
        enabled: form.enabled.checked, title: form.title.value.trim(), showPlayers: form.showPlayers.checked, showClaims: form.showClaims.checked,
        showStatus: form.showStatus.checked, statusNames: form.statusNames.checked, showClock: form.showClock.checked,
        hubs: form.hubs.value, hidden: mapData.dimensions.map((d) => d.id).filter((id) => !shown.includes(id)),
      });
      formMsg('pn-map-msg', 'Saved.', true);
      loadMap();
    } catch (err) {
      formMsg('pn-map-msg', err.message, false);
    }
  });
  $('pn-map-rerender').addEventListener('click', async () => {
    try {
      await api('api/map/rerender', {});
      formMsg('pn-map-msg', 'Every region is drawn again over the next minutes.', true);
    } catch (err) {
      formMsg('pn-map-msg', err.message, false);
    }
  });

  // ── Players: whitelist, operators, bans ────────────────────────────────

  let plData = null;

  async function loadPlayerLists() {
    try {
      plData = await api('api/players/lists');
    } catch (err) {
      formMsg('pn-pl-msg', err.message, false);
      return;
    }
    const d = plData;
    $('pn-pl-intro').textContent = (d.running
      ? 'The server is running: changes go through its console and apply right away.'
      : 'The server is stopped: changes are written to whitelist.json, ops.json and the ban lists and apply at the next start.')
      + (d.onlineMode ? ' Names are looked up at Mojang.' : ' The server runs in offline mode, so offline UUIDs are used.')
      + (d.whitelistOn && !d.enforce ? ' Note: players already online stay until they leave (enforce-whitelist is off).' : '');
    $('pn-pl-wl').checked = d.whitelistOn;
    const head = (name) => `<img src="https://mc-heads.net/avatar/${encodeURIComponent(name)}/20" alt="" loading="lazy">`;
    const btn = (action, target, label) => `<button type="button" class="np-btn small" data-pl="${action}" data-target="${esc(target)}">${label}</button>`;
    const set = (tableId, countId, list, headRow, row, empty) => {
      $(countId).textContent = list.length ? `(${list.length})` : '';
      $(tableId).innerHTML = list.length ? `<thead><tr>${headRow}</tr></thead><tbody>${list.map(row).join('')}</tbody>` : `<tbody><tr><td>${empty}</td></tr></tbody>`;
    };
    set('pn-pl-whitelist', 'pn-pl-wl-count', d.whitelist, '<th>Player</th><th></th>',
      (p) => `<tr><td class="name">${head(p.name)}${esc(p.name)}</td><td class="num">${btn('whitelist-remove', p.name, 'Remove')}</td></tr>`, 'Nobody is on the whitelist.');
    set('pn-pl-ops', 'pn-pl-op-count', d.ops, '<th>Player</th><th class="num">Level</th><th></th>',
      (p) => `<tr><td class="name">${head(p.name)}${esc(p.name)}</td><td class="num">${p.level ?? '–'}</td><td class="num">${btn('deop', p.name, 'Remove')}</td></tr>`, 'No operators.');
    set('pn-pl-bans', 'pn-pl-ban-count', d.bans, '<th>Player</th><th>Reason</th><th>By</th><th></th>',
      (p) => `<tr><td class="name">${head(p.name)}${esc(p.name)}</td><td>${esc(p.reason || '')}</td><td>${esc(p.source || '')}<br><small>${esc(p.created || '')}</small></td><td class="num">${btn('pardon', p.name, 'Unban')}</td></tr>`, 'Nobody is banned.');
    loadOnlinePlayers(d.running, head, btn);
    set('pn-pl-ipbans', 'pn-pl-ip-count', d.ipBans, '<th>IP</th><th>Reason</th><th></th>',
      (p) => `<tr><td>${esc(p.ip)}</td><td>${esc(p.reason || '')}</td><td class="num">${btn('pardon-ip', p.ip, 'Unban')}</td></tr>`, 'No IP bans.');
  }

  /** Kick and ban for whoever is online (the mod's player list); moderation lives here, not on the Players page. */
  async function loadOnlinePlayers(running, head, btn) {
    let online = [];
    if (running) {
      try {
        online = ((await api('dashboard/api/players')).players || []).filter((p) => p.online);
      } catch (e) { /* the mod isn't answering yet */ }
    }
    $('pn-pl-online-card').hidden = !running;
    $('pn-pl-online-count').textContent = online.length ? `(${online.length})` : '';
    const banned = new Set((plData.bans || []).map((b) => b.name.toLowerCase()));
    $('pn-pl-online').innerHTML = online.length
      ? `<thead><tr><th>Player</th><th></th></tr></thead><tbody>${online.map((p) => `<tr><td class="name">${head(p.name)}${esc(p.name)}</td>
        <td class="num"><div class="pn-row-actions">${btn('kick', p.name, 'Kick')}${banned.has(p.name.toLowerCase()) ? '' : btn('ban', p.name, 'Ban')}</div></td></tr>`).join('')}</tbody>`
      : '<tbody><tr><td>Nobody is online.</td></tr></tbody>';
  }

  async function playerAction(body) {
    try {
      const res = await api('api/players/lists', body);
      formMsg('pn-pl-msg', res.console ? 'Sent to the server.' : 'Saved.', true);
      // The server writes its files a moment after the command.
      setTimeout(loadPlayerLists, res.console ? 1200 : 0);
      return true;
    } catch (err) {
      formMsg('pn-pl-msg', err.message, false);
      return false;
    }
  }

  // Several actions for one player in one go (whitelist and operator, or ban), applied one after another.
  $('pn-pl-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target;
    const target = f.plTarget.value.trim();
    const actions = [];
    if (f.wl.checked) actions.push({ action: 'whitelist-add' });
    if (f.op.checked) actions.push({ action: 'op', level: f.level.value });
    if (f.ban.checked) actions.push({ action: 'ban', reason: f.reason.value });
    if (!actions.length) return formMsg('pn-pl-msg', 'Tick at least one: whitelist, operator or ban.', false);
    if (f.ban.checked && (f.wl.checked || f.op.checked) && !confirm(`Ban ${target} and also whitelist or op them?`)) return;
    let ok = true;
    for (const a of actions) {
      if (!(await playerAction({ target, ...a }))) { ok = false; break; }
    }
    if (ok) {
      f.plTarget.value = '';
      f.reason.value = '';
    }
  });
  $('pn-pl-ipform').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target;
    if (await playerAction({ action: 'ban-ip', target: f.ip.value.trim(), reason: f.reason.value })) f.reset();
  });
  $('pn-pl-wl').addEventListener('change', (e) => playerAction({ action: e.target.checked ? 'whitelist-on' : 'whitelist-off', target: '' }));
  document.querySelector('[data-tab="players"].np-tab-panel').addEventListener('click', (e) => {
    const b = e.target.closest('[data-pl]');
    if (!b) return;
    const action = b.dataset.pl;
    if (action === 'kick' || action === 'ban') {
      const reason = prompt(`${b.textContent} ${b.dataset.target}? Optional reason:`, '');
      if (reason !== null) playerAction({ action, target: b.dataset.target, reason });
      return;
    }
    if (action.startsWith('pardon') || confirm(`${b.textContent} ${b.dataset.target}?`)) playerAction({ action, target: b.dataset.target });
  });

  /** A step log as a checklist: done steps ticked, the current one spinning, the outcome green or red. */
  function stepList(lines, running) {
    return lines.map((l, i) => {
      const last = i === lines.length - 1;
      const failed = /^(Failed|Stopped|Rolled back|The update failed)|failed/i.test(l);
      const cls = last ? (running ? 'now' : failed ? 'bad' : 'ok') : '';
      const icon = last && running ? 'bi-arrow-repeat' : last && failed ? 'bi-x-circle' : last ? 'bi-check2-circle' : 'bi-check2';
      return `<li class="${cls}"><i class="bi ${icon}"></i><span>${esc(l)}</span></li>`;
    }).join('');
  }

  // ── Background jobs (copy, zip, unzip, trim preview) ───────────────────

  /** Polls a panel job until it's done: resolves with its result, rejects with its error. */
  async function awaitJob(id, onProgress) {
    for (;;) {
      const j = await api('api/jobs/' + id);
      if (j.done) {
        if (j.error) throw new Error(j.error);
        return j.result;
      }
      if (onProgress) onProgress(j.progress);
      await new Promise((r) => setTimeout(r, 700));
    }
  }

  // ── Stats: the server over days and weeks ──────────────────────────────

  let statsRange = '24h';
  let statsData = null;
  const MARKER_TEXT = { start: 'Server started', stop: 'Server stopped', crash: 'Crash', trimDone: 'Outer wilds reset', backupFailed: 'Backup failed',
    taskFailed: 'Task failed', lag: 'Lag', lagOver: 'Lag over', diskLow: 'Disk almost full', memory: 'Memory pressure' };
  const svgNs = 'http://www.w3.org/2000/svg';

  async function loadStats() {
    try {
      const [series, heat] = await Promise.all([api('api/stats?range=' + statsRange), api('api/stats/heatmap?days=30')]);
      statsData = series;
      renderStats(series);
      renderHeat(heat);
    } catch (err) {
      $('pn-stats-summary').textContent = err.message;
    }
  }

  $('pn-stats-range').addEventListener('click', (e) => {
    const b = e.target.closest('[data-range]');
    if (!b) return;
    statsRange = b.dataset.range;
    $('pn-stats-range').querySelectorAll('button').forEach((x) => x.classList.toggle('active', x === b));
    loadStats();
  });

  const fmtTime = (t, span) => new Date(t).toLocaleString([], span > 2 * 86400000
    ? { day: 'numeric', month: 'short' } : { hour: '2-digit', minute: '2-digit' });
  const fmtFull = (t) => new Date(t).toLocaleString([], { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
  const num1 = (v) => v == null ? '–' : (Math.round(v * 10) / 10).toLocaleString();

  function renderStats(d) {
    const pts = d.points;
    const span = d.to - d.from;
    const up = pts.length ? pts.reduce((s, p) => s + (p.up || 0), 0) / pts.length * 100 : 0;
    const withTps = pts.filter((p) => p.tps != null);
    const avgTps = withTps.length ? withTps.reduce((s, p) => s + p.tps, 0) / withTps.length : null;
    const peak = pts.reduce((m, p) => Math.max(m, p.players || 0), 0);
    const crashes = d.markers.filter((m) => m.type === 'crash').length;
    const f = d.forecast;
    const forecast = f && f.days != null ? `the drive is full in about ${f.days.toLocaleString()} days at the current rate (${bytes(f.perDay)} a day)`
      : f ? 'free disk space isn\'t shrinking' : 'not enough data for a disk forecast yet';
    $('pn-stats-summary').textContent = pts.length
      ? `Online ${num1(up)}% of the time · average ${num1(avgTps)} TPS · up to ${peak} players · ${crashes} crash${crashes === 1 ? '' : 'es'} · ${forecast}.`
      : 'No data yet: the panel records a point every minute while it runs.';
    const charts = [
      { title: 'TPS', series: [{ key: 'tps', label: 'TPS', color: 'var(--series-1)' }], max: 20, fmt: (v) => num1(v), ref: { v: 20, label: 'target 20' } },
      { title: 'Milliseconds per tick', series: [{ key: 'mspt', label: 'average', color: 'var(--series-1)' }, { key: 'msptMax', label: 'slowest tick', color: 'var(--series-2)' }],
        fmt: (v) => num1(v) + ' ms', ref: { v: 50, label: '50 ms budget' } },
      { title: 'Players online', series: [{ key: 'players', label: 'players', color: 'var(--series-1)' }], fmt: (v) => Math.round(v), area: true, integer: true },
      { title: 'Memory after garbage collection', series: [{ key: 'heapPct', label: '% of max', color: 'var(--series-1)' }], max: 100, fmt: (v) => Math.round(v) + '%',
        ref: { v: 90, label: '90%' }, map: (p) => p.heapLive != null && p.heapMax ? { heapPct: p.heapLive / p.heapMax * 100 } : {} },
      { title: 'Free disk space', series: [{ key: 'freeGb', label: 'free', color: 'var(--series-1)' }], fmt: (v) => num1(v) + ' GB', area: true,
        map: (p) => p.free != null ? { freeGb: p.free / 1073741824 } : {} },
    ];
    $('pn-stats-charts').innerHTML = '';
    // All cards go in first so the grid has its final layout; then each chart is measured and drawn.
    const cards = charts.map(() => {
      const card = document.createElement('section');
      card.className = 'np-card pn-chart';
      $('pn-stats-charts').appendChild(card);
      return card;
    });
    charts.forEach((c, i) => lineChart(cards[i], c, pts.map((p) => Object.assign({}, p, c.map ? c.map(p) : {})), d, span));
    renderStatsTable(pts);
  }

  /** One card with an SVG line chart: thin lines, recessive grid, crosshair + tooltip, markers along the bottom. */
  function lineChart(card, c, pts, d, span) {
    const latest = [...pts].reverse().find((p) => p[c.series[0].key] != null);
    // Reference lines are named in the legend row, never on the plot, so they can't cover the data.
    const refKey = c.ref ? `<span class="ref">${esc(c.ref.label)}</span>` : '';
    card.innerHTML = `<div class="pn-chart-head"><h4>${esc(c.title)}</h4>${c.series.length > 1
      ? `<div class="pn-legend">${c.series.map((s) => `<span style="--c:${s.color}">${esc(s.label)}</span>`).join('')}${refKey}</div>`
      : `<div class="pn-legend">${refKey}<span class="now">${latest ? 'now ' + esc(c.fmt(latest[c.series[0].key])) : ''}</span></div>`}</div>`;
    // Drawn at its real width, so text and lines keep their size (redrawn on resize).
    const cs = getComputedStyle(card);
    const W = Math.max(260, Math.round(card.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight)));
    const H = 150, L = 46, R = 8, T = 8, B = 22;
    const svg = document.createElementNS(svgNs, 'svg');
    svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
    svg.setAttribute('width', W);
    svg.setAttribute('height', H);
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', c.title + ' over the selected time range');
    let max = c.max || 0;
    for (const p of pts) for (const s of c.series) if (p[s.key] != null) max = Math.max(max, p[s.key]);
    if (!c.max) max = niceMax(max || 1, c.integer);
    const x = (t) => L + (t - d.from) / (d.to - d.from) * (W - L - R);
    const y = (v) => T + (1 - v / max) * (H - T - B);
    let html = '<g class="grid">';
    const ticks = 4;
    for (let i = 0; i <= ticks; i++) {
      const v = max * i / ticks;
      html += `<line x1="${L}" x2="${W - R}" y1="${y(v)}" y2="${y(v)}"/>`;
    }
    html += '</g><g class="axis">';
    for (let i = 0; i <= ticks; i += 2) html += `<text x="${L - 6}" y="${y(max * i / ticks) + 4}" text-anchor="end">${esc(c.fmt(max * i / ticks))}</text>`;
    // As many time labels as fit (about one per 90 px).
    const xt = Math.max(1, Math.min(4, Math.floor((W - L) / 90)));
    for (let i = 0; i <= xt; i++) {
      const t = d.from + (d.to - d.from) * i / xt;
      html += `<text x="${x(t)}" y="${H - 6}" text-anchor="${i === 0 ? 'start' : i === xt ? 'end' : 'middle'}">${esc(fmtTime(t, span))}</text>`;
    }
    html += '</g>';
    if (c.ref && c.ref.v <= max) html += `<line class="ref" x1="${L}" x2="${W - R}" y1="${y(c.ref.v)}" y2="${y(c.ref.v)}"/>`;
    for (const m of d.markers) {
      if (m.t < d.from) continue;
      html += `<line class="marker ${m.type === 'crash' ? 'crash' : ''}" x1="${x(m.t)}" x2="${x(m.t)}" y1="${H - B - 6}" y2="${H - B}"/>`;
    }
    // Lines break where there's no data (server off) or a gap longer than two steps.
    for (const s of c.series) {
      let path = '', area = '', prevT = null, segStart = null;
      for (const p of pts) {
        const v = p[s.key];
        if (v == null || (prevT != null && p.t - prevT > 2.5 * d.step)) {
          if (segStart != null && c.area) area += `L${x(prevT)},${y(0)}L${segStart},${y(0)}Z`;
          segStart = null;
          if (v == null) { prevT = null; continue; }
        }
        if (segStart == null) { segStart = x(p.t); path += `M${x(p.t)},${y(v)}`; area += `M${x(p.t)},${y(v)}`; } else { path += `L${x(p.t)},${y(v)}`; area += `L${x(p.t)},${y(v)}`; }
        prevT = p.t;
      }
      if (segStart != null && c.area) area += `L${x(prevT)},${y(0)}L${segStart},${y(0)}Z`;
      if (c.area && area) html += `<path class="area" d="${area}" fill="${s.color}"/>`;
      html += `<path class="series" d="${path}" stroke="${s.color}" />`;
    }
    html += `<line class="cross" y1="${T}" y2="${H - B}" visibility="hidden"/>`;
    svg.innerHTML = html;
    card.appendChild(svg);
    const tip = document.createElement('div');
    tip.className = 'pn-tip';
    tip.hidden = true;
    card.appendChild(tip);
    const cross = svg.querySelector('.cross');
    svg.addEventListener('pointermove', (e) => {
      const r = svg.getBoundingClientRect();
      const t = d.from + ((e.clientX - r.left) / r.width * W - L) / (W - L - R) * (d.to - d.from);
      let best = null;
      for (const p of pts) if (!best || Math.abs(p.t - t) < Math.abs(best.t - t)) best = p;
      if (!best || Math.abs(best.t - t) > 3 * d.step) { tip.hidden = true; cross.setAttribute('visibility', 'hidden'); return; }
      const near = d.markers.filter((m) => Math.abs(m.t - best.t) <= d.step);
      cross.setAttribute('x1', x(best.t));
      cross.setAttribute('x2', x(best.t));
      cross.setAttribute('visibility', 'visible');
      tip.innerHTML = `<b>${esc(fmtFull(best.t))}</b><br>` + (best[c.series[0].key] == null && (best.up === 0 || best.paused) ? (best.up === 0 ? 'Server offline' : 'Idle: nobody online, ticks paused')
        : c.series.map((s) => `<i style="--c:${s.color}"></i>${esc(s.label)}: <b>${esc(best[s.key] == null ? '–' : c.fmt(best[s.key]))}</b>`).join('<br>'))
        + near.map((m) => `<br>⚑ ${esc(MARKER_TEXT[m.type] || m.type)}${m.text ? ': ' + esc(m.text) : ''}`).join('');
      tip.hidden = false;
      const px = x(best.t);
      const left = px + 14 + tip.offsetWidth > r.width ? px - tip.offsetWidth - 10 : px + 14;
      tip.style.left = Math.max(0, left + svg.offsetLeft) + 'px';
      tip.style.top = (svg.offsetTop + 10) + 'px';
    });
    svg.addEventListener('pointerleave', () => { tip.hidden = true; cross.setAttribute('visibility', 'hidden'); });
  }

  // Redrawn when the chart area changes width (window resize, the page's scrollbar appearing, the sidebar).
  let statsResize = null;
  let statsWidth = 0;
  new ResizeObserver((entries) => {
    const w = Math.round(entries[0].contentRect.width);
    if (!w || w === statsWidth) return;
    statsWidth = w;
    clearTimeout(statsResize);
    statsResize = setTimeout(() => { if (activeTab === 'stats' && statsData) renderStats(statsData); }, 120);
  }).observe($('pn-stats-charts'));

  function niceMax(v, integer) {
    const p = 10 ** Math.floor(Math.log10(v));
    const n = [1, 2, 2.5, 5, 10].map((k) => k * p).find((k) => k >= v) || v;
    return integer ? Math.max(4, Math.ceil(n)) : n;
  }

  function renderHeat(h) {
    $('pn-heat-zone').textContent = h.zone;
    const max = Math.max(1, ...h.grid.flat().filter((v) => v != null));
    const days = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
    let html = '<span></span>' + Array.from({ length: 24 }, (_, i) => `<span class="hr">${i}</span>`).join('');
    h.grid.forEach((row, d) => {
      html += `<span class="lbl">${days[d]}</span>` + row.map((v, hr) => v == null
        ? `<span class="cell empty" title="${days[d]} ${hr}:00 – no data"></span>`
        : `<span class="cell" style="--v:${Math.round(8 + v / max * 92)}%" title="${days[d]} ${hr}:00 – ${num1(v)} players on average"></span>`).join('');
    });
    $('pn-heat').innerHTML = html;
    $('pn-heat').insertAdjacentHTML('afterend', '');
    let scale = $('pn-heat').nextElementSibling;
    if (!scale || !scale.classList.contains('pn-heat-scale')) {
      scale = document.createElement('div');
      scale.className = 'pn-heat-scale';
      $('pn-heat').after(scale);
    }
    scale.innerHTML = `<span>0</span><span class="bar"></span><span>${num1(max)} players</span>`;
  }

  /** The table: one row per day (newest first); a day opens to its hours when the data is that fine. */
  function renderStatsTable(pts) {
    const dayKey = (t) => { const d = new Date(t); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; };
    const groups = new Map();
    for (const p of pts) {
      const k = dayKey(p.t);
      if (!groups.has(k)) groups.set(k, []);
      groups.get(k).push(p);
    }
    // Crashes, and how often dynamic distances stepped down (Settings → Tuning).
    const crashesOn = (from, to) => {
      const ms = (statsData ? statsData.markers : []).filter((m) => m.t >= from && m.t < to);
      return [ms.filter((m) => m.type === 'crash').length, ms.filter((m) => m.type === 'perf' && /^Lag/.test(m.text || '')).length];
    };
    const summary = (list) => {
      const avg = (k) => { const v = list.filter((p) => p[k] != null); return v.length ? v.reduce((s, p) => s + p[k], 0) / v.length : null; };
      const max = (k) => { const v = list.filter((p) => p[k] != null); return v.length ? Math.max(...v.map((p) => p[k])) : null; };
      const mem = list.filter((p) => p.heapLive != null && p.heapMax);
      const disk = [...list].reverse().find((p) => p.free != null);
      return { up: avg('up'), tps: avg('tps'), mspt: avg('mspt'), msptMax: max('msptMax'), players: max('players'),
        mem: mem.length ? Math.max(...mem.map((p) => p.heapLive / p.heapMax * 100)) : null, free: disk ? disk.free : null };
    };
    const cells = (s, label, [crashes, steps]) => `<td>${label}</td><td class="num">${s.up == null ? '–' : Math.round(s.up * 100) + '%'}</td><td class="num">${num1(s.tps)}</td>
      <td class="num">${num1(s.mspt)}</td><td class="num">${num1(s.msptMax)}</td><td class="num">${s.players ?? '–'}</td><td class="num">${crashes || ''}</td><td class="num">${steps || ''}</td>
      <td class="num">${s.mem == null ? '–' : Math.round(s.mem) + '%'}</td><td class="num">${s.free == null ? '–' : bytes(s.free)}</td>`;
    const fine = statsData && statsData.step <= 3600000;
    const rows = [...groups].reverse().map(([key, list]) => {
      const start = new Date(list[0].t); start.setHours(0, 0, 0, 0);
      const label = start.toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' });
      const day = `<tr class="pn-day" data-day="${key}">${cells(summary(list), `${fine ? '<i class="bi bi-chevron-right"></i>' : ''}<b>${esc(label)}</b>`, crashesOn(start.getTime(), start.getTime() + 86400000))}</tr>`;
      if (!fine) return day;
      const hours = new Map();
      for (const p of list) {
        const h = new Date(p.t).getHours();
        if (!hours.has(h)) hours.set(h, []);
        hours.get(h).push(p);
      }
      return day + [...hours].reverse().map(([h, hl]) => {
        const from = new Date(start); from.setHours(h);
        return `<tr class="pn-hour" data-of="${key}" hidden>${cells(summary(hl), `${String(h).padStart(2, '0')}:00`, crashesOn(from.getTime(), from.getTime() + 3600000))}</tr>`;
      }).join('');
    }).join('');
    $('pn-stats-table').innerHTML = `<thead><tr><th>Day</th><th class="num">Online</th><th class="num">TPS</th><th class="num">MSPT</th><th class="num">Slowest tick</th>
      <th class="num">Peak players</th><th class="num">Crashes</th><th class="num" title="How often dynamic distances stepped down (Settings → Tuning)">Lag steps</th><th class="num">Peak memory</th><th class="num">Free disk</th></tr></thead><tbody>${rows}</tbody>`;
  }

  $('pn-stats-table').addEventListener('click', (e) => {
    const row = e.target.closest('tr.pn-day');
    if (!row || !row.querySelector('.bi-chevron-right')) return;
    const open = row.classList.toggle('open');
    $('pn-stats-table').querySelectorAll(`tr[data-of="${row.dataset.day}"]`).forEach((r) => { r.hidden = !open; });
  });


  // ── World: borders, pregenerate and trim ───────────────────────────────

  let worldDims = [];
  let worldBorders = {};
  let worldTicks = 0;
  let bordersDirty = false;

  async function loadWorld() {
    try {
      const data = await api('api/trim');
      worldDims = data.dimensions;
      worldBorders = data.borders || {};
      renderBorders();
      renderWilds(data.wilds || {});
      renderTrim(data);
    } catch (err) {
      formMsg('pn-borders-msg', err.message, false);
    }
    loadPregen();
  }

  function worldTick() {
    if (++worldTicks % 2) return;
    loadPregen();
    api('api/trim').then((d) => renderTrim(d)).catch(() => {});
  }

  /** One row per dimension; a dimension without saved borders shows a suggestion from its world border. */
  function renderBorders() {
    bordersDirty = false;
    $('pn-borders').querySelector('tbody').innerHTML = worldDims.map((d) => {
      const b = worldBorders[d.id];
      const vanilla = d.border && d.border.size < 59999968 ? d.border : null;
      const v = b || { shape: 'square', x: Math.round(vanilla ? vanilla.x : 0), z: Math.round(vanilla ? vanilla.z : 0),
        soft: vanilla ? Math.round(vanilla.size / 4) : 2000, hard: vanilla ? Math.round(vanilla.size / 2) : 0, pregen: true, worldBorder: true, showSoft: true };
      // A <label>, so clicking the switch's track toggles the hidden checkbox.
      const sw = (f, on) => `<label class="np-switch"><input type="checkbox" data-f="${f}" ${on ? 'checked' : ''}><span></span></label>`;
      return `<tr data-dim="${esc(d.id)}" class="${b ? '' : 'off'}">
        <td class="name">${esc(d.name)}<small>${esc(d.id)}</small></td>
        <td>${sw('on', !!b)}</td>
        <td><select data-f="shape"><option value="square" ${v.shape === 'square' ? 'selected' : ''}>Square</option><option value="circle" ${v.shape === 'circle' ? 'selected' : ''}>Circle</option></select></td>
        <td><input type="number" data-f="x" value="${v.x}"></td>
        <td><input type="number" data-f="z" value="${v.z}"></td>
        <td><input type="number" data-f="soft" min="16" value="${v.soft}"></td>
        <td><input type="number" data-f="hard" min="0" value="${v.hard}"></td>
        <td>${sw('pregen', v.pregen !== false)}</td>
        <td>${sw('worldBorder', v.worldBorder !== false)}</td>
        <td>${sw('showSoft', v.showSoft !== false)}</td>
      </tr>`;
    }).join('') || '<tr><td colspan="10">No dimensions found yet: start the server once.</td></tr>';
    const sel = $('pn-trim-dim');
    const chosen = sel.value;
    const bordered = worldDims.filter((d) => worldBorders[d.id]);
    sel.innerHTML = bordered.map((d) => `<option value="${esc(d.id)}">${esc(d.name)}</option>`).join('') || '<option value="">No borders saved</option>';
    if (chosen && bordered.some((d) => d.id === chosen)) sel.value = chosen;
    Dirty.reset('borders');
  }

  $('pn-borders').addEventListener('input', (e) => {
    bordersDirty = true;
    const row = e.target.closest('tr');
    if (row && e.target.dataset.f === 'on') row.classList.toggle('off', !e.target.checked);
  });

  $('pn-borders-save').addEventListener('click', async () => {
    const rows = [...$('pn-borders').querySelectorAll('tr[data-dim]')];
    try {
      for (const row of rows) {
        const f = (name) => row.querySelector(`[data-f="${name}"]`);
        const on = f('on').checked;
        if (!on && !worldBorders[row.dataset.dim]) continue;
        const border = on ? { shape: f('shape').value, x: Number(f('x').value), z: Number(f('z').value), soft: Number(f('soft').value),
          hard: Number(f('hard').value) || 0, pregen: f('pregen').checked, worldBorder: f('worldBorder').checked, showSoft: f('showSoft').checked } : null;
        const res = await api('api/borders/save', { dimension: row.dataset.dim, border });
        worldBorders = res.borders;
      }
      renderBorders();
      formMsg('pn-borders-msg', 'Saved. A running server picks the hard border up within a few seconds.', true);
    } catch (err) {
      formMsg('pn-borders-msg', err.message, false);
    }
  });

  function renderWilds(w) {
    const f = $('pn-wilds-form');
    for (const k of ['enabled', 'sound', 'postEffect']) f.elements[k].checked = !!w[k];
    for (const k of ['enteredText', 'enteredColor', 'exitedText', 'exitedColor', 'postEffectId']) f.elements[k].value = w[k] ?? '';
    f.elements.postEffectId.disabled = !w.postEffect;
    Dirty.reset('wilds');
  }

  $('pn-wilds-form').addEventListener('change', (e) => {
    if (e.target.name === 'postEffect') e.currentTarget.elements.postEffectId.disabled = !e.target.checked;
  });

  $('pn-wilds-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.currentTarget;
    const w = { enabled: f.elements.enabled.checked, sound: f.elements.sound.checked, postEffect: f.elements.postEffect.checked };
    for (const k of ['enteredText', 'enteredColor', 'exitedText', 'exitedColor', 'postEffectId']) w[k] = f.elements[k].value;
    try {
      const res = await api('api/borders/wilds', w);
      renderWilds(res.wilds);
      formMsg('pn-wilds-msg', 'Saved.', true);
    } catch (err) {
      formMsg('pn-wilds-msg', err.message, false);
    }
  });

  const duration2 = (sec) => sec < 0 ? '–' : sec >= 3600 ? `${Math.floor(sec / 3600)} h ${Math.round(sec % 3600 / 60)} min` : sec >= 60 ? `${Math.round(sec / 60)} min` : `${sec} s`;
  const dimName = (id) => (worldDims.find((d) => d.id === id) || { name: id }).name;

  async function loadPregen() {
    let st;
    try {
      st = await api('dashboard/api/pregen');
    } catch (err) {
      $('pn-pregen-status').textContent = /not available|doesn't answer|runs/.test(err.message) ? 'Pregenerating needs the server to run (with the Nice Control Center mod 1.5.0 or newer).' : err.message;
      return;
    }
    const show = st.running ? st : st.last;
    const queued = (st.queued || []).map(dimName);
    if (!show) {
      $('pn-pregen-status').textContent = 'Nothing pregenerated yet.';
    } else {
      const pct = show.total ? Math.round(show.done / show.total * 100) : 0;
      const ring = show.skipRadius ? ` outside ${show.skipRadius}` : '';
      $('pn-pregen-status').innerHTML = `<b>${st.running ? (show.paused ? 'Paused: ' + esc(show.paused) : 'Pregenerating') : 'Last run (' + esc(show.result || 'stopped') + ')'}</b> · ${esc(dimName(show.dimension))} · ${show.shape} radius ${show.radius}${ring} around ${show.x}, ${show.z}<br>
        ${show.counting ? 'Counting chunks…' : `${show.done.toLocaleString()} of ${show.total.toLocaleString()} chunks (${pct}%) · ${show.perSecond}/s${st.running ? ' · about ' + duration2(show.etaSeconds) + ' left' : ''}${show.failed ? ` · ${show.failed} failed` : ''}`}
        ${queued.length ? `<br>Then: ${queued.map(esc).join(', ')}` : ''}
        <div class="pn-bar"><span style="width:${pct}%"></span></div>`;
    }
    $('pn-pregen-start').disabled = st.running;
    $('pn-pregen-stop').disabled = !st.running;
  }

  async function modPost(path, body) {
    const res = await fetch('dashboard/' + path, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-NCC': '1' }, body: JSON.stringify(body) });
    const data = await res.json().catch(() => ({}));
    if (!res.ok || data.error) throw new Error(data.error || res.statusText);
    return data;
  }

  $('pn-pregen-start').addEventListener('click', async () => {
    if (bordersDirty) return formMsg('pn-pregen-msg', 'Save the borders first.', false);
    const wilds = $('pn-pregen-wilds').checked;
    const jobs = Object.entries(worldBorders).filter(([, b]) => b.pregen !== false && (!wilds || b.hard > b.soft));
    if (!jobs.length) return formMsg('pn-pregen-msg', wilds ? 'No dimension has outer wilds to pregenerate (a hard border outside the soft one, with Pregen on).' : 'No dimension with borders has Pregen on.', false);
    const chunks = jobs.reduce((sum, [, b]) => {
      const area = (r) => b.shape === 'circle' ? Math.PI * (r / 16) ** 2 : (2 * r / 16) ** 2;
      const reach = b.hard > 0 ? b.hard : b.soft;
      return sum + area(reach) - (wilds ? area(b.soft) : 0);
    }, 0);
    if (!confirm(`Pregenerate about ${Math.round(chunks).toLocaleString()} chunks in ${jobs.map(([d]) => dimName(d)).join(', ')}? Large areas take hours; it only uses the server's spare time.`)) return;
    try {
      for (const [dim, b] of jobs) {
        await modPost('api/pregen/start', { dimension: dim, x: b.x, z: b.z, radius: b.hard > 0 ? b.hard : b.soft, skipRadius: wilds ? b.soft : 0, shape: b.shape, queue: true, players: $('pn-pregen-players').value });
      }
      formMsg('pn-pregen-msg', jobs.length > 1 ? `Started; ${jobs.length} dimensions, one after another.` : 'Started.', true);
      loadPregen();
    } catch (err) {
      formMsg('pn-pregen-msg', err.message, false);
    }
  });
  $('pn-pregen-stop').addEventListener('click', async () => {
    try {
      await modPost('api/pregen/stop', {});
      loadPregen();
    } catch (err) {
      formMsg('pn-pregen-msg', err.message, false);
    }
  });

  function renderTrim(st) {
    $('pn-trim-log').hidden = !st.log.length;
    $('pn-trim-log').innerHTML = stepList(st.log, st.running);
    $('pn-trim-run').disabled = st.running;
    const msg = $('pn-trim-msg');
    if (st.running) {
      formMsg('pn-trim-msg', st.phase || 'Trimming…', true);
      msg.dataset.progress = '1';
    } else if (msg.dataset.progress) {
      // Done: the progress line gives way to the outcome (also shown as the last step).
      delete msg.dataset.progress;
      formMsg('pn-trim-msg', st.result || '', !/^Failed/.test(st.result || ''));
    }
  }

  $('pn-trim-preview').addEventListener('click', async () => {
    const dim = $('pn-trim-dim').value;
    if (!worldBorders[dim]) return formMsg('pn-trim-msg', 'Save borders for a dimension first.', false);
    formMsg('pn-trim-msg', 'Looking at the region files…', true);
    try {
      const { job } = await api('api/trim/preview', { dimension: dim, keepVisitedMinutes: $('pn-trim-keep').value });
      const p = await awaitJob(job, (t) => formMsg('pn-trim-msg', t || 'Looking at the region files…', true));
      const nothing = !p.regions && !p.partialRegions;
      formMsg('pn-trim-msg', nothing ? `Nothing lies outside the soft border in ${dimName(dim)}.`
        : `${dimName(dim)}: ${p.chunks.toLocaleString()} chunks would go (${p.regions} whole region file${p.regions === 1 ? '' : 's'} and parts of ${p.partialRegions} more), about ${bytes(p.bytes)}.`, !nothing);
    } catch (err) {
      formMsg('pn-trim-msg', err.message, false);
    }
  });

  $('pn-trim-run').addEventListener('click', async () => {
    const dims = Object.keys(worldBorders).map(dimName);
    if (!dims.length) return formMsg('pn-trim-msg', 'Save borders for a dimension first.', false);
    const backupFirst = $('pn-trim-backup').checked;
    if (!confirm(`Reset the outer wilds now? Everything outside the soft border in ${dims.join(', ')} is deleted${backupFirst ? ' (a backup is made first)' : ' (no backup)'}. The server must be stopped.`)) return;
    try {
      await api('api/trim/run', { keepVisitedMinutes: $('pn-trim-keep').value, backupFirst });
      $('pn-trim-run').disabled = true;
      formMsg('pn-trim-msg', 'Trimming…', true);
      $('pn-trim-msg').dataset.progress = '1';
    } catch (err) {
      formMsg('pn-trim-msg', err.message, false);
    }
  });

  // ── Home: needs attention, setup checklist ───────────────────────────────

  const SETUP_KEY = 'pn-setup-hidden';

  async function loadHome() {
    if (!can('server.view')) return;
    let d;
    try {
      d = await api('api/home');
    } catch (e) {
      return;
    }
    renderHero(d);
    // Updates aren't listed: the Updates entry in the sidebar already counts them.
    const items = [...d.attention];
    $('pn-attention').hidden = !items.length;
    $('pn-attn-list').innerHTML = items.map((i) => `<li class="pn-attn ${esc(i.level)}"><i class="bi ${i.level === 'bad' ? 'bi-x-circle-fill' : i.level === 'warn' ? 'bi-exclamation-triangle-fill' : 'bi-info-circle-fill'}"></i>
      <span>${esc(i.text)}</span><button type="button" class="np-btn small" data-goto="${esc(i.tab)}">${esc(i.action)} <i class="bi bi-arrow-right"></i></button></li>`).join('');
    let hidden = false;
    try { hidden = localStorage.getItem(SETUP_KEY) === '1'; } catch (e) { /* shown */ }
    const open = d.setup.filter((x) => !x.done).length;
    $('pn-setup').hidden = hidden || !open;
    $('pn-setup-count').textContent = `${d.setup.length - open} of ${d.setup.length} done`;
    $('pn-setup-list').innerHTML = d.setup.map((x) => `<li class="${x.done ? 'done' : ''}"><i class="bi ${x.done ? 'bi-check-circle-fill' : 'bi-circle'}"></i>
      <span>${esc(x.text)}</span>${x.done ? '' : `<button type="button" class="np-btn small" data-goto="${esc(x.tab)}">Set up <i class="bi bi-arrow-right"></i></button>`}</li>`).join('');
  }

  /** The greeting banner on Home: time of day, the server list message as players see it, a few facts. */
  function renderHero(d) {
    const h = new Date().getHours();
    const part = h < 5 ? 'Up late' : h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening';
    $('pn-hero-greet').textContent = `${part}, ${me ? me.user : ''}`;
    $('pn-hero-motd').innerHTML = motdHtml(d.motd);
    // Server state and the game clock are always in the navbar, so they aren't repeated here.
    heroFacts = [];
    if (d.players != null) heroFacts.push(`<span class="pn-hero-fact"><i class="bi bi-people-fill"></i>${d.players} / ${esc(d.maxPlayers)} playing</span>`);
    if (d.uptime7d != null) heroFacts.push(`<span class="pn-hero-fact"><i class="bi bi-activity"></i>${d.uptime7d.toLocaleString(undefined, { maximumFractionDigits: 1 })}% up this week</span>`);
    syncHeroHealth();
  }
  let heroFacts = [];

  /**
   * The mod's health banner ("The server is running smoothly", TPS line, summary, time window) shown
   * in the greeting card on Home; the frame's own banner is hidden on that page (EMBED_CSS).
   */
  function syncHeroHealth() {
    let doc = null;
    try { doc = $('pn-dashboard-frame').contentDocument; } catch (e) { /* not loaded */ }
    const on = activeTab === 'mod:overview';
    if (doc && doc.body) doc.body.classList.toggle('pn-overview', on);
    const title = on && doc && doc.getElementById('np-health-title');
    const health = !!(title && serverRunning && title.textContent.trim() && !/^Waiting/.test(title.textContent));
    $('pn-hero-health').hidden = !health;
    $('pn-hero-window').hidden = !health;
    const extra = [];
    if (health) {
      const dot = 'np-status-dot ' + doc.getElementById('np-status-dot').className.replace(/^np-status-dot\s*/, '');
      if ($('pn-hero-dot').className !== dot) $('pn-hero-dot').className = dot;
      for (const [mine, theirs] of [['pn-hero-title', 'np-health-title'], ['pn-hero-headline', 'np-health-headline'], ['pn-hero-summary', 'np-health-summary']]) {
        const text = doc.getElementById(theirs).textContent;
        if ($(mine).textContent !== text) $(mine).textContent = text;
      }
      const win = [...doc.querySelectorAll('#np-window button[data-window]')]
        .map((b) => `<button type="button" role="tab" data-window="${esc(b.dataset.window)}" class="${b.classList.contains('active') ? 'active' : ''}">${esc(b.textContent)}</button>`).join('');
      if ($('pn-hero-window').innerHTML !== win) $('pn-hero-window').innerHTML = win;
      // Version, mods and a running lag report from the banner's chips (the server name is the message above).
      doc.querySelectorAll('#np-meta .np-tag').forEach((t) => {
        if (t.querySelector('.bi-hdd-network, .bi-clock-history')) return;
        extra.push(`<span class="pn-hero-fact${t.classList.contains('np-tag--lag') ? ' warn' : ''}">${t.innerHTML}</span>`);
      });
    }
    const facts = heroFacts.concat(extra).join('');
    if ($('pn-hero-facts').innerHTML !== facts) $('pn-hero-facts').innerHTML = facts;
  }
  $('pn-hero-window').addEventListener('click', (e) => {
    const b = e.target.closest('button[data-window]');
    if (!b) return;
    try {
      const theirs = $('pn-dashboard-frame').contentDocument.querySelector(`#np-window button[data-window="${b.dataset.window}"]`);
      if (theirs) theirs.click();
    } catch (err) { /* not loaded */ }
    syncHeroHealth();
  });

  $('pn-home').addEventListener('click', (e) => {
    const b = e.target.closest('[data-goto]');
    if (b) selectTab(b.dataset.goto);
  });
  $('pn-setup-hide').addEventListener('click', () => {
    try { localStorage.setItem(SETUP_KEY, '1'); } catch (e) { /* hidden for now */ }
    $('pn-setup').hidden = true;
  });

  // ── Settings → Tuning ─────────────────────────────────────────────────

  let perfSettings = null;

  /** Form fields are named "section.key"; values go back into the settings object. */
  function fillPerf(s) {
    const form = $('pn-perf-form');
    renderRules(s.rules || []);
    renderJunk((s.despawn && s.despawn.items) || []);
    renderSaveLimits((s.saveLimits && s.saveLimits.limits) || {});
    form.querySelectorAll('[name]').forEach((el) => {
      const [sec, key] = el.name.split('.');
      const v = s[sec] ? s[sec][key] : undefined;
      if (el.type === 'checkbox') el.checked = !!v;
      else el.value = v ?? '';
    });
    const editable = can('settings.server');
    form.querySelectorAll('input, button').forEach((el) => { el.disabled = !editable; });
    perfNeeds();
    Dirty.reset('perf');
  }

  /** Settings that only matter while their switch is on are greyed out (the switch itself stays usable). */
  function perfNeeds() {
    const form = $('pn-perf-form');
    form.querySelectorAll('[data-needs] input').forEach((el) => {
      let on = true;
      for (let g = el.closest('[data-needs]'); g; g = g.parentElement.closest('[data-needs]')) {
        if (g.dataset.needs !== el.name && !form.elements[g.dataset.needs]?.checked) on = false;
      }
      el.closest('.np-form-row')?.classList.toggle('pn-off', !on);
    });
  }
  $('pn-perf-form').addEventListener('change', perfNeeds);

  async function loadTuning() {
    try {
      perfSettings = await api('api/perf/settings');
      fillPerf(perfSettings);
    } catch (e) {
      formMsg('pn-perf-msg', e.message, false);
    }
    renderFrozen();
    loadPerfLive();
    loadHotspots();
    loadLoaded();
    loadTypes();
  }

  // Mob limits, junk items and save limits: lists the form can't hold in "section.key" fields.

  const GROUP_LABELS = { '#animals': 'All animals', '#monsters': 'All monsters', '#villagers': 'All villagers', '#water': 'All water mobs', '#ambient': 'All bats & ambient', '*': 'Any other mob' };
  const shortId = (id) => String(id || '').replace(/^minecraft:/, '');
  let typesLoaded = false;

  /** Mob, entity and item names for the pickers; typed ids work too while the server is off. */
  async function loadTypes() {
    if (typesLoaded) return;
    try {
      const [e, i] = await Promise.all([api('dashboard/api/entitytypes'), api('dashboard/api/itemtypes')]);
      const mobs = e.types.filter((t) => t.category !== 'misc' || /villager|golem|trader/.test(t.id));
      $('pn-mob-types').innerHTML = Object.entries(GROUP_LABELS).map(([id, label]) => `<option value="${esc(id)}">${esc(label)}</option>`).join('')
        + mobs.map((t) => `<option value="${esc(shortId(t.id))}">${esc(t.name)}</option>`).join('');
      $('pn-entity-types').innerHTML = e.types.map((t) => `<option value="${esc(shortId(t.id))}">${esc(t.name)}</option>`).join('');
      $('pn-item-types').innerHTML = i.items.map((t) => `<option value="${esc(shortId(t.id))}">${esc(t.name)}</option>`).join('');
      typesLoaded = true;
    } catch (err) { /* names show once the server runs */ }
  }

  const ruleRow = (r) => `<div class="pn-rule">
      <input type="text" class="pn-rule-type" list="pn-mob-types" value="${esc(r.type === '*' || r.type.startsWith('#') ? r.type : shortId(r.type))}" placeholder="cow, #animals or *" aria-label="Mob">
      <span class="pn-rule-label">${esc(GROUP_LABELS[r.type] || '')}</span>
      <span>at most</span><input type="number" class="pn-rule-n" min="1" max="500" value="${r.perChunk}" aria-label="Per chunk"><span>per chunk</span>
      <button type="button" class="np-btn small" data-rule-remove title="Remove"><i class="bi bi-x-lg"></i></button></div>`;

  function renderRules(rules) {
    $('pn-rules').innerHTML = rules.map(ruleRow).join('') || '<p class="pn-hint pn-rules-empty">No limits yet: add a mob or use a preset.</p>';
  }

  function readRules() {
    return [...$('pn-rules').querySelectorAll('.pn-rule')].map((row) => ({
      type: row.querySelector('.pn-rule-type').value.trim().toLowerCase(), perChunk: Number(row.querySelector('.pn-rule-n').value) || 1,
    })).filter((r) => r.type);
  }

  function addRule(type, perChunk) {
    $('pn-rules').querySelector('.pn-rules-empty')?.remove();
    const existing = [...$('pn-rules').querySelectorAll('.pn-rule')].find((row) => row.querySelector('.pn-rule-type').value.trim() === type && type);
    if (existing) { existing.querySelector('.pn-rule-n').value = perChunk; return; }
    $('pn-rules').insertAdjacentHTML('beforeend', ruleRow({ type, perChunk }));
    if (!type) $('pn-rules').lastElementChild.querySelector('input').focus();
  }

  $('pn-rule-add').addEventListener('click', () => addRule('', 24));
  document.querySelectorAll('[data-rule-preset]').forEach((b) => b.addEventListener('click', () => {
    if (b.dataset.rulePreset === 'animals') addRule('#animals', 24); else addRule('#monsters', 32);
  }));
  $('pn-rules').addEventListener('click', (e) => { if (e.target.closest('[data-rule-remove]')) e.target.closest('.pn-rule').remove(); });
  $('pn-rules').addEventListener('input', (e) => {
    if (!e.target.classList.contains('pn-rule-type')) return;
    e.target.parentElement.querySelector('.pn-rule-label').textContent = GROUP_LABELS[e.target.value.trim()] || '';
  });

  let junk = [];
  function renderJunk(items) {
    junk = items.slice();
    $('pn-junk').innerHTML = junk.map((id, i) => `<span class="pn-chip">${esc(shortId(id))}<button type="button" data-junk-remove="${i}" aria-label="Remove"><i class="bi bi-x"></i></button></span>`).join('')
      || '<span class="pn-hint">No junk items.</span>';
  }
  function addJunk() {
    const v = $('pn-junk-new').value.trim().toLowerCase().replace(/\s+/g, '_');
    if (!v) return;
    const id = v.includes(':') ? v : 'minecraft:' + v;
    if (!junk.includes(id)) junk.push(id);
    $('pn-junk-new').value = '';
    renderJunk(junk);
  }
  $('pn-junk-add').addEventListener('click', addJunk);
  $('pn-junk-new').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); addJunk(); } });
  $('pn-junk').addEventListener('click', (e) => {
    const b = e.target.closest('[data-junk-remove]');
    if (!b) return;
    junk.splice(Number(b.dataset.junkRemove), 1);
    renderJunk(junk);
  });

  const saveRow = (id, n) => `<div class="pn-rule">
      <input type="text" class="pn-rule-type" list="pn-entity-types" value="${esc(shortId(id))}" placeholder="arrow" aria-label="Kind">
      <span>keep at most</span><input type="number" class="pn-rule-n" min="0" max="5000" value="${n}" aria-label="Per chunk"><span>per chunk</span>
      <button type="button" class="np-btn small" data-rule-remove title="Remove"><i class="bi bi-x-lg"></i></button></div>`;
  function renderSaveLimits(limits) {
    $('pn-save-limits').innerHTML = Object.entries(limits).map(([id, n]) => saveRow(id, n)).join('');
  }
  function readSaveLimits() {
    const out = {};
    $('pn-save-limits').querySelectorAll('.pn-rule').forEach((row) => {
      const t = row.querySelector('.pn-rule-type').value.trim().toLowerCase();
      if (t) out[t.includes(':') ? t : 'minecraft:' + t] = Number(row.querySelector('.pn-rule-n').value) || 0;
    });
    return out;
  }
  $('pn-save-add').addEventListener('click', () => {
    $('pn-save-limits').insertAdjacentHTML('beforeend', saveRow('', 16));
    $('pn-save-limits').lastElementChild.querySelector('input').focus();
  });
  $('pn-save-limits').addEventListener('click', (e) => { if (e.target.closest('[data-rule-remove]')) e.target.closest('.pn-rule').remove(); });

  // Chunks kept loaded with nobody near (the first 15, the rest on request).
  let loadedAll = false;
  async function loadLoaded() {
    const table = $('pn-loaded');
    let r;
    try {
      r = await api('dashboard/api/loadedchunks');
    } catch (e) {
      $('pn-loaded-count').textContent = '';
      table.innerHTML = '<tbody><tr><td>Shows while the server runs.</td></tr></tbody>';
      return;
    }
    $('pn-loaded-count').textContent = r.count ? `${r.count}${r.ticking ? `, ${r.ticking} ticking` : ''}` : '';
    const REASONS = { forced: '/forceload', ender_pearl: 'Ender pearl', portal: 'Portal', dragon: 'Dragon fight', spawn_search: 'Spawn search', player_spawn: 'Respawn point', warm: 'Teleport target', pregen: 'Pregeneration', unknown: 'Other' };
    const shown = loadedAll ? r.chunks : r.chunks.slice(0, 15);
    const more = r.chunks.length - shown.length;
    table.innerHTML = r.chunks.length
      ? `<thead><tr><th>Where</th><th>Why</th><th>Ticks</th><th class="num" title="In the chunk and the 2 chunks around it">Mobs around</th><th class="num">Block entities</th><th></th></tr></thead><tbody>${shown.map((c) => `<tr>
          <td>${esc(dimLabel(c.dimension))} <small>x ${c.x * 16 + 8}, z ${c.z * 16 + 8} · chunk ${c.x}, ${c.z}</small></td>
          <td>${c.reasons.map((x) => `<span class="pn-tag">${esc(REASONS[x] || x)}</span>`).join(' ')}</td>
          <td>${c.ticks ? '<span class="pn-tag warn">ticks</span>' : '<span class="pn-muted">only loaded</span>'}</td>
          <td class="num">${c.entities}</td><td class="num">${c.blockEntities}</td>
          <td class="num">${can('settings.server') ? `<button type="button" class="np-btn small" data-release data-dim="${esc(c.dimension)}" data-x="${c.x}" data-z="${c.z}">Release</button>` : ''}</td></tr>`).join('')}
          ${more > 0 ? `<tr><td colspan="6"><button type="button" class="np-btn small" data-loaded-all>Show all ${r.chunks.length}${r.count > r.chunks.length ? ` (of ${r.count})` : ''}</button></td></tr>` : ''}</tbody>`
      : '<tbody><tr><td>None: every loaded chunk has a player near.</td></tr></tbody>';
  }

  $('pn-loaded').addEventListener('click', async (e) => {
    if (e.target.closest('[data-loaded-all]')) { loadedAll = true; loadLoaded(); return; }
    const b = e.target.closest('[data-release]');
    if (!b) return;
    b.disabled = true;
    try {
      await api('dashboard/api/loadedchunks/release', { dimension: b.dataset.dim, x: Number(b.dataset.x), z: Number(b.dataset.z) });
      loadLoaded();
    } catch (err) {
      b.disabled = false;
      alert(err.message);
    }
  });

  async function loadPerfLive() {
    const el = $('pn-perf-live');
    try {
      const p = await api('dashboard/api/perf');
      el.className = 'pn-perf-live' + (p.lowered ? ' lowered' : '');
      el.innerHTML = `<span><b>${p.mspt}</b> ms per tick</span><span>Simulation distance <b>${p.simulation}</b>${p.simulation !== p.simulationBase ? ` of ${p.simulationBase}` : ''}</span>
        <span>View distance <b>${p.view}</b>${p.view !== p.viewBase ? ` of ${p.viewBase}` : ''}</span><span>Mob caps <b>${p.mobCap} %</b></span>${p.rangeStep ? `<span>Far-mob ranges <b>${p.rangeStep === p.rangeSteps ? 'lowest' : `step ${p.rangeStep} of ${p.rangeSteps}`}</b></span>` : ''}${p.activationHalved ? '<span>Activation ranges <b>halved</b></span>' : ''}${p.warmTargets ? `<span>Warm teleport targets <b>${p.warmTargets}</b></span>` : ''}${p.tracking < 100 ? `<span>Mobs seen from <b>${p.tracking} %</b></span>` : ''}${p.cappedMobsHour || p.cappedItemsHour ? `<span>Auto caps, last hour: <b>${p.cappedMobsHour}</b> mobs, <b>${p.cappedItemsHour}</b> items</span>` : ''}
        ${p.lastChange ? `<small>${esc(p.lastChange)}</small>` : ''}`;
    } catch (e) {
      el.className = 'pn-perf-live off';
      el.textContent = 'Live values show while the server runs.';
    }
  }

  $('pn-perf-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const s = JSON.parse(JSON.stringify(perfSettings || {}));
    e.target.querySelectorAll('[name]').forEach((el) => {
      const [sec, key] = el.name.split('.');
      s[sec] = s[sec] || {};
      s[sec][key] = el.type === 'checkbox' ? el.checked : Number(el.value);
    });
    s.rules = readRules();
    s.despawn = { ...(s.despawn || {}), items: junk.slice() };
    s.saveLimits = { ...(s.saveLimits || {}), limits: readSaveLimits() };
    try {
      await api('api/perf/settings', s);
      perfSettings = s;
      formMsg('pn-perf-msg', 'Saved.', true);
    } catch (err) {
      formMsg('pn-perf-msg', err.message, false);
    }
  });

  const dimLabel = (id) => ({ 'minecraft:overworld': 'Overworld', 'minecraft:the_nether': 'Nether', 'minecraft:the_end': 'End' }[id] || id);

  /** The laggiest chunks and the frozen ones in one list; each row has its own Frozen switch. */
  let hotspots = null;
  async function loadHotspots() {
    try {
      hotspots = (await api('dashboard/api/window?m=15')).hotspots || [];
    } catch (e) {
      hotspots = null;
    }
    renderFrozen();
  }

  const chunkKey = (dim, x, z) => `${dim}|${x}|${z}`;

  function renderFrozen() {
    const table = $('pn-hotspots');
    const frozen = (perfSettings && perfSettings.frozen) || [];
    const frozenKeys = new Set(frozen.map((c) => chunkKey(c.dimension, c.x, c.z)));
    $('pn-frozen-count').textContent = frozen.length ? `${frozen.length} frozen` : '';
    const rows = (hotspots || []).map((h) => ({ dim: h.dimension, x: h.chunkX, z: h.chunkZ, h }));
    const listed = new Set(rows.map((r) => chunkKey(r.dim, r.x, r.z)));
    // Frozen chunks don't tick, so they drop out of the laggiest list: keep them here anyway.
    for (const c of frozen) if (!listed.has(chunkKey(c.dimension, c.x, c.z))) rows.push({ dim: c.dimension, x: c.x, z: c.z });
    if (!rows.length) {
      table.innerHTML = `<tbody><tr><td>${hotspots ? 'No data yet. The live monitor finds these (Performance → Live); it needs a few minutes.' : 'Shows while the server runs.'}</td></tr></tbody>`;
      return;
    }
    table.innerHTML = `<thead><tr><th>Where</th><th class="num">ms per tick</th><th class="num">Mobs</th><th class="num">Block entities</th><th>Most</th><th class="num">Frozen</th></tr></thead><tbody>${rows.map((r) => {
      const on = frozenKeys.has(chunkKey(r.dim, r.x, r.z));
      const h = r.h;
      const where = `${esc(dimLabel(r.dim))} <small>x ${h ? h.blockX : r.x * 16 + 8}, z ${h ? h.blockZ : r.z * 16 + 8} · chunk ${r.x}, ${r.z}</small>`;
      const stats = h
        ? `<td class="num">${h.ms.toFixed(2)}</td><td class="num">${h.entities}</td><td class="num">${h.blockEntities}</td><td>${h.topType ? `${esc(h.topType.replace('minecraft:', ''))} × ${h.topTypeCount}` : ''}</td>`
        : '<td class="num pn-muted">–</td><td class="num pn-muted">–</td><td class="num pn-muted">–</td><td class="pn-muted">not ticking</td>';
      return `<tr class="pn-chunk${on ? ' frozen' : ''}"><td>${where}</td>${stats}
        <td class="num"><label class="np-switch" title="Freeze this chunk"><input type="checkbox" data-freeze data-dim="${esc(r.dim)}" data-x="${r.x}" data-z="${r.z}"${on ? ' checked' : ''}><span></span></label></td></tr>`;
    }).join('')}</tbody>`;
  }

  $('pn-hotspots').addEventListener('change', async (e) => {
    const f = e.target.closest('[data-freeze]');
    if (!f) return;
    const on = f.checked;
    if (on && !confirm('Freeze this chunk? Everything in it stops (mobs, furnaces, hoppers, farms) until you switch it off again.')) {
      f.checked = false;
      return;
    }
    f.disabled = true;
    try {
      const res = await api('api/perf/freeze', { dimension: f.dataset.dim, x: f.dataset.x, z: f.dataset.z, on });
      perfSettings.frozen = res.frozen;
      // Only this row changes; the list keeps its order.
      f.closest('tr').classList.toggle('frozen', on);
      $('pn-frozen-count').textContent = res.frozen.length ? `${res.frozen.length} frozen` : '';
    } catch (err) {
      f.checked = !on;
      alert(err.message);
    } finally {
      f.disabled = false;
    }
  });

  document.querySelector('.np-tab-panel[data-tab="tuning"]').addEventListener('click', async (e) => {
    const c = e.target.closest('[data-cleanup]');
    if (!c) return;
    const kind = c.dataset.cleanup;
    const run = c.dataset.run === '1';
    const note = $(kind === 'items' ? 'pn-cl-items-note' : 'pn-cl-excess-note');
    const per = Math.max(1, Math.min(500, parseInt($('pn-cl-per').value, 10) || 24));
    $('pn-cl-per').value = per;
    if (run && !confirm(kind === 'items' ? 'Remove all dropped items and XP orbs in loaded chunks?' : `Remove mobs over ${per} of one kind per chunk?`)) return;
    const q = kind === 'items' ? 'kind=items' : `kind=excess&perChunk=${per}`;
    try {
      const r = run ? await api('dashboard/api/cleanup/run?' + q, {}) : await api('dashboard/api/cleanup/preview?' + q);
      if (kind === 'items') {
        note.textContent = `${run ? 'Removed' : 'Lying around'}: ${r.items} items, ${r.xp} XP orbs`;
        $('pn-cl-table-wrap').hidden = true;
      } else {
        if (r.error) { note.textContent = r.error; return; }
        note.textContent = r.remove ? `${run ? 'Removed' : 'Would remove'} ${r.remove} mobs in ${r.chunkCount} chunks` : `No chunk has more than ${per} of one kind.`;
        $('pn-cl-table-wrap').hidden = !r.chunks.length || run;
        $('pn-cl-table').innerHTML = `<thead><tr><th>Where</th><th>Kind</th><th class="num">There</th><th class="num">Limit</th><th class="num">Would go</th></tr></thead><tbody>${r.chunks.map((x) =>
          `<tr><td>${esc(dimLabel(x.dimension))} <small>x ${x.x * 16}, z ${x.z * 16}</small></td><td>${esc(x.type.replace('minecraft:', ''))}</td><td class="num">${x.count}</td><td class="num">${x.limit ?? ''}</td><td class="num">${x.remove}</td></tr>`).join('')}</tbody>`;
      }
    } catch (err) {
      note.textContent = err.message;
    }
  });

  // ── Settings → Maintenance ──────────────────────────────────────────────

  /** A MOTD with § codes as it looks in the server list (MC_COLORS at the top). */
  function motdHtml(text) {
    let out = '';
    let style = { color: '#AAAAAA', b: false, i: false, u: false, s: false };
    const parts = String(text || '').replace(/\\n/g, '\n').split(/(§[0-9a-fk-or])/i);
    for (const part of parts) {
      const m = part.match(/^§([0-9a-fk-or])$/i);
      if (m) {
        const c = m[1].toLowerCase();
        if (MC_COLORS[c]) style = { color: MC_COLORS[c], b: false, i: false, u: false, s: false };
        else if (c === 'l') style.b = true;
        else if (c === 'o') style.i = true;
        else if (c === 'n') style.u = true;
        else if (c === 'm') style.s = true;
        else if (c === 'r') style = { color: '#AAAAAA', b: false, i: false, u: false, s: false };
        continue;
      }
      if (!part) continue;
      const deco = [style.u && 'underline', style.s && 'line-through'].filter(Boolean).join(' ');
      out += `<span style="color:${style.color};${style.b ? 'font-weight:700;' : ''}${style.i ? 'font-style:italic;' : ''}${deco ? `text-decoration:${deco};` : ''}">${esc(part).replace(/\n/g, '<br>')}</span>`;
    }
    return out || '&nbsp;';
  }

  let mtData = null;
  let mtStaff = new Set();
  let mtTicks = 0;

  /** Operators change in game and in the console: the list follows, keeping ticks not saved yet. */
  async function refreshStaffList() {
    if (!mtData || document.activeElement === $('pn-mt-add')) return;
    try {
      const fresh = await api('api/maintenance');
      if (JSON.stringify(fresh.players) !== JSON.stringify(mtData.players)) {
        mtData.players = fresh.players;
        renderStaff();
      }
    } catch (e) { /* next time */ }
  }

  /** Known players as checkboxes; ticked ones may join during maintenance. Operators are always ticked. */
  function renderStaff() {
    const q = $('pn-mt-filter').value.trim().toLowerCase();
    const players = [...(mtData ? mtData.players : [])];
    for (const n of mtStaff) if (!players.some((p) => p.name.toLowerCase() === n)) players.push({ name: n, op: false });
    players.sort((a, b) => (b.op - a.op) || (mtStaff.has(b.name.toLowerCase()) - mtStaff.has(a.name.toLowerCase())) || a.name.localeCompare(b.name));
    const shown = players.filter((p) => !q || p.name.toLowerCase().includes(q));
    const editable = can('players.manage');
    $('pn-mt-staff').innerHTML = shown.map((p) => `<label class="pn-staff-item${p.op ? ' op' : ''}">
        <input type="checkbox" data-staff="${esc(p.name.toLowerCase())}" ${p.op || mtStaff.has(p.name.toLowerCase()) ? 'checked' : ''} ${p.op || !editable ? 'disabled' : ''}>
        <img src="https://mc-heads.net/avatar/${encodeURIComponent(p.name)}/20" alt="" width="20" height="20" loading="lazy"><span>${esc(p.name)}</span>${p.op ? '<small>operator</small>' : ''}</label>`).join('')
      || `<p class="pn-hint">${q ? 'Nobody by that name yet: add it above.' : 'Nobody has joined yet: add names above.'}</p>`;
  }

  $('pn-mt-filter').addEventListener('input', renderStaff);
  $('pn-mt-filter').addEventListener('keydown', (e) => { if (e.key === 'Enter') e.preventDefault(); });
  $('pn-mt-staff').addEventListener('change', (e) => {
    const n = e.target.dataset.staff;
    if (!n) return;
    if (e.target.checked) mtStaff.add(n); else mtStaff.delete(n);
  });
  const addStaff = () => {
    const name = $('pn-mt-add').value.trim();
    if (!/^[A-Za-z0-9_]{1,16}$/.test(name)) return formMsg('pn-mt-msg', 'Minecraft names are 1–16 letters, digits or _.', false);
    mtStaff.add(name.toLowerCase());
    $('pn-mt-add').value = '';
    renderStaff();
  };
  $('pn-mt-add-btn').addEventListener('click', addStaff);
  $('pn-mt-add').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); addStaff(); } });

  async function loadMaintenance() {
    try {
      mtData = await api('api/maintenance');
    } catch (e) {
      formMsg('pn-mt-msg', e.message, false);
      return;
    }
    const s = mtData.settings;
    const form = $('pn-mt-form');
    form.enabled.checked = s.enabled;
    form.countdown.value = s.countdown;
    form.motd.value = s.motd;
    form.kickMessage.value = s.kickMessage;
    mtStaff = new Set((s.staff || []).map((n) => n.toLowerCase()));
    renderStaff();
    $('pn-mt-preview').innerHTML = motdHtml(s.motd);
    const editable = can('players.manage');
    form.querySelectorAll('input, textarea, button').forEach((el) => { el.disabled = !editable; });
    Dirty.reset('maintenance');
  }

  $('pn-mt-form').motd.addEventListener('input', (e) => { $('pn-mt-preview').innerHTML = motdHtml(e.target.value); });
  $('pn-mt-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target;
    const turningOn = f.enabled.checked && !(mtData && mtData.settings.enabled);
    if (turningOn && !confirm('Switch maintenance on? Everyone but operators and staff is warned and then kicked.')) return;
    try {
      const res = await api('api/maintenance', { enabled: f.enabled.checked, countdown: Number(f.countdown.value) || 0, motd: f.motd.value,
        kickMessage: f.kickMessage.value, staff: [...mtStaff] });
      formMsg('pn-mt-msg', res.running ? 'Saved, applies within a few seconds.' : 'Saved; applies when the server starts.', true);
      loadMaintenance();
      pollServer();
    } catch (err) {
      formMsg('pn-mt-msg', err.message, false);
    }
  });

  // ── Settings → Server list ───────────────────────────────────────────────

  async function loadServerList() {
    let d;
    try {
      d = await api('api/serverlist');
    } catch (e) {
      formMsg('pn-sl-msg', e.message, false);
      return;
    }
    const form = $('pn-sl-form');
    form.motd.value = d.motd.replace(/\\n/g, '\n');
    motdState = d.motds || { rotate: false, list: [] };
    renderMotds();
    $('pn-sl-preview').innerHTML = motdHtml(form.motd.value);
    $('pn-sl-players').textContent = `0/${d.maxPlayers}`;
    const editable = can('settings.server');
    form.querySelectorAll('textarea, button').forEach((el) => { el.disabled = !editable; });
    document.querySelectorAll('.pn-icon-slot').forEach((slot) => {
      const img = slot.querySelector('img');
      const empty = slot.querySelector('.pn-logo-preview span');
      img.onload = () => { img.hidden = false; empty.hidden = true; };
      img.onerror = () => { img.hidden = true; empty.hidden = false; };
      img.src = `api/serverlist/icon/${slot.dataset.icon}?t=${Date.now()}`;
      slot.querySelectorAll('input, button').forEach((el) => { el.disabled = !editable; });
    });
    const icon = $('pn-sl-preview-icon');
    icon.onerror = () => { icon.src = 'img/apple-touch-icon.png'; icon.onerror = null; };
    icon.src = `api/serverlist/icon/normal?t=${Date.now()}`;
  }

  let motdState = { rotate: false, list: [] };

  function renderMotds() {
    $('pn-motd-rotate').checked = !!motdState.rotate;
    const editable = can('settings.server');
    $('pn-motd-rotate').disabled = !editable;
    $('pn-motd-list').innerHTML = motdState.list.map((m, i) => `<div class="pn-motd-item${m.on ? '' : ' off'}" data-i="${i}">
        <label class="np-check" title="Can be picked"><input type="checkbox" data-motd-on ${m.on ? 'checked' : ''} ${editable ? '' : 'disabled'}></label>
        <div class="pn-motd-preview">${motdHtml(m.text)}</div>
        ${editable ? `<div class="pn-row-actions"><button type="button" class="np-btn small" data-motd-edit title="Edit"><i class="bi bi-pencil"></i></button>
        <button type="button" class="np-btn small danger" data-motd-del title="Remove"><i class="bi bi-trash3"></i></button></div>` : ''}</div>`).join('')
      || '<p class="pn-hint">No messages yet: write one above and press <b>Add to the collection</b>.</p>';
  }

  async function saveMotds(note) {
    try {
      await api('api/serverlist/motds', motdState);
      if (note) formMsg('pn-sl-msg', note, true);
    } catch (err) {
      formMsg('pn-sl-msg', err.message, false);
    }
    renderMotds();
  }

  /** Index of the message being edited, so "Add" saves it in place. */
  let motdEditing = -1;
  $('pn-motd-add').addEventListener('click', () => {
    const text = $('pn-sl-form').motd.value;
    if (!text.trim()) return formMsg('pn-sl-msg', 'Write a message first.', false);
    if (motdEditing >= 0 && motdState.list[motdEditing]) motdState.list[motdEditing].text = text;
    else motdState.list.push({ text, on: true });
    const note = motdEditing >= 0 ? 'Changed in the collection.' : 'Added to the collection.';
    motdEditing = -1;
    $('pn-motd-add').innerHTML = '<i class="bi bi-plus-lg"></i> Add to the collection';
    saveMotds(note);
  });
  $('pn-motd-rotate').addEventListener('change', (e) => {
    motdState.rotate = e.target.checked;
    saveMotds(e.target.checked ? 'A random ticked message is used from the next start.' : 'The message stays as it is.');
  });
  $('pn-motd-list').addEventListener('click', (e) => {
    const row = e.target.closest('[data-i]');
    if (!row) return;
    const i = Number(row.dataset.i);
    if (e.target.closest('[data-motd-del]')) {
      if (!confirm('Remove this message from the collection?')) return;
      motdState.list.splice(i, 1);
      if (motdEditing === i) motdEditing = -1;
      saveMotds('Removed.');
    } else if (e.target.closest('[data-motd-edit]')) {
      const t = $('pn-sl-form').motd;
      t.value = motdState.list[i].text;
      $('pn-sl-preview').innerHTML = motdHtml(t.value);
      motdEditing = i;
      $('pn-motd-add').innerHTML = '<i class="bi bi-check2"></i> Save in the collection';
      t.focus();
      t.scrollIntoView({ block: 'center', behavior: 'smooth' });
    }
  });
  $('pn-motd-list').addEventListener('change', (e) => {
    if (!e.target.matches('[data-motd-on]')) return;
    motdState.list[Number(e.target.closest('[data-i]').dataset.i)].on = e.target.checked;
    saveMotds();
  });

  $('pn-sl-colors').innerHTML = Object.entries(MC_COLORS).map(([c, hex]) => `<button type="button" class="pn-color" data-code="§${c}" title="§${c}" style="background:${hex}"></button>`).join('')
    + ['l', 'o', 'n', 'm', 'r'].map((c) => `<button type="button" class="pn-color pn-fmt" data-code="§${c}" title="§${c}">${{ l: '<b>B</b>', o: '<i>I</i>', n: '<u>U</u>', m: '<s>S</s>', r: '↺' }[c]}</button>`).join('');
  $('pn-sl-colors').addEventListener('click', (e) => {
    const b = e.target.closest('[data-code]');
    if (!b) return;
    const t = $('pn-sl-form').motd;
    const at = t.selectionStart ?? t.value.length;
    t.value = t.value.slice(0, at) + b.dataset.code + t.value.slice(t.selectionEnd ?? at);
    t.focus();
    t.selectionStart = t.selectionEnd = at + 2;
    $('pn-sl-preview').innerHTML = motdHtml(t.value);
  });
  $('pn-sl-form').motd.addEventListener('input', (e) => { $('pn-sl-preview').innerHTML = motdHtml(e.target.value); });
  $('pn-sl-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
      const res = await api('api/serverlist/motd', { motd: e.target.motd.value });
      formMsg('pn-sl-msg', res.running ? 'Saved; shows after the next restart.' : 'Saved.', true);
    } catch (err) {
      formMsg('pn-sl-msg', err.message, false);
    }
  });
  document.querySelectorAll('.pn-icon-slot').forEach((slot) => {
    const which = slot.dataset.icon;
    slot.querySelector('input[type="file"]').addEventListener('change', async (e) => {
      const file = e.target.files[0];
      e.target.value = '';
      if (!file) return;
      const form = new FormData();
      form.append('icon', file);
      try {
        const res = await fetch('api/serverlist/icon/' + which, { method: 'POST', headers: { 'X-NCC': '1' }, body: form });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(data.error || res.statusText);
        formMsg('pn-icon-msg', which === 'maintenance' ? 'Saved; used while maintenance is on.' : 'Saved; shows after the next restart.', true);
        loadServerList();
      } catch (err) {
        formMsg('pn-icon-msg', err.message, false);
      }
    });
    slot.querySelector('[data-icon-remove]').addEventListener('click', async () => {
      try {
        await api('api/serverlist/icon/' + which + '/remove', {});
        formMsg('pn-icon-msg', 'Removed.', true);
        loadServerList();
      } catch (err) {
        formMsg('pn-icon-msg', err.message, false);
      }
    });
  });

  // ── Public pages → Info page ──────────────────────────────────────────────

  let infoData = null;
  let infoFiles = [];

  async function loadInfo() {
    try {
      [infoData, infoFiles] = await Promise.all([api('api/info'), api('api/configs').then((d) => d.files.map((f) => f.path)).catch(() => [])]);
    } catch (err) {
      return formMsg('pn-info-msg', err.message, false);
    }
    const s = infoData.settings;
    const f = $('pn-info-form');
    for (const k of ['enabled', 'properties', 'gamerules', 'packs', 'performance', 'commands', 'restart', 'claims', 'borders', 'tweaks', 'market', 'waypoints']) f.elements[k].checked = !!s[k];
    f.title.value = s.title || '';
    f.intro.value = s.intro || '';
    f.infoText.value = s.infoText || '';
    f.infoLabel.value = s.infoLabel || '';
    f.infoAddress.value = s.infoAddress || '';
    renderLinks(s.links || []);
    f.infoAddress.placeholder = infoData.autoAddress || 'https://info.example.com';
    $('pn-info-auto').textContent = infoData.autoAddress ? `Empty: ${infoData.autoAddress}` : 'Set this, INFO_HOST or PANEL_PUBLIC_URL, or /info has no link';
    // While it's off, the button opens an admin preview here (the public address shows "switched off").
    const url = !s.enabled ? new URL('info/?preview=1', location.href).href : infoData.host ? `https://${infoData.host}/` : new URL('info/', location.href).href;
    $('pn-info-open').href = url;
    $('pn-info-open').innerHTML = s.enabled ? '<i class="bi bi-box-arrow-up-right"></i> Open the page' : '<i class="bi bi-eye"></i> Preview the page';
    // Same wording as the map's card.
    $('pn-info-link').innerHTML = infoData.host
      ? `Public address: <a href="https://${esc(infoData.host)}/" target="_blank" rel="noopener">${esc(infoData.host)}</a>`
      : 'Set <code>INFO_HOST</code> (e.g. info.example.com) to give the page its own address.';
    $('pn-info-status').textContent = s.enabled ? 'It\'s public.' : 'It\'s off: nobody can open it; you can preview it from here.';
    $('pn-info-props').innerHTML = infoData.propertyKeys.map((k) => `<label class="np-check"><input type="checkbox" value="${esc(k)}" ${s.hiddenProperties.includes(k) ? 'checked' : ''}> ${esc(k)}</label>`).join('')
      || '<p class="pn-hint">Shows after the server has run once with the mod.</p>';
    renderInfoFiles();
    Dirty.reset('info');
  }

  function renderInfoFiles() {
    const q = $('pn-info-filter').value.trim().toLowerCase();
    const chosen = new Set(infoData ? infoData.settings.configs : []);
    const list = infoFiles.filter((p) => !q || p.toLowerCase().includes(q));
    $('pn-info-configs').innerHTML = list.map((p) => `<label class="np-check"><input type="checkbox" value="${esc(p)}" ${chosen.has(p) ? 'checked' : ''}> ${esc(p.replace(/^config\//, ''))}</label>`).join('')
      || '<p class="pn-hint">No config files found.</p>';
  }

  // Own link commands: one row each, read back when saving.
  function renderLinks(links) {
    $('pn-links').innerHTML = links.map((l) => `<div class="pn-link-row">
      <label class="pn-field">Command<input type="text" data-k="command" value="${esc(l.command)}" placeholder="discord" maxlength="32"></label>
      <label class="pn-field">More names<input type="text" data-k="aliases" value="${esc((l.aliases || []).join(', '))}" placeholder="dc, chat"></label>
      <label class="pn-field">Text before the link<input type="text" data-k="text" value="${esc(l.text)}" placeholder="Join our Discord:" maxlength="200"></label>
      <label class="pn-field">Link text<input type="text" data-k="label" value="${esc(l.label)}" placeholder="the address" maxlength="80"></label>
      <label class="pn-field">Address<input type="text" data-k="url" value="${esc(l.url)}" placeholder="https://discord.gg/…" maxlength="300"></label>
      <button type="button" class="np-btn small danger" data-link-remove title="Remove this link"><i class="bi bi-trash"></i></button>
    </div>`).join('') || '<p class="pn-hint">No own links yet.</p>';
  }

  function readLinks() {
    return [...$('pn-links').querySelectorAll('.pn-link-row')].map((row) => {
      const v = (k) => row.querySelector(`[data-k="${k}"]`).value.trim();
      return { command: v('command'), aliases: v('aliases').split(',').map((a) => a.trim()).filter(Boolean), text: v('text'), label: v('label'), url: v('url') };
    }).filter((l) => l.command || l.url || l.text || l.label || l.aliases.length);
  }

  $('pn-links-add').addEventListener('click', () => {
    renderLinks([...readLinks(), { command: '', aliases: [], text: '', label: '', url: '' }]);
    $('pn-links').querySelector('.pn-link-row:last-child [data-k="command"]').focus();
  });
  $('pn-links').addEventListener('click', (e) => {
    const b = e.target.closest('[data-link-remove]');
    if (!b) return;
    b.closest('.pn-link-row').remove();
    if (!$('pn-links').querySelector('.pn-link-row')) renderLinks([]);
  });

  $('pn-info-filter').addEventListener('input', renderInfoFiles);
  $('pn-info-filter').addEventListener('keydown', (e) => { if (e.key === 'Enter') e.preventDefault(); });
  $('pn-info-configs').addEventListener('change', (e) => {
    const set = new Set(infoData.settings.configs);
    if (e.target.checked) set.add(e.target.value); else set.delete(e.target.value);
    infoData.settings.configs = [...set];
  });
  $('pn-info-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target;
    const body = {
      enabled: f.enabled.checked, title: f.title.value.trim() || 'Server info', intro: f.intro.value,
      properties: f.properties.checked, gamerules: f.gamerules.checked, packs: f.packs.checked, performance: f.performance.checked, commands: f.commands.checked, restart: f.restart.checked,
      claims: f.claims.checked, borders: f.borders.checked, tweaks: f.tweaks.checked, market: f.market.checked, waypoints: f.waypoints.checked,
      hiddenProperties: [...$('pn-info-props').querySelectorAll('input:checked')].map((i) => i.value),
      configs: infoData.settings.configs,
      infoAddress: f.infoAddress.value.trim(), infoText: f.infoText.value.trim(), infoLabel: f.infoLabel.value.trim(),
      links: readLinks(),
    };
    try {
      await api('api/info', body);
      infoData.settings = body;
      formMsg('pn-info-msg', body.enabled ? 'Saved; the page is public.' : 'Saved; the page is off.', true);
    } catch (err) {
      formMsg('pn-info-msg', err.message, false);
    }
  });

  // ── Quick search (Ctrl/⌘ K) ─────────────────────────────────────────────
  // Pages and sub-pages, plus every setting on the panel's own pages (by its label).

  let searchItems = [];
  let searchPick = 0;

  function buildSearch() {
    const items = [];
    NAV.forEach((g) => g.items.forEach((e) => subsOf(e).forEach((x) => {
      const multi = subsOf(e).length > 1;
      items.push({ tab: x.tab, title: multi ? `${e.label} › ${x.label}` : e.label, sub: x.desc, icon: e.icon, kind: 'page' });
    })));
    const pages = new Set(items.map((i) => i.tab));
    document.querySelectorAll('#pn-app .np-tab-panel[data-tab]').forEach((panel) => {
      const tab = panel.dataset.tab;
      if (!pages.has(tab)) return;
      const where = items.find((i) => i.tab === tab).title;
      panel.querySelectorAll('.np-card-title, .np-form-row, .pn-field > span, label.pn-field').forEach((el) => {
        const clone = el.cloneNode(true);
        clone.querySelectorAll('input, select, textarea, .pn-comment, .pn-count, button').forEach((x) => x.remove());
        const text = clone.textContent.replace(/\s+/g, ' ').trim();
        if (text && text.length < 90 && !items.some((i) => i.tab === tab && i.title === text)) items.push({ tab, title: text, sub: where, el, icon: 'bi-sliders', kind: 'setting' });
      });
    });
    return items;
  }

  function renderSearch() {
    const q = $('pn-search-q').value.trim().toLowerCase();
    const words = q.split(/\s+/).filter(Boolean);
    const hits = searchItems.filter((i) => words.every((w) => (i.title + ' ' + (i.sub || '')).toLowerCase().includes(w)))
      .sort((a, b) => (a.kind === b.kind ? 0 : a.kind === 'page' ? -1 : 1)).slice(0, 12);
    searchPick = Math.min(searchPick, Math.max(0, hits.length - 1));
    $('pn-search-results').innerHTML = hits.length ? hits.map((h, i) => `<button type="button" role="option" class="pn-search-hit${i === searchPick ? ' active' : ''}" data-i="${searchItems.indexOf(h)}">
      <i class="bi ${h.icon}"></i><span><b>${esc(h.title)}</b><small>${esc(h.sub || '')}</small></span></button>`).join('')
      : '<p class="pn-search-none">Nothing found.</p>';
  }

  function openSearch() {
    if (!loggedIn) return;
    searchItems = buildSearch();
    searchPick = 0;
    $('pn-search').hidden = false;
    $('pn-search-q').value = '';
    renderSearch();
    $('pn-search-q').focus();
  }

  function closeSearch() {
    $('pn-search').hidden = true;
  }

  function goSearch(item) {
    closeSearch();
    selectTab(item.tab);
    if (!item.el) return;
    // Open folded sections around the setting, then point at it.
    for (let p = item.el.parentElement; p; p = p.parentElement) if (p.tagName === 'DETAILS') p.open = true;
    setTimeout(() => {
      item.el.scrollIntoView({ block: 'center', behavior: 'smooth' });
      item.el.classList.add('pn-found');
      setTimeout(() => item.el.classList.remove('pn-found'), 1800);
    }, 60);
  }

  $('pn-search-key').textContent = /Mac|iPhone|iPad/.test(navigator.platform) ? '⌘K' : 'Ctrl K';
  $('pn-search-open').addEventListener('click', openSearch);
  $('pn-search').addEventListener('click', (e) => {
    if (e.target === $('pn-search')) return closeSearch();
    const hit = e.target.closest('[data-i]');
    if (hit) goSearch(searchItems[Number(hit.dataset.i)]);
  });
  $('pn-search-q').addEventListener('input', () => { searchPick = 0; renderSearch(); });
  $('pn-search-q').addEventListener('keydown', (e) => {
    const hits = [...$('pn-search-results').querySelectorAll('[data-i]')];
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      searchPick = (searchPick + (e.key === 'ArrowDown' ? 1 : -1) + hits.length) % Math.max(1, hits.length);
      renderSearch();
    } else if (e.key === 'Enter' && hits[searchPick]) {
      goSearch(searchItems[Number(hits[searchPick].dataset.i)]);
    } else if (e.key === 'Escape') {
      closeSearch();
    }
  });
  document.addEventListener('keydown', (e) => {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
      e.preventDefault();
      if ($('pn-search').hidden) openSearch(); else closeSearch();
    }
  });

  // ── Commands: grouped by the mod that adds them ───────────────────────────────

  const LEVELS = { 0: 'Everyone', 1: 'Op level 1', 2: 'Op level 2', 3: 'Op level 3', 4: 'Op level 4' };
  const LEVEL_HELP = { 0: 'Every player', 1: 'Operators (level 1: moderators, can bypass spawn protection)', 2: 'Operators (level 2: game masters, most cheats and command blocks)',
    3: 'Operators (level 3: admins, kick, ban and op)', 4: 'Operators (level 4: owners, everything incl. stop)' };
  let cmdData = null;

  async function loadCommands() {
    try {
      cmdData = await api('api/commands');
    } catch (e) {
      $('pn-cmd-groups').innerHTML = `<section class="np-card"><p class="pn-hint">${esc(e.message)}</p></section>`;
      return;
    }
    renderCommands();
  }

  function whoPill(c) {
    if (c.level === -1) return `<span class="pn-who special" title="No operator level passes the check: it needs a player, or a permission from a permissions mod">${c.permission ? 'Permission' : 'Special'}</span>`;
    return `<span class="pn-who l${c.level}" title="${esc(LEVEL_HELP[c.level] || '')}">${c.level === 0 ? '<i class="bi bi-people"></i> ' : '<i class="bi bi-shield-lock"></i> '}${LEVELS[c.level] || 'Level ' + c.level}</span>`;
  }

  function cmdRow(c, sub) {
    const desc = c.description ? esc(c.description) : (sub ? '' : '<span class="pn-muted">No description yet.</span>');
    const usage = (c.usage || []).filter((u) => u !== '/' + c.name || c.usage.length === 1);
    const edit = cmdData.canEdit ? `<button type="button" class="pn-cmd-edit" data-cmd-edit="${esc(c.name)}" title="Write a description"><i class="bi bi-pencil"></i></button>` : '';
    const search = [c.name, c.description, c.permission, c.aliasOf, ...(c.usage || [])].join(' ').toLowerCase();
    return `<div class="pn-cmd${sub ? ' sub' : ''}" data-search="${esc(search)}" data-level="${c.level}">
      <div class="pn-cmd-main">
        <code class="pn-cmd-name">/${esc(c.name)}</code>${c.aliasOf ? `<span class="pn-muted">same as /${esc(c.aliasOf)}</span>` : ''}
        <span class="pn-cmd-who">${whoPill(c)}${c.permission ? `<code class="pn-perm" title="Permission node (LuckPerms and other permissions mods)">${esc(c.permission)}</code>` : ''}</span>
      </div>
      <div class="pn-cmd-desc" data-cmd-desc="${esc(c.name)}">${desc}${c.custom ? ' <span class="pn-tag">yours</span>' : ''}${edit}</div>
      ${usage.length ? `<details class="pn-cmd-usage"><summary>How to use</summary>${usage.map((u) => `<code>${esc(u)}</code>`).join('')}</details>` : ''}
    </div>`;
  }

  function renderCommands() {
    const list = cmdData.commands || [];
    $('pn-cmd-note').textContent = !list.length
      ? (cmdData.running ? 'Loading… (needs the Nice Control Center mod 1.7.3 or newer)' : 'Shows after the server has run once with the mod.')
      : `${list.length} commands${cmdData.updated ? ` · read ${new Date(cmdData.updated).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' })}` : ''}${cmdData.running ? '' : ' · the server is off, this is the last known list'}.`
        + ' Who may use a command is the lowest operator level that passes its check; a permissions mod like LuckPerms can change that with the permission nodes.';
    const groups = new Map();
    for (const c of list) {
      const key = c.mod || 'unknown';
      if (!groups.has(key)) groups.set(key, { name: c.modName || key, list: [] });
      groups.get(key).list.push(c);
    }
    // Minecraft first, the mods by name, unknown last.
    const order = [...groups].sort(([a, ga], [b, gb]) => (a === 'minecraft' ? -1 : b === 'minecraft' ? 1 : a === 'unknown' ? 1 : b === 'unknown' ? -1 : ga.name.localeCompare(gb.name)));
    $('pn-cmd-groups').innerHTML = order.map(([id, g]) => `<section class="np-card pn-cmd-group" id="cmd-${esc(id)}"${id === 'minecraft' ? ' data-folded' : ''}>
        <h3 class="np-card-title"><i class="bi ${id === 'minecraft' ? 'bi-box' : id === 'unknown' ? 'bi-question-circle' : 'bi-puzzle'}"></i> ${esc(g.name)} <span class="pn-count">${g.list.length}</span></h3>
        <div class="pn-cmd-list">${g.list.map((c) => cmdRow(c, false) + (c.subs || []).map((sc) => cmdRow(sc, true)).join('')).join('')}</div>
      </section>`).join('');
    setupFolding($('pn-cmd-groups'));
    filterCommands();
  }

  function filterCommands() {
    const words = $('pn-cmd-q').value.trim().toLowerCase().split(/\s+/).filter(Boolean);
    const who = document.querySelector('#pn-cmd-who .active').dataset.who;
    document.querySelectorAll('#pn-cmd-groups .pn-cmd-group').forEach((g) => {
      let shown = 0;
      g.querySelectorAll('.pn-cmd').forEach((row) => {
        const level = Number(row.dataset.level);
        const ok = words.every((w) => row.dataset.search.includes(w)) && (who === 'all' || (who === 'everyone' ? level === 0 : level !== 0));
        row.hidden = !ok;
        if (ok) shown++;
      });
      g.hidden = shown === 0;
      // While searching, matches show even in folded boxes.
      g.classList.toggle('pn-cmd-searching', words.length > 0);
    });
  }

  $('pn-cmd-q').addEventListener('input', filterCommands);
  $('pn-cmd-who').addEventListener('click', (e) => {
    const b = e.target.closest('[data-who]');
    if (!b) return;
    $('pn-cmd-who').querySelectorAll('.np-filter').forEach((x) => x.classList.toggle('active', x === b));
    filterCommands();
  });

  $('pn-cmd-groups').addEventListener('click', async (e) => {
    const b = e.target.closest('[data-cmd-edit]');
    if (!b) return;
    const name = b.dataset.cmdEdit;
    const c = cmdData.commands.flatMap((x) => [x, ...(x.subs || [])]).find((x) => x.name === name);
    const box = b.closest('.pn-cmd-desc');
    box.innerHTML = `<form class="pn-cmd-form"><input class="np-input" maxlength="300" value="${esc(c.custom ? c.description : '')}" placeholder="${esc(c.description || 'What does it do?')}">
      <button class="np-btn small primary">Save</button><button type="button" class="np-btn small" data-cancel>Cancel</button></form>`;
    const form = box.querySelector('form');
    form.querySelector('input').focus();
    form.querySelector('[data-cancel]').onclick = () => renderCommands();
    form.onsubmit = async (ev) => {
      ev.preventDefault();
      try {
        await api('api/commands/describe', { name, description: form.querySelector('input').value });
        loadCommands();
      } catch (err) {
        alert(err.message);
      }
    };
  });

  // ── Folding cards (like the dashboard's): click a card's title ───────────────

  const FOLD_KEY = 'pn-folded';
  let folded = {};
  try { folded = JSON.parse(localStorage.getItem(FOLD_KEY) || '{}') || {}; } catch (e) { /* none folded */ }

  function setupFolding(root) {
    root.querySelectorAll('.np-card').forEach((card) => {
      const title = card.querySelector(':scope > .np-card-title');
      if (!title || title.dataset.fold || card.closest('#pn-login')) return;
      title.dataset.fold = '1';
      const page = card.closest('.np-tab-panel');
      const id = card.id || (page ? page.dataset.tab : '') + ':' + title.textContent.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-');
      title.setAttribute('role', 'button');
      title.setAttribute('tabindex', '0');
      title.insertAdjacentHTML('beforeend', '<i class="bi bi-chevron-down np-collapse-caret"></i>');
      const apply = (on) => {
        card.classList.toggle('collapsed', on);
        title.setAttribute('aria-expanded', String(!on));
      };
      apply(id in folded ? folded[id] : card.hasAttribute('data-folded'));
      const toggle = (e) => {
        // Buttons and fields inside a title keep working.
        if (e.target.closest('button, a, input, select, label') && e.target.closest('button, a, input, select, label') !== title) return;
        const on = !card.classList.contains('collapsed');
        apply(on);
        folded[id] = on;
        try { localStorage.setItem(FOLD_KEY, JSON.stringify(folded)); } catch (err) { /* this visit only */ }
        if (!on && card.id === 'pn-stats-days' && statsData) renderStats(statsData);
      };
      title.addEventListener('click', toggle);
      title.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          toggle(e);
        }
      });
    });
  }
  setupFolding($('pn-app'));

  // ── Help for technical terms ─────────────────────────────────────────────

  const HELP = {
    mspt: 'Milliseconds per tick: how long the server needs for one game step. Under 50 is fine; above 50 the game slows down.',
    tps: 'Ticks per second: 20 means the game runs at full speed.',
    sim: 'Simulation distance: how far around players crops grow, mobs move and redstone runs (in chunks of 16 blocks).',
    view: 'View distance: how far players can see (in chunks). Costs memory and bandwidth, less so tick time.',
    soft: 'Soft border: the permanent area. Nothing inside it is ever reset.',
    hard: 'Hard border: the edge of the world. Nobody gets past it.',
  };
  function addHelp(root) {
    root.querySelectorAll('[data-help]:not([data-help-done])').forEach((el) => {
      el.dataset.helpDone = '1';
      const tip = HELP[el.dataset.help];
      if (!tip) return;
      const q = document.createElement('i');
      q.className = 'bi bi-question-circle pn-help';
      q.tabIndex = 0;
      q.title = tip;
      q.setAttribute('aria-label', tip);
      el.after(q);
    });
  }
  addHelp(document);

  // ── Themes and profile pictures ───────────────────────────────────────────

  const THEMES = [
    { id: 'dark', name: 'Dark', colors: ['#0b1018', '#121825', '#b6c8ff'] },
    { id: 'light', name: 'Light', colors: ['#f3f5f9', '#ffffff', '#3550c8'] },
    { id: 'midnight', name: 'Midnight', colors: ['#05070c', '#0c1019', '#8fb1ff'] },
    { id: 'forest', name: 'Forest', colors: ['#0b130e', '#122019', '#8fe3a8'] },
    { id: 'nether', name: 'Nether', colors: ['#150a09', '#22110e', '#ff9d6b'] },
    { id: 'end', name: 'The End', colors: ['#0d0a16', '#171225', '#d0a6ff'] },
    { id: 'ocean', name: 'Ocean', colors: ['#071217', '#0d1f27', '#7fdcf0'] },
    { id: 'sakura', name: 'Sakura', colors: ['#fbf3f6', '#ffffff', '#c2417a'] },
  ];
  const LIGHT_THEMES = ['light', 'sakura'];

  /** Applies a theme here and in the dashboard frame; saved in this browser and (save) with the account. */
  function setTheme(id, save) {
    document.documentElement.dataset.theme = id;
    try { localStorage.setItem('np-theme', id); } catch (e) { /* not remembered */ }
    try { $('pn-dashboard-frame').contentDocument.documentElement.dataset.theme = id; } catch (e) { /* not loaded */ }
    renderTheme();
    renderThemes();
    if (save && loggedIn) api('api/me/theme', { theme: id }).catch(() => {});
  }

  function renderThemes() {
    const box = $('pn-themes');
    if (!box) return;
    const now = document.documentElement.dataset.theme;
    box.innerHTML = THEMES.map((t) => `<button type="button" class="pn-theme-card${t.id === now ? ' active' : ''}" data-theme-pick="${t.id}" aria-pressed="${t.id === now}">
      <span class="pn-theme-swatch" style="background:${t.colors[0]}"><span style="background:${t.colors[1]}"></span><i style="background:${t.colors[2]}"></i></span>${esc(t.name)}</button>`).join('');
  }
  document.addEventListener('click', (e) => {
    const b = e.target.closest('[data-theme-pick]');
    if (b) setTheme(b.dataset.themePick, true);
  });

  /** The account's picture in the navbar and on the Account page, or its first letter. */
  function showAvatar(fresh) {
    const url = me && me.avatar ? `api/avatar/${encodeURIComponent(me.user)}${fresh ? '?t=' + Date.now() : ''}` : null;
    for (const el of [$('pn-avatar'), $('pn-profile-avatar')]) {
      if (!el) continue;
      el.textContent = url ? '' : (me ? me.user : '?').slice(0, 1).toUpperCase();
      el.style.backgroundImage = url ? `url("${url}")` : '';
      el.classList.toggle('pic', !!url);
    }
    $('pn-avatar-remove').hidden = !(me && me.avatar);
  }

  $('pn-avatar-file').addEventListener('change', async (e) => {
    const file = e.target.files[0];
    e.target.value = '';
    if (!file) return;
    const form = new FormData();
    form.append('avatar', file);
    try {
      const res = await fetch('api/me/avatar', { method: 'POST', headers: { 'X-NCC': '1' }, body: form });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || res.statusText);
      me.avatar = true;
      showAvatar(true);
      formMsg('pn-avatar-msg', 'Saved.', true);
    } catch (err) {
      formMsg('pn-avatar-msg', err.message, false);
    }
  });
  $('pn-avatar-remove').addEventListener('click', async () => {
    try {
      await api('api/me/avatar/remove', {});
      me.avatar = false;
      showAvatar(true);
    } catch (err) {
      formMsg('pn-avatar-msg', err.message, false);
    }
  });

  // ── Theme ──────────────────────────────────────────────────────────────

  function renderTheme() {
    const light = LIGHT_THEMES.includes(document.documentElement.dataset.theme);
    $('pn-theme').innerHTML = light ? '<i class="bi bi-moon-stars"></i>' : '<i class="bi bi-sun"></i>';
    $('pn-theme').title = light ? 'Switch to dark mode' : 'Switch to light mode';
  }
  // Quick switch between light and dark; the other themes are under your account.
  $('pn-theme').addEventListener('click', () => setTheme(LIGHT_THEMES.includes(document.documentElement.dataset.theme) ? 'dark' : 'light', true));
  renderTheme();

  // ── Claims ─────────────────────────────────────────────────────────────

  let clData = null;
  /** The claim open in the editor; {} for a new server claim. */
  let clOpen = null;
  /** Members as edited (uuid → {name, role}); saved with the claim. */
  let clMembers = [];

  const dimShort = (d) => { const n = dimName(d); return n === d ? d.replace(/^minecraft:/, '').replace(/_/g, ' ') : n; };
  const roleLabel = (id) => (clData && clData.roles.find((r) => r.id === id) || { label: id }).label;

  function clNotice(d) {
    const notes = [];
    if (!d.enabled) notes.push('Claims are <b>off</b>: nothing is protected and players can\'t claim. Switch them on under Settings.');
    if (!d.running) notes.push(d.hasWorld ? 'The server is off: you\'re editing the world file.' : 'There\'s no world yet.');
    if (d.busy) notes.push(esc(d.busy));
    const db = d.database;
    if (!db.configured) notes.push('No database: claims work, but aren\'t copied for a website.');
    else if (!db.ready || db.problem) notes.push('Database: ' + esc(db.problem || 'not ready'));
    else if (db.syncedAt) notes.push('Copied to the database ' + esc(dateTime(db.syncedAt)) + '.');
    return notes.join(' ');
  }

  async function loadClaims() {
    try {
      clData = await api('api/claims');
    } catch (e) {
      $('pn-cl-intro').textContent = e.message;
      return;
    }
    $('pn-cl-intro').innerHTML = 'Land players protect with the claim tool. ' + clNotice(clData);
    const dims = [...new Set(clData.claims.map((c) => c.dim))].sort();
    const sel = $('pn-cl-dim');
    const keep = sel.value;
    sel.innerHTML = '<option value="">All dimensions</option>' + dims.map((d) => `<option value="${esc(d)}">${esc(dimShort(d))}</option>`).join('');
    sel.value = dims.includes(keep) ? keep : '';
    $('pn-cl-add-role').innerHTML = clData.roles.map((r) => `<option value="${r.id}" ${r.id === 'BUILD' ? 'selected' : ''}>${esc(r.label)}</option>`).join('');
    renderClaims();
  }

  function renderClaims() {
    if (!clData) return;
    const q = $('pn-cl-search').value.trim().toLowerCase();
    const dim = $('pn-cl-dim').value;
    const byId = Object.fromEntries(clData.claims.map((c) => [c.id, c]));
    const rows = clData.claims.filter((c) => (!dim || c.dim === dim) && (!q || [c.displayName, c.ownerName || 'server', ...c.members.map((m) => m.name)]
      .some((t) => String(t).toLowerCase().includes(q))))
      .sort((a, b) => (a.parent ? byId[a.parent]?.displayName || '' : a.displayName).localeCompare(b.parent ? byId[b.parent]?.displayName || '' : b.displayName)
        || (a.parent ? 1 : 0) - (b.parent ? 1 : 0) || a.displayName.localeCompare(b.displayName));
    $('pn-claims-table').innerHTML = rows.length ? `<thead><tr><th>Claim</th><th>Owner</th><th>Where</th><th>Size</th><th>Members</th><th>Changed</th></tr></thead><tbody>${rows.map((c) => `
      <tr data-id="${esc(c.id)}" class="pn-cl-row">
        <td>${c.parent ? '<span class="pn-hint">↳</span> ' : ''}<span class="pn-cl-dot" style="background:${esc(c.color)}"></span>${esc(c.displayName)}${c.subclaims ? ` <small class="pn-hint">${c.subclaims} sub</small>` : ''}</td>
        <td>${esc(c.ownerName || 'Server')}</td>
        <td>${esc(dimShort(c.dim))} ${Math.round((c.minX + c.maxX) / 2)}, ${Math.round((c.minZ + c.maxZ) / 2)}</td>
        <td>${c.width} × ${c.length} <small class="pn-hint">${c.area.toLocaleString()}</small></td>
        <td>${c.members.length ? esc(c.members.map((m) => m.name).join(', ')) : '<span class="pn-hint">–</span>'}</td>
        <td>${esc(dateTime(c.updated))}</td></tr>`).join('')}</tbody>`
      : `<tbody><tr><td class="pn-hint">${clData.claims.length ? 'Nothing matches.' : 'No claims yet.'}</td></tr></tbody>`;
  }

  function openClaim(c) {
    clOpen = c || {};
    const isNew = !c;
    const f = $('pn-cl-form');
    $('pn-cl-detail').hidden = false;
    $('pn-cl-title').textContent = isNew ? 'New server claim' : c.displayName;
    $('pn-cl-sub').textContent = isNew ? 'A claim the server owns: no claim blocks needed, members as you like. Leave the owner empty, or give it to a player.'
      : `${c.parent ? 'Subclaim · ' : ''}${dimShort(c.dim)} · ${c.width} × ${c.length} (${c.area.toLocaleString()} blocks) · made ${dateTime(c.created)}`;
    f.querySelectorAll('[data-new]').forEach((el) => { el.hidden = !isNew; });
    f.querySelectorAll('[data-old]').forEach((el) => { el.hidden = isNew; });
    f.name.value = c ? c.name : '';
    f.color.value = c ? c.color.toLowerCase() : '#f2b233';
    f.ownerName.value = c ? c.ownerName || '' : '';
    f.ownerName.disabled = !!(c && c.parent);
    for (const k of ['minX', 'minZ', 'maxX', 'maxZ']) f[k].value = c ? c[k] : '';
    $('pn-cl-flags').innerHTML = clData.flags.map((fl) => `<label class="np-check"><input type="checkbox" name="flag-${fl.id}" ${c && c.flags[fl.id] ? 'checked' : ''}> ${esc(fl.label)}</label>`).join('');
    clMembers = c ? c.members.map((m) => ({ ...m })) : [];
    renderClMembers();
    const editable = can('claims.manage');
    f.querySelectorAll('input, select, button[type=submit], #pn-cl-delete, #pn-cl-add-btn').forEach((el) => { if (el.id !== 'pn-cl-close') el.disabled = !editable || (el === f.ownerName && c && c.parent); });
    Dirty.reset('claim');
    $('pn-cl-detail').scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  function renderClMembers() {
    $('pn-cl-members').innerHTML = clMembers.length ? clMembers.map((m, i) => `<div class="pn-cl-member">
      <span>${esc(m.name)}</span>
      <select data-i="${i}" aria-label="Role of ${esc(m.name)}">${clData.roles.map((r) => `<option value="${r.id}" ${r.id === m.role ? 'selected' : ''}>${esc(r.label)}</option>`).join('')}</select>
      <button type="button" class="np-btn small" data-remove="${i}" title="Remove"><i class="bi bi-x-lg"></i></button></div>`).join('')
      : '<p class="pn-hint">No members: only the owner can do anything here.</p>';
  }

  $('pn-cl-members').addEventListener('change', (e) => {
    const i = e.target.dataset.i;
    if (i != null) clMembers[i].role = e.target.value;
  });
  $('pn-cl-members').addEventListener('click', (e) => {
    const b = e.target.closest('[data-remove]');
    if (!b) return;
    clMembers.splice(Number(b.dataset.remove), 1);
    renderClMembers();
  });
  $('pn-cl-add-btn').addEventListener('click', () => {
    const name = $('pn-cl-add-name').value.trim();
    if (!name) return;
    if (!clMembers.some((m) => m.name.toLowerCase() === name.toLowerCase())) clMembers.push({ name, role: $('pn-cl-add-role').value, added: true });
    $('pn-cl-add-name').value = '';
    renderClMembers();
  });
  $('pn-cl-search').addEventListener('input', renderClaims);
  $('pn-cl-dim').addEventListener('change', renderClaims);
  $('pn-claims-table').addEventListener('click', (e) => {
    const row = e.target.closest('.pn-cl-row');
    if (row) openClaim(clData.claims.find((c) => c.id === row.dataset.id));
  });
  $('pn-cl-new').addEventListener('click', () => openClaim(null));
  $('pn-cl-close').addEventListener('click', () => { $('pn-cl-detail').hidden = true; clOpen = null; Dirty.reset('claim'); });
  $('pn-cl-delete').addEventListener('click', async () => {
    if (!clOpen || !clOpen.id) return;
    if (!confirm(`Delete ${clOpen.displayName}${clOpen.subclaims ? ' and its subclaims' : ''}? The land is free to build on again.`)) return;
    try {
      await api('api/claims/action', { action: 'delete', claim: clOpen.id });
      $('pn-cl-detail').hidden = true;
      clOpen = null;
      Dirty.reset('claim');
      loadClaims();
    } catch (err) {
      formMsg('pn-cl-msg', err.message, false);
    }
  });

  $('pn-cl-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target;
    const c = clOpen;
    const box = Object.fromEntries(['minX', 'minZ', 'maxX', 'maxZ'].map((k) => [k, Number(f[k].value)]));
    if (['minX', 'minZ', 'maxX', 'maxZ'].some((k) => f[k].value === '')) return formMsg('pn-cl-msg', 'Fill in both corners.', false);
    const owner = f.ownerName.value.trim();
    try {
      if (!c.id) {
        await api('api/claims/action', { action: 'create', dim: f.dim.value.trim(), name: f.name.value, ownerName: owner, ...box });
      } else {
        const steps = [];
        const flags = Object.fromEntries(clData.flags.map((fl) => [fl.id, f['flag-' + fl.id].checked]));
        steps.push({ action: 'update', name: f.name.value, color: f.color.value, flags });
        if (['minX', 'minZ', 'maxX', 'maxZ'].some((k) => box[k] !== c[k])) steps.push({ action: 'resize', ...box });
        if (!c.parent && owner.toLowerCase() !== (c.ownerName || '').toLowerCase()) steps.push(owner ? { action: 'transfer', ownerName: owner } : { action: 'transfer', owner: '' });
        for (const m of c.members) if (!clMembers.some((x) => x.uuid === m.uuid)) steps.push({ action: 'member_remove', uuid: m.uuid });
        for (const m of clMembers) {
          const was = c.members.find((x) => x.uuid && x.uuid === m.uuid);
          if (!was || was.role !== m.role) steps.push(m.uuid ? { action: 'member_set', uuid: m.uuid, name: m.name, role: m.role } : { action: 'member_set', playerName: m.name, role: m.role });
        }
        for (const s of steps) await api('api/claims/action', { claim: c.id, ...s });
      }
      formMsg('pn-cl-msg', 'Saved.', true);
      await loadClaims();
      const fresh = c.id ? clData.claims.find((x) => x.id === c.id) : null;
      if (fresh) openClaim(fresh);
      else { $('pn-cl-detail').hidden = true; Dirty.reset('claim'); }
    } catch (err) {
      formMsg('pn-cl-msg', err.message, false);
      if (c.id) loadClaims();
    }
  });

  // ── Claims → Claim blocks ──────────────────────────────────────────────

  async function loadClaimBlocks() {
    try {
      clData = await api('api/claims');
    } catch (e) {
      return formMsg('pn-cb-msg', e.message, false);
    }
    renderClaimBlocks();
  }

  function renderClaimBlocks() {
    const q = $('pn-cb-search').value.trim().toLowerCase();
    const rows = clData.players.filter((p) => !q || String(p.name || p.uuid).toLowerCase().includes(q)).sort((a, b) => b.used - a.used || String(a.name).localeCompare(String(b.name)));
    const edit = can('claims.manage');
    $('pn-cb-table').innerHTML = rows.length ? `<thead><tr><th>Player</th><th>Available</th><th>From playing</th><th>Bought</th><th>Bonus</th><th>Used</th><th>Claims</th><th>Played</th><th>Last seen</th></tr></thead><tbody>${rows.map((p) => `
      <tr><td>${esc(p.name || p.uuid)}</td><td><b>${p.available.toLocaleString()}</b></td><td>${p.accrued.toLocaleString()}</td><td>${p.bought.toLocaleString()}</td>
      <td>${edit ? `<input type="number" class="pn-cb-bonus" data-uuid="${esc(p.uuid)}" data-name="${esc(p.name || '')}" value="${p.bonus}" aria-label="Bonus for ${esc(p.name)}">` : p.bonus.toLocaleString()}</td>
      <td>${p.used.toLocaleString()}</td><td>${p.claims}</td><td>${duration2(p.playMinutes * 60)}</td><td>${p.lastSeen ? esc(dateTime(p.lastSeen)) : '–'}</td></tr>`).join('')}</tbody>`
      : '<tbody><tr><td class="pn-hint">Nobody has earned claim blocks yet.</td></tr></tbody>';
  }

  $('pn-cb-search').addEventListener('input', () => clData && renderClaimBlocks());
  $('pn-cb-table').addEventListener('change', async (e) => {
    const input = e.target.closest('.pn-cb-bonus');
    if (!input) return;
    try {
      await api('api/claims/action', { action: 'blocks', uuid: input.dataset.uuid, name: input.dataset.name, bonus: Number(input.value) || 0 });
      formMsg('pn-cb-msg', `Bonus for ${input.dataset.name || 'the player'} saved.`, true);
      loadClaimBlocks();
    } catch (err) {
      formMsg('pn-cb-msg', err.message, false);
    }
  });

  // ── Claims → Settings ──────────────────────────────────────────────────

  let csData = null;
  const CS_NUM = ['startingBlocks', 'blocksPerHour', 'maxAccruedBlocks', 'afkMinutes', 'minWidth', 'minArea', 'maxClaims', 'inactiveDays',
    'visualSpacing', 'visualRange', 'visualSeconds'];
  const CS_TEXT = ['toolItem', 'visualCorner', 'visualEdge', 'visualSubCorner', 'visualSubEdge', 'visualSelection'];
  const CS_BOOL = ['enabled', 'accrueWhileDisabled', 'onlyPermanentArea', 'hubsOnlyPermanentArea', 'hubsOwnerMenu'];
  const CS_FLAGS = [['access', 'Use doors, buttons and levers'], ['containers', 'Open chests and interact with entities'], ['pvp', 'Fight (PvP)'],
    ['explosions', 'Explosions break blocks'], ['fireSpread', 'Fire spreads'], ['mobGriefing', 'Mobs change blocks']];

  async function loadClaimSettings() {
    try {
      csData = await api('api/claims/settings');
    } catch (e) {
      return formMsg('pn-cs-msg', e.message, false);
    }
    const s = csData.settings;
    const f = $('pn-cs-form');
    CS_BOOL.forEach((k) => { f[k].checked = !!s[k]; });
    CS_NUM.forEach((k) => { f[k].value = s[k]; });
    CS_TEXT.forEach((k) => { f[k].value = s[k]; });
    $('pn-cs-dims').innerHTML = csData.dimensions.map((d) => `<label class="np-check"><input type="checkbox" name="dim" value="${esc(d.id)}" ${s.dimensions.includes(d.id) ? 'checked' : ''}>
      ${esc(d.name)}${d.missing ? ' <small class="pn-hint">(not in this world)</small>' : ''}</label>`).join('');
    const hubDenied = s.hubsDeniedDimensions || [];
    $('pn-cs-hubdims').innerHTML = csData.dimensions.map((d) => `<label class="np-check"><input type="checkbox" name="hubdim" value="${esc(d.id)}" ${hubDenied.includes(d.id) ? 'checked' : ''}> ${esc(d.name)}</label>`).join('');
    $('pn-cs-flags').innerHTML = CS_FLAGS.map(([k, l]) => `<label class="np-check"><input type="checkbox" name="df-${k}" ${s.defaultFlags[k] ? 'checked' : ''}> ${esc(l)}</label>`).join('');
    // The usual palette plus colours added in the file by hand.
    const colors = { ...csData.defaults.colors, ...s.colors };
    $('pn-cs-colors').innerHTML = Object.entries(colors).map(([n, c]) => `<label class="np-check"><input type="checkbox" name="color" value="${esc(n)}" data-hex="${esc(c)}" ${n in s.colors ? 'checked' : ''}>
      <span class="pn-cl-dot" style="background:${esc(c)}"></span>${esc(n)}</label>`).join('');
    const areas = Object.entries(csData.permanentAreas);
    $('pn-cs-areas').innerHTML = areas.length ? 'Permanent areas: ' + areas.map(([d, a]) => `${esc(dimShort(d))} ${a.shape === 'circle' ? 'radius' : '±'} ${a.radius.toLocaleString()} around ${a.x}, ${a.z}`).join(' · ')
      + ' (set under <a href="#world/borders-reset">World → Borders &amp; reset</a>). Dimensions without one have no limit.'
      : 'No permanent area is set (World → Borders & reset), so players can claim anywhere in the dimensions below.';
    const editable = can('claims.manage');
    f.querySelectorAll('input, textarea, button').forEach((el) => { el.disabled = !editable; });
    Dirty.reset('claimsettings');
  }

  $('pn-cs-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target;
    const body = {};
    CS_BOOL.forEach((k) => { body[k] = f[k].checked; });
    CS_NUM.forEach((k) => { body[k] = Number(f[k].value) || 0; });
    CS_TEXT.forEach((k) => { body[k] = f[k].value.trim(); });
    body.dimensions = [...f.querySelectorAll('input[name=dim]:checked')].map((i) => i.value);
    body.hubsDeniedDimensions = [...f.querySelectorAll('input[name=hubdim]:checked')].map((i) => i.value);
    body.defaultFlags = Object.fromEntries(CS_FLAGS.map(([k]) => [k, f['df-' + k].checked]));
    body.colors = Object.fromEntries([...f.querySelectorAll('input[name=color]:checked')].map((i) => [i.value, i.dataset.hex]));
    if (!Object.keys(body.colors).length) return formMsg('pn-cs-msg', 'Pick at least one colour.', false);
    if (body.enabled && !body.dimensions.length) return formMsg('pn-cs-msg', 'Pick at least one dimension, or nobody can claim.', false);
    if (body.enabled && !csData.settings.enabled && !confirm('Switch claims on? Players can claim land and claimed land is protected right away.')) return;
    try {
      const res = await api('api/claims/settings', body);
      formMsg('pn-cs-msg', res.running ? 'Saved, applies within a few seconds.' : 'Saved; applies when the server starts.', true);
      loadClaimSettings();
    } catch (err) {
      formMsg('pn-cs-msg', err.message, false);
    }
  });

  // ── Claims → GOML import ───────────────────────────────────────────────

  function renderGoml(d) {
    const list = d.claims || [];
    $('pn-gi-run').disabled = !list.some((c) => !c.problem && !c.imported);
    $('pn-gi-table').innerHTML = list.length ? `<thead><tr><th>Owner</th><th>Where</th><th>Size</th><th>Members</th><th></th></tr></thead><tbody>${list.map((c) => `
      <tr><td>${esc(c.owner)}</td><td>${esc(dimShort(c.dim))} ${c.x}, ${c.z}</td><td>${c.size} × ${c.size}</td><td>${esc(c.members.join(', ')) || '–'}</td>
      <td>${c.imported ? '<b>imported</b>' : c.problem ? `<span class="pn-hint">${esc(c.problem)}</span>` : 'ready'}</td></tr>`).join('')}</tbody>`
      : `<tbody><tr><td class="pn-hint">${d.installed === false ? 'GOML isn\'t installed on the server.' : 'GOML has no claims.'}</td></tr></tbody>`;
  }

  $('pn-gi-preview').addEventListener('click', async () => {
    try {
      const d = await api('api/claims/goml');
      renderGoml(d);
      const ready = (d.claims || []).filter((c) => !c.problem).length;
      formMsg('pn-gi-msg', `${(d.claims || []).length} GOML claims, ${ready} can be imported.`, true);
    } catch (err) {
      formMsg('pn-gi-msg', err.message, false);
    }
  });
  $('pn-gi-run').addEventListener('click', async () => {
    if (!confirm('Import the GOML claims marked "ready"? Their owners get claim blocks for them.')) return;
    try {
      const d = await api('api/claims/goml/import', {});
      renderGoml(d);
      formMsg('pn-gi-msg', `${d.imported} claims imported.`, true);
    } catch (err) {
      formMsg('pn-gi-msg', err.message, false);
    }
  });

  // ── Tweaks ─────────────────────────────────────────────────────────────

  let twData = null;
  /** Fields named "section.key" in a tweaks form. */
  const twGet = (o, path) => path.split('.').reduce((v, k) => (v == null ? v : v[k]), o);
  const twSet = (o, path, value) => { const ks = path.split('.'); const last = ks.pop(); ks.reduce((v, k) => v[k], o)[last] = value; };

  function twFill(form) {
    for (const el of form.elements) {
      if (!el.name || !el.name.includes('.')) continue;
      const v = twGet(twData.settings, el.name);
      if (el.type === 'checkbox') el.checked = !!v;
      else if (Array.isArray(v)) el.value = v.join(', ');
      else el.value = v ?? '';
    }
    const editable = can('settings.server');
    form.querySelectorAll('input, select, textarea, button').forEach((el) => { el.disabled = !editable; });
  }

  async function loadTweaks() {
    try {
      twData = await api('api/tweaks');
    } catch (e) {
      formMsg('pn-tw-msg', e.message, false);
      formMsg('pn-tf-msg', e.message, false);
      formMsg('pn-tc-msg', e.message, false);
      return;
    }
    twFill($('pn-tw-form'));
    twFill($('pn-tf-form'));
    twFill($('pn-tc-form'));
    twDims();
    twSaplingWarning();
    Dirty.reset('tweaks');
    Dirty.reset('felling');
    Dirty.reset('chattweaks');
  }

  /** Performance → quick despawn removes saplings before they could plant themselves. */
  function twSaplingWarning() {
    const q = twData.quickDespawn || {};
    const s = twData.settings.saplings;
    const hits = q.enabled && (q.items || []).some((i) => /sapling|propagule/.test(i));
    const warn = $('pn-sap-warn');
    warn.hidden = !(s.replant && hits && q.seconds <= s.delaySeconds);
    warn.textContent = `Tuning → item despawn removes saplings after ${q.seconds} seconds, before they plant themselves; set the delay below that or take saplings off that list.`;
  }

  /** The tab list's dimension names and colours: one row per dimension of the world. */
  function twDims() {
    const set = twData.settings.tabList.dimensions || {};
    const pretty = (id) => { const p = id.replace(/^[^:]*:/, '').replace(/^the_/, '').replace(/_/g, ' '); return p.charAt(0).toUpperCase() + p.slice(1); };
    const editable = can('settings.server');
    $('pn-tw-dims').innerHTML = (twData.dimensions || []).map((d) => {
      const v = set[d.id] || {};
      const color = /^#[0-9a-fA-F]{6}$/.test(v.color || '') ? v.color : '#AAAAAA';
      return `<tr data-dim="${esc(d.id)}"><td><code>${esc(d.id)}</code></td>
        <td><input type="text" data-f="name" maxlength="32" value="${esc(v.name || '')}" placeholder="${esc(pretty(d.id))}" ${editable ? '' : 'disabled'}></td>
        <td><input type="color" data-f="color" value="${esc(color)}" ${editable ? '' : 'disabled'}></td>
        <td><span class="pn-tw-dim-preview"><span data-f="preview" style="color:${esc(color)}">${esc(v.name || pretty(d.id))}</span> Steve</span></td></tr>`;
    }).join('') || '<tr><td colspan="4">No dimensions found yet: start the server once.</td></tr>';
    $('pn-tw-dims').querySelectorAll('tr[data-dim]').forEach((tr) => {
      const upd = () => {
        const pv = tr.querySelector('[data-f=preview]');
        pv.textContent = tr.querySelector('[data-f=name]').value || tr.querySelector('[data-f=name]').placeholder;
        pv.style.color = tr.querySelector('[data-f=color]').value;
      };
      tr.addEventListener('input', upd);
    });
  }

  async function saveTweaks(e, msgId) {
    e.preventDefault();
    const next = JSON.parse(JSON.stringify(twData.settings));
    if (e.target.contains($('pn-tw-dims'))) {
      const dims = {};
      $('pn-tw-dims').querySelectorAll('tr[data-dim]').forEach((tr) => {
        dims[tr.dataset.dim] = { name: tr.querySelector('[data-f=name]').value.trim(), color: tr.querySelector('[data-f=color]').value.toUpperCase() };
      });
      next.tabList.dimensions = dims;
    }
    for (const el of e.target.elements) {
      if (!el.name || !el.name.includes('.')) continue;
      const before = twGet(twData.settings, el.name);
      if (el.type === 'number' && el.step && el.step !== '1') { twSet(next, el.name, Number(el.value) || 0); continue; }
      let v = el.type === 'checkbox' ? el.checked : el.value;
      if (Array.isArray(before)) v = String(v).split(',').map((x) => x.trim()).filter(Boolean);
      else if (typeof before === 'number') v = Number(v) || 0;
      twSet(next, el.name, v);
    }
    try {
      const res = await api('api/tweaks', next);
      formMsg(msgId, res.running ? 'Saved, applies within a few seconds.' : 'Saved; applies when the server starts.', true);
      loadTweaks();
    } catch (err) {
      formMsg(msgId, err.message, false);
    }
  }

  $('pn-tw-form').addEventListener('submit', (e) => saveTweaks(e, 'pn-tw-msg'));
  $('pn-tf-form').addEventListener('submit', (e) => saveTweaks(e, 'pn-tf-msg'));
  $('pn-tc-form').addEventListener('submit', (e) => saveTweaks(e, 'pn-tc-msg'));

  // ── Community → Market (player market and server shop) ──────────────────

  /** When a listing (pet, claim blocks, command) is sold: always, until a day, or ended. */
  const ptSale = (p) => !p.saleEnds ? 'always' : (p.saleEnds < Date.now() ? '<span class="pn-warn">ended</span> ' : 'until ') + esc(new Date(p.saleEnds).toLocaleDateString([], { dateStyle: 'medium' }));
  /** The yyyy-mm-dd of a time, in local time (for the date field). */
  const ptDay = (t) => { const d = new Date(t); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; };
  let mkData = null;
  let mkSettings = null;
  let ptData = null;
  let ptEditing = null;
  let ssClaimEditing = null;
  let ssCmdEditing = null;

  async function loadMarket() {
    try {
      [mkData, mkSettings] = await Promise.all([api('api/market'), api('api/market/settings')]);
    } catch (e) {
      formMsg('pn-mk-msg', e.message, false);
      return formMsg('pn-ss-claim-msg', e.message, false);
    }
    const s = mkSettings.settings;
    const f = $('pn-mk-form');
    f.enabled.checked = s.enabled;
    f.currency.value = s.currency;
    f.listingDays.value = s.listingDays;
    f.maxListings.value = s.maxListings;
    f.maxPrice.value = s.maxPrice;
    f.bannedItems.value = s.bannedItems.join(', ');
    const edit = can('settings.server');
    f.querySelectorAll('input, button').forEach((el) => { el.disabled = !edit; });
    $('pn-mk-intro').textContent = (mkData.enabled ? '' : 'The market is closed. ') + (mkData.running ? '' : 'The server is off: this is the last saved state. ')
      + (mkData.icons ? 'Icons: ' + mkData.icons : '');
    $('pn-ss-max').claimBlocksMax.value = s.claimBlocksMax || 0;
    $('pn-ss-max').querySelectorAll('input, button').forEach((el) => { el.disabled = !edit; });
    $('pn-ss-claim-new').hidden = !edit;
    $('pn-ss-cmd-new').hidden = !edit || !mkSettings.canCommands;
    if (!edit) ssClaimOpen(null);
    if (!edit || !mkSettings.canCommands) ssCmdOpen(null);
    const cur = s.currency.replace(/^[^:]+:/, '').replace(/_/g, ' ');
    document.querySelectorAll('.pn-ss-cur').forEach((el) => { el.textContent = `In ${cur}s (the market's currency)`; });
    $('pn-ss-closed').hidden = s.enabled;
    $('pn-ss-form').serverName.value = s.serverName || 'the server';
    $('pn-ss-form').querySelectorAll('input, button').forEach((el) => { el.disabled = !edit; });
    $('pn-mk-vars').innerHTML = 'Use ' + (mkSettings.variables || []).map((v) => `<code>${esc(v)}</code>`).join(' ') + ' for the buyer (name, UUID, block position, dimension).';
    renderMarket();
    renderServerShop();
    Dirty.reset('market');
    Dirty.reset('servershop');
    Dirty.reset('ssmax');
  }

  function renderMarket() {
    if (!mkData) return;
    const q = $('pn-mk-search').value.trim().toLowerCase();
    const rows = mkData.listings.filter((l) => !q || [l.name, l.seller, l.item].some((t) => String(t).toLowerCase().includes(q)));
    const remove = can('players.manage');
    $('pn-mk-table').innerHTML = rows.length ? `<thead><tr><th>Item</th><th>Seller</th><th>Left</th><th>Price</th><th>Ends</th><th></th></tr></thead><tbody>${rows.map((l) => `
      <tr><td class="pn-mk-item">${l.icon ? `<img src="info/items/${esc(l.icon)}" alt="" width="24" height="24">` : ''}<span>${mcHtml(l.name)}<small class="pn-hint">${[...(l.enchantments || []), ...(l.lore || [])].map(mcHtml).join(' · ')}</small></span></td>
      <td>${esc(l.seller)}</td><td>${Number(l.amount).toLocaleString()}</td><td>${Number(l.price).toLocaleString()}${l.per > 1 ? ` per ${Number(l.per).toLocaleString()}` : ' each'}</td><td>${esc(dateTime(l.expires))}</td>
      <td>${remove ? `<button type="button" class="np-btn small" data-remove="${esc(l.id)}" title="Back to the seller's mailbox"><i class="bi bi-x-lg"></i> Take off</button>` : ''}</td></tr>`).join('')}</tbody>`
      : `<tbody><tr><td class="pn-hint">${mkData.listings.length ? 'Nothing matches.' : 'Nothing for sale.'}</td></tr></tbody>`;
    const sales = mkData.sales.filter((s) => s.seller);
    $('pn-mk-sales').innerHTML = sales.length ? `<thead><tr><th>When</th><th>Item</th><th>Amount</th><th>Total</th><th>Seller</th><th>Buyer</th></tr></thead><tbody>${sales.map((s) => `
      <tr><td>${esc(dateTime(s.time))}</td><td>${esc(s.itemName)}</td><td>${s.amount}</td><td>${Number(s.total).toLocaleString()}</td><td>${esc(s.sellerName)}</td><td>${esc(s.buyerName)}</td></tr>`).join('')}</tbody>`
      : '<tbody><tr><td class="pn-hint">No sales yet.</td></tr></tbody>';
    renderServerSales();
  }

  /** Server shop purchases: claim blocks and commands (market sales without a seller) and pets, newest first. */
  function renderServerSales() {
    const rows = [...(mkData ? mkData.sales.filter((s) => !s.seller).map((s) => ({ time: s.time, what: s.amount > 1 ? `${s.amount} × ${s.itemName}` : s.itemName, who: s.buyerName, paid: s.total })) : []),
      ...(ptData ? (ptData.state.purchases || []).map((b) => ({ time: b.time, what: 'Pet: ' + b.petName, who: b.buyerName, paid: b.price })) : [])]
      .sort((a, b) => b.time - a.time).slice(0, 200);
    $('pn-ss-sales').innerHTML = rows.length ? `<thead><tr><th>When</th><th>What</th><th>Player</th><th>Paid</th></tr></thead><tbody>${rows.map((r) => `
      <tr><td>${esc(dateTime(r.time))}</td><td>${esc(r.what)}</td><td>${esc(r.who)}</td><td>${r.paid ? Number(r.paid).toLocaleString() : 'free'}</td></tr>`).join('')}</tbody>`
      : '<tbody><tr><td class="pn-hint">Nothing bought from the server yet.</td></tr></tbody>';
  }

  const ssIcon = (hash) => (hash ? `<img src="info/items/${esc(hash)}" alt="" width="24" height="24">` : '<span class="pn-ss-noicon"></span>');
  const ssActions = (i) => (can('settings.server') ? `<button type="button" class="np-btn small" data-edit="${i}"><i class="bi bi-pencil"></i> Edit</button>
    <button type="button" class="np-btn small" data-delete="${i}" title="Delete this listing"><i class="bi bi-trash"></i></button>` : '');

  function renderServerShop() {
    const s = mkSettings.settings;
    $('pn-ss-claims').innerHTML = s.claimBlocks.length ? `<thead><tr><th>Listing</th><th>Price</th><th>On sale</th><th>For sale</th><th></th></tr></thead><tbody>${s.claimBlocks.map((o, i) => `
      <tr><td class="pn-mk-item">${ssIcon((mkSettings.claimIcons || [])[i])}<span>${Number(o.blocks).toLocaleString()} claim blocks</span></td>
      <td>${Number(o.price).toLocaleString()}</td><td>${ptSale(o)}</td><td>${o.forSale === false ? 'no' : 'yes'}</td><td>${ssActions(i)}</td></tr>`).join('')}</tbody>`
      : '<tbody><tr><td class="pn-hint">No claim blocks for sale. Add a listing below.</td></tr></tbody>';
    const cmdEdit = mkSettings.canCommands;
    $('pn-ss-cmds').innerHTML = s.commandOffers.length ? `<thead><tr><th>Listing</th><th>Price</th><th>On sale</th><th>Per player</th><th>For sale</th><th></th></tr></thead><tbody>${s.commandOffers.map((o, i) => `
      <tr><td class="pn-mk-item">${ssIcon((mkSettings.commandIcons || [])[i])}<span>${esc(o.name)}<small class="pn-hint">${esc(o.description || '')}</small></span></td>
      <td>${Number(o.price).toLocaleString()}</td><td>${ptSale(o)}</td><td>${o.perPlayer ? Number(o.perPlayer).toLocaleString() : 'no limit'}</td><td>${o.forSale === false ? 'no' : 'yes'}</td>
      <td>${cmdEdit ? ssActions(i) : ''}</td></tr>`).join('')}</tbody>`
      : '<tbody><tr><td class="pn-hint">No command listings. Add one below.</td></tr></tbody>';
  }

  /** Saves the market settings with some fields changed (the rest as loaded). */
  async function mkSave(changes, msgId, done) {
    try {
      const res = await api('api/market/settings', { ...mkSettings.settings, ...changes });
      await loadMarket();
      formMsg(msgId, done + (res.running ? ' Applies within a few seconds.' : ''), true);
      return true;
    } catch (err) {
      formMsg(msgId, err.message, false);
      return false;
    }
  }

  /** The "Always on sale" and "No limit" pairs: the field is only used while its switch is off. */
  function ssPairs(f) {
    f.saleEnd.disabled = f.always.checked;
    if (f.unlimited) f.perPlayer.disabled = f.unlimited.checked;
  }

  /** The end of sale from an editor (keeps the old time while its day isn't changed), or an error text. */
  function ssSaleEnds(f, before) {
    if (f.always.checked) return 0;
    if (!f.saleEnd.value) return 'Pick a day, or switch on Always on sale.';
    if (before && before.saleEnds && ptDay(before.saleEnds) === f.saleEnd.value) return before.saleEnds;
    const t = new Date(f.saleEnd.value + 'T23:59:59').getTime();
    return t < Date.now() ? 'Pick a day from today on, or switch on Always on sale.' : t;
  }

  function ssIconPreview(f, hash) {
    const img = f.querySelector('.pn-ss-icon');
    img.hidden = !hash;
    if (hash) img.src = 'info/items/' + hash;
  }

  function ssClaimOpen(i) {
    ssClaimEditing = i;
    const o = i === null ? null : mkSettings.settings.claimBlocks[i];
    const f = $('pn-ss-claim-edit');
    f.blocks.value = o ? o.blocks : 100;
    f.price.value = o ? o.price : '';
    f.icon.value = o ? o.icon : 'minecraft:grass_block';
    f.iconModel.value = o ? o.iconModel || '' : '';
    f.always.checked = o ? !o.saleEnds : true;
    f.saleEnd.value = ptDay(o && o.saleEnds ? o.saleEnds : Date.now() + 30 * 86400000);
    f.forSale.checked = o ? o.forSale !== false : true;
    $('pn-ss-claim-title').textContent = o ? `Edit ${Number(o.blocks).toLocaleString()} claim blocks` : 'Add a listing';
    $('pn-ss-claim-add').innerHTML = o ? '<i class="bi bi-check2"></i> Save listing' : '<i class="bi bi-plus-lg"></i> Add listing';
    f.hidden = !o;
    ssIconPreview(f, o ? (mkSettings.claimIcons || [])[i] : null);
    formMsg('pn-ss-claim-msg', '', true);
    ssPairs(f);
  }

  function ssCmdOpen(i) {
    ssCmdEditing = i;
    const o = i === null ? null : mkSettings.settings.commandOffers[i];
    const f = $('pn-ss-cmd-edit');
    f.name.value = o ? o.name : '';
    f.description.value = o ? o.description || '' : '';
    f.commands.value = o ? o.commands.join('\n') : '';
    f.price.value = o ? o.price : '';
    f.icon.value = o ? o.icon : 'minecraft:paper';
    f.iconModel.value = o ? o.iconModel || '' : '';
    f.always.checked = o ? !o.saleEnds : true;
    f.saleEnd.value = ptDay(o && o.saleEnds ? o.saleEnds : Date.now() + 30 * 86400000);
    f.unlimited.checked = o ? !o.perPlayer : true;
    f.perPlayer.value = o && o.perPlayer ? o.perPlayer : 1;
    f.forSale.checked = o ? o.forSale !== false : true;
    $('pn-ss-cmd-title').textContent = o ? 'Edit ' + o.name : 'Add a listing';
    $('pn-ss-cmd-add').innerHTML = o ? '<i class="bi bi-check2"></i> Save listing' : '<i class="bi bi-plus-lg"></i> Add listing';
    f.hidden = !o;
    ssIconPreview(f, o ? (mkSettings.commandIcons || [])[i] : null);
    formMsg('pn-ss-cmd-msg', '', true);
    ssPairs(f);
  }

  for (const id of ['pn-ss-claim-edit', 'pn-ss-cmd-edit']) {
    const f = $(id);
    f.always.addEventListener('change', () => ssPairs(f));
    if (f.unlimited) f.unlimited.addEventListener('change', () => ssPairs(f));
  }
  $('pn-ss-claim-cancel').addEventListener('click', () => ssClaimOpen(null));
  $('pn-ss-cmd-cancel').addEventListener('click', () => ssCmdOpen(null));
  /** "Add …" opens an empty editor. */
  function ssNew(open, formId, first) {
    open(null);
    const f = $(formId);
    f.hidden = false;
    f[first].focus();
  }
  $('pn-ss-claim-new').addEventListener('click', () => ssNew(ssClaimOpen, 'pn-ss-claim-edit', 'blocks'));
  $('pn-ss-cmd-new').addEventListener('click', () => ssNew(ssCmdOpen, 'pn-ss-cmd-edit', 'name'));

  $('pn-ss-claim-edit').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target;
    const list = mkSettings.settings.claimBlocks.slice();
    const before = ssClaimEditing === null ? null : list[ssClaimEditing];
    const saleEnds = ssSaleEnds(f, before);
    if (typeof saleEnds === 'string') return formMsg('pn-ss-claim-msg', saleEnds, false);
    const o = { id: before ? before.id : undefined, blocks: Number(f.blocks.value) || 0, price: Number(f.price.value) || 0,
      icon: f.icon.value.trim() || 'minecraft:grass_block', iconModel: f.iconModel.value.trim(), saleEnds, forSale: f.forSale.checked };
    if (before) list[ssClaimEditing] = o; else list.push(o);
    if (await mkSave({ claimBlocks: list }, 'pn-ss-claim-msg', before ? 'Saved.' : 'Added.')) {
      formMsg('pn-ss-claim-note', $('pn-ss-claim-msg').textContent, true);
      ssClaimOpen(null);
    }
  });

  $('pn-ss-cmd-edit').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target;
    const list = mkSettings.settings.commandOffers.slice();
    const before = ssCmdEditing === null ? null : list[ssCmdEditing];
    const saleEnds = ssSaleEnds(f, before);
    if (typeof saleEnds === 'string') return formMsg('pn-ss-cmd-msg', saleEnds, false);
    const commands = f.commands.value.split('\n').map((c) => c.trim()).filter(Boolean);
    if (!commands.length) return formMsg('pn-ss-cmd-msg', 'Add at least one command.', false);
    if (!f.unlimited.checked && !(Number(f.perPlayer.value) >= 1)) return formMsg('pn-ss-cmd-msg', 'Type how many each player can buy, or switch on No limit.', false);
    const o = { id: before ? before.id : undefined, name: f.name.value.trim(), description: f.description.value.trim(), commands, price: Number(f.price.value) || 0,
      icon: f.icon.value.trim() || 'minecraft:paper', iconModel: f.iconModel.value.trim(), saleEnds, perPlayer: f.unlimited.checked ? 0 : Number(f.perPlayer.value), forSale: f.forSale.checked };
    if (before) list[ssCmdEditing] = o; else list.push(o);
    if (await mkSave({ commandOffers: list }, 'pn-ss-cmd-msg', before ? 'Saved.' : 'Added.')) {
      formMsg('pn-ss-cmd-note', $('pn-ss-cmd-msg').textContent, true);
      ssCmdOpen(null);
    }
  });

  /** Edit and delete buttons in a server shop table. */
  function ssTableClick(key, open, msgId, label) {
    return async (e) => {
      const ed = e.target.closest('[data-edit]');
      if (ed) {
        open(Number(ed.dataset.edit));
        e.currentTarget.closest('section').querySelector('.pn-pt-edit').scrollIntoView({ behavior: 'smooth', block: 'center' });
        return;
      }
      const del = e.target.closest('[data-delete]');
      if (!del) return;
      const i = Number(del.dataset.delete);
      const list = mkSettings.settings[key];
      if (!confirm(`Delete ${label(list[i])}? Players who bought it keep what they got.`)) return;
      open(null);
      await mkSave({ [key]: list.filter((_, j) => j !== i) }, msgId, 'Deleted.');
    };
  }
  $('pn-ss-claims').addEventListener('click', ssTableClick('claimBlocks', ssClaimOpen, 'pn-ss-claim-note', (o) => `the listing of ${Number(o.blocks).toLocaleString()} claim blocks`));
  $('pn-ss-cmds').addEventListener('click', ssTableClick('commandOffers', ssCmdOpen, 'pn-ss-cmd-note', (o) => o.name));

  $('pn-ss-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    if (await mkSave({ serverName: e.target.serverName.value.trim() }, 'pn-ss-msg', 'Saved.')) Dirty.reset('servershop');
  });

  $('pn-ss-max').addEventListener('submit', (e) => {
    e.preventDefault();
    mkSave({ claimBlocksMax: Number(e.target.claimBlocksMax.value) || 0 }, 'pn-ss-max-msg', 'Saved.');
  });

  $('pn-mk-search').addEventListener('input', renderMarket);
  $('pn-mk-table').addEventListener('click', async (e) => {
    const b = e.target.closest('[data-remove]');
    if (!b || !confirm('Take this listing off the market? The items go back to the seller\'s mailbox.')) return;
    try {
      await api('api/market/remove', { id: b.dataset.remove });
      loadMarket();
    } catch (err) {
      alert(err.message);
    }
  });
  $('pn-mk-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target;
    const changes = { enabled: f.enabled.checked, currency: f.currency.value.trim(), listingDays: Number(f.listingDays.value) || 7,
      maxListings: Number(f.maxListings.value) || 10, maxPrice: Number(f.maxPrice.value) || 100000,
      bannedItems: f.bannedItems.value.split(',').map((x) => x.trim()).filter(Boolean) };
    if (changes.enabled && !mkSettings.settings.enabled && !confirm('Open the market? Players can sell and buy with /market right away.')) return;
    if (await mkSave(changes, 'pn-mk-msg', 'Saved.')) Dirty.reset('market');
  });
  ssClaimOpen(null);
  ssCmdOpen(null);

  // ── Community → Pets, and Market → Server shop → Pet listings ─────────────
  // One settings file: a pet (name, head, size, description) is made on the Pets page; its listing
  // (price, last day, how many, forSale) is set in the Server shop. Unlisted pets can still be granted.

  const ptFace = (hash) => hash ? `<img class="pn-pt-face" src="info/heads/${esc(hash)}" alt="" loading="lazy">` : '<span class="pn-pt-face"></span>';
  /** In the Server shop: listed and not taken out. */
  const ptListed = (p) => p.forSale && !p.retired;
  let ptlEditing = null;

  async function loadPets() {
    try {
      ptData = await api('api/pets');
    } catch (e) {
      return formMsg('pn-pt-msg', e.message, false);
    }
    const s = ptData.settings;
    const f = $('pn-pt-form');
    f.enabled.checked = s.enabled;
    f.particles.checked = s.particles;
    const edit = can('settings.server');
    f.querySelectorAll('input, button').forEach((el) => { el.disabled = !edit; });
    $('pn-pt-new').hidden = !edit;
    $('pn-ptl-new').hidden = !edit;
    if (!edit) { ptOpen(null); ptlOpen(null); }
    const cur = ptData.currency.replace(/^minecraft:/, '').replace(/_/g, ' ');
    $('pn-pt-currency').textContent = `In ${cur}s (the market's currency); 0 makes it free`;
    ptPerkOptions();
    renderPets();
    Dirty.reset('pets');
    pkFill();
    $('pn-pk-form').querySelectorAll('input, button, select').forEach((el) => { el.disabled = !edit; });
    Dirty.reset('petperks');
  }

  function ptInShop(p, sold) {
    if (!ptListed(p)) return 'not listed';
    if (p.stock > 0 && (sold[p.id] || 0) >= p.stock) return '<span class="pn-warn">sold out</span>';
    if (p.saleEnds && p.saleEnds < Date.now()) return '<span class="pn-warn">sale ended</span>';
    return p.price ? Number(p.price).toLocaleString() : 'free';
  }

  function renderPets() {
    const s = ptData.settings;
    const sold = ptData.state.sold || {};
    const owners = ptData.state.owners || {};
    const edit = can('settings.server');
    const pets = s.pets.filter((p) => !p.retired);
    const retired = s.pets.filter((p) => p.retired);
    const listed = pets.filter(ptListed);
    // The Pets page: every pet.
    $('pn-pt-intro').textContent = (s.enabled ? '' : 'Pets are off: switch them on above. ') + (ptData.running ? '' : 'The server is off: counts are from the last save. ')
      + `${pets.length} pet${pets.length === 1 ? '' : 's'}, ${listed.length} listed in the Server shop${retired.length ? `, ${retired.length} removed but still owned` : ''}.`;
    $('pn-pt-table').innerHTML = pets.length ? `<thead><tr><th>Pet</th><th>Description</th><th>Size</th><th>Perks</th><th>Players with it</th><th>Server shop</th><th></th></tr></thead><tbody>${pets.map((p) => `
      <tr><td class="pn-mk-item">${ptFace(ptData.heads[p.id])}<span>${esc(p.name)}</span></td>
      <td>${esc(p.description || '')}</td><td>${Number(p.size || 0.8)}</td><td>${ptPerkNames(p)}</td>
      <td>${Number(owners[p.id] || 0).toLocaleString()}</td><td>${ptInShop(p, sold)}</td>
      <td>${edit ? `<button type="button" class="np-btn small" data-edit="${esc(p.id)}"><i class="bi bi-pencil"></i> Edit</button>
        <button type="button" class="np-btn small" data-delete="${esc(p.id)}" title="Remove"><i class="bi bi-trash"></i></button>` : ''}</td></tr>`).join('')}</tbody>`
      : '<tbody><tr><td class="pn-hint">No pets yet. Add one below.</td></tr></tbody>';
    $('pn-pt-retired-box').hidden = !retired.length;
    $('pn-pt-retired').innerHTML = retired.length ? `<thead><tr><th>Pet</th><th>Players with it</th><th></th></tr></thead><tbody>${retired.map((p) => `
      <tr><td class="pn-mk-item">${ptFace(ptData.heads[p.id])}<span>${esc(p.name)}</span></td><td>${Number(owners[p.id] || 0).toLocaleString()}</td>
      <td>${edit ? `<button type="button" class="np-btn small" data-edit="${esc(p.id)}"><i class="bi bi-pencil"></i> Name &amp; head</button>
        <button type="button" class="np-btn small" data-revive="${esc(p.id)}"><i class="bi bi-arrow-counterclockwise"></i> Restore</button>` : ''}</td></tr>`).join('')}</tbody>` : '';
    // The Server shop: only the listings.
    $('pn-ptl-intro').textContent = (s.enabled ? '' : 'Pets are off (Community → Pets), so none are sold right now. ')
      + (pets.length ? `${listed.length} of ${pets.length} pet${pets.length === 1 ? '' : 's'} listed.` : 'Add pets under Community → Pets first.');
    $('pn-ptl-table').innerHTML = listed.length ? `<thead><tr><th>Pet</th><th>Price</th><th>On sale</th><th>Sold</th><th>Players with it</th><th></th></tr></thead><tbody>${listed.map((p) => `
      <tr><td class="pn-mk-item">${ptFace(ptData.heads[p.id])}<span>${esc(p.name)}</span></td>
      <td>${p.price ? Number(p.price).toLocaleString() : 'free'}</td><td>${ptSale(p)}</td>
      <td>${Number(sold[p.id] || 0).toLocaleString()}${p.stock > 0 ? ' of ' + Number(p.stock).toLocaleString() : ''}${p.stock > 0 && (sold[p.id] || 0) >= p.stock ? ' <span class="pn-warn">sold out</span>' : ''}</td>
      <td>${Number(owners[p.id] || 0).toLocaleString()}</td>
      <td>${edit ? `<button type="button" class="np-btn small" data-edit="${esc(p.id)}"><i class="bi bi-pencil"></i> Edit</button>
        <button type="button" class="np-btn small" data-unlist="${esc(p.id)}" title="Remove the listing (the pet stays)"><i class="bi bi-trash"></i></button>` : ''}</td></tr>`).join('')}</tbody>`
      : '<tbody><tr><td class="pn-hint">No pets listed. Add a listing below.</td></tr></tbody>';
    $('pn-ptl-new').disabled = !pets.some((p) => !ptListed(p));
    renderServerSales();
  }

  // ── The Pets page: a pet's name, head, size and description ──

  function ptOpen(pet) {
    ptEditing = pet ? pet.id : null;
    const f = $('pn-pt-edit');
    f.name.value = pet ? pet.name : '';
    f.description.value = pet ? pet.description || '' : '';
    f.size.value = pet && pet.size ? pet.size : 0.8;
    f.texture.value = pet ? pet.texture : '';
    const perks = pet && pet.perks ? pet.perks : [];
    f.perk1.value = perks[0] || '';
    f.perk2.value = perks[1] || '';
    ptPerkDesc();
    $('pn-pt-edit-title').textContent = pet ? (pet.retired ? 'Name and head of ' : 'Edit ') + pet.name : 'Add a pet';
    $('pn-pt-add').innerHTML = pet ? '<i class="bi bi-check2"></i> Save pet' : '<i class="bi bi-plus-lg"></i> Add pet';
    f.hidden = !pet;
    const face = $('pn-pt-face');
    face.hidden = !(pet && ptData && ptData.heads[pet.id]);
    if (!face.hidden) face.src = 'info/heads/' + ptData.heads[pet.id];
    formMsg('pn-pt-edit-msg', '', true);
  }

  async function ptSave(pets, msgId, done) {
    const body = { enabled: ptData.settings.enabled, size: ptData.settings.size, particles: ptData.settings.particles, pets };
    try {
      const res = await api('api/pets/settings', body);
      ptData.settings.pets = pets;
      ptData.heads = res.heads;
      renderPets();
      formMsg(msgId, done + (res.running ? ' Players see it within a few seconds.' : ''), true);
      return true;
    } catch (err) {
      formMsg(msgId, err.message, false);
      return false;
    }
  }

  $('pn-pt-edit').texture.addEventListener('change', async (e) => {
    const face = $('pn-pt-face');
    try {
      const res = await api('api/pets/texture', { texture: e.target.value });
      face.src = 'api/pets/head/' + res.head;
      face.hidden = false;
      formMsg('pn-pt-edit-msg', '', true);
    } catch (err) {
      face.hidden = true;
      formMsg('pn-pt-edit-msg', err.message, false);
    }
  });
  $('pn-pt-cancel').addEventListener('click', () => ptOpen(null));
  $('pn-pt-new').addEventListener('click', () => ssNew(ptOpen, 'pn-pt-edit', 'name'));
  $('pn-pt-edit').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target;
    const before = ptData.settings.pets.find((p) => p.id === ptEditing);
    // The listing stays as it was; a new pet isn't for sale until it's listed in the Server shop.
    const pet = { ...(before || { price: 0, saleEnds: 0, stock: 0, forSale: false }), id: ptEditing || undefined, name: f.name.value.trim(),
      description: f.description.value.trim(), size: Number(f.size.value) || 0.8, texture: f.texture.value.trim(),
      perks: [f.perk1.value, f.perk2.value].filter(Boolean) };
    const pets = ptData.settings.pets.map((p) => (p.id === ptEditing ? pet : p));
    if (!ptEditing) pets.push(pet);
    const wasEditing = ptEditing;
    if (await ptSave(pets, 'pn-pt-edit-msg', wasEditing ? 'Saved.' : 'Added. List it under Market → Server shop to sell it.')) {
      const fresh = await api('api/pets').catch(() => null);
      if (fresh) { ptData = fresh; renderPets(); }
      formMsg('pn-pt-note', $('pn-pt-edit-msg').textContent, true);
      ptOpen(null);
    }
  });
  async function ptClick(e) {
    const ed = e.target.closest('[data-edit]');
    if (ed) {
      ptOpen(ptData.settings.pets.find((p) => p.id === ed.dataset.edit));
      $('pn-pt-edit').scrollIntoView({ behavior: 'smooth', block: 'center' });
      return;
    }
    const revive = e.target.closest('[data-revive]');
    if (revive) {
      const pets = ptData.settings.pets.map((p) => (p.id === revive.dataset.revive ? { ...p, retired: false, forSale: false } : p));
      await ptSave(pets, 'pn-pt-note', 'Restored (not listed in the Server shop).');
      return;
    }
    const del = e.target.closest('[data-delete]');
    if (!del) return;
    const pet = ptData.settings.pets.find((p) => p.id === del.dataset.delete);
    if (!pet) return;
    const have = Number((ptData.state.owners || {})[pet.id] || 0);
    if (have > 0) {
      if (!confirm(`Remove ${pet.name}? ${have} player${have === 1 ? ' has' : 's have'} it and keep${have === 1 ? 's' : ''} it; its listing goes. It moves to "Removed, still owned", where you can still change its name and head.`)) return;
      if (ptEditing === pet.id) ptOpen(null);
      if (ptlEditing === pet.id) ptlOpen(null);
      await ptSave(ptData.settings.pets.map((p) => (p.id === pet.id ? { ...p, retired: true, forSale: false } : p)), 'pn-pt-note', 'Removed.');
      return;
    }
    if (!confirm(`Delete ${pet.name}? Nobody has it${ptListed(pet) ? ', and its Server shop listing goes too' : ''}.`)) return;
    if (ptEditing === pet.id) ptOpen(null);
    if (ptlEditing === pet.id) ptlOpen(null);
    await ptSave(ptData.settings.pets.filter((p) => p.id !== pet.id), 'pn-pt-note', 'Deleted.');
  }
  $('pn-pt-table').addEventListener('click', ptClick);
  $('pn-pt-retired').addEventListener('click', ptClick);
  $('pn-pt-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target;
    const enabled = f.enabled.checked;
    if (enabled && !ptData.settings.enabled && !confirm('Switch pets on? Players can use /pets and buy listed pets right away.')) return;
    try {
      const res = await api('api/pets/settings', { enabled, size: ptData.settings.size, particles: f.particles.checked, pets: ptData.settings.pets });
      formMsg('pn-pt-msg', res.running ? 'Saved, applies within a few seconds.' : 'Saved; applies when the server starts.', true);
      loadPets();
    } catch (err) {
      formMsg('pn-pt-msg', err.message, false);
    }
  });
  ptOpen(null);

  // ── Pet perks (two per pet), bond levels, names and reactions ──

  /** The pet's perks by name for the table, each with what it does on hover (HTML). */
  function ptPerkNames(p) {
    const all = (ptData && ptData.perks) || [];
    return (p.perks || []).map((id) => {
      const k = all.find((x) => x.id === id) || { label: id, description: '' };
      return `<span class="pn-pk-name" title="${esc(k.description)}">${esc(k.label)}</span>`;
    }).join(', ');
  }

  /** The perk lists in the pet editor, grouped. */
  function ptPerkOptions() {
    const all = ptData.perks || [];
    const group = (g, label) => `<optgroup label="${label}">${all.filter((k) => k.group === g).map((k) => `<option value="${esc(k.id)}" title="${esc(k.description)}">${esc(k.label)}</option>`).join('')}</optgroup>`;
    const html = '<option value="">None</option>' + group('HELPER', 'Helpers') + group('STRONG', 'Stronger');
    const f = $('pn-pt-edit');
    for (const sel of [f.perk1, f.perk2]) {
      const v = sel.value;
      sel.innerHTML = html;
      sel.value = v;
    }
    const k = ptData.settings.perks;
    const jump = '<a href="#" class="pn-pk-jump">Bond &amp; perks</a>';
    $('pn-pt-perk1-note').innerHTML = `Unlocks at bond level ${k.slot1Level}, for every pet (set under ${jump})`;
    $('pn-pt-perk2-note').innerHTML = `Unlocks at bond level ${k.slot2Level}, for every pet (set under ${jump})`;
    const list = (g) => all.filter((x) => x.group === g).map((x) => `<li><b>${esc(x.label)}</b>: ${esc(x.description)}</li>`).join('');
    $('pn-pk-list').innerHTML = `<h5>Helpers</h5><ul>${list('HELPER')}</ul><h5>Stronger</h5><ul>${list('STRONG')}</ul>`;
  }

  function ptPerkDesc() {
    const f = $('pn-pt-edit');
    const all = (ptData && ptData.perks) || [];
    const lines = [f.perk1.value, f.perk2.value].filter(Boolean).map((id) => all.find((k) => k.id === id)).filter(Boolean).map((k) => `${k.label}: ${k.description}.`);
    const clash = f.perk1.value && f.perk2.value && f.perk1.value === f.perk2.value ? ' Pick two different perks.' : '';
    const lamp = [f.perk1.value, f.perk2.value].includes('LANTERN') && [f.perk1.value, f.perk2.value].includes('NIGHT_EYES') ? ' Night Eyes already is a lantern: pick one of the two.' : '';
    $('pn-pt-perk-desc').textContent = lines.join(' ') + clash + lamp;
  }
  $('pn-pt-edit').addEventListener('click', (e) => {
    if (!e.target.closest('.pn-pk-jump')) return;
    e.preventDefault();
    $('pn-pk-form').scrollIntoView({ behavior: 'smooth', block: 'start' });
  });
  $('pn-pt-edit').perk1.addEventListener('change', ptPerkDesc);
  $('pn-pt-edit').perk2.addEventListener('change', ptPerkDesc);

  /** The perks' strength fields: name, label, comment, min, max, step. */
  const PK_FIELDS = [
    ['lanternLevel', 'Lantern light', 'Light level around the pet (a torch is 14)', 1, 15, 1],
    ['nightEyesLevel', 'Night Eyes light deep down', 'Night Eyes is as bright as Lantern, and this bright where no daylight reaches', 1, 15, 1],
    ['fetchRadius', 'Fetch reaches (blocks)', '', 1, 10, 1],
    ['truffleRadius', 'Truffle Nose sniffs (blocks)', '', 1, 16, 1],
    ['fishingMinutes', 'Fishing every (minutes)', 'Of being out together, near water', 1, 1440, 1],
    ['gardenerSeconds', 'Gardener every (seconds)', 'One growth step for a crop', 5, 86400, 1],
    ['torchSeconds', 'Torch Bearer waits (seconds)', 'Between two torches', 1, 3600, 1],
    ['minerSeconds', 'Miner waits (seconds)', 'Between two blocks', 1, 3600, 1],
    ['chopperSeconds', 'Wood Chopper waits (seconds)', 'Between two logs', 1, 3600, 1],
    ['giftMinutes', 'Small Gifts every (minutes)', 'Of being out together, not AFK', 1, 10080, 1],
    ['secondWindMinutes', 'Second Wind waits (minutes)', 'Regeneration for 5 seconds at 2 hearts or less', 1, 10080, 1],
    ['nipperDamage', 'Nipper damage', '2 is one heart; a wolf does 4', 0.5, 20, 0.5],
    ['nipperSeconds', 'Nipper bites every (seconds)', '', 0.5, 60, 0.5],
    ['shieldSeconds', 'Shield pushes every (seconds)', '', 1, 600, 1],
    ['xpRadius', 'XP Magnet reaches (blocks)', 'Orbs come by themselves from 8 blocks', 8, 32, 1],
    ['cookMinutes', 'Cook every (minutes)', 'One raw food from the inventory', 1, 1440, 1],
    ['snackFood', 'Snack Sharer feeds at food level', '20 is full, 6 is when sprinting stops', 1, 19, 1],
    ['snackSeconds', 'Snack Sharer waits (seconds)', 'Between two snacks', 5, 3600, 1],
    ['tinkerMinutes', 'Tinkerer every (minutes)', 'Of being out together, not AFK', 1, 1440, 1],
    ['tinkerPoints', 'Tinkerer mends (durability)', 'An iron pickaxe has 250', 1, 1000, 1],
    ['lureLevels', 'Lure adds (levels)', 'Rod and pet together never go past Lure III', 1, 3, 1],
    ['eyeRadius', 'Watchful Eye sees (blocks)', '', 4, 48, 1],
    ['bagRows', 'Pack Mule bag (rows of 9)', 'Fewer rows later: what doesn\'t fit goes back to the player', 1, 6, 1],
    ['luckyPercent', 'Lucky Paw chance (%)', 'Per natural ore or grown crop broken', 1, 100, 1],
    ['fireSeconds', 'Fire Watch waits (seconds)', '', 1, 3600, 1],
    ['softPercent', 'Soft Paws takes (% of a fall)', 'Only falls that hurt more than a heart', 1, 100, 1],
    ['softMinutes', 'Soft Paws waits (minutes)', '0: every fall', 0, 1440, 1],
    ['roarCount', 'Guardian Roar needs (monsters)', 'Within 5 blocks', 1, 20, 1],
    ['roarMinutes', 'Guardian Roar waits (minutes)', '', 1, 1440, 1],
  ];
  $('pn-pk-tuning').innerHTML = PK_FIELDS.map(([name, label, comment, min, max, step]) => `<label class="np-form-row"><span>${esc(label)}${comment ? `<small class="pn-comment">${esc(comment)}</small>` : ''}</span><input type="number" name="${name}" min="${min}" max="${max}" step="${step}" required></label>`).join('');

  /** "Level 1 after 30 min, level 2 after 1 h 30 min…" for the levels that matter. */
  function pkLevels() {
    const f = $('pn-pk-form');
    const per = Number(f.levelMinutes.value) || 0;
    const max = Number(f.maxLevel.value) || 0;
    if (per < 1 || max < 1) return;
    const time = (m) => (m >= 60 ? `${Math.floor(m / 60)} h${m % 60 ? ` ${m % 60} min` : ''}` : `${m} min`);
    const at = (n) => time(per * n * (n + 1) / 2);
    const s1 = Number(f.slot1Level.value);
    const s2 = Number(f.slot2Level.value);
    $('pn-pk-levels').textContent = `Out together: level 1 after ${at(1)}, the first perk (level ${s1}) after ${s1 > 0 ? at(s1) : 'no time'}, the second (level ${s2}) after ${s2 > 0 ? at(s2) : 'no time'}, the highest (level ${max}) after ${at(max)}.`;
  }
  ['levelMinutes', 'maxLevel', 'slot1Level', 'slot2Level'].forEach((n) => $('pn-pk-form')[n].addEventListener('input', pkLevels));

  function pkGiftRow(g) {
    return `<tr data-gift><td><input type="text" data-g="item" value="${esc(g.item || '')}" placeholder="minecraft:bone_meal" required></td>
      <td><input type="number" data-g="min" min="1" max="64" value="${Number(g.min) || 1}" required></td>
      <td><input type="number" data-g="max" min="1" max="64" value="${Number(g.max) || 1}" required></td>
      <td><input type="number" data-g="weight" min="1" max="1000" value="${Number(g.weight) || 1}" required></td>
      <td><button type="button" class="np-btn small" data-gift-del title="Remove"><i class="bi bi-trash"></i></button></td></tr>`;
  }

  function pkFill() {
    const s = ptData.settings;
    const k = s.perks;
    const f = $('pn-pk-form');
    f.enabled.checked = k.enabled;
    for (const n of ['slot1Level', 'slot2Level', 'levelMinutes', 'maxLevel', ...PK_FIELDS.map((x) => x[0])]) f[n].value = k[n];
    pkLevels();
    $('pn-pk-gifts').innerHTML = `<thead><tr><th>Item</th><th>Fewest</th><th>Most</th><th>Weight</th><th></th></tr></thead><tbody>${k.gifts.map(pkGiftRow).join('')}</tbody>`;
    f.names.checked = s.names;
    const colors = { ...ptData.defaults.nameColors, ...s.nameColors };
    $('pn-pk-colors').innerHTML = Object.entries(colors).map(([n, c]) => `<label class="np-check"><input type="checkbox" name="nameColor" value="${esc(n)}" data-hex="${esc(c)}" ${n in s.nameColors ? 'checked' : ''}>
      <span class="pn-cl-dot" style="background:${esc(c)}"></span>${esc(n)}</label>`).join('');
    f.reactionsOn.checked = s.reactions.enabled;
    f.gapSeconds.value = s.reactions.gapSeconds;
    $('pn-pk-reactions').innerHTML = `<thead><tr><th>Reaction</th><th>On</th><th>Bubble lines (separated by |)</th></tr></thead><tbody>${(ptData.reactions || []).map((r) => {
      const e = s.reactions.list[r.id] || { on: true, lines: r.lines };
      return `<tr data-reaction="${esc(r.id)}"><td>${esc(r.label)}${r.warning ? ' <span class="np-tag">warning</span>' : ''}</td>
        <td><span class="np-switch"><input type="checkbox" data-r="on" ${e.on ? 'checked' : ''}><span></span></span></td>
        <td><input type="text" data-r="lines" value="${esc(e.lines.join(' | '))}" placeholder="${esc(r.lines.join(' | '))}"></td></tr>`;
    }).join('')}</tbody>`;
  }

  $('pn-pk-gift-add').addEventListener('click', () => {
    $('pn-pk-gifts').querySelector('tbody').insertAdjacentHTML('beforeend', pkGiftRow({ item: '', min: 1, max: 1, weight: 1 }));
  });
  $('pn-pk-gifts').addEventListener('click', (e) => {
    const del = e.target.closest('[data-gift-del]');
    if (del) del.closest('tr').remove();
  });

  $('pn-pk-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target;
    const perks = { ...ptData.settings.perks, enabled: f.enabled.checked };
    for (const n of ['slot1Level', 'slot2Level', 'levelMinutes', 'maxLevel', ...PK_FIELDS.map((x) => x[0])]) perks[n] = Number(f[n].value);
    perks.gifts = [...$('pn-pk-gifts').querySelectorAll('tr[data-gift]')].map((tr) => ({
      item: tr.querySelector('[data-g=item]').value.trim(), min: Number(tr.querySelector('[data-g=min]').value),
      max: Number(tr.querySelector('[data-g=max]').value), weight: Number(tr.querySelector('[data-g=weight]').value) }));
    const nameColors = Object.fromEntries([...f.querySelectorAll('input[name=nameColor]:checked')].map((i) => [i.value, i.dataset.hex]));
    if (!Object.keys(nameColors).length) return formMsg('pn-pk-msg', 'Pick at least one name colour.', false);
    const list = {};
    $('pn-pk-reactions').querySelectorAll('tr[data-reaction]').forEach((tr) => {
      list[tr.dataset.reaction] = { on: tr.querySelector('[data-r=on]').checked, lines: tr.querySelector('[data-r=lines]').value.split('|').map((l) => l.trim()).filter(Boolean) };
    });
    const body = { perks, names: f.names.checked, nameColors, reactions: { enabled: f.reactionsOn.checked, gapSeconds: Number(f.gapSeconds.value), list } };
    try {
      const res = await api('api/pets/settings', body);
      formMsg('pn-pk-msg', res.running ? 'Saved, applies within a few seconds.' : 'Saved; applies when the server starts.', true);
      loadPets();
    } catch (err) {
      formMsg('pn-pk-msg', err.message, false);
    }
  });

  // ── Server shop → Pet listings: price, last day and how many ──

  function ptlPairs() {
    const f = $('pn-ptl-edit');
    f.saleEnd.disabled = f.always.checked;
    f.stock.disabled = f.unlimited.checked;
  }

  /** pet: the listed pet to edit, or null for a new listing (or to close the form). */
  function ptlOpen(pet) {
    ptlEditing = pet ? pet.id : null;
    const f = $('pn-ptl-edit');
    const choices = ptData ? ptData.settings.pets.filter((p) => !p.retired && (!ptListed(p) || (pet && p.id === pet.id))) : [];
    f.pet.innerHTML = choices.map((p) => `<option value="${esc(p.id)}">${esc(p.name)}</option>`).join('');
    f.pet.disabled = !!pet;
    if (pet) f.pet.value = pet.id;
    const shown = pet || (ptData && ptData.settings.pets.find((p) => p.id === f.pet.value)) || null;
    // An unlisted pet keeps what it was last sold for, as a start.
    f.price.value = shown ? shown.price : '';
    f.always.checked = shown ? !shown.saleEnds || shown.saleEnds < Date.now() && !pet : true;
    f.saleEnd.value = ptDay(shown && shown.saleEnds && shown.saleEnds > Date.now() ? shown.saleEnds : Date.now() + 30 * 86400000);
    f.unlimited.checked = shown ? shown.stock === 0 : true;
    f.stock.value = shown && shown.stock > 0 ? shown.stock : 10;
    $('pn-ptl-edit-title').textContent = pet ? 'Listing of ' + pet.name : 'List a pet';
    $('pn-ptl-save').innerHTML = pet ? '<i class="bi bi-check2"></i> Save listing' : '<i class="bi bi-plus-lg"></i> Add listing';
    f.hidden = !pet;
    formMsg('pn-ptl-msg', '', true);
    ptlPairs();
  }

  $('pn-ptl-edit').always.addEventListener('change', ptlPairs);
  $('pn-ptl-edit').unlimited.addEventListener('change', ptlPairs);
  $('pn-ptl-edit').pet.addEventListener('change', () => {
    const f = $('pn-ptl-edit');
    const chosen = f.pet.value;
    ptlOpen(null);
    f.pet.value = chosen;
    const p = ptData.settings.pets.find((x) => x.id === chosen);
    if (p) { f.price.value = p.price; f.unlimited.checked = p.stock === 0; f.stock.value = p.stock > 0 ? p.stock : 10; ptlPairs(); }
    f.hidden = false;
  });
  $('pn-ptl-cancel').addEventListener('click', () => ptlOpen(null));
  $('pn-ptl-new').addEventListener('click', () => ssNew(ptlOpen, 'pn-ptl-edit', 'pet'));
  $('pn-ptl-edit').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target;
    const id = ptlEditing || f.pet.value;
    const before = ptData.settings.pets.find((p) => p.id === id);
    if (!before) return formMsg('pn-ptl-msg', 'Pick a pet.', false);
    const saleEnds = f.always.checked || !f.saleEnd.value ? 0 : new Date(f.saleEnd.value + 'T23:59:59').getTime();
    // A day in the past only matters when it was just picked (an ended sale can stay as it is).
    const sameDay = ptlEditing && before.saleEnds && saleEnds && ptDay(before.saleEnds) === f.saleEnd.value;
    if (!f.always.checked && !sameDay && (!f.saleEnd.value || saleEnds < Date.now())) return formMsg('pn-ptl-msg', 'Pick a day from today on, or switch on Always on sale.', false);
    const stock = f.unlimited.checked ? 0 : Number(f.stock.value) || 0;
    if (!f.unlimited.checked && stock < 1) return formMsg('pn-ptl-msg', 'Type how many can be sold, or switch on No limit.', false);
    const listing = { price: Number(f.price.value) || 0, saleEnds: sameDay ? before.saleEnds : saleEnds, stock, forSale: true };
    const pets = ptData.settings.pets.map((p) => (p.id === id ? { ...p, ...listing } : p));
    if (await ptSave(pets, 'pn-ptl-msg', ptlEditing ? 'Saved.' : 'Listed.')) {
      formMsg('pn-ptl-note', $('pn-ptl-msg').textContent, true);
      ptlOpen(null);
    }
  });
  $('pn-ptl-table').addEventListener('click', async (e) => {
    const ed = e.target.closest('[data-edit]');
    if (ed) {
      ptlOpen(ptData.settings.pets.find((p) => p.id === ed.dataset.edit));
      $('pn-ptl-edit').scrollIntoView({ behavior: 'smooth', block: 'center' });
      return;
    }
    const un = e.target.closest('[data-unlist]');
    if (!un) return;
    const pet = ptData.settings.pets.find((p) => p.id === un.dataset.unlist);
    if (!pet || !confirm(`Stop selling ${pet.name}? The pet stays (Community → Pets), and players who have it keep it.`)) return;
    if (ptlEditing === pet.id) ptlOpen(null);
    await ptSave(ptData.settings.pets.map((p) => (p.id === pet.id ? { ...p, forSale: false } : p)), 'pn-ptl-note', 'Listing removed.');
  });
  ptlOpen(null);

  // ── Start ──────────────────────────────────────────────────────────────

  Dirty.track('settings', $('pn-settings'), { reload: loadSettings });
  Dirty.track('backups', $('pn-backup-settings'), { reload: () => { delete $('pn-backup-settings').dataset.filled; loadBackups(); },
    extra: () => [...exSet].sort() });
  Dirty.track('logs', $('pn-cleanup-save').closest('section'), { saveButtons: [$('pn-cleanup-save')], reload: loadCleanup });
  Dirty.track('borders', $('pn-borders-save').closest('section'), { saveButtons: [$('pn-borders-save')], reload: renderBorders });
  Dirty.track('wilds', $('pn-wilds-form'), { reload: loadWorld });
  Dirty.track('discord', $('pn-discord-form'), { reload: loadDiscord });
  Dirty.track('map', $('pn-map-form'), { reload: loadMap });
  Dirty.track('maintenance', $('pn-mt-form'), { reload: loadMaintenance, extra: () => [...mtStaff].sort() });
  Dirty.track('info', $('pn-info-form'), { reload: loadInfo, extra: () => (infoData ? [...infoData.settings.configs].sort() : []) });
  Dirty.track('perf', $('pn-perf-form'), { reload: loadTuning, extra: () => junk.slice() });
  Dirty.track('claimsettings', $('pn-cs-form'), { reload: loadClaimSettings });
  Dirty.track('tweaks', $('pn-tw-form'), { reload: loadTweaks });
  Dirty.track('felling', $('pn-tf-form'), { reload: loadTweaks });
  Dirty.track('chattweaks', $('pn-tc-form'), { reload: loadTweaks });
  Dirty.track('market', $('pn-mk-form'), { reload: loadMarket });
  Dirty.track('servershop', $('pn-ss-form'), { reload: loadMarket });
  Dirty.track('ssmax', $('pn-ss-max'), { reload: loadMarket });
  Dirty.track('pets', $('pn-pt-form'), { reload: loadPets });
  Dirty.track('petperks', $('pn-pk-form'), { reload: loadPets });
  Dirty.track('claim', $('pn-cl-form'), { reload: () => openClaim(clOpen && clOpen.id ? clData.claims.find((c) => c.id === clOpen.id) : null),
    extra: () => JSON.stringify(clMembers) });

  showApp();
  api('api/health').then((h) => { $('pn-version').textContent = h.version; }).catch(() => {});
})();
