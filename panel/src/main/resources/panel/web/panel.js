(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

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
    $('pn-logout').hidden = true;
    $('pn-state-pill').hidden = true;
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
    $('pn-logout').hidden = false;
    $('pn-logout').title = 'Logged in as ' + me.user;
    applyPermissions();
    if (can('console.read')) openConsole();
    if (can('server.view')) {
      $('pn-state-pill').hidden = false;
      loadSettings();
      pollServer();
    }
  }

  // ── Tabs and permissions ───────────────────────────────────────────────

  const TAB_KEY = 'pn-tab';

  function applyPermissions() {
    document.querySelectorAll('#pn-app [data-perm]').forEach((el) => { el.hidden = !can(el.dataset.perm); });
    document.querySelectorAll('#pn-app [data-perm-any]').forEach((el) => { el.hidden = !el.dataset.permAny.split(' ').some(can); });
    document.querySelector('.np-banner').hidden = !can('server.view');
    $('pn-app').querySelector('.pn-actions').hidden = !can('server.power');
    $('pn-command-form').hidden = !can('console.write');
    const editable = can('settings.java');
    $('pn-settings').querySelectorAll('input, select, button').forEach((el) => { el.disabled = !editable; });
    // Links from the game (/ncc web) open ?tab=dashboard, optionally with #<dashboard tab>.
    const params = new URLSearchParams(location.search);
    let tab = params.get('tab');
    if (tab === 'dashboard' && location.hash) dashboardHash = location.hash;
    if (tab) history.replaceState(null, '', location.pathname);
    if (!tab) {
      tab = 'dashboard';
      try { tab = localStorage.getItem(TAB_KEY) || tab; } catch (e) { /* default */ }
    }
    selectTab(tab);
  }

  let dashboardHash = '';
  let activeTab = '';

  // Backups and schedules change on their own (progress, next run): refresh the open tab.
  setInterval(() => {
    if (!loggedIn || document.hidden) return;
    if (activeTab === 'backups') loadBackups();
    if (activeTab === 'schedule') loadSchedule();
    if (activeTab === 'versions' && versionState.running) loadVersions(false);
  }, 2000);

  /** The dashboard loads once and keeps running in the background while other tabs are open. */
  function showDashboard() {
    const frame = $('pn-dashboard-frame');
    if (frame.dataset.loaded && !dashboardHash) return;
    frame.dataset.loaded = '1';
    frame.src = 'dashboard/' + dashboardHash;
    dashboardHash = '';
  }

  function selectTab(tab) {
    const visible = [...document.querySelectorAll('#pn-tabs .np-tab')].filter((b) => !b.hidden).map((b) => b.dataset.tab);
    if (!visible.includes(tab)) tab = visible[0];
    document.querySelectorAll('#pn-tabs .np-tab').forEach((b) => {
      b.classList.toggle('active', b.dataset.tab === tab);
      b.setAttribute('aria-selected', String(b.dataset.tab === tab));
    });
    document.querySelectorAll('#pn-app .np-tab-panel').forEach((p) => { p.hidden = p.dataset.tab !== tab; });
    try { localStorage.setItem(TAB_KEY, tab); } catch (e) { /* not remembered */ }
    if (tab === 'dashboard') showDashboard();
    activeTab = tab;
    if (tab === 'backups') loadBackups();
    if (tab === 'files') { loadFiles(); loadCleanup(); }
    if (tab === 'configs') loadConfigs();
    if (tab === 'versions') loadVersions(true);
    if (tab === 'discord') loadDiscord();
    if (tab === 'map') loadMap();
    if (tab === 'schedule') loadSchedule();
    if (tab === 'users') loadUsers();
    if (tab === 'audit') loadAudit(true);
    if (tab === 'account') renderAccount();
    if (tab === 'server') { const log = $('pn-log'); log.scrollTop = log.scrollHeight; }
  }

  $('pn-tabs').addEventListener('click', (e) => {
    const b = e.target.closest('.np-tab');
    if (b) selectTab(b.dataset.tab);
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
    $('pn-title').textContent = 'Server ' + label.toLowerCase();
    $('pn-dot').className = 'np-status-dot ' + dot;
    $('pn-state-pill').className = 'np-live ' + pill;
    $('pn-state-text').textContent = label;
    const parts = [];
    if (st.state === 'RUNNING' && st.runningSince) parts.push('up ' + duration(Date.now() - st.runningSince));
    if (st.state === 'STARTING' && st.startedAt) parts.push('starting for ' + duration(Date.now() - st.startedAt));
    if (st.pid) parts.push('PID ' + st.pid);
    if (st.message) parts.push(st.message);
    if (st.nextRestartAt) parts.push('restarting in ' + duration(st.nextRestartAt - Date.now()));
    if (st.recentCrashes && st.state !== 'CRASHED') parts.push(`${st.recentCrashes} crash${st.recentCrashes === 1 ? '' : 'es'} in the last 15 minutes`);
    if (st.ready && (st.state === 'STOPPED' || st.state === 'CRASHED') && st.ready !== st.message) parts.push(st.ready);
    $('pn-sub').textContent = parts.join(' · ');
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

  function lineClass(line) {
    if (line.startsWith('[panel] ')) return 'panel';
    if (line.startsWith('> ')) return 'cmd';
    if (/\b(ERROR|FATAL)\b|Exception|^\s+at /.test(line)) return 'poor';
    if (/\bWARN\b/.test(line)) return 'warn';
    return '';
  }

  function addLines(lines) {
    const log = $('pn-log');
    const filter = $('pn-console-search').value.trim().toLowerCase();
    const frag = document.createDocumentFragment();
    for (const line of lines) {
      const div = document.createElement('div');
      div.className = lineClass(line);
      div.textContent = line;
      if (filter && !line.toLowerCase().includes(filter)) div.hidden = true;
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
    socket.onmessage = (e) => {
      const msg = JSON.parse(e.data);
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
      if (loggedIn) reconnect = setTimeout(openConsole, 3000);
    };
  }

  function closeConsole() {
    clearTimeout(reconnect);
    if (socket) {
      socket.onclose = null;
      socket.close();
      socket = null;
    }
  }

  $('pn-console-search').addEventListener('input', () => {
    const filter = $('pn-console-search').value.trim().toLowerCase();
    for (const div of $('pn-log').children) div.hidden = !!filter && !div.textContent.toLowerCase().includes(filter);
  });

  $('pn-command-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const input = $('pn-command');
    const command = input.value.trim();
    if (!command) return;
    try {
      await api('api/server/command', { command });
      input.value = '';
    } catch (err) {
      addLines(['[panel] ' + err.message]);
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
    $('pn-preview').textContent = [s.javaPath, `-Xms${s.memoryMinMb}M`, `-Xmx${s.memoryMaxMb}M`, preset && preset.flags, s.customArgs, '-jar', s.serverJar, 'nogui']
      .filter(Boolean).join(' ');
  }

  async function loadSettings() {
    try {
      fillSettings(await api('api/settings'));
    } catch (e) { /* the form stays empty; saving shows the error */ }
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

  function renderUsers() {
    $('pn-env-note').textContent = `${accounts.envAdmin} (from the container settings) can always log in and isn't listed here.`;
    $('pn-user-rows').innerHTML = accounts.users.map((u) => `<tr class="${u.disabled ? 'disabled' : ''}" data-user="${u.id}">
      <td class="name">${esc(u.name)}${u.disabled ? '<small>disabled</small>' : ''}</td>
      <td><div class="np-chips">${u.roles.map((r) => `<span class="np-chip">${esc(roleName(r))}</span>`).join('') || '<span class="np-chip">no role</span>'}</div></td>
      <td>${esc(dateTime(u.lastLogin))}</td>
      <td><div class="pn-row-actions">
        <button type="button" class="np-btn small" data-user-act="edit">Edit</button>
        <button type="button" class="np-btn small" data-user-act="toggle">${u.disabled ? 'Enable' : 'Disable'}</button>
        <button type="button" class="np-btn small danger" data-user-act="delete">Delete</button>
      </div></td></tr>`).join('') || '<tr><td colspan="4" class="np-empty">No users yet. Create the first one below.</td></tr>';
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
  }

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
      form.keepDaily.value = backupData.settings.keepDaily;
      form.keepWeekly.value = backupData.settings.keepWeekly;
      form.exclude.value = backupData.settings.exclude.join('\n');
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
    if (!confirm(`Restore ${only.length} item${only.length === 1 ? '' : 's'} from ${restoreName}? The current state is backed up first.`)) return;
    try {
      await api(`api/backups/${encodeURIComponent(restoreName)}/restore`, { only });
      $('pn-restore').hidden = true;
      loadBackups();
    } catch (err) {
      formMsg('pn-restore-msg', err.message, false);
    }
  });

  $('pn-backup-settings').addEventListener('submit', async (e) => {
    e.preventDefault();
    const form = e.target;
    try {
      const res = await api('api/backups/settings', {
        keepLast: Number(form.keepLast.value), keepDaily: Number(form.keepDaily.value), keepWeekly: Number(form.keepWeekly.value),
        exclude: form.exclude.value.split('\n').map((l) => l.trim()).filter(Boolean),
      });
      formMsg('pn-backup-settings-msg', res.deleted.length ? `Saved. ${res.deleted.length} old backup(s) removed.` : 'Saved.', true);
      loadBackups();
    } catch (err) {
      formMsg('pn-backup-settings-msg', err.message, false);
    }
  });

  // ── Scheduled tasks ────────────────────────────────────────────────────

  let scheduleData = null;
  const STEP_LABELS = { restart: 'Restart', stop: 'Stop the server', start: 'Start the server', backup: 'Backup', command: 'Console command', say: 'Chat message', wait: 'Wait' };

  async function loadSchedule() {
    try {
      scheduleData = await api('api/schedule');
    } catch (err) {
      $('pn-task-list').textContent = err.message;
      return;
    }
    $('pn-zone').textContent = scheduleData.zone;
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
    if (s.type === 'wait') fields = `<input type="number" data-f="seconds" min="1" max="3600" value="${s.seconds ?? 30}"><span>seconds</span>`;
    return `<div class="pn-step"><select data-f="type">${opts}</select>${fields}<button type="button" class="np-btn small" data-step-remove title="Remove"><i class="bi bi-x-lg"></i></button></div>`;
  }

  function renderSteps(steps) {
    $('pn-steps').innerHTML = steps.map(stepRow).join('');
  }

  function readSteps() {
    return [...$('pn-steps').children].map((row) => {
      const s = { type: row.querySelector('[data-f="type"]').value, text: '', minutes: 5, seconds: 30 };
      row.querySelectorAll('input[data-f]').forEach((i) => { s[i.dataset.f] = i.type === 'number' ? Number(i.value) : i.value.trim(); });
      return s;
    });
  }

  $('pn-steps').addEventListener('change', (e) => {
    if (e.target.dataset.f !== 'type') return;
    const steps = readSteps();
    renderSteps(steps);
  });
  $('pn-steps').addEventListener('click', (e) => {
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

  // ── Text editor (files and configs) ────────────────────────────────────

  const extOf = (path) => (path.match(/\.([^./]+)$/) || [])[1]?.toLowerCase() || '';

  /** Fields a form view can show: key/value lines (properties, flat TOML) or a JSON object of plain values. */
  function parseForm(path, text, flatJson) {
    const ext = extOf(path);
    const lines = text.split('\n');
    const fields = [];
    let section = '';
    let comments = [];
    if (ext === 'json' && flatJson) {
      const obj = JSON.parse(text);
      for (const [key, value] of Object.entries(obj)) fields.push({ key, value, type: typeof value === 'boolean' ? 'bool' : typeof value === 'number' ? 'number' : 'text', json: true });
      return fields.length ? { kind: 'json', fields, indent: (text.match(/\n([ \t]+)"/) || [])[1] || '  ' } : null;
    }
    if (ext !== 'properties' && ext !== 'toml') return null;
    lines.forEach((line, i) => {
      const t = line.trim();
      if (!t) { comments = []; return; }
      if (t.startsWith('#') || t.startsWith('!')) { comments.push(t.replace(/^[#!]\s?/, '')); return; }
      if (ext === 'toml') {
        const sec = t.match(/^\[\[?([^\]]+)\]\]?$/);
        if (sec) { section = sec[1]; comments = []; return; }
        const m = line.match(/^(\s*)([A-Za-z0-9_.\-]+|"[^"]*")(\s*=\s*)(true|false|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|"(?:[^"\\]|\\.)*")(\s*(?:#.*)?)$/);
        if (m) {
          const raw = m[4];
          const type = raw === 'true' || raw === 'false' ? 'bool' : raw.startsWith('"') ? 'text' : 'number';
          fields.push({ line: i, prefix: m[1] + m[2] + m[3], suffix: m[5], key: m[2].replace(/^"|"$/g, ''), section, comment: comments.join(' '), type,
            value: type === 'bool' ? raw === 'true' : type === 'number' ? Number(raw) : JSON.parse(raw) });
        }
      } else {
        const m = line.match(/^(\s*)([^=:\s][^=:]*?)(\s*[=:]\s*)(.*)$/);
        if (m) {
          const raw = m[4];
          const type = raw === 'true' || raw === 'false' ? 'bool' : /^-?\d+(\.\d+)?$/.test(raw) ? 'number' : 'text';
          fields.push({ line: i, prefix: m[1] + m[2] + m[3], suffix: '', key: m[2], section: '', comment: comments.join(' '), type, value: type === 'bool' ? raw === 'true' : raw });
        }
      }
      comments = [];
    });
    return fields.length ? { kind: ext, fields } : null;
  }

  function makeEditor(root) {
    const text = root.querySelector('.pn-text');
    const gutter = root.querySelector('.pn-gutter');
    const code = root.querySelector('.pn-code');
    const formView = root.querySelector('.pn-form-view');
    const modes = root.querySelector('.pn-editor-modes');
    const msg = root.querySelector('.pn-editor-msg');
    const force = root.querySelector('.pn-editor-force');
    const editor = { path: null, onClose: null, onSaved: null };
    let form = null;
    let mode = 'text';
    let original = '';

    function numbers() {
      const n = text.value.split('\n').length;
      if (gutter.dataset.n !== String(n)) {
        gutter.textContent = Array.from({ length: n }, (_, i) => i + 1).join('\n');
        gutter.dataset.n = String(n);
      }
      gutter.scrollTop = text.scrollTop;
    }

    function note(t, ok) {
      msg.className = 'np-form-msg pn-editor-msg ' + (ok === true ? 'ok' : ok === false ? 'error' : '');
      msg.textContent = t;
    }

    function goToLine(line, column) {
      const lines = text.value.split('\n');
      let pos = 0;
      for (let i = 0; i < Math.min(line - 1, lines.length); i++) pos += lines[i].length + 1;
      pos += Math.max(0, (column || 1) - 1);
      text.focus();
      text.setSelectionRange(pos, Math.min(text.value.length, pos + 1));
      const lineHeight = parseFloat(getComputedStyle(text).lineHeight) || 19;
      text.scrollTop = Math.max(0, (line - 5) * lineHeight);
      numbers();
    }

    function renderForm() {
      const groups = new Map();
      form.fields.forEach((f, i) => {
        const g = f.section || '';
        if (!groups.has(g)) groups.set(g, []);
        groups.get(g).push([f, i]);
      });
      formView.innerHTML = [...groups].map(([g, list]) => (g ? `<h5>${esc(g)}</h5>` : '') + list.map(([f, i]) => {
        const label = `<span>${esc(f.key)}${f.comment ? `<span class="pn-comment">${esc(f.comment)}</span>` : ''}</span>`;
        if (f.type === 'bool') return `<label class="np-form-row">${label}<span class="np-switch"><input type="checkbox" data-field="${i}" ${f.value ? 'checked' : ''}><span></span></span></label>`;
        return `<label class="np-form-row">${label}<input type="${f.type === 'number' ? 'number' : 'text'}" step="any" data-field="${i}" value="${esc(f.value)}"></label>`;
      }).join('')).join('');
    }

    /** The form's values written back into the text, keeping comments and layout. */
    function fromForm() {
      formView.querySelectorAll('[data-field]').forEach((input) => {
        const f = form.fields[Number(input.dataset.field)];
        f.value = f.type === 'bool' ? input.checked : f.type === 'number' ? (input.value === '' ? 0 : Number(input.value)) : input.value;
      });
      if (form.kind === 'json') {
        const obj = {};
        form.fields.forEach((f) => { obj[f.key] = f.value; });
        return JSON.stringify(obj, null, form.indent) + (text.value.endsWith('\n') ? '\n' : '');
      }
      const lines = text.value.split('\n');
      for (const f of form.fields) {
        const token = form.kind === 'toml' && f.type === 'text' ? JSON.stringify(f.value) : String(f.value);
        lines[f.line] = f.prefix + token + f.suffix;
      }
      return lines.join('\n');
    }

    function setMode(next) {
      if (next === mode) return;
      if (next === 'form') {
        form = parseForm(editor.path, text.value, extOf(editor.path) === 'json' && (() => { try { const o = JSON.parse(text.value); return o && typeof o === 'object' && !Array.isArray(o) && Object.values(o).every((v) => v === null || typeof v !== 'object'); } catch (e) { return false; } })());
        if (!form) return note('This file can only be edited as text right now (check it for errors).', false);
        renderForm();
      } else if (form) {
        text.value = fromForm();
        numbers();
      }
      mode = next;
      code.hidden = mode !== 'text';
      formView.hidden = mode !== 'form';
      modes.querySelectorAll('[data-mode]').forEach((b) => b.classList.toggle('active', b.dataset.mode === mode));
    }

    async function save(forced) {
      if (mode === 'form') {
        text.value = fromForm();
        numbers();
      }
      try {
        await api('api/files/write', { path: editor.path, text: text.value, force: !!forced });
        original = text.value;
        force.hidden = true;
        note('Saved.', true);
        if (editor.onSaved) editor.onSaved();
      } catch (err) {
        const d = err.data || {};
        note(d.line ? `Line ${d.line}${d.column ? ', column ' + d.column : ''}: ${err.message}` : err.message, false);
        force.hidden = !d.line && !/JSON|TOML|YAML|expected|Expected/.test(err.message);
        if (d.line) {
          if (mode === 'form') setMode('text');
          goToLine(d.line, d.column);
        }
      }
    }

    editor.open = async (path) => {
      if (editor.path && text.value !== original && !confirm('Discard the unsaved changes?')) return false;
      let data;
      try {
        data = await api('api/files/read?path=' + encodeURIComponent(path));
      } catch (err) {
        alert(err.message);
        return false;
      }
      if (data.text === null) {
        alert(`${path} isn't a text file (or is over 5 MB), so it can't be edited here. Download it instead.`);
        return false;
      }
      editor.path = data.path;
      root.hidden = false;
      root.querySelector('.pn-editor-name').textContent = data.path;
      text.value = original = data.text;
      text.scrollTop = 0;
      gutter.dataset.n = '';
      numbers();
      mode = 'text';
      form = null;
      code.hidden = false;
      formView.hidden = true;
      modes.querySelectorAll('[data-mode]').forEach((b) => b.classList.toggle('active', b.dataset.mode === 'text'));
      modes.hidden = !parseFormSafe(data);
      force.hidden = true;
      note('');
      const editable = can('files.write') || (can('files.config') && data.path.startsWith('config/'));
      text.readOnly = !editable;
      root.querySelector('.pn-editor-save').hidden = !editable;
      return true;
    };

    function parseFormSafe(data) {
      try {
        return !!parseForm(data.path, data.text, data.flatJson);
      } catch (e) {
        return false;
      }
    }

    editor.close = () => {
      if (editor.path && text.value !== original && !confirm('Discard the unsaved changes?')) return;
      editor.path = null;
      root.hidden = true;
      if (editor.onClose) editor.onClose();
    };

    text.addEventListener('input', numbers);
    text.addEventListener('scroll', () => { gutter.scrollTop = text.scrollTop; });
    text.addEventListener('keydown', (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
        e.preventDefault();
        save(false);
      } else if (e.key === 'Tab' && !text.readOnly) {
        e.preventDefault();
        const indent = /\n\t/.test(text.value) ? '\t' : '  ';
        const start = text.selectionStart;
        text.setRangeText(indent, start, text.selectionEnd, 'end');
        numbers();
      }
    });
    root.addEventListener('keydown', (e) => {
      if (mode === 'form' && (e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
        e.preventDefault();
        save(false);
      }
    });
    modes.addEventListener('click', (e) => { const b = e.target.closest('[data-mode]'); if (b) setMode(b.dataset.mode); });
    root.querySelector('.pn-editor-save').addEventListener('click', () => save(false));
    force.addEventListener('click', () => { if (confirm('Save even though the file has errors? The mod may fail to load it.')) save(true); });
    root.querySelector('.pn-editor-close').addEventListener('click', () => editor.close());
    return editor;
  }

  // ── Files ──────────────────────────────────────────────────────────────

  const fileEditor = makeEditor($('pn-file-editor'));
  fileEditor.onSaved = () => loadFiles();
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

  $('pn-file-rows').addEventListener('click', async (e) => {
    const open = e.target.closest('[data-open]');
    if (open) {
      if (open.dataset.dir) return loadFiles(open.dataset.open);
      if (await fileEditor.open(open.dataset.open)) $('pn-file-editor').scrollIntoView({ behavior: 'smooth', block: 'start' });
      return;
    }
    const b = e.target.closest('[data-file-act]');
    if (!b) return;
    const path = b.closest('tr').dataset.path;
    const name = path.split('/').pop();
    try {
      switch (b.dataset.fileAct) {
        case 'rename': {
          const to = prompt('New name or path (relative to the server folder):', path);
          if (!to || to === path) return;
          await api('api/files/move', { from: path, to });
          break;
        }
        case 'copy': {
          const to = prompt('Copy to (relative to the server folder):', path.replace(/(\.[^./]+)?$/, ' copy$1'));
          if (!to) return;
          await api('api/files/copy', { from: path, to });
          break;
        }
        case 'unzip': {
          const res = await api('api/files/unzip', { path });
          filesNote(`Extracted ${res.files} files.`, true);
          break;
        }
        case 'delete':
          if (!confirm(`Delete ${name}? It stays in .panel-trash for 7 days.`)) return;
          await api('api/files/delete', { paths: [path] });
          break;
      }
      loadFiles();
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
      await api('api/files/zip', { paths, to });
      loadFiles();
    } catch (err) {
      filesNote(err.message, false);
    }
  });

  /** Uploads with progress (fetch has none); overwrite asks first. */
  function upload(fileList, overwrite) {
    const files = [...fileList];
    if (!files.length) return;
    const form = new FormData();
    files.forEach((f) => form.append('files', f, f.name));
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `api/files/upload?path=${encodeURIComponent(cwd)}${overwrite ? '&overwrite=1' : ''}`);
    xhr.setRequestHeader('X-NCC', '1');
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) filesNote(`Uploading ${files.length} file${files.length === 1 ? '' : 's'}… ${Math.round(e.loaded / e.total * 100)}%`, true);
    };
    xhr.onload = () => {
      let data = {};
      try { data = JSON.parse(xhr.responseText); } catch (e) { /* not JSON */ }
      if (xhr.status === 400 && /already exists/.test(data.error || '') && !overwrite) {
        if (confirm(data.error + ' Replace it?')) return upload(files, true);
      }
      if (xhr.status >= 400) filesNote(data.error || 'Upload failed', false);
      else filesNote(`Uploaded ${data.saved.length} file${data.saved.length === 1 ? '' : 's'}.`, true);
      loadFiles();
    };
    xhr.onerror = () => filesNote('Upload failed (connection).', false);
    xhr.send(form);
  }

  $('pn-upload').addEventListener('change', (e) => { upload(e.target.files, false); e.target.value = ''; });
  const drop = $('pn-drop');
  drop.addEventListener('dragover', (e) => {
    if (!can('files.write') || !e.dataTransfer.types.includes('Files')) return;
    e.preventDefault();
    drop.classList.add('dragging');
  });
  drop.addEventListener('dragleave', (e) => { if (!drop.contains(e.relatedTarget)) drop.classList.remove('dragging'); });
  drop.addEventListener('drop', (e) => {
    e.preventDefault();
    drop.classList.remove('dragging');
    if (can('files.write')) upload(e.dataTransfer.files, false);
  });

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

  const configEditor = makeEditor($('pn-config-editor'));
  configEditor.onClose = () => {
    $('pn-config-empty').hidden = false;
    $('pn-config-files').querySelectorAll('.active').forEach((b) => b.classList.remove('active'));
  };
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
      + list.map(([f, name]) => `<button type="button" class="pn-config-file ${f.path === configEditor.path ? 'active' : ''}" data-config="${esc(f.path)}" title="${esc(f.path)}">${esc(name)}</button>`).join('')
    ).join('') || '<p class="np-empty">No config files found.</p>';
  }

  $('pn-config-search').addEventListener('input', renderConfigs);
  $('pn-config-files').addEventListener('click', async (e) => {
    const b = e.target.closest('[data-config]');
    if (!b) return;
    if (await configEditor.open(b.dataset.config)) {
      $('pn-config-empty').hidden = true;
      renderConfigs();
    }
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
    $('pn-update-log').innerHTML = st.log.map((l) => `<li>${esc(l)}</li>`).join('');
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
    if (!confirm(`${$('pn-update-label').textContent}?\n\n1. Backup\n2. Stop the server\n3. New server jar${upd ? `, ${upd} mod update${upd === 1 ? '' : 's'}` : ''}${off ? `, ${off} mod${off === 1 ? '' : 's'} turned off` : ''}\n4. Start; if it doesn't come up, the backup is restored`)) return;
    try {
      await api('api/versions/update', { mc, loader, actions });
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

  const EVENT_LABELS = { join: 'Join', leave: 'Leave', death: 'Death', advancement: 'Advancement', start: 'Server online', stop: 'Server offline', crash: 'Crash' };
  let discordData = null;

  async function loadDiscord() {
    try {
      discordData = await api('api/discord');
    } catch (err) {
      $('pn-discord-status').textContent = err.message;
      return;
    }
    const s = discordData.settings;
    const form = $('pn-discord-form');
    for (const key of ['enabled', 'requireLink', 'requireMember', 'opsBypass', 'chatToDiscord', 'chatToGame', 'presence']) form[key].checked = !!s[key];
    form.token.value = '';
    form.token.placeholder = discordData.tokenFromEnv ? 'set with DISCORD_TOKEN' : s.token ? 'saved (paste a new one to replace it)' : 'paste the bot token';
    form.token.disabled = discordData.tokenFromEnv;
    form.guildId.value = s.guildId || '';
    form.channelId.value = s.channelId || '';
    form.bypass.value = (s.bypass || []).join('\n');
    form.kickMessage.value = s.kickMessage || '';
    $('pn-discord-events').innerHTML = Object.keys(EVENT_LABELS).map((k) => `<div class="pn-step" data-event="${k}">
      <label class="np-check"><input type="checkbox" ${s.events[k] ? 'checked' : ''}> <span class="pn-event-name">${EVENT_LABELS[k]}</span></label>
      <input type="text" value="${esc(s.templates[k] || '')}"></div>`).join('');
    $('pn-discord-status').textContent = 'Bot: ' + discordData.status;
    renderLinks();
  }

  function renderLinks() {
    const q = $('pn-link-search').value.trim().toLowerCase();
    const rows = discordData.links.filter((l) => !q || l.name.toLowerCase().includes(q) || l.discordName.toLowerCase().includes(q))
      .sort((a, b) => a.name.localeCompare(b.name));
    $('pn-link-rows').innerHTML = rows.map((l) => `<tr data-link="${esc(l.uuid)}"><td class="name">${esc(l.name)}<small>${esc(l.uuid)}</small></td>
      <td>${esc(l.discordName)}</td><td>${esc(dateTime(l.linkedAt))}</td>
      <td><div class="pn-row-actions"><button type="button" class="np-btn small danger" data-unlink>Unlink</button></div></td></tr>`).join('')
      || '<tr><td colspan="4" class="np-empty">No linked accounts yet.</td></tr>';
  }

  $('pn-link-search').addEventListener('input', renderLinks);
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
    $('pn-discord-events').querySelectorAll('[data-event]').forEach((row) => {
      events[row.dataset.event] = row.querySelector('input[type="checkbox"]').checked;
      templates[row.dataset.event] = row.querySelector('input[type="text"]').value;
    });
    const body = {
      enabled: form.enabled.checked, token: form.token.value.trim(), guildId: form.guildId.value.trim(), channelId: form.channelId.value.trim(),
      requireLink: form.requireLink.checked, requireMember: form.requireMember.checked, opsBypass: form.opsBypass.checked,
      bypass: form.bypass.value.split('\n').map((l) => l.trim()).filter(Boolean), kickMessage: form.kickMessage.value,
      notMemberMessage: discordData.settings.notMemberMessage,
      chatToDiscord: form.chatToDiscord.checked, chatToGame: form.chatToGame.checked, presence: form.presence.checked, events, templates,
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
    form.hubs.value = s.hubs;
    $('pn-map-dims').innerHTML = mapData.dimensions.map((d) => `<label><input type="checkbox" value="${esc(d.id)}" ${s.hidden.includes(d.id) ? '' : 'checked'}> ${esc(d.name)}</label>`).join('');
    $('pn-map-status').textContent = `${st.status} · ${st.rendered}/${st.regions} regions · colors: ${st.colors}`;
    $('pn-map-host').innerHTML = mapData.host
      ? `Public address: <a href="https://${esc(mapData.host)}/" target="_blank" rel="noopener">${esc(mapData.host)}</a>`
      : 'Set <code>MAP_HOST</code> (e.g. map.example.com) to give the map its own address.';
  }

  $('pn-map-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const form = e.target;
    const shown = [...$('pn-map-dims').querySelectorAll('input:checked')].map((i) => i.value);
    try {
      await api('api/map/settings', {
        enabled: form.enabled.checked, title: form.title.value.trim(), showPlayers: form.showPlayers.checked, showClaims: form.showClaims.checked,
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

  // ── Theme ──────────────────────────────────────────────────────────────

  function renderTheme() {
    const light = document.documentElement.dataset.theme === 'light';
    $('pn-theme').innerHTML = light ? '<i class="bi bi-moon-stars"></i>' : '<i class="bi bi-sun"></i>';
    $('pn-theme').title = light ? 'Switch to dark mode' : 'Switch to light mode';
  }
  $('pn-theme').addEventListener('click', () => {
    const next = document.documentElement.dataset.theme === 'light' ? 'dark' : 'light';
    document.documentElement.dataset.theme = next;
    try { localStorage.setItem('np-theme', next); } catch (e) { /* not remembered */ }
    renderTheme();
  });
  renderTheme();

  // ── Start ──────────────────────────────────────────────────────────────

  showApp();
  api('api/health').then((h) => { $('pn-version').textContent = h.version; }).catch(() => {});
})();
