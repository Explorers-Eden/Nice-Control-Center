(() => {
  'use strict';

  // The public info page: server settings, game rules, data pack settings and published config files,
  // in plain words. A tree on the left mirrors the page (data packs follow their own in-game menus);
  // it follows the scroll position and the search. Read-only; everything comes from data.json.

  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const COLORS = { 0: '#000000', 1: '#0000AA', 2: '#00AA00', 3: '#00AAAA', 4: '#AA0000', 5: '#AA00AA', 6: '#FFAA00', 7: '#AAAAAA',
    8: '#555555', 9: '#5555FF', a: '#55FF55', b: '#55FFFF', c: '#FF5555', d: '#FF55FF', e: '#FFFF55', f: '#FFFFFF' };

  /** Minecraft § codes (the server list message, names in data packs) as coloured text. */
  function mc(text) {
    let color = null;
    let bold = false;
    let italic = false;
    return String(text ?? '').replace(/\\n/g, '\n').split(/(§[0-9a-fk-or])/i).map((part) => {
      const m = part.match(/^§([0-9a-fk-or])$/i);
      if (m) {
        const c = m[1].toLowerCase();
        if (COLORS[c]) { color = COLORS[c]; bold = false; italic = false; } else if (c === 'l') bold = true; else if (c === 'o') italic = true;
        else if (c === 'r') { color = null; bold = false; italic = false; }
        return '';
      }
      if (!part) return '';
      const css = `${color ? `color:${color};` : ''}${bold ? 'font-weight:700;' : ''}${italic ? 'font-style:italic;' : ''}`;
      const html = esc(part).replace(/\n/g, '<br>');
      return css ? `<span style="${css}">${html}</span>` : html;
    }).join('');
  }
  const plain = (s) => String(s ?? '').replace(/§./g, '');

  /** "spawn_protection" or "maxPlayers" → "Spawn protection", "Max players". */
  const humanize = (k) => {
    const t = String(k).replace(/[-_.]+/g, ' ').replace(/([a-z])([A-Z])/g, '$1 $2').trim().toLowerCase();
    return t ? t[0].toUpperCase() + t.slice(1) : k;
  };

  /** "fabled_roots" → "Fabled Roots": names of packs and mods, every word capitalised. */
  const titleCase = (k) => humanize(k).replace(/\b([a-z])/g, (c) => c.toUpperCase());

  let uid = 0;
  const slug = (s) => (String(s).toLowerCase().replace(/§./g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'part') + '-' + (++uid);

  /** Values in plain words: Yes/No, words capitalised, empty shown as a dash. */
  function value(v, key) {
    const s = String(v ?? '').trim().replace(/^"(.*)"$/, '$1');
    if (s === 'true') return '<span class="in-v yes"><i class="bi bi-check-lg"></i> Yes</span>';
    if (s === 'false') return '<span class="in-v no"><i class="bi bi-x-lg"></i> No</span>';
    if (s === '') return '<span class="in-v none">–</span>';
    if (key === 'motd') return `<span class="in-v motd">${mc(s)}</span>`;
    if (/^https?:\/\//.test(s)) return `<a class="in-v" href="${esc(s)}" target="_blank" rel="noopener nofollow">${esc(s.length > 48 ? s.slice(0, 45) + '…' : s)}</a>`;
    if (/^[a-z][a-z_]*$/.test(s) && s.length < 24) return `<span class="in-v">${esc(humanize(s))}</span>`;
    return `<span class="in-v">${mc(s)}</span>`;
  }

  const row = (label, val, desc, extra = '') => `<div class="in-row" data-search="${esc((plain(label) + ' ' + plain(desc) + ' ' + plain(val)).toLowerCase())}">
      <div class="in-row-text"><b>${mc(label)}</b>${extra}${desc ? `<small>${mc(desc)}</small>` : ''}</div><div class="in-row-value">${val}</div></div>`;

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
        rows.push({ label: (section ? section + ' › ' : '') + humanize(m[1].replace(/^"|"$/g, '')), value: m[2].replace(/\s+#.*$/, ''), desc: comment.join(' ') });
      }
      comment = [];
    }
    return rows.length ? rows : null;
  }

  /** "config/xaerominimap/client.json" → "Xaerominimap › Client". */
  const fileTitle = (p) => p.replace(/^config\//, '').replace(/\.[^./]+$/, '').split('/').map(titleCase).join(' › ');

  // ── The model: sections → nodes (cards, or groups of nodes) ──

  /** A card: { id, title, icon, body, sub } · a group: { id, title, desc, children } */
  function buildSections(d) {
    const sections = [];
    if (d.properties && d.properties.length) {
      const groups = new Map();
      for (const p of d.properties) {
        if (!groups.has(p.group)) groups.set(p.group, []);
        groups.get(p.group).push(p);
      }
      const ICONS = { Gameplay: 'bi-controller', World: 'bi-globe-americas', 'Players & access': 'bi-people', Network: 'bi-wifi', 'Resource pack': 'bi-palette', Performance: 'bi-speedometer2' };
      sections.push({ id: 'server', title: 'Server settings', icon: 'bi-hdd-rack', hue: '#7b93ff', count: d.properties.length,
        children: [...groups].map(([g, list]) => ({ id: slug(g), title: g || 'Other', icon: ICONS[g] || 'bi-sliders',
          body: list.map((p) => row(p.label || humanize(p.key), value(p.value, p.key), p.description)).join('') })) });
    }
    if (d.gamerules && d.gamerules.length) {
      const groups = new Map();
      for (const r of d.gamerules) {
        if (!groups.has(r.category)) groups.set(r.category, []);
        groups.get(r.category).push(r);
      }
      const changed = d.gamerules.filter((r) => r.changed).length;
      sections.push({ id: 'rules', title: 'Game rules', icon: 'bi-journal-check', hue: '#4fd1c5', count: d.gamerules.length, note: changed ? `${changed} changed` : '',
        children: [...groups].map(([g, list]) => ({ id: slug(g), title: g || 'Other', icon: 'bi-journal-check',
          body: list.map((r) => row(r.name || humanize(r.id), value(r.value), r.description, r.changed ? ' <span class="in-tag">changed</span>' : '')).join('') })) });
    }
    if (d.commands && d.commands.length) {
      const groups = new Map();
      for (const c of d.commands) {
        if (!groups.has(c.mod)) groups.set(c.mod, { name: c.modName, list: [] });
        groups.get(c.mod).list.push(c);
      }
      const order = [...groups].sort(([a, ga], [b, gb]) => (a === 'minecraft' ? -1 : b === 'minecraft' ? 1 : ga.name.localeCompare(gb.name)));
      const cmd = (c) => {
        const search = [c.name, ...(c.aliases || []), c.description, ...(c.usage || []), ...(c.parts || []).map((p) => p.name + ' ' + (p.description || ''))].join(' ').toLowerCase();
        const usage = (c.usage || []).filter((u) => c.usage.length > 1 || u !== '/' + c.name);
        return `<div class="in-row in-cmd" data-search="${esc(search)}">
          <div class="in-row-text"><b><code>/${esc(c.name)}</code>${(c.aliases || []).map((a) => ` <code class="in-alias">/${esc(a)}</code>`).join('')}</b>
            ${c.description ? `<small>${esc(c.description)}</small>` : ''}
            ${(c.parts || []).map((p) => `<small class="in-cmd-part"><code>/${esc(p.name)}</code>${p.description ? ' ' + esc(p.description) : ''}</small>`).join('')}
            ${usage.length ? `<details class="in-cmd-usage"><summary>How to write it</summary>${usage.map((u) => `<code>${esc(u)}</code>`).join('')}</details>` : ''}</div></div>`;
      };
      sections.push({ id: 'commands', title: 'Commands', statLabel: 'commands for everyone', icon: 'bi-slash-square', hue: '#ffd166', count: d.commands.length,
        children: order.map(([id, g]) => ({ id: slug(g.name), title: g.name, icon: id === 'minecraft' ? 'bi-box' : 'bi-puzzle', sub: `${g.list.length} command${g.list.length === 1 ? '' : 's'}`,
          body: g.list.map(cmd).join('') })) });
    }
    if (d.packs && d.packs.length) {
      let settings = 0;
      const packs = d.packs.map((p) => {
        const dialogs = new Map(p.dialogs.map((dl) => [dl.id, dl]));
        const used = new Set();
        settings += p.dialogs.reduce((n, dl) => n + dl.settings.length, 0);
        const card = (dl, title) => {
          used.add(dl.id);
          return { id: slug(title || dl.title), title: title || dl.title, icon: 'bi-sliders2', sub: dl.section || '',
            body: dl.settings.map((s) => row(s.label, value(s.value))).join('') || '<p class="in-empty">No settings.</p>' };
        };
        // The pack's own menu: menus become groups, forms become cards.
        const walk = (nodes) => (nodes || []).map((n) => {
          if (n.dialog && dialogs.has(n.dialog)) return { ...card(dialogs.get(n.dialog), n.label), desc: n.description };
          const kids = walk(n.children);
          return kids.length ? { id: slug(n.label), title: n.label, desc: n.description, children: kids } : null;
        }).filter(Boolean);
        let children = walk(p.menu);
        const rest = p.dialogs.filter((dl) => !used.has(dl.id)).map((dl) => card(dl));
        if (rest.length) children = children.length ? children.concat([{ id: slug('more'), title: 'More settings', children: rest }]) : rest;
        return { id: slug(p.pack), title: titleCase(p.pack), icon: 'bi-box-seam', children };
      });
      sections.push({ id: 'packs', title: 'Data packs', statLabel: 'data pack settings', icon: 'bi-boxes', hue: '#ffb35c', count: settings, children: packs });
    }
    if (d.performance && d.performance.length) {
      sections.push({ id: 'perf', title: 'Performance tweaks', statLabel: 'performance tweaks', icon: 'bi-speedometer2', hue: '#ff7b9c', count: d.performance.length,
        children: d.performance.map((t) => ({ id: slug(t.id), title: t.title, icon: t.icon, desc: t.description,
          body: t.rows.map((r) => row(r.label, value(r.value), r.description)).join('') })) });
    }
    if (d.configs && d.configs.length) {
      sections.push({ id: 'configs', title: 'Mod settings', statLabel: 'mod config files', icon: 'bi-puzzle', hue: '#a77bff', count: d.configs.length,
        children: d.configs.map((c) => {
          const rows = configRows(c.path, c.text);
          return { id: slug(c.path), title: fileTitle(c.path), icon: 'bi-gear', sub: c.path,
            body: rows ? rows.map((r) => row(r.label, value(r.value), r.desc)).join('') : `<pre class="in-pre" data-search="${esc(c.text.toLowerCase())}">${esc(c.text)}</pre>` };
        }) });
    }
    return sections;
  }

  // ── Rendering ──

  function renderNode(n, depth) {
    if (n.children) {
      return `<div class="in-group" id="${n.id}" data-node data-depth="${depth}">
        <h3 class="in-group-title">${n.icon ? `<i class="bi ${n.icon}"></i>` : ''}${mc(n.title)}</h3>${n.desc ? `<p class="in-group-desc">${mc(n.desc)}</p>` : ''}
        <div class="in-cards">${n.children.map((c) => renderNode(c, depth + 1)).join('')}</div></div>`;
    }
    return `<section class="np-card in-card" id="${n.id}" data-node>
      <h3 class="in-card-title"><span class="in-icon"><i class="bi ${n.icon || 'bi-sliders'}"></i></span><span>${mc(n.title)}${n.sub ? `<small>${esc(n.sub)}</small>` : ''}</span></h3>
      ${n.desc ? `<p class="in-card-desc">${mc(n.desc)}</p>` : ''}${n.body}</section>`;
  }

  function treeItem(n, depth) {
    const kids = n.children || [];
    return `<li data-for="${n.id}"${kids.length ? ' class="has-kids"' : ''}>
      <div class="in-tree-row" style="--depth:${depth}">${kids.length ? '<button type="button" class="in-tree-toggle" aria-label="Open"><i class="bi bi-chevron-right"></i></button>' : '<span class="in-tree-pad"></span>'}
        <a href="#${n.id}">${mc(plain(n.title))}</a></div>
      ${kids.length ? `<ul>${kids.map((k) => treeItem(k, depth + 1)).join('')}</ul>` : ''}</li>`;
  }

  function render(d) {
    document.title = d.title;
    if (d.motd) { $('in-motd').hidden = false; $('in-motd').innerHTML = mc(d.motd); }
    $('in-intro').innerHTML = (d.intro || '').split(/\n\s*\n/).filter((p) => p.trim()).map((p) => `<p>${esc(p).replace(/\n/g, '<br>')}</p>`).join('');
    $('in-updated').textContent = d.updated ? `Last updated ${new Date(d.updated).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' })}` : '';
    const sections = buildSections(d);
    $('in-stats').innerHTML = sections.map((s) => `<a class="in-stat" href="#${s.id}" style="--hue:${s.hue}"><i class="bi ${s.icon}"></i><b>${s.count}</b><span>${esc(s.statLabel || s.title)}${s.note ? ` · ${esc(s.note)}` : ''}</span></a>`).join('');
    $('in-main').innerHTML = sections.map((s) => `<div class="in-section" id="${s.id}" data-node style="--hue:${s.hue}">
        <h2 class="in-section-title"><span class="in-icon big"><i class="bi ${s.icon}"></i></span>${esc(s.title)}<small>${s.count}</small></h2>
        <div class="in-cards">${s.children.map((c) => renderNode(c, 1)).join('')}</div></div>`).join('')
      || '<p class="in-empty">Nothing is published here yet.</p>';
    $('in-tree').innerHTML = sections.length ? `<ul class="in-tree-root">${sections.map((s) => `<li data-for="${s.id}" class="has-kids open" style="--hue:${s.hue}">
        <div class="in-tree-row in-tree-top"><button type="button" class="in-tree-toggle" aria-label="Open"><i class="bi bi-chevron-right"></i></button>
          <a href="#${s.id}"><i class="bi ${s.icon}"></i>${esc(s.title)}</a></div>
        <ul>${s.children.map((c) => treeItem(c, 1)).join('')}</ul></li>`).join('')}</ul>` : '';
  }

  // ── Tree behaviour ──

  $('in-tree').addEventListener('click', (e) => {
    const toggle = e.target.closest('.in-tree-toggle');
    if (toggle) {
      toggle.closest('li').classList.toggle('open');
      return;
    }

  });

  /** Shows only the chosen part (a section, a group or one card), with a breadcrumb; the tree marks it. */
  let selected = null;
  function select(id, push) {
    const target = document.getElementById(id) || document.querySelector('.in-section');
    if (!target) return;
    selected = target.id;
    document.querySelectorAll('[data-node]').forEach((el) => {
      el.classList.toggle('in-off', !(el === target || el.contains(target) || target.contains(el)));
    });
    document.querySelectorAll('#in-tree li.active, #in-tree li.trail').forEach((li) => li.classList.remove('active', 'trail'));
    const li = document.querySelector(`#in-tree li[data-for="${selected}"]`);
    const path = [];
    if (li) {
      li.classList.add('active', 'open');
      for (let p = li; p; p = p.parentElement.closest('li')) {
        p.classList.add('open');
        if (p !== li) p.classList.add('trail');
        path.unshift(p);
      }
    }
    $('in-crumbs').innerHTML = path.map((p, i) => {
      const label = p.querySelector(':scope > .in-tree-row a').textContent.trim();
      return i === path.length - 1 ? `<b>${esc(label)}</b>` : `<a href="#${p.dataset.for}">${esc(label)}</a>`;
    }).join('<i class="bi bi-chevron-right"></i>');
    if (push) history.replaceState(null, '', '#' + selected);
    document.body.classList.remove('in-nav-open');
    const top = $('in-main').getBoundingClientRect().top + window.scrollY - 70;
    if (window.scrollY > top) window.scrollTo({ top, behavior: 'smooth' });
  }

  // Links in the tree, the breadcrumb and the banner choose a part instead of jumping down the page.
  document.addEventListener('click', (e) => {
    const a = e.target.closest('a[href^="#"]');
    if (!a || !document.getElementById(a.getAttribute('href').slice(1))) return;
    e.preventDefault();
    if ($('in-q').value) { $('in-q').value = ''; $('in-q').dispatchEvent(new Event('input')); }
    select(a.getAttribute('href').slice(1), true);
  });
  window.addEventListener('hashchange', () => { if (location.hash.length > 1) select(location.hash.slice(1), false); });

  // ── Search: rows, cards, groups, sections and the tree follow ──

  $('in-q').addEventListener('input', () => {
    const words = $('in-q').value.trim().toLowerCase().split(/\s+/).filter(Boolean);
    const on = words.length > 0;
    // While searching, everything that matches shows; afterwards the chosen part again.
    document.body.classList.toggle('in-searching', on);
    document.querySelectorAll('[data-search]').forEach((el) => { el.hidden = !words.every((w) => el.dataset.search.includes(w)); });
    // Bottom-up: a card shows if a row matches (or its title does), a group/section if anything inside shows.
    document.querySelectorAll('.in-card').forEach((c) => {
      const titleHit = on && words.every((w) => c.querySelector('.in-card-title').textContent.toLowerCase().includes(w));
      if (titleHit) c.querySelectorAll('[data-search]').forEach((el) => { el.hidden = false; });
      c.hidden = on && !titleHit && !c.querySelector('[data-search]:not([hidden])');
    });
    [...document.querySelectorAll('.in-group')].reverse().forEach((g) => { g.hidden = on && !g.querySelector('.in-card:not([hidden])'); });
    document.querySelectorAll('.in-section').forEach((s) => { s.hidden = on && !s.querySelector('.in-card:not([hidden])'); });
    document.querySelectorAll('#in-tree li[data-for]').forEach((li) => {
      const target = document.getElementById(li.dataset.for);
      li.hidden = !!target && target.hidden;
      if (on && !li.hidden && li.classList.contains('has-kids')) li.classList.add('open');
    });
    const none = on && !document.querySelector('.in-section:not([hidden])');
    let empty = $('in-none');
    if (none && !empty) $('in-main').insertAdjacentHTML('beforeend', '<p class="in-empty" id="in-none">Nothing matches.</p>');
    if (!none && empty) empty.remove();
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === '/' && document.activeElement !== $('in-q')) {
      e.preventDefault();
      $('in-q').focus();
    }
  });

  // ── Phones: the tree slides in ──

  $('in-menu').addEventListener('click', () => document.body.classList.toggle('in-nav-open'));
  $('in-backdrop').addEventListener('click', () => document.body.classList.remove('in-nav-open'));

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
    select(location.hash.length > 1 ? decodeURIComponent(location.hash.slice(1)) : null, false);
  }).catch((e) => { $('in-main').innerHTML = `<p class="in-empty">${esc(e.message)}</p>`; });
})();
