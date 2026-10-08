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
    if (!res.ok) throw new Error(data.error || res.statusText);
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
    document.querySelectorAll('#pn-tabs .np-tab').forEach((b) => { b.hidden = !!b.dataset.perm && !can(b.dataset.perm); });
    document.querySelector('.np-banner').hidden = !can('server.view');
    $('pn-app').querySelector('.pn-actions').hidden = !can('server.power');
    $('pn-command-form').hidden = !can('console.write');
    const editable = can('settings.java');
    $('pn-settings').querySelectorAll('input, select, button').forEach((el) => { el.disabled = !editable; });
    let tab = 'server';
    try { tab = localStorage.getItem(TAB_KEY) || tab; } catch (e) { /* default */ }
    selectTab(tab);
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
  }

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
