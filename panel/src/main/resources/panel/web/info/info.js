(() => {
  'use strict';

  // The public info page: server settings, game rules, data pack settings and published config files,
  // in plain words. Each section is a tab; inside it, chips pick one part (data packs follow their own
  // in-game menus). The search looks through everything. Read-only; everything comes from data.json.

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
      sections.push({ id: 'server', title: 'Server settings', tab: 'Server', icon: 'bi-hdd-rack', hue: '#7b93ff', count: d.properties.length, statLabel: 'settings',
        lead: 'How the server is set up: difficulty, the world, who can join and how far you can see.',
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
      sections.push({ id: 'rules', title: 'Game rules', icon: 'bi-journal-check', hue: '#4fd1c5', count: d.gamerules.length, statLabel: 'rules', note: changed ? `${changed} changed from vanilla` : '',
        lead: 'The game rules of this world. The ones marked "changed" differ from a fresh vanilla world.',
        children: [...groups].map(([g, list]) => ({ id: slug(g), title: g || 'Other', icon: 'bi-journal-check',
          body: list.map((r) => row(r.name || humanize(r.id), value(r.value), r.description, r.changed ? ' <span class="in-tag">changed</span>' : '')).join('') })) });
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
      sections.push({ id: 'packs', title: 'Data packs', statLabel: 'settings', icon: 'bi-boxes', hue: '#ffb35c', count: settings, children: packs,
        lead: 'How the data packs are set up, laid out like their own menus in game.' });
    }
    if (d.borders && d.borders.length) {
      const dim = (id) => titleCase(id.replace(/^minecraft:/, '').replace(/^the_/, '').replace(/_/g, ' '));
      const size = (b, r) => b.shape === 'circle' ? `${(r * 2).toLocaleString()} blocks across (radius ${r.toLocaleString()})` : `${(r * 2).toLocaleString()} × ${(r * 2).toLocaleString()} blocks`;
      sections.push({ id: 'borders', title: 'World borders', tab: 'Borders', statLabel: 'dimensions', icon: 'bi-bounding-box-circles', hue: '#5fd68a', count: d.borders.length,
        lead: 'How far each dimension goes, and the permanent area where your builds are always kept.',
        children: d.borders.map((b) => ({ id: slug(b.dim), title: dim(b.dim), icon: 'bi-globe-americas', sub: `around ${b.x}, ${b.z}`,
          body: (b.permanent > 0 ? row('Permanent area', value(size(b, b.permanent)), 'Builds here are kept. Land outside it may be reset to fresh terrain from time to time.') : '')
            + (b.edge > 0 ? row('Edge of the world', value(size(b, b.edge)), 'You can\'t go further than this.') : row('Edge of the world', value('None'))) })) });
    }
    if (d.claims) {
      const c = d.claims;
      const how = `<p class="in-card-desc">Type <code>/claim</code> for the claim menu, or <code>/claim mode</code> to get the claim tool. Right-click two opposite corners of the area to claim it: everything inside, from bedrock to the sky, is protected. Right-click a corner of your claim to move it. In the menu you add members, choose what everyone else may do, and pick a name and colour.</p>`;
      const blocks = row('Claim blocks for new players', value(c.startingBlocks.toLocaleString()), 'Each claimed block costs one claim block.')
        + row('Claim blocks per hour of play', value(c.blocksPerHour.toLocaleString()), c.afkMinutes ? `Not while you're AFK for more than ${c.afkMinutes} minutes.` : '')
        + row('Most claim blocks from playing', value(c.maxAccruedBlocks.toLocaleString()))
        + (c.shop ? row('Buy claim blocks', value(`${c.shop.blocks.toLocaleString()} for ${c.shop.price} × ${titleCase(c.shop.item.replace(/^minecraft:/, '').replace(/_/g, ' '))}`),
          'In the claim menu' + (c.shop.max ? `, up to ${c.shop.max.toLocaleString()} blocks in total.` : '.')) : '');
      const rules = row('Where', value(c.dimensions.map((x) => titleCase(x.replace(/^minecraft:/, '').replace(/^the_/, '').replace(/_/g, ' '))).join(', ') || 'Nowhere'),
        c.onlyPermanentArea ? 'Only inside the permanent area (see World borders).' : '')
        + row('Smallest claim', value(`${c.minWidth} blocks wide, ${c.minArea.toLocaleString()} blocks in all`))
        + (c.maxClaims ? row('Claims per player', value(c.maxClaims)) : '')
        + (c.inactiveDays ? row('Inactive players', value(`Claims go after ${c.inactiveDays} days away`)) : '');
      const roles = c.roles.map((r) => row(r.label, '', r.description)).join('');
      sections.push({ id: 'claims', title: 'Land claims', tab: 'Claims', statLabel: 'member roles', icon: 'bi-shield-check', hue: '#f2b233', count: c.roles.length,
        lead: 'Protect your builds: claimed land is yours, and only the people you add can change it.',
        children: [{ id: 'how', title: 'How to claim land', icon: 'bi-question-circle', body: how },
          { id: 'blocks', title: 'Claim blocks', icon: 'bi-grid-3x3', body: blocks },
          { id: 'rules', title: 'Rules', icon: 'bi-rulers', body: rules },
          { id: 'roles', title: 'Member roles', icon: 'bi-people', sub: 'what members may do', body: roles }] });
    }
    const petTiles = (cur, coin) => (d.pets || []).map((p) => `<div class="in-tile" data-search="${esc(p.name.toLowerCase())} pet">
          <span class="in-slot">${p.head ? `<img class="in-head" src="heads/${esc(p.head)}" alt="" loading="lazy">` : '<i class="bi bi-emoji-smile"></i>'}</span>
          <span class="in-tile__text"><b>${esc(p.name)}</b>
            <span class="in-tile__price">${p.price ? `${coin}${Number(p.price).toLocaleString()} <small>${esc(p.price === 1 ? cur : cur + 's')}</small>` : 'Free'}</span>
            <small class="in-tile__seller"><i class="bi bi-clock"></i> ${p.days > 0 ? p.days + (p.days === 1 ? ' day' : ' days') : 'forever'}${p.left > 0 ? ' · ' + Number(p.left).toLocaleString() + ' left' : ''}</small></span></div>`).join('');
    const petChild = (cur, coin = '') => ({ id: 'pets', title: 'Pets', icon: 'bi-emoji-smile', sub: 'floating heads that follow you, only for looks', wide: true,
      desc: 'Type /pets in game to get one and pick how it moves (float, orbit, halo, spiral, wander and more) and a particle trail. Everyone near you sees it.',
      body: d.pets.length ? `<div class="in-tiles">${petTiles(cur, coin)}</div>` : '<p class="in-empty">No pets for sale right now.</p>' });
    if (!d.market && d.pets) {
      const cur = titleCase(String(d.petCurrency || 'minecraft:diamond').replace(/^[^:]+:/, '').replace(/_/g, ' '));
      sections.push({ id: 'market', title: 'Pets', statLabel: 'pets for sale', icon: 'bi-emoji-smile', hue: '#ffd166', count: d.pets.length,
        lead: `Floating heads that follow you around. Type /pets in game; pets are paid in ${cur}s.`, children: [petChild(cur)] });
    }
    if (d.market) {
      const m = d.market;
      const cur = titleCase(String(m.currency || 'minecraft:diamond').replace(/^[^:]+:/, '').replace(/_/g, ' '));
      const coin = m.currencyIcon ? `<img class="in-item in-item--coin" src="items/${esc(m.currencyIcon)}" alt="">` : '';
      const item = (l) => {
        const extra = [...(l.enchantments || []), ...(l.lore || [])];
        return `<div class="in-tile" data-search="${esc([l.name, l.seller, l.item, ...extra].join(' ').toLowerCase())}">
          <span class="in-slot">${l.icon ? `<img src="items/${esc(l.icon)}" alt="" loading="lazy">` : '<i class="bi bi-box"></i>'}${l.amount > 1 ? `<b class="in-slot__count">${l.amount > 999 ? Math.round(l.amount / 100) / 10 + 'k' : l.amount}</b>` : ''}</span>
          <span class="in-tile__text"><b${extra.length ? ' class="in-tile__name--magic"' : ''}>${esc(l.name)}</b>
            ${extra.length ? `<small class="in-tile__extra">${esc(extra.join(' · '))}</small>` : ''}
            <span class="in-tile__price">${coin}${Number(l.price).toLocaleString()} <small>${esc(l.price === 1 ? cur : cur + 's')} each</small></span>
            <small class="in-tile__seller"><i class="bi bi-person"></i> ${esc(l.seller || 'someone')} · ${Number(l.amount).toLocaleString()} left</small></span></div>`;
      };
      const list = m.listings || [];
      sections.push({ id: 'market', title: 'Market', statLabel: 'items for sale', icon: 'bi-shop', hue: '#ffd166', count: list.length,
        lead: `Everything players are selling right now. Type /market in game to buy or sell; everything is paid in ${cur}s.`,
        children: [{ id: 'market-list', title: 'For sale now', icon: 'bi-bag', sub: `${list.length} listing${list.length === 1 ? '' : 's'}`, wide: true,
          body: list.length ? `<div class="in-tiles">${list.map(item).join('')}</div>` : '<p class="in-empty">Nothing for sale right now.</p>' },
          ...(d.pets ? [petChild(cur, coin)] : [])] });
    }
    if (d.tweaks && d.tweaks.length) {
      sections.push({ id: 'tweaks', title: 'Gameplay tweaks', tab: 'Gameplay', statLabel: 'tweaks', icon: 'bi-magic', hue: '#c4a6ff', count: d.tweaks.length,
        lead: 'Things that work differently from vanilla here.',
        children: d.tweaks.map((t) => ({ id: slug(t.id), title: t.title, icon: t.icon, desc: t.description,
          body: t.rows.map((r) => row(r.label, value(r.value), r.description)).join('') })) });
    }
    if (d.performance && d.performance.length) {
      sections.push({ id: 'perf', title: 'Performance tweaks', tab: 'Performance', statLabel: 'tweaks', icon: 'bi-speedometer2', hue: '#ff7b9c', count: d.performance.length,
        lead: 'What the server does to stay fast with many players and farms. Good to know before you build a big farm.',
        children: d.performance.map((t) => ({ id: slug(t.id), title: t.title, icon: t.icon, desc: t.description,
          body: t.rows.map((r) => row(r.label, value(r.value), r.description)).join('') })) });
    }
    if (d.configs && d.configs.length) {
      sections.push({ id: 'configs', title: 'Mod settings', tab: 'Mods', statLabel: 'files', icon: 'bi-puzzle', hue: '#a77bff', count: d.configs.length,
        lead: 'Settings of the server\'s mods.',
        children: d.configs.map((c) => {
          const rows = configRows(c.path, c.text);
          return { id: slug(c.path), title: fileTitle(c.path), icon: 'bi-gear', sub: c.path,
            wide: !rows, body: rows ? rows.map((r) => row(r.label, value(r.value), r.desc)).join('') : `<pre class="in-pre" data-search="${esc(c.text.toLowerCase())}">${esc(c.text)}</pre>` };
        }) });
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
      sections.push({ id: 'commands', title: 'Commands', statLabel: 'commands', icon: 'bi-slash-square', hue: '#6cc7ff', count: d.commands.length,
        lead: 'Commands everyone can use, by the mod or pack they come from.',
        children: order.map(([id, g]) => ({ id: slug(g.name), title: g.name, icon: id === 'minecraft' ? 'bi-box' : 'bi-puzzle', sub: `${g.list.length} command${g.list.length === 1 ? '' : 's'}`,
          body: g.list.map(cmd).join('') })) });
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
    return `<section class="in-card${n.wide ? ' in-card--wide' : ''}" id="${n.id}" data-node>
      <h3 class="in-card-title"><span class="in-icon"><i class="bi ${n.icon || 'bi-sliders'}"></i></span><span>${mc(n.title)}${n.sub ? `<small>${esc(n.sub)}</small>` : ''}</span></h3>
      ${n.desc ? `<p class="in-card-desc">${mc(n.desc)}</p>` : ''}${n.body}</section>`;
  }

  function render(d) {
    document.title = d.title;
    if (d.motd) { $('in-motd').hidden = false; $('in-motd').innerHTML = mc(d.motd); }
    if (d.nextRestart) {
      // Calendar days in the reader's own time zone (named), like on the map.
      const midnight = (t) => { const x = new Date(t); x.setHours(0, 0, 0, 0); return x.getTime(); };
      const days = Math.round((midnight(d.nextRestart) - midnight(Date.now())) / 86400000);
      const when = new Date(d.nextRestart).toLocaleString([], { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', timeZoneName: 'short' });
      $('in-restart').hidden = false;
      $('in-restart').innerHTML = `<i class="bi bi-arrow-clockwise"></i> Next restart <b>${days <= 0 ? 'today' : days === 1 ? 'tomorrow' : `in ${days} days`}</b> <span>${esc(when)}</span>`;
    }
    $('in-intro').innerHTML = (d.intro || '').split(/\n\s*\n/).filter((p) => p.trim()).map((p) => `<p>${esc(p).replace(/\n/g, '<br>')}</p>`).join('');
    $('in-updated').textContent = d.updated ? `Last updated ${new Date(d.updated).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' })}` : '';
    const sections = buildSections(d);
    $('in-tabs').innerHTML = sections.map((s) => `<a class="in-tab" href="#${s.id}" data-tab="${s.id}" style="--hue:${s.hue}">
        <i class="bi ${s.icon}"></i><span>${esc(s.tab || s.title)}</span><small class="in-tab-hits" hidden></small></a>`).join('');
    $('in-main').innerHTML = sections.map((s) => `<div class="in-section" id="${s.id}" data-node style="--hue:${s.hue}">
        <header class="in-section-head">
          <span class="in-icon big"><i class="bi ${s.icon}"></i></span>
          <div class="in-section-head__text"><h2 class="in-section-title">${esc(s.title)}</h2>${s.lead ? `<p class="in-section-lead">${mc(s.lead)}</p>` : ''}</div>
          <div class="in-section-count"><b>${Number(s.count).toLocaleString()}</b><span>${esc(s.statLabel || '')}</span>${s.note ? `<small>${esc(s.note)}</small>` : ''}</div>
        </header>
        ${s.children.length > 1 ? `<nav class="in-pills" aria-label="${esc(s.title)}"><a class="in-pill" href="#${s.id}" data-pill="">All</a>${s.children.map((c) =>
          `<a class="in-pill" href="#${c.id}" data-pill="${c.id}">${c.icon ? `<i class="bi ${c.icon}"></i>` : ''}${esc(plain(c.title))}</a>`).join('')}</nav>` : ''}
        <div class="in-cards${s.children.length === 1 ? ' in-cards--single' : ''}">${s.children.map((c) => renderNode(c, 1)).join('')}</div></div>`).join('')
      || '<p class="in-empty">Nothing is published here yet.</p>';
  }

  /** Shows the tab holding the chosen part (a section, a part of it, or a card deeper down). */
  function select(id, push) {
    const target = (id && document.getElementById(id)) || document.querySelector('.in-section');
    if (!target) return;
    const section = target.closest('.in-section');
    document.querySelectorAll('.in-section').forEach((s) => s.classList.toggle('in-off', s !== section));
    document.querySelectorAll('.in-tab').forEach((t) => {
      const on = t.dataset.tab === section.id;
      t.classList.toggle('active', on);
      if (on) t.setAttribute('aria-current', 'page'); else t.removeAttribute('aria-current');
      if (on) t.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    });
    // The part of the section it belongs to; the section itself means all of them.
    const cards = section.querySelector(':scope > .in-cards');
    let top = target;
    while (top !== section && top.parentElement !== cards) top = top.parentElement;
    const part = top === section ? null : top;
    [...cards.children].forEach((c) => c.classList.toggle('in-off', !!part && c !== part));
    // One card picked: it gets the whole width, its rows flow in columns.
    cards.classList.toggle('in-cards--picked', !!part && part.classList.contains('in-card'));
    section.querySelectorAll('.in-pill').forEach((p) => p.classList.toggle('active', p.dataset.pill === (part ? part.id : '')));
    if (push) history.replaceState(null, '', '#' + target.id);
    if (target !== section && target !== part) target.scrollIntoView({ behavior: 'smooth', block: 'start' });
    else {
      const top = $('in-main').getBoundingClientRect().top + window.scrollY - $('in-bar').offsetHeight - 12;
      if (window.scrollY > top) window.scrollTo({ top, behavior: 'smooth' });
    }
  }

  // Tabs, chips and links pick a part instead of jumping down the page.
  document.addEventListener('click', (e) => {
    const a = e.target.closest('a[href^="#"]');
    if (!a || !document.getElementById(a.getAttribute('href').slice(1))) return;
    e.preventDefault();
    if ($('in-q').value) { $('in-q').value = ''; $('in-q').dispatchEvent(new Event('input')); }
    select(a.getAttribute('href').slice(1), true);
  });
  window.addEventListener('hashchange', () => { if (location.hash.length > 1) select(location.hash.slice(1), false); });

  // ── Search: rows, cards, groups and sections follow; the tabs count the hits ──

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
    document.querySelectorAll('.in-tab').forEach((t) => {
      const s = document.getElementById(t.dataset.tab);
      const hits = on && s ? s.querySelectorAll('[data-search]:not([hidden])').length : 0;
      const badge = t.querySelector('.in-tab-hits');
      badge.hidden = !on;
      badge.textContent = hits;
      t.classList.toggle('in-tab--none', on && !hits);
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

  $('in-theme').addEventListener('click', () => {
    const next = document.documentElement.dataset.theme === 'light' ? 'dark' : 'light';
    document.documentElement.dataset.theme = next;
    try { localStorage.setItem('np-theme', next); } catch (e) { /* this visit only */ }
  });

  // The banner's own background picture, when one is set (Appearance in the panel).
  const banner = new Image();
  banner.onload = () => { document.querySelector('.in-hero').classList.add('in-hero--picture'); document.querySelector('.in-hero').style.setProperty('--banner', 'url("banner")'); };
  banner.src = 'banner';

  const logo = $('in-logo');
  logo.onload = () => { logo.hidden = false; };
  logo.src = '../branding/panel';

  const preview = new URLSearchParams(location.search).has('preview');
  fetch('data.json' + (preview ? '?preview=1' : ''), { cache: 'no-store' }).then(async (r) => {
    const d = await r.json();
    if (!r.ok) throw new Error(d.error || 'Not available');
    render(d);
    if (d.preview) document.body.insertAdjacentHTML('afterbegin', '<div class="in-preview"><i class="bi bi-eye-slash"></i> Preview: this page is switched off, only you can see it.</div>');
    select(location.hash.length > 1 ? decodeURIComponent(location.hash.slice(1)) : null, false);
  }).catch((e) => { $('in-main').innerHTML = `<p class="in-empty">${esc(e.message)}</p>`; });
})();
