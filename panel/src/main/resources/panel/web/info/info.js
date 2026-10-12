(() => {
  'use strict';

  // The public info page: server settings, game rules, data pack settings and published config files,
  // in plain words and groups. Read-only; everything comes from data.json.

  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const COLORS = { 0: '#000000', 1: '#0000AA', 2: '#00AA00', 3: '#00AAAA', 4: '#AA0000', 5: '#AA00AA', 6: '#FFAA00', 7: '#AAAAAA',
    8: '#555555', 9: '#5555FF', a: '#55FF55', b: '#55FFFF', c: '#FF5555', d: '#FF55FF', e: '#FFFF55', f: '#FFFFFF' };

  /** Minecraft § codes (the server list message) as coloured text. */
  function mc(text) {
    let color = null;
    let bold = false;
    return String(text ?? '').replace(/\\n/g, '\n').split(/(§[0-9a-fk-or])/i).map((part) => {
      const m = part.match(/^§([0-9a-fk-or])$/i);
      if (m) {
        const c = m[1].toLowerCase();
        if (COLORS[c]) { color = COLORS[c]; bold = false; } else if (c === 'l') bold = true; else if (c === 'r') { color = null; bold = false; }
        return '';
      }
      if (!part) return '';
      const css = `${color ? `color:${color};` : ''}${bold ? 'font-weight:700;' : ''}`;
      return css ? `<span style="${css}">${esc(part).replace(/\n/g, '<br>')}</span>` : esc(part).replace(/\n/g, '<br>');
    }).join('');
  }

  /** "spawn_protection" or "maxPlayers" → "Spawn protection", "Max players". */
  const humanize = (k) => {
    const t = String(k).replace(/[-_.]+/g, ' ').replace(/([a-z])([A-Z])/g, '$1 $2').trim().toLowerCase();
    return t ? t[0].toUpperCase() + t.slice(1) : k;
  };

  /** Values in plain words: Yes/No, words capitalised, empty shown as a dash. */
  function value(v, key) {
    const s = String(v ?? '').trim().replace(/^"(.*)"$/, '$1');
    if (s === 'true') return '<span class="in-v yes"><i class="bi bi-check-lg"></i> Yes</span>';
    if (s === 'false') return '<span class="in-v no"><i class="bi bi-x-lg"></i> No</span>';
    if (s === '') return '<span class="in-v none">–</span>';
    if (key === 'motd') return `<span class="in-v motd">${mc(s)}</span>`;
    if (/^https?:\/\//.test(s)) return `<a class="in-v" href="${esc(s)}" target="_blank" rel="noopener nofollow">${esc(s.length > 48 ? s.slice(0, 45) + '…' : s)}</a>`;
    if (/^[a-z][a-z_]*$/.test(s) && s.length < 24) return `<span class="in-v">${esc(humanize(s))}</span>`;
    return `<span class="in-v">${esc(s)}</span>`;
  }

  const row = (label, val, desc, extra = '') => `<div class="in-row" data-search="${esc((label + ' ' + (desc || '') + ' ' + String(val ?? '')).toLowerCase())}">
      <div class="in-row-text"><b>${esc(label)}</b>${extra}${desc ? `<small>${esc(desc)}</small>` : ''}</div><div class="in-row-value">${val}</div></div>`;

  const card = (title, icon, body, sub) => `<section class="np-card in-card"><h3 class="np-card-title"><i class="bi ${icon}"></i> ${esc(title)}${sub ? ` <small>${esc(sub)}</small>` : ''}</h3>${body}</section>`;

  // ── Config files as readable settings ──

  /** Rows from a config file: JSON flattened, TOML/properties/YAML by line (comments become descriptions). */
  function configRows(path, text) {
    const ext = (path.match(/\.([^.]+)$/) || [])[1]?.toLowerCase() || '';
    if (ext === 'json' || ext === 'json5' || ext === 'jsonc') {
      try {
        const rows = [];
        const walk = (o, prefix) => {
          for (const [k, v] of Object.entries(o)) {
            const label = prefix ? `${prefix} › ${humanize(k)}` : humanize(k);
            if (v && typeof v === 'object' && !Array.isArray(v)) walk(v, label);
            else if (Array.isArray(v)) rows.push({ label, value: v.every((x) => x === null || typeof x !== 'object') ? (v.length ? v.join(', ') : '–') : `${v.length} entries` });
            else rows.push({ label, value: v });
          }
        };
        walk(JSON.parse(text.replace(/^\s*\/\/.*$/gm, '')), '');
        return rows;
      } catch (e) { return null; }
    }
    if (!['toml', 'properties', 'cfg', 'conf', 'ini', 'yml', 'yaml', 'txt'].includes(ext)) return null;
    const rows = [];
    let section = '';
    let comment = [];
    const indents = [];
    for (const raw of text.split('\n')) {
      const line = raw.replace(/\r$/, '');
      const t = line.trim();
      if (!t) { comment = []; continue; }
      if (t.startsWith('#') || t.startsWith('!') || t.startsWith(';') || t.startsWith('//')) { comment.push(t.replace(/^[#!;/]+\s?/, '')); continue; }
      const sec = t.match(/^\[\[?([^\]]+)\]\]?$/);
      if (sec) { section = sec[1].split('.').map(humanize).join(' › '); comment = []; continue; }
      if (ext === 'yml' || ext === 'yaml') {
        const m = line.match(/^(\s*)([^:#]+):\s*(.*)$/);
        if (!m) continue;
        const depth = m[1].length;
        while (indents.length && indents[indents.length - 1].depth >= depth) indents.pop();
        if (m[3] === '') { indents.push({ depth, name: humanize(m[2].trim()) }); comment = []; continue; }
        rows.push({ label: [...indents.map((x) => x.name), humanize(m[2].trim())].join(' › '), value: m[3].replace(/\s+#.*$/, ''), desc: comment.join(' ') });
      } else {
        const m = t.match(/^([^=:]+?)\s*[=:]\s*(.*)$/);
        if (!m) continue;
        const key = m[1].replace(/^"|"$/g, '');
        rows.push({ label: (section ? section + ' › ' : '') + humanize(key), value: m[2].replace(/\s+#.*$/, ''), desc: comment.join(' ') });
      }
      comment = [];
    }
    return rows.length ? rows : null;
  }

  /** "config/xaerominimap/client.json" → "Xaerominimap › Client". */
  const fileTitle = (p) => p.replace(/^config\//, '').replace(/\.[^./]+$/, '').split('/').map(humanize).join(' › ');

  // ── The page ──

  function render(d) {
    document.title = d.title;
    $('in-title').textContent = d.title;
    $('in-intro').innerHTML = (d.intro || '').split(/\n\s*\n/).filter((p) => p.trim()).map((p) => `<p>${esc(p).replace(/\n/g, '<br>')}</p>`).join('');
    $('in-updated').textContent = d.updated ? `Last updated ${new Date(d.updated).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' })}` : '';
    const sections = [];

    if (d.properties && d.properties.length) {
      const groups = new Map();
      for (const p of d.properties) {
        if (!groups.has(p.group)) groups.set(p.group, []);
        groups.get(p.group).push(p);
      }
      const ICONS = { Gameplay: 'bi-controller', World: 'bi-globe-americas', 'Players & access': 'bi-people', Network: 'bi-wifi', 'Resource pack': 'bi-palette', Performance: 'bi-speedometer2' };
      sections.push({ id: 'server', title: 'Server settings', html: [...groups].map(([g, list]) => card(g || 'Other', ICONS[g] || 'bi-sliders',
        list.map((p) => row(p.label || humanize(p.key), value(p.value, p.key), p.description)).join(''))).join('') });
    }
    if (d.gamerules && d.gamerules.length) {
      const groups = new Map();
      for (const r of d.gamerules) {
        if (!groups.has(r.category)) groups.set(r.category, []);
        groups.get(r.category).push(r);
      }
      sections.push({ id: 'rules', title: 'Game rules', html: [...groups].map(([g, list]) => card(g || 'Other', 'bi-journal-check',
        list.map((r) => row(r.name || humanize(r.id), value(r.value), r.description, r.changed ? ' <span class="in-tag">changed</span>' : '')).join(''))).join('') });
    }
    if (d.packs && d.packs.length) {
      sections.push({ id: 'packs', title: 'Data packs', html: d.packs.map((p) => card(humanize(p.pack), 'bi-box-seam',
        p.dialogs.map((dl) => `<h4 class="in-sub">${esc(dl.title)}${dl.section ? ` <small>${esc(dl.section)}</small>` : ''}</h4>`
          + dl.settings.map((s) => row(s.label, value(s.value))).join('')).join(''))).join('') });
    }
    if (d.configs && d.configs.length) {
      sections.push({ id: 'configs', title: 'Mod settings', html: d.configs.map((c) => {
        const rows = configRows(c.path, c.text);
        const body = rows ? rows.map((r) => row(r.label, value(r.value), r.desc)).join('') : `<pre class="in-pre" data-search="${esc(c.text.toLowerCase())}">${esc(c.text)}</pre>`;
        return card(fileTitle(c.path), 'bi-gear', body, c.path);
      }).join('') });
    }

    $('in-jump').innerHTML = sections.map((s) => `<a href="#${s.id}">${esc(s.title)}</a>`).join('');
    $('in-main').innerHTML = sections.map((s) => `<div class="in-section" id="${s.id}"><h2>${esc(s.title)}</h2><div class="in-cards">${s.html}</div></div>`).join('')
      || '<p class="in-empty">Nothing is published here yet.</p>';
  }

  /** Search: hides rows that don't match, and cards and sections left empty. */
  $('in-q').addEventListener('input', () => {
    const words = $('in-q').value.trim().toLowerCase().split(/\s+/).filter(Boolean);
    document.querySelectorAll('[data-search]').forEach((el) => { el.hidden = !words.every((w) => el.dataset.search.includes(w)); });
    document.querySelectorAll('.in-card').forEach((c) => { c.hidden = !!words.length && !c.querySelector('[data-search]:not([hidden])'); });
    document.querySelectorAll('.in-section').forEach((s) => { s.hidden = !!words.length && !s.querySelector('.in-card:not([hidden])'); });
  });

  $('in-theme').addEventListener('click', () => {
    const next = document.documentElement.dataset.theme === 'light' ? 'dark' : 'light';
    document.documentElement.dataset.theme = next;
    try { localStorage.setItem('np-theme', next); } catch (e) { /* this visit only */ }
  });

  const logo = $('in-logo');
  logo.onload = () => { logo.hidden = false; };
  logo.src = '../branding/panel';

  fetch('data.json', { cache: 'no-store' }).then(async (r) => {
    const d = await r.json();
    if (!r.ok) throw new Error(d.error || 'Not available');
    render(d);
    if (location.hash) document.querySelector(location.hash)?.scrollIntoView();
  }).catch((e) => { $('in-main').innerHTML = `<p class="in-empty">${esc(e.message)}</p>`; });
})();
