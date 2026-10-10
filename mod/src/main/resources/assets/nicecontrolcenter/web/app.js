/* Nice Control Center dashboard. Also runs inside exported reports, where window.NP_REPORT holds the data. */
(() => {
  'use strict';

  const REPORT = window.NP_REPORT || null;
  const MAX_POINTS = 60 * 60;

  const state = {
    scale: {},
    animating: false,
    window: 1,
    points: [],
    lastT: 0,
    view: null,
    open: new Set(),
    showAll: new Set(),
    recording: null,
    monitoring: true,
    reports: [],
    hover: {},
  };

  // ── Helpers ────────────────────────────────────────────────────────────

  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  // Minecraft § formatting codes (the MOTD) as HTML, on one line (also a \n typed into server.properties).
  // Black and white keep the theme's text color so they stay readable on both themes.
  const MC_COLORS = { 1: '#0000AA', 2: '#00AA00', 3: '#00AAAA', 4: '#AA0000', 5: '#AA00AA', 6: '#FFAA00', 7: '#AAAAAA',
    8: '#555555', 9: '#5555FF', a: '#55FF55', b: '#55FFFF', c: '#FF5555', d: '#FF55FF', e: '#FFFF55' };
  const MC_STYLES = { l: 'font-weight:700', m: 'text-decoration:line-through', n: 'text-decoration:underline', o: 'font-style:italic' };
  function mcText(text) {
    let color = null;
    let styles = [];
    return String(text ?? '').replace(/\s*(?:\n|\\n)\s*/g, ' ').split('§').map((part, i) => {
      if (i > 0) {
        const code = part.charAt(0).toLowerCase();
        part = part.slice(1);
        if (/[0-9a-fr]/.test(code)) {
          color = MC_COLORS[code] || null;
          styles = [];
        } else if (MC_STYLES[code] && !styles.includes(MC_STYLES[code])) {
          styles.push(MC_STYLES[code]);
        }
      }
      if (!part) return '';
      const css = (color ? [`color:${color}`] : []).concat(styles).join(';');
      return css ? `<span style="${css}">${esc(part)}</span>` : esc(part);
    }).join('');
  }
  const md = (s) => esc(s).replace(/`([^`]+)`/g, '<code>$1</code>');
  const fixed = (v, d) => (Number.isFinite(v) ? v.toFixed(d) : '–');
  const ms = (v) => (v >= 10 ? fixed(v, 1) : fixed(v, 2)) + ' ms';
  /** Small per-item times, e.g. 0.07 ms, without switching units. */
  const eachMs = (v) => (v >= 1 ? fixed(v, 1) : v >= 0.01 ? fixed(v, 2) : '<0.01') + ' ms';
  const pct = (v) => (v >= 10 ? fixed(v, 0) : fixed(v, 1)) + '%';
  const num = (v) => (Number.isFinite(v) ? Math.round(v).toLocaleString('en-US') : '–');
  const bytes = (b) => {
    if (!b) return '0';
    const mb = b / 1048576;
    if (mb < 1) return fixed(b / 1024, 0) + ' KB';
    return mb >= 1024 ? fixed(mb / 1024, 1) + ' GB' : fixed(mb, mb < 10 ? 1 : 0) + ' MB';
  };
  const clock = (t) => new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  const dateTime = (t) => new Date(t).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' });
  const duration = (msec) => {
    const m = Math.round(msec / 60000);
    if (m < 60) return m + ' min';
    const h = Math.floor(m / 60);
    return h + ' h ' + (m % 60 ? (m % 60) + ' min' : '');
  };
  const sev = (s) => String(s || 'INFO').toLowerCase();
  const barClass = (p) => (p >= 30 ? 'hot' : p >= 10 ? 'warm' : '');
  const prettyType = (id) => {
    const i = id.indexOf(':');
    const path = (i < 0 ? id : id.slice(i + 1)).replace(/_/g, ' ');
    const name = path.charAt(0).toUpperCase() + path.slice(1);
    return i > 0 && !id.startsWith('minecraft:') ? `${name} (${id.slice(0, i)})` : name;
  };
  const prettyDimension = (d) => ({ 'minecraft:overworld': 'Overworld', 'minecraft:the_nether': 'Nether', 'minecraft:the_end': 'End' }[d] || d);

  async function api(path, options) {
    const res = await fetch(path, Object.assign({ credentials: 'same-origin', cache: 'no-store' }, options || {}));
    if (!res.ok) throw new Error('HTTP ' + res.status);
    return res.json();
  }

  // ── Shared row rendering ───────────────────────────────────────────────

  function scaleFor(items, budget) {
    const max = Math.max(0, ...items.map((i) => i.ms || 0));
    return Math.max(max, budget * 0.25, 0.001);
  }

  function valueCell(msValue, percent) {
    return `<span class="np-value"><span class="np-value-ms">${ms(msValue)}</span><span class="np-value-pct">${pct(percent)} of budget</span></span>`;
  }

  function bar(msValue, scale, percent) {
    const width = Math.max(0.5, Math.min(100, (msValue / scale) * 100));
    return `<div class="np-bar-wrap"><div class="np-bar ${barClass(percent)}" style="width:${width}%"></div></div>`;
  }

  function row({ key, name, sub, mono, msValue, percent, scale, body, value }) {
    const head = `<span class="np-caret ${body ? '' : 'none'} bi bi-chevron-right"></span>
      <span class="np-name"><span class="np-name-main ${mono ? 'mono' : ''}" title="${esc(name)}">${esc(name)}</span>${sub ? `<span class="np-name-sub" title="${esc(sub)}">${sub}</span>` : ''}</span>
      ${bar(msValue, scale, percent)}
      ${value || valueCell(msValue, percent)}`;
    if (!body) return `<div class="np-row">${head}</div>`;
    return `<details class="np-item" data-key="${esc(key)}" ${state.open.has(key) ? 'open' : ''}><summary class="np-row">${head}</summary><div class="np-sublist">${body}</div></details>`;
  }

  // ── Banner, stats, findings ────────────────────────────────────────────

  function renderHealth(health) {
    $('np-status-dot').className = 'np-status-dot ' + sev(health.status);
    $('np-health-title').textContent = health.title;
    $('np-health-headline').textContent = health.headline;
    $('np-health-summary').textContent = health.summary;
  }

  /** Same colors as the MSPT chart: green with headroom, gold when busy, red over budget. */
  function msptClass(v, budget) {
    return v <= budget * 0.7 ? 'good' : v <= budget ? 'warn' : 'poor';
  }

  /** One short line instead of every number; the details are in the tooltip and the tiles. */
  function renderHeadline(k) {
    const headline = $('np-health-headline');
    const full = headline.textContent;
    headline.textContent = `${fixed(k.tps, 1)} TPS · ${fixed(k.msptMedian, 1)} ms per tick · ${fixed(k.budget ? k.msptAvg / k.budget * 100 : 0, 0)}% of the ${fixed(k.budget, 0)} ms budget`;
    headline.title = full;
  }

  /** The newest one-second point, if it's fresh; reports and stale data fall back to the window. */
  function livePoint() {
    if (REPORT || !state.points.length) return null;
    const p = state.points[state.points.length - 1];
    return Date.now() - p.t < 10000 ? p : null;
  }

  function renderStats(k) {
    const budget = k.budget;
    // The tiles always show the server right now: this second, and the last few seconds for the
    // MSPT spread and GC. Reports and stale data fall back to the window.
    const now = livePoint();
    const cur = now && state.current && state.current.seconds ? state.current : null;
    const span = REPORT ? 'recording' : `${state.window} min`;
    const recent = cur ? `last ${cur.seconds} s` : span;
    const tps = now ? now.tps : k.tps;
    const cpu = now ? now.cpuProcess : k.cpuProcess;
    const cpuMachine = now ? now.cpuSystem : k.cpuSystem;
    const heapLive = now ? now.heapLive : k.heapLive;
    const heapUsed = now ? now.heapUsed : k.heapUsed;
    const heapMax = (now && now.heapMax) || k.heapMax;
    const tpsClass = tps >= k.targetTps * 0.97 ? 'good' : tps >= k.targetTps * 0.85 ? 'warn' : 'poor';
    // Judged by memory still in use after garbage collection; "used" includes garbage not collected yet.
    const livePct = heapMax ? ((heapLive || heapUsed) / heapMax) * 100 : 0;
    const heapClass = livePct < 80 ? 'good' : livePct < 90 ? 'warn' : 'poor';
    const cpuClass = cpuMachine >= 90 ? 'poor' : cpuMachine >= 70 ? 'warn' : 'good';
    const gc = cur ? { percent: cur.wallMs ? cur.gcTimeMs * 100 / cur.wallMs : 0, count: cur.gcCount, timeMs: cur.gcTimeMs }
      : { percent: k.gcPercent, count: k.gcCount, timeMs: k.gcTimeMs || 0 };
    // Same limits as the memory finding: 5% of the time paused is worth a look, 10% hurts.
    const gcClass = gc.percent >= 10 ? 'poor' : gc.percent >= 5 ? 'warn' : 'good';
    const gcAvg = gc.count ? gc.timeMs / gc.count : 0;
    const c = k.counts || {};
    const ms = (v) => v >= 100 ? fixed(v, 0) : fixed(v, 1);
    const mspt = cur || k;
    const memNow = heapLive || heapUsed;
    // [label, value html, tooltip, priority]: lower-priority tiles drop out first on narrower screens.
    const tiles = [
      ['TPS', `<span class="${tpsClass}">${fixed(tps, 1)}</span>`,
        `${now ? 'This second' : `Average over the ${span}`}: ${fixed(tps, 1)} · target ${fixed(k.targetTps, 0)}`, 1],
      ['MSPT', [mspt.msptMedian, mspt.msptP95, mspt.msptMax].map((v) => `<span class="${msptClass(v, budget)}">${ms(v)}</span>`).join('<i>·</i>'),
        `Milliseconds per tick over the ${recent} (median · 95% of ticks under · slowest): fastest ${fixed(mspt.msptMin, 1)} · median ${fixed(mspt.msptMedian, 1)} · 95%ile ${fixed(mspt.msptP95, 1)} · slowest ${fixed(mspt.msptMax, 1)} · budget ${fixed(budget, 0)}`, 1],
      ['CPU', `<span class="${cpuClass}">${fixed(cpu, 0)}%</span>`,
        `${now ? 'This second' : `Average over the ${span}`}: server ${fixed(cpu, 0)}% · whole machine ${fixed(cpuMachine, 0)}% · ${k.cores} cores`, 1],
      ['Memory', `<span class="${heapClass}">${bytes(memNow)}</span><i>/</i>${bytes(heapMax)}`,
        `Still in use after garbage collection: ${bytes(memNow)} · including garbage not collected yet: ${bytes(heapUsed)} · maximum: ${bytes(heapMax)}`, 1],
      ['GC', `<span class="${gcClass}">${fixed(gc.percent, 1)}%</span>`,
        `Share of time the server was paused for garbage collection over the ${recent}: ${num(gc.timeMs)} ms over ${num(gc.count)} pauses${gc.count ? `, ${fixed(gcAvg, 1)} ms each on average` : ''}. Background (concurrent) GC work isn't counted.`, 2],
      ['Entities', num(now ? now.entities : c.entities), 'Entities loaded', 3],
      ['Block ent.', num(now ? now.blockEntities : c.blockEntities), 'Block entities ticking', 3],
      ['Chunks', num(now ? now.chunks : c.chunks), `Chunks loaded · ${num(c.chunkTasks)} tasks waiting`, 3],
      ['Players', num(now ? now.players : c.players), 'Players online', 2],
    ];
    patchHtml($('np-stats'), tiles.map(([label, value, tip, prio]) => `<div class="np-nt np-nt-p${prio}" title="${esc(tip)}">
      <span class="np-nt-l">${label}</span><span class="np-nt-v">${value}</span></div>`).join(''));
  }

  function renderFindings(findings, k) {
    $('np-budget').textContent = fixed(k.budget, 0);
    if (!findings.length) {
      $('np-findings').innerHTML = '<div class="np-empty">Nothing uses a notable share of the tick right now.</div>';
      return;
    }
    const scale = scaleFor(findings, k.budget);
    $('np-findings').innerHTML = findings.map((f) => `<article class="np-finding ${sev(f.severity)}">
      <div class="np-finding-text">
        <div class="np-finding-head"><span class="np-chip ${f.severity === 'POOR' || f.severity === 'WARN' ? 'gold' : ''}">${esc(f.category)}</span><span class="np-finding-title">${esc(f.title)}</span></div>
        <p class="np-finding-reason">${md(f.reason)}</p>
        <p class="np-finding-hint"><i class="bi bi-lightbulb"></i>${md(f.hint)}</p>
      </div>
      <div class="np-finding-cost">${f.ms > 0 ? `<div class="np-finding-ms">${ms(f.ms)}<span class="np-finding-pct"> /tick</span></div>
        <div class="np-finding-pct">${pct(f.percent)} of the tick budget</div>${bar(f.ms, scale, f.percent)}` : ''}</div>
    </article>`).join('');
  }

  // ── Breakdowns ─────────────────────────────────────────────────────────

  /** One quiet line pointing to setting tips and bloat, instead of mixing them into the costs. */
  function renderNotesLink(notes) {
    const link = $('np-notes-link');
    if (!notes.length) {
      link.hidden = true;
      return;
    }
    const warn = notes.some((n) => n.severity === 'WARN' || n.severity === 'POOR');
    link.hidden = false;
    link.className = 'np-notes-link' + (warn ? ' warn' : '');
    const errors = notes.filter((n) => n.category === 'Errors').length;
    const label = errors ? `${notes.length} ${notes.length === 1 ? 'thing' : 'things'} worth a look (${errors} with log errors)` : `${notes.length} server ${notes.length === 1 ? 'setting or check' : 'settings and checks'} worth a look`;
    link.innerHTML = `<i class="bi ${errors ? 'bi-exclamation-octagon' : 'bi-sliders'}"></i> ${label}: ${notes.map((n) => esc(n.title)).join(' · ')} <i class="bi bi-arrow-right-short"></i>`;
  }

  function openSection(id) {
    const card = $(id);
    if (!card) return;
    const panel = card.closest('.np-tab-panel');
    if (panel && panel.dataset.tab !== activeTab) selectTab(panel.dataset.tab, false);
    const sub = card.closest('[data-sub]');
    if (sub && panel && panel.id === 'tab-server') selectSub(sub.dataset.sub);
    if (card.classList.contains('collapsed')) card.querySelector('.np-card-title').click();
    card.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  function renderPhases(phases, k) {
    const scale = scaleFor(phases, k.budget);
    const visible = phases.filter((p) => p.ms > 0.005);
    const all = state.showAll.has('phases');
    const main = visible.filter((p, i) => i < 5 || p.percent >= 1);
    const shown = all ? visible : main;
    const hidden = visible.length - main.length;
    $('np-phases').innerHTML = shown.map((p) => {
      const dims = p.dimensions || [];
      const body = dims.length > 1
        ? dims.map((d) => `<div class="np-line"><span>${esc(prettyDimension(d.dimension))}</span><span class="np-value"><span class="np-value-ms">${ms(d.ms)}</span></span></div>`).join('')
        : '';
      return row({ key: 'phase:' + p.phase, name: p.label, sub: esc(p.description), msValue: p.ms, percent: p.percent, scale, body });
    }).join('') + (hidden > 0 ? `<button type="button" class="np-more np-more--list" data-show-all="phases">${all ? 'Show only the main parts' : `Show ${hidden} smaller parts`}</button>` : '')
      || '<div class="np-empty">No ticks measured yet.</div>';
  }

  function renderPacks(packs, k) {
    if (!packs.length) {
      $('np-packs').innerHTML = '<div class="np-empty">No functions ran in this window.</div>';
      return;
    }
    const scale = scaleFor(packs, k.budget);
    const kinds = { datapack: 'Data pack', mod: 'Mod functions', vanilla: 'Minecraft', commands: 'Commands' };
    $('np-packs').innerHTML = packs.map((p) => {
      const fnScale = scaleFor(p.functions, k.budget);
      const functions = p.functions.map((f) => {
        const lines = f.lines.map((l) => `<div class="np-line"><code>${esc(l.text)}</code>
          <span class="np-value"><span class="np-value-ms">${ms(l.ms)}</span></span>
          <span class="np-line-meta">${l.runsPerTick > 0 ? 'runs ' + rate(l.runsPerTick) : ''}${l.perRun >= 2 ? ` · about ${num(l.perRun)} executions per run` : ''}</span></div>`).join('');
        const sub = [f.runsPerTick > 0 ? 'runs ' + rate(f.runsPerTick) : '', f.calledFrom ? 'from ' + f.calledFrom.toLowerCase() : ''].filter(Boolean).join(' · ');
        return row({ key: 'fn:' + f.id, name: f.id, sub: esc(sub), mono: true, msValue: f.ms, percent: f.percent, scale: fnScale, body: lines });
      }).join('');
      const sub = `${kinds[p.kind] || p.kind} · ${p.functions.length} function${p.functions.length === 1 ? '' : 's'}`;
      return row({ key: 'pack:' + p.id, name: p.name, sub: esc(sub), msValue: p.ms, percent: p.percent, scale, body: functions });
    }).join('');
  }

  function rate(perTick) {
    if (perTick >= 0.95) return `${perTick >= 10 ? num(perTick) : fixed(perTick, 1)}× per tick`;
    const perSecond = perTick * 20;
    return perSecond >= 1 ? `${fixed(perSecond, perSecond >= 10 ? 0 : 1)}× per second` : 'occasionally';
  }

  function renderMods(mods, view) {
    const k = view.kpi;
    $('np-mods-intro').innerHTML = k.samples > 0
      ? `Sampled ${num(k.samples)} times from the server thread (every ${view.samplerIntervalMs} ms). <b>Own code</b> is the mod itself running; <b>vanilla code it called</b> is game code it asked for.`
      : 'The sampler is collecting data…';
    const list = mods.filter((m) => m.ms > 0.005);
    if (!list.length) {
      $('np-mods').innerHTML = '<div class="np-empty">No mod shows up in the samples yet.</div>';
      return;
    }
    const scale = scaleFor(list.filter((m) => m.id !== 'minecraft'), k.budget);
    $('np-mods').innerHTML = list.map((m) => {
      const methods = m.methods.map((x) => `<div class="np-line"><code>${esc(x.name)}</code><span class="np-value"><span class="np-value-pct">${pct(x.percent)} of samples</span></span></div>`).join('');
      const sub = `own code ${pct(m.selfPercent)} · vanilla code it called ${pct(m.causedPercent)} · on stack ${pct(m.stackPercent)}`;
      return row({ key: 'mod:' + m.id, name: m.name, sub: esc(sub), msValue: m.ms, percent: m.percent, scale: m.id === 'minecraft' ? Math.max(scale, m.ms) : scale, body: methods });
    }).join('');
  }

  function renderTypes(id, types, k) {
    const table = $(id);
    const list = types.filter((t) => t.ms > 0.0005 || t.averageCount >= 1);
    if (!list.length) {
      table.innerHTML = '<tr><td class="np-empty">Nothing loaded.</td></tr>';
      return;
    }
    const scale = scaleFor(list, k.budget);
    const limit = 5;
    const all = state.showAll.has(id);
    const rows = all ? list : list.slice(0, limit);
    const more = list.length > limit
      ? `<tfoot><tr><td colspan="5"><button type="button" class="np-more" data-show-all="${id}">${all ? 'Show top 5' : `Show all ${list.length}`}</button></td></tr></tfoot>`
      : '';
    table.innerHTML = `<thead><tr><th>Type</th><th class="num">Loaded</th><th class="num" title="How long one of them takes to tick">Time each</th><th>Cost</th><th class="num">Per tick</th></tr></thead><tbody>` +
      rows.map((t) => `<tr>
        <td class="name">${esc(prettyType(t.id))}<small>${esc(t.source)}</small></td>
        <td class="num">${num(t.averageCount)}${t.maxCount > t.averageCount + 0.5 ? `<br><small>max ${num(t.maxCount)}</small>` : ''}</td>
        <td class="num">${t.microsEach > 0 ? eachMs(t.microsEach / 1000) : '–'}</td>
        <td class="bar-cell">${bar(t.ms, scale, t.percent)}</td>
        <td class="num">${ms(t.ms)}<br><small>${pct(t.percent)}</small></td></tr>`).join('') + '</tbody>' + more;
  }

  function renderHotspots(hotspots) {
    const table = $('np-hotspots');
    if (!hotspots.length) {
      table.innerHTML = '<tr><td class="np-empty">No busy chunks.</td></tr>';
      return;
    }
    const all = state.showAll.has('np-hotspots');
    const shown = all ? hotspots : hotspots.slice(0, 5);
    const more = hotspots.length > 5
      ? `<tfoot><tr><td colspan="6"><button type="button" class="np-more" data-show-all="np-hotspots">${all ? 'Show top 5' : `Show all ${hotspots.length}`}</button></td></tr></tfoot>` : '';
    table.innerHTML = `<thead><tr><th>Where</th><th class="num">Entities</th><th class="num">Block ent.</th><th>Mostly</th><th class="num">Per tick</th>${REPORT ? '' : '<th></th>'}</tr></thead><tbody>` +
      shown.map((h) => {
        const tp = `/execute in ${h.dimension} run tp @s ${h.blockX} ~ ${h.blockZ}`;
        return `<tr><td class="name">${h.blockX}, ${h.blockZ}<small>${esc(prettyDimension(h.dimension))} · chunk ${h.chunkX}, ${h.chunkZ}</small></td>
          <td class="num">${num(h.entities)}</td><td class="num">${num(h.blockEntities)}</td>
          <td>${h.topType ? esc(prettyType(h.topType)) + ` <small>(${num(h.topTypeCount)})</small>` : '–'}</td>
          <td class="num">${ms(h.ms)}</td>
          ${REPORT ? '' : `<td class="num"><button type="button" class="np-copy" data-copy="${esc(tp)}" title="Copy: ${esc(tp)}"><i class="bi bi-clipboard"></i> Teleport</button></td>`}</tr>`;
      }).join('') + '</tbody>' + more;
  }

  function renderWorldgen(w) {
    const box = $('np-worldgen');
    if (!w || !w.chunks) {
      box.innerHTML = '<div class="np-empty">No new chunks were generated in this window.</div>';
      return;
    }
    const value = (t) => `<span class="np-value"><span class="np-value-ms">${eachMsText(t.msPerChunk)}</span><span class="np-value-pct">${pct(t.percent)} of generation</span></span>`;
    const list = (title, items, withSource) => {
      if (!items.length) return '';
      const scale = Math.max(...items.map((t) => t.msPerChunk), 0.001);
      return `<div class="np-wg-list"><h4>${title}</h4><div class="np-breakdown-list">${items.map((t) => row({
        name: t.id === 'vanilla' ? 'Minecraft' : (withSource === 'source' ? t.source : t.id),
        sub: withSource === true && t.source ? esc(t.source) : '', mono: withSource === true,
        msValue: t.msPerChunk, percent: t.percent >= 30 ? 30 : t.percent >= 10 ? 10 : 0, scale, value: value(t),
      })).join('')}</div></div>`;
    };
    box.innerHTML = `<div class="np-wg-summary">
        <span><b>${num(w.chunks)}</b> new chunks</span><span><b>${w.chunksPerMinute >= 10 ? num(w.chunksPerMinute) : fixed(w.chunksPerMinute, 1)}</b> per minute</span>
        <span><b>${eachMsText(w.msPerChunk)}</b> each</span></div>
      <div class="np-wg-grid">${list('Steps', w.stages, false)}${list('By data pack / mod', w.sources, 'source')}
        ${list('Structures', w.structures, true)}${list('Features', w.features, true)}</div>`;
  }

  function eachMsText(v) {
    return (v >= 10 ? fixed(v, 1) : v >= 0.01 ? fixed(v, 2) : '<0.01') + ' ms';
  }

  function renderPlayers(players) {
    const table = $('np-players');
    if (!players || !players.length) {
      table.innerHTML = '<tr><td class="np-empty">Nobody was online in this window.</td></tr>';
      return;
    }
    table.innerHTML = `<thead><tr><th>Player</th><th class="num">Ping</th><th class="num">Entities near</th><th class="num">Block ent. near</th><th class="num">New chunks/min</th>${REPORT ? '' : '<th></th>'}</tr></thead><tbody>` +
      players.map((p) => `<tr>
        <td class="name">${esc(p.name)}${p.online ? '' : ' <span class="np-chip">left</span>'}<small>${esc(prettyDimension(p.dimension))} · ${p.x}, ${p.y}, ${p.z}</small></td>
        <td class="num">${p.online ? num(p.ping) + ' ms' : '–'}</td>
        <td class="num">${num(p.entitiesNear)}</td>
        <td class="num">${num(p.blockEntitiesNear)}</td>
        <td class="num">${p.newChunksPerMinute >= 10 ? num(p.newChunksPerMinute) : fixed(p.newChunksPerMinute, 1)}${p.newChunksPercent >= 1 ? `<br><small>${pct(p.newChunksPercent)} of all</small>` : ''}</td>
        ${REPORT ? '' : `<td class="num">${p.online ? `<button type="button" class="np-copy" data-copy="/tp @s ${esc(p.name)}" title="Copy: /tp @s ${esc(p.name)}"><i class="bi bi-clipboard"></i> Teleport</button>` : ''}</td>`}</tr>`).join('') + '</tbody>';
  }

  function renderSettings(checks) {
    const box = $('np-settings');
    if (!checks || !checks.length) {
      box.innerHTML = '<div class="np-empty">Not checked yet.</div>';
      return;
    }
    const groups = {};
    checks.forEach((c) => (groups[c.group] = groups[c.group] || []).push(c));
    const label = { GOOD: 'OK', INFO: 'Tip', WARN: 'Check', POOR: 'Problem' };
    // Only what needs attention; the rest is one line away.
    const fine = checks.filter((c) => c.status === 'GOOD').length;
    const all = state.showAll.has('np-settings');
    if (!all) {
      Object.keys(groups).forEach((g) => {
        groups[g] = groups[g].filter((c) => c.status !== 'GOOD');
        if (!groups[g].length) delete groups[g];
      });
    }
    const footer = fine ? `<button type="button" class="np-more np-settings-more" data-show-all="np-settings">${all ? 'Hide the settings that are fine' : `${fine} more ${fine === 1 ? 'setting is' : 'settings are'} fine · show`}</button>` : '';
    if (!Object.keys(groups).length) {
      box.innerHTML = `<div class="np-empty">Every checked setting looks fine.</div>${footer}`;
      return;
    }
    box.innerHTML = Object.entries(groups).map(([group, items]) => `<div class="np-settings-group"><h4>${esc(group)}</h4>
      ${items.map((c) => `<div class="np-setting"><span class="np-setting-name">${esc(c.name)}</span><span class="np-setting-value">${esc(c.value)}</span>
        <span class="np-badge ${c.status === 'GOOD' ? 'good' : c.status === 'WARN' ? 'warn' : c.status === 'POOR' ? 'poor' : 'info'}">${label[c.status] || c.status}</span>
        ${c.status === 'GOOD' ? '' : `<span class="np-setting-note">${esc(c.note)}</span>`}</div>`).join('')}</div>`).join('') + footer;
  }

  function renderBlockUpdates(rows) {
    const table = $('np-block-updates');
    if (!rows || !rows.length) {
      table.innerHTML = '<tr><td class="np-empty">No busy blocks.</td></tr>';
      return;
    }
    table.innerHTML = `<thead><tr><th>Where</th><th>Block</th><th class="num">Updates/s</th><th class="num">Active</th>${REPORT ? '' : '<th></th>'}</tr></thead><tbody>` +
      rows.map((u) => {
        const tp = `/execute in ${u.dimension} run tp @s ${u.x} ${u.y + 1} ${u.z}`;
        return `<tr><td class="name">${u.x}, ${u.y}, ${u.z}<small>${esc(prettyDimension(u.dimension))}</small></td>
          <td>${u.block ? esc(prettyType(u.block)) : '–'}</td>
          <td class="num">${u.perSecond >= 10 ? num(u.perSecond) : fixed(u.perSecond, 1)}</td>
          <td class="num">${fixed(u.activePercent, 0)}%</td>
          ${REPORT ? '' : `<td class="num"><button type="button" class="np-copy" data-copy="${esc(tp)}" title="Copy: ${esc(tp)}"><i class="bi bi-clipboard"></i> Teleport</button></td>`}</tr>`;
      }).join('') + '</tbody>';
  }

  function renderSpikes(spikes, k) {
    if (!spikes.length) {
      $('np-spikes').innerHTML = `<div class="np-empty">No tick took longer than the spike threshold.</div>`;
      return;
    }
    $('np-spikes').innerHTML = spikes.map((s) => `<div class="np-spike"><span>${clock(s.time)}</span><span class="np-spike-ms">${fixed(s.ms, 0)} ms</span>
      <span class="np-spike-phases">${(s.phases || []).map((p) => `<span>${esc(p.name)} <b>${fixed(p.ms, 0)} ms</b></span>`).join('')}</span>
      ${(s.top || []).length ? `<span class="np-spike-top">${s.top.map((t) => `<span>${esc(t.name)} <b>${fixed(t.ms, 1)} ms</b></span>`).join('')}</span>` : ''}</div>`).join('');
  }

  function renderView(view) {
    state.view = view;
    const k = view.kpi;
    renderHealth(view.diagnosis.health);
    renderHeadline(k);
    renderStats(k);
    renderNotesLink(view.diagnosis.notes || []);
    setTabBadge('server', (view.diagnosis.notes || []).length, (view.diagnosis.notes || []).some((n) => n.severity === 'WARN' || n.severity === 'POOR'));
    // Only the visible tab is drawn; the others catch up when they are opened.
    switch (activeTab) {
      case 'overview':
        renderFindings(view.diagnosis.findings, k);
        break;
      case 'performance':
        renderPhases(view.phases, k);
        renderPacks(view.packs, k);
        renderMods(view.mods, view);
        renderTypes('np-entities', view.entities, k);
        renderTypes('np-block-entities', view.blockEntities, k);
        renderSpikes(view.spikes, k);
        break;
      case 'world':
        renderWorldgen(view.worldgen);
        renderHotspots(view.hotspots);
        renderBlockUpdates(view.blockUpdates);
        renderPlayers(view.players);
        break;
      case 'server':
        renderSettings(view.settings);
        break;
      default:
        break;
    }
  }

  // ── Tabs ───────────────────────────────────────────────────────────────

  const TAB_KEY = 'np-tab';
  const DATA_TABS = ['overview', 'performance', 'world'];
  let activeTab = 'overview';

  function availableTabs() {
    return [...document.querySelectorAll('.np-tab')].filter((b) => getComputedStyle(b).display !== 'none').map((b) => b.dataset.tab);
  }

  function setTabBadge(tab, count, warn) {
    const badge = document.querySelector(`.np-tab[data-tab="${tab}"] .np-tab-badge`);
    if (!badge) return;
    badge.hidden = !count;
    badge.textContent = count > 99 ? '99+' : String(count || '');
    badge.classList.toggle('warn', !!warn);
  }

  function selectTab(tab, focus) {
    if (!availableTabs().includes(tab)) tab = 'overview';
    activeTab = tab;
    document.querySelectorAll('.np-tab-panel').forEach((p) => (p.hidden = p.dataset.tab !== tab));
    document.querySelectorAll('.np-tab').forEach((b) => {
      const on = b.dataset.tab === tab;
      b.classList.toggle('active', on);
      b.setAttribute('aria-selected', String(on));
      b.tabIndex = on ? 0 : -1;
      if (on && focus) b.focus();
    });
    if (!REPORT) {
      $('np-window').hidden = !DATA_TABS.includes(tab);
      try { localStorage.setItem(TAB_KEY, tab); } catch (e) { /* not remembered */ }
      history.replaceState(null, '', location.pathname + location.search.replace(/([?&])view=reports&?/, '$1').replace(/[?&]$/, '') + '#' + tab);
    }
    if (state.view) renderView(state.view);
    if (tab === 'overview') drawCharts();
    onTabShown(tab);
  }

  /** Data that is only loaded when its tab is opened. */
  /** Server tab: one of Health, server.properties or Gamerules at a time. */
  function selectSub(sub) {
    document.querySelectorAll('#tab-server [data-sub]').forEach((el) => {
      if (el.closest('#np-server-sub')) el.classList.toggle('active', el.dataset.sub === sub);
      else el.hidden = el.dataset.sub !== sub;
    });
    try { localStorage.setItem('np-server-sub', sub); } catch (e) { /* not remembered */ }
  }

  function setupSubs() {
    $('np-server-sub').addEventListener('click', (e) => {
      const b = e.target.closest('[data-sub]');
      if (b) selectSub(b.dataset.sub);
    });
    let sub = 'health';
    try { sub = localStorage.getItem('np-server-sub') || 'health'; } catch (e) { /* default */ }
    selectSub(sub);
  }

  /** "More charts" works the same in the live dashboard and in exported reports. */
  function setupMoreCharts() {
    let more = false;
    try { more = localStorage.getItem('np-charts-more') === '1'; } catch (e) { /* default */ }
    const apply = () => {
      document.body.classList.toggle('charts-more', more);
      $('np-charts-more').textContent = more ? 'Fewer charts' : 'More charts: entities, chunks, players';
      drawCharts();
    };
    $('np-charts-more').addEventListener('click', () => {
      more = !more;
      try { localStorage.setItem('np-charts-more', more ? '1' : '0'); } catch (e) { /* not remembered */ }
      apply();
    });
    apply();
  }

  function onTabShown(tab) {
    // Only the Overview shows the full header with all tiles; other tabs get one compact status line.
    document.body.classList.toggle('compact-head', !REPORT && tab !== 'overview');
    if (REPORT) return;
    if (tab === 'packs' && !packSettings.loaded) loadPackSettings();
    if (tab === 'reports') loadReports();
    if (tab === 'console' && typeof openConsole === 'function') openConsole();
    if (tab === 'chat') openChat();
    if (tab === 'updates') loadUpdates();
    if (tab === 'server') {
      loadErrors();
      if (!propsState.data) loadProperties();
      if (!rulesState.data) loadGamerules();
    }
    if (tab === 'players') loadPlayers();
    if (tab === 'schedule') loadSchedule();
  }

  function setupTabs() {
    const bar = $('np-tabs');
    bar.addEventListener('click', (e) => {
      const button = e.target.closest('.np-tab');
      if (button) selectTab(button.dataset.tab, false);
    });
    bar.addEventListener('keydown', (e) => {
      if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
      const tabs = availableTabs();
      const i = tabs.indexOf(activeTab);
      selectTab(tabs[(i + (e.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length], true);
      e.preventDefault();
    });
    window.addEventListener('hashchange', () => {
      const tab = location.hash.slice(1);
      if (tab && tab !== activeTab) selectTab(tab, false);
    });
    let start = location.hash.slice(1);
    if (REPORT && !DATA_TABS.includes(start)) start = 'overview';
    if (!REPORT && new URLSearchParams(location.search).get('view') === 'reports') start = 'reports';
    if (!start && !REPORT) {
      try { start = localStorage.getItem(TAB_KEY) || ''; } catch (e) { start = ''; }
    }
    selectTab(start || 'overview', false);
  }

  // ── Charts ─────────────────────────────────────────────────────────────
  //
  // Points are grouped into buckets so a chart never shows more than ~1 bar or point per few
  // pixels: an hour of per-second data becomes a calm curve instead of noise.

  /** Chart colors come from the theme tokens in app.css, so they follow the light/dark switch. */
  const COLORS = {};
  function readColors() {
    const css = getComputedStyle(document.documentElement);
    const v = (name) => css.getPropertyValue(name).trim();
    Object.assign(COLORS, {
      accent: v('--accent'), gold: v('--accent-2'), good: v('--good'), poor: v('--poor'),
      muted: v('--chart-muted'), grid: v('--chart-grid'), label: v('--chart-label'),
      guide: v('--chart-guide'), max: v('--chart-max'), ink: v('--ink'),
    });
  }
  const PAD = { left: 46, right: 10, top: 10, bottom: 22 };

  function visiblePoints() {
    if (REPORT) return state.points;
    // A margin before the left edge: the oldest slots stay whole and slide out under the clip
    // instead of shrinking and vanishing inside the chart.
    const from = Date.now() - LIVE_LAG - state.window * 60000 * 1.05 - 5000;
    return state.points.filter((p) => p.t >= from);
  }

  /**
   * Groups points into fixed time slots (so slots don't shift as new data arrives); averages each
   * slot but keeps its worst tick and largest heap.
   */
  function bucketize(points, size) {
    const out = [];
    let part = [];
    let key = null;
    const flush = () => {
      if (!part.length) return;
      const active = part.filter((p) => !p.paused);
      const src = active.length ? active : part;
      const avg = (f) => src.reduce((a, p) => a + p[f], 0) / src.length;
      out.push({
        t: part[0].t, t2: part[part.length - 1].t, start: key * size, tc: part.reduce((a, p) => a + p.t, 0) / part.length, paused: !active.length,
        tps: avg('tps'), mspt: avg('mspt'), msptMax: Math.max(...src.map((p) => p.msptMax)),
        cpuProcess: avg('cpuProcess'), cpuSystem: avg('cpuSystem'),
        heapUsed: avg('heapUsed'), heapMax: Math.max(...src.map((p) => p.heapMax)), heapLive: Math.max(...src.map((p) => p.heapLive || 0)),
        entities: avg('entities'), chunks: avg('chunks'), players: avg('players'), blockEntities: avg('blockEntities'),
        gcMs: part.reduce((a, p) => a + p.gcMs, 0),
      });
    };
    for (const p of points) {
      const k = Math.floor(p.t / size);
      if (k !== key) {
        flush();
        part = [];
        key = k;
      }
      part.push(p);
    }
    flush();
    return out;
  }

  /** Round axis steps (1, 2, 2.5, 5 × 10ⁿ) covering 0..max. */
  function niceTicks(max, count, base) {
    if (base) {
      const r = niceTicks(max / base, count);
      return { ticks: r.ticks.map((t) => t * base), top: r.top * base };
    }
    if (!(max > 0)) max = 1;
    const raw = max / count;
    const mag = Math.pow(10, Math.floor(Math.log10(raw)));
    const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => s >= raw) || 10 * mag;
    const top = Math.ceil(max / step) * step;
    const ticks = [];
    for (let v = 0; v <= top + step / 2; v += step) ticks.push(v);
    return { ticks, top };
  }

  const TIME_STEPS = [5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 10800, 21600, 43200, 86400].map((s) => s * 1000);

  function timeTicks(from, to, width) {
    const span = Math.max(1000, to - from);
    const wanted = Math.max(2, Math.floor(width / 170));
    const step = TIME_STEPS.find((s) => span / s <= wanted) || TIME_STEPS[TIME_STEPS.length - 1];
    const ticks = [];
    for (let t = Math.ceil(from / step) * step; t <= to; t += step) ticks.push(t);
    return { ticks, step };
  }

  function timeText(t, step, span) {
    const d = new Date(t);
    if (span > 36 * 3600000) return d.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
    if (step < 60000) return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

  function rangeText(b) {
    return b.t2 && b.t2 - b.t >= 2000 ? `${clock(b.t)} – ${clock(b.t2)}` : clock(b.t);
  }

  function chartConfigs(budget, targetTps) {
    const msptColor = (v) => (v <= budget * 0.7 ? COLORS.good : v <= budget ? COLORS.gold : COLORS.poor);
    return {
      mspt: {
        bars: { field: 'mspt', color: msptColor },
        lines: [{ field: 'msptMax', color: COLORS.max, width: 1.25 }],
        guide: budget, max: (bs) => Math.max(budget * 1.2, ...bs.map((b) => b.mspt)),
        unit: (v) => fixed(v, 0) + ' ms',
        tip: (b) => `<b>${fixed(b.mspt, 1)} ms</b> average · <span style="color:${COLORS.poor}">${fixed(b.msptMax, 0)} ms</span> slowest<br><span class="t">${rangeText(b)}</span>`,
      },
      tps: {
        lines: [{ field: 'tps', color: COLORS.accent, fill: true }],
        guide: targetTps, max: () => targetTps * 1.05, fixedMax: true, unit: (v) => fixed(v, 0),
        tip: (b) => `<b>${fixed(b.tps, 1)} TPS</b><br><span class="t">${rangeText(b)}</span>`,
      },
      cpu: {
        lines: [{ field: 'cpuSystem', color: COLORS.muted, width: 1.5 }, { field: 'cpuProcess', color: COLORS.accent, fill: true }],
        max: (bs) => Math.max(25, ...bs.map((b) => b.cpuSystem)), cap: 100, unit: (v) => fixed(v, 0) + '%',
        tip: (b) => `Server <b>${fixed(b.cpuProcess, 0)}%</b> · machine ${fixed(b.cpuSystem, 0)}%<br><span class="t">${rangeText(b)}</span>`,
      },
      memory: {
        lines: [{ field: 'heapUsed', color: COLORS.muted, width: 1.5 }, { field: 'heapLive', color: COLORS.gold, fill: true }],
        guide: (bs) => Math.max(0, ...bs.map((b) => b.heapMax)), max: (bs) => Math.max(1, ...bs.map((b) => b.heapMax)),
        base: (max) => (max >= 1073741824 ? 1073741824 : 1048576),
        unit: (v) => (v >= 1073741824 ? fixed(v / 1073741824, v % 1073741824 ? 1 : 0) + ' GB' : v ? fixed(v / 1048576, 0) + ' MB' : '0'),
        tip: (b) => `<b>${bytes(b.heapLive || b.heapUsed)}</b> after GC · ${bytes(b.heapUsed)} incl. garbage · of ${bytes(b.heapMax)}${b.gcMs ? ` · GC ${num(b.gcMs)} ms` : ''}<br><span class="t">${rangeText(b)}</span>`,
      },
      entities: {
        lines: [{ field: 'entities', color: COLORS.accent, fill: true }],
        max: (bs) => Math.max(10, ...bs.map((b) => b.entities)), unit: (v) => num(v),
        tip: (b) => `<b>${num(b.entities)}</b> entities<br><span class="t">${rangeText(b)}</span>`,
      },
      chunks: {
        lines: [{ field: 'chunks', color: COLORS.gold, fill: true }],
        max: (bs) => Math.max(10, ...bs.map((b) => b.chunks)), unit: (v) => num(v),
        tip: (b) => `<b>${num(b.chunks)}</b> loaded chunks<br><span class="t">${rangeText(b)}</span>`,
      },
      players: {
        lines: [{ field: 'players', color: COLORS.good, fill: true, step: true }],
        max: (bs) => Math.max(4, ...bs.map((b) => b.players)), unit: (v) => num(v),
        tip: (b) => `<b>${fixed(b.players, b.players % 1 ? 1 : 0)}</b> players<br><span class="t">${rangeText(b)}</span>`,
      },
    };
  }

  function currentConfigs() {
    const k = state.view ? state.view.kpi : null;
    return chartConfigs(k ? k.budget : 50, k ? k.targetTps : 20);
  }

  /** Live charts trail real time a little, so new points arrive before the line reaches the edge. */
  const LIVE_LAG = 1500;
  const SLOT_SIZES = [1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600].map((s) => s * 1000);

  /** The visible time range: the selected window ending now (live), or the whole report. */
  function chartDomain(points) {
    if (REPORT) {
      const from = points.length ? points[0].t : 0;
      const to = points.length ? points[points.length - 1].t : 1000;
      return { from, to: Math.max(to, from + 1000) };
    }
    const to = Date.now() - LIVE_LAG;
    return { from: to - state.window * 60000, to };
  }

  function chartBuckets(canvas, cfg, points) {
    const pw = Math.max(50, canvas.clientWidth - PAD.left - PAD.right);
    const { from, to } = chartDomain(points);
    const wanted = (to - from) / Math.max(1, Math.floor(pw / (cfg.bars ? 7 : 3)));
    const size = SLOT_SIZES.find((s) => s >= wanted) || SLOT_SIZES[SLOT_SIZES.length - 1];
    const buckets = bucketize(points, size);
    buckets.size = size;
    return buckets;
  }

  function drawCharts() {
    if (!COLORS.accent) readColors();
    const points = visiblePoints();
    const configs = currentConfigs();
    document.querySelectorAll('canvas[data-chart]').forEach((canvas) => {
      const cfg = configs[canvas.dataset.chart];
      if (cfg) drawChart(canvas, cfg, chartBuckets(canvas, cfg, points));
    });
  }

  /**
   * Keeps live charts scrolling smoothly: redraws as often as the time axis moves by about half a
   * pixel (every frame for 1 minute, every couple of seconds for 60), and every frame while a
   * scale is easing.
   */
  let lastFrame = 0;
  function chartLoop(now) {
    if (!REPORT && activeTab === 'overview' && !document.hidden) {
      const canvas = document.querySelector('canvas[data-chart]');
      const pw = canvas ? Math.max(50, canvas.clientWidth - PAD.left - PAD.right) : 600;
      const msPerHalfPixel = (state.window * 60000 / pw) / 2;
      if (state.animating || now - lastFrame >= Math.min(2000, Math.max(16, msPerHalfPixel))) {
        lastFrame = now;
        state.animating = false;
        drawCharts();
      }
    }
    requestAnimationFrame(chartLoop);
  }

  function drawChart(canvas, cfg, buckets) {
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth;
    const h = canvas.clientHeight;
    if (!w || !h) return;
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
    const ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    ctx.font = '600 10.5px Montserrat, Arial, sans-serif';

    const pw = w - PAD.left - PAD.right;
    const ph = h - PAD.top - PAD.bottom;
    const n = buckets.length;
    if (!n) {
      ctx.fillStyle = COLORS.label;
      ctx.textAlign = 'center';
      ctx.fillText('Collecting data…', PAD.left + pw / 2, PAD.top + ph / 2);
      return;
    }

    const { from, to } = chartDomain(visiblePoints());
    const span = Math.max(1000, to - from);
    const x = (t) => PAD.left + ((t - from) / span) * pw;
    const size = buckets.size || 1000;
    const slotW = (size / span) * pw;
    // The scale (and guide lines) only follow what's on screen, not the margin left of it.
    const shown = buckets.filter((b) => b.t2 >= from);
    let maxValue = cfg.max(shown.length ? shown : buckets);
    if (cfg.cap) maxValue = Math.min(cfg.cap, maxValue);
    const base = typeof cfg.base === 'function' ? cfg.base(maxValue) : cfg.base;
    const nice = cfg.fixedMax ? { ticks: niceTicks(maxValue, 3).ticks.filter((t) => t <= maxValue), top: maxValue } : niceTicks(maxValue, 3, base);
    const ticks = nice.ticks;
    // Ease the scale towards a new maximum instead of jumping.
    const key = canvas.dataset.chart;
    const prev = state.scale[key];
    let top = nice.top;
    if (prev && !REPORT && prev.window === state.window) {
      top = prev.top + (nice.top - prev.top) * 0.18;
      if (Math.abs(top - nice.top) < nice.top * 0.004) top = nice.top;
    }
    state.scale[key] = { top, window: state.window };
    if (top !== nice.top) state.animating = true;
    const y = (v) => PAD.top + ph - (Math.max(0, Math.min(v, top)) / top) * ph;
    const xc = (i) => x(buckets[i].tc);

    // Horizontal grid with round values
    ctx.textAlign = 'right';
    ctx.textBaseline = 'middle';
    ctx.lineWidth = 1;
    for (const v of ticks) {
      if (v > top * 1.001) continue;
      const gy = Math.round(y(v)) + 0.5;
      ctx.strokeStyle = COLORS.grid;
      ctx.beginPath(); ctx.moveTo(PAD.left, gy); ctx.lineTo(w - PAD.right, gy); ctx.stroke();
      ctx.fillStyle = COLORS.label;
      ctx.fillText(cfg.unit(v), PAD.left - 8, gy);
    }

    // Time axis
    const tt = timeTicks(from, to, pw);
    ctx.textAlign = 'center';
    ctx.textBaseline = 'alphabetic';
    for (const t of tt.ticks) {
      const tx = PAD.left + ((t - from) / span) * pw;
      if (tx < PAD.left + 20 || tx > w - PAD.right - 20) continue;
      ctx.strokeStyle = COLORS.grid;
      ctx.beginPath(); ctx.moveTo(Math.round(tx) + 0.5, PAD.top); ctx.lineTo(Math.round(tx) + 0.5, PAD.top + ph); ctx.stroke();
      ctx.fillStyle = COLORS.label;
      ctx.fillText(timeText(t, tt.step, span), tx, h - 5);
    }

    // Everything that scrolls is clipped to the plot area.
    ctx.save();
    ctx.beginPath();
    ctx.rect(PAD.left, 0, pw, h);
    ctx.clip();

    // Paused stretches (nobody online)
    ctx.fillStyle = `rgba(${COLORS.ink},.05)`;
    buckets.forEach((b) => { if (b.paused) ctx.fillRect(x(b.start), PAD.top, slotW + 0.5, ph); });

    // Bars
    if (cfg.bars) {
      const gap = slotW > 6 ? Math.min(3, slotW * 0.25) : 0;
      const bw = Math.max(1, slotW - gap);
      buckets.forEach((b) => {
        if (b.paused) return;
        const v = b[cfg.bars.field];
        const by = y(v);
        const bh = PAD.top + ph - by;
        ctx.fillStyle = cfg.bars.color(v);
        roundTop(ctx, x(b.start) + gap / 2, by, bw, bh, Math.min(3, bw / 2));
      });
    }

    // Lines (smoothed through bucket centres)
    for (const line of cfg.lines || []) {
      const pts = buckets.map((b, i) => [xc(i), y(b[line.field])]);
      if (pts.length === 1) pts.unshift([pts[0][0] - 2, pts[0][1]]);
      ctx.beginPath();
      tracePath(ctx, pts, line.step);
      if (line.fill) {
        ctx.save();
        ctx.lineTo(pts[pts.length - 1][0], PAD.top + ph);
        ctx.lineTo(pts[0][0], PAD.top + ph);
        ctx.closePath();
        const gradient = ctx.createLinearGradient(0, PAD.top, 0, PAD.top + ph);
        gradient.addColorStop(0, hexAlpha(line.color, 0.22));
        gradient.addColorStop(1, hexAlpha(line.color, 0.02));
        ctx.fillStyle = gradient;
        ctx.fill();
        ctx.restore();
        ctx.beginPath();
        tracePath(ctx, pts, line.step);
      }
      ctx.strokeStyle = line.color;
      ctx.lineWidth = line.width || 2;
      ctx.lineJoin = 'round';
      ctx.lineCap = 'round';
      ctx.stroke();
    }

    ctx.restore();

    // Budget / target / heap limit
    const guide = typeof cfg.guide === 'function' ? cfg.guide(shown.length ? shown : buckets) : cfg.guide;
    if (guide) {
      const gy = Math.round(y(guide)) + 0.5;
      ctx.setLineDash([4, 4]);
      ctx.strokeStyle = COLORS.guide;
      ctx.lineWidth = 1;
      ctx.beginPath(); ctx.moveTo(PAD.left, gy); ctx.lineTo(w - PAD.right, gy); ctx.stroke();
      ctx.setLineDash([]);
    }

    // Hover marker: the slot nearest to the mouse, which keeps up as the chart scrolls.
    const hoverX = state.hover[canvas.dataset.chart];
    let hi = null;
    if (hoverX != null) {
      let best = Infinity;
      buckets.forEach((b, i) => {
        const d = Math.abs(xc(i) - hoverX);
        if (d < best) { best = d; hi = i; }
      });
      const tooltip = canvas.parentElement.querySelector('.np-tooltip');
      if (tooltip && hi != null) {
        const b = buckets[hi];
        tooltip.innerHTML = b.paused ? `Paused (nobody online)<br><span class="t">${rangeText(b)}</span>` : cfg.tip(b);
      }
    }
    if (hi != null && hi < n) {
      const hx = Math.round(xc(hi)) + 0.5;
      ctx.strokeStyle = COLORS.guide;
      ctx.lineWidth = 1;
      ctx.beginPath(); ctx.moveTo(hx, PAD.top); ctx.lineTo(hx, PAD.top + ph); ctx.stroke();
      for (const line of cfg.lines || []) {
        ctx.fillStyle = line.color;
        ctx.beginPath(); ctx.arc(xc(hi), y(buckets[hi][line.field]), 3.5, 0, Math.PI * 2); ctx.fill();
      }
    }
  }

  function tracePath(ctx, pts, step) {
    ctx.moveTo(pts[0][0], pts[0][1]);
    for (let i = 1; i < pts.length; i++) {
      const [x0, y0] = pts[i - 1];
      const [x1, y1] = pts[i];
      if (step) {
        ctx.lineTo(x1, y0);
        ctx.lineTo(x1, y1);
      } else {
        const mx = (x0 + x1) / 2;
        ctx.bezierCurveTo(mx, y0, mx, y1, x1, y1);
      }
    }
  }

  function roundTop(ctx, x, y, w, h, r) {
    if (h <= 0) return;
    r = Math.min(r, h);
    ctx.beginPath();
    ctx.moveTo(x, y + h);
    ctx.lineTo(x, y + r);
    ctx.quadraticCurveTo(x, y, x + r, y);
    ctx.lineTo(x + w - r, y);
    ctx.quadraticCurveTo(x + w, y, x + w, y + r);
    ctx.lineTo(x + w, y + h);
    ctx.closePath();
    ctx.fill();
  }

  function hexAlpha(color, alpha) {
    if (color.startsWith('#')) {
      const v = parseInt(color.slice(1), 16);
      return `rgba(${(v >> 16) & 255},${(v >> 8) & 255},${v & 255},${alpha})`;
    }
    return color.replace(/[\d.]+\)$/, alpha + ')');
  }

  function setupChartHover() {
    document.querySelectorAll('canvas[data-chart]').forEach((canvas) => {
      const tooltip = canvas.parentElement.querySelector('.np-tooltip');
      const move = (clientX) => {
        const cfg = currentConfigs()[canvas.dataset.chart];
        const rect = canvas.getBoundingClientRect();
        state.hover[canvas.dataset.chart] = clientX - rect.left;
        tooltip.classList.add('show');
        drawChart(canvas, cfg, chartBuckets(canvas, cfg, visiblePoints()));
        const tw = tooltip.offsetWidth;
        const px = clientX - rect.left;
        tooltip.style.left = Math.max(0, Math.min(rect.width - tw, px + 14 + tw > rect.width ? px - tw - 14 : px + 14)) + 'px';
      };
      const leave = () => {
        delete state.hover[canvas.dataset.chart];
        tooltip.classList.remove('show');
        drawCharts();
      };
      canvas.addEventListener('mousemove', (e) => move(e.clientX));
      canvas.addEventListener('mouseleave', leave);
      canvas.addEventListener('touchmove', (e) => { if (e.touches[0]) move(e.touches[0].clientX); }, { passive: true });
      canvas.addEventListener('touchend', leave);
    });
    let resizeTimer;
    window.addEventListener('resize', () => {
      clearTimeout(resizeTimer);
      resizeTimer = setTimeout(drawCharts, 100);
    });
  }

  // ── Console ────────────────────────────────────────────────────────────

  const consoleState = { lines: [], last: 0, filter: 'all', search: '', follow: true, history: [], historyAt: -1, timer: null, ready: false, commands: true };
  const CONSOLE_MAX = 2000;

  function openConsole() {
    if (!consoleState.ready) setupConsole();
    pollConsole();
  }

  function setupConsole() {
    consoleState.ready = true;
    const chips = [['all', 'All'], ['errors', 'Errors'], ['warnings', 'Warnings'], ['ncc', 'Nice Control Center']];
    $('np-console').innerHTML = `<div class="np-filters">${chips.map(([k, l]) => `<button type="button" class="np-filter ${k === 'all' ? 'active' : ''}" data-confilter="${k}">${l}</button>`).join('')}
        <input type="search" class="np-console-search" id="np-console-search" placeholder="Search the log" aria-label="Search the log">
        <label class="np-check" title="Detailed loader and mod messages, hidden by default"><input type="checkbox" id="np-console-debug"> Show debug</label>
        <label class="np-check"><input type="checkbox" id="np-console-follow" checked> Follow</label></div>
      <div class="np-console-log" id="np-console-log" role="log" aria-live="polite"></div>
      <form class="np-console-form" id="np-console-form" autocomplete="off">
        <span class="np-console-prompt">/</span>
        <input type="text" id="np-console-input" placeholder="Type a command, e.g. time query daytime" aria-label="Command" spellcheck="false">
        <button type="submit" class="np-btn primary">Run</button>
      </form>`;
    const log = $('np-console-log');
    log.addEventListener('scroll', () => {
      const atBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 30;
      if (consoleState.follow !== atBottom) {
        consoleState.follow = atBottom;
        $('np-console-follow').checked = atBottom;
      }
    });
    $('np-console-debug').addEventListener('change', (e) => {
      consoleState.debug = e.target.checked;
      renderConsole(true);
    });
    $('np-console-follow').addEventListener('change', (e) => {
      consoleState.follow = e.target.checked;
      if (consoleState.follow) log.scrollTop = log.scrollHeight;
    });
    $('np-console').addEventListener('click', (e) => {
      const chip = e.target.closest('[data-confilter]');
      if (!chip) return;
      consoleState.filter = chip.dataset.confilter;
      document.querySelectorAll('[data-confilter]').forEach((c) => c.classList.toggle('active', c === chip));
      renderConsole(true);
    });
    $('np-console-search').addEventListener('input', (e) => {
      consoleState.search = e.target.value.toLowerCase();
      renderConsole(true);
    });
    const input = $('np-console-input');
    input.addEventListener('keydown', (e) => {
      const h = consoleState.history;
      if (e.key === 'ArrowUp' && h.length) {
        consoleState.historyAt = Math.min(h.length - 1, consoleState.historyAt + 1);
        input.value = h[h.length - 1 - consoleState.historyAt];
        e.preventDefault();
      } else if (e.key === 'ArrowDown') {
        consoleState.historyAt = Math.max(-1, consoleState.historyAt - 1);
        input.value = consoleState.historyAt < 0 ? '' : h[h.length - 1 - consoleState.historyAt];
        e.preventDefault();
      }
    });
    $('np-console-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      const command = input.value.trim();
      if (!command) return;
      consoleState.history.push(command);
      consoleState.historyAt = -1;
      input.value = '';
      addConsoleLocal('cmd', '/' + command.replace(/^\//, ''));
      try {
        const res = await api('api/console/run', { method: 'POST', body: JSON.stringify({ command }) });
        if (res.error) addConsoleLocal('out err', res.error);
        else (res.output.length ? res.output : ['(no output)']).forEach((line) => addConsoleLocal('out', line));
      } catch (err) {
        addConsoleLocal('out err', 'The server did not respond: ' + err.message);
      }
    });
  }

  function addConsoleLocal(kind, text) {
    consoleState.lines.push({ local: kind, message: text, time: Date.now(), level: 'INFO', logger: '' });
    renderConsole(false);
  }

  async function pollConsole() {
    clearTimeout(consoleState.timer);
    if (activeTab !== 'console') return;
    try {
      const data = await api('api/console?after=' + consoleState.last);
      consoleState.commands = data.commands;
      $('np-console-form').hidden = !data.commands;
      if (!data.enabled) {
        $('np-console-log').innerHTML = '<div class="np-empty">The console is switched off (web_console in the config).</div>';
        return;
      }
      if (data.lines.length) {
        consoleState.lines.push(...data.lines);
        consoleState.last = data.lines[data.lines.length - 1].seq;
        if (consoleState.lines.length > CONSOLE_MAX) consoleState.lines.splice(0, consoleState.lines.length - CONSOLE_MAX);
        renderConsole(false);
      }
    } catch (e) { /* the live badge shows connection problems */ }
    consoleState.timer = setTimeout(pollConsole, 1000);
  }

  function consoleMatches(l) {
    const f = consoleState.filter;
    if (!l.local) {
      if (f === 'errors' && l.level !== 'ERROR' && l.level !== 'FATAL') return false;
      if (f === 'warnings' && l.level !== 'WARN') return false;
      if (f === 'ncc' && l.logger !== 'Nice Control Center') return false;
      if (!consoleState.debug && (l.level === 'DEBUG' || l.level === 'TRACE')) return false;
    }
    return !consoleState.search || (l.message + ' ' + (l.logger || '')).toLowerCase().includes(consoleState.search);
  }

  function consoleLine(l) {
    const t = new Date(l.time).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
    if (l.local) return `<div class="np-con-line np-con-${l.local.replace(' ', ' np-con-')}"><span class="np-con-time">${t}</span><span>${esc(l.message)}</span></div>`;
    const lvl = (l.level || 'INFO').toLowerCase();
    const logger = (l.logger || '').split('.').pop();
    return `<div class="np-con-line np-con-${lvl}"><span class="np-con-time">${t}</span><span class="np-con-level">${esc(l.level)}</span><span class="np-con-logger">${esc(logger)}</span><span class="np-con-msg">${esc(l.message)}${l.error ? '\n' + esc(l.error) : ''}</span></div>`;
  }

  function renderConsole(full) {
    const log = $('np-console-log');
    if (!log) return;
    const lines = consoleState.lines.filter(consoleMatches).slice(-800);
    log.innerHTML = lines.map(consoleLine).join('') || '<div class="np-empty">No lines match.</div>';
    if (consoleState.follow || full) log.scrollTop = log.scrollHeight;
  }

  // ── Chat ───────────────────────────────────────────────────────────────

  const chatState = { lines: [], bySeq: new Map(), last: 0, filter: 'all', search: '', private: true, follow: true, timer: null, ready: false };
  const CHAT_FILTERS = { all: null, chat: ['chat'], discord: ['discord'], events: ['event'], system: ['system'] };

  function openChat() {
    if (!chatState.ready) setupChat();
    pollChat();
  }

  function setupChat() {
    chatState.ready = true;
    const chips = [['all', 'All'], ['chat', 'Player chat'], ['discord', 'Discord'], ['events', 'Joins, deaths, advancements'], ['system', 'Server messages']];
    $('np-chat').innerHTML = `<div class="np-filters">${chips.map(([k, l]) => `<button type="button" class="np-filter ${k === 'all' ? 'active' : ''}" data-chatfilter="${k}">${l}</button>`).join('')}
        <input type="search" class="np-console-search" id="np-chat-search" placeholder="Search the chat" aria-label="Search the chat">
        <label class="np-check" title="Messages only some players got: /msg, command feedback, tellraw to one player"><input type="checkbox" id="np-chat-private" checked> Private messages</label>
        <label class="np-check"><input type="checkbox" id="np-chat-follow" checked> Follow</label></div>
      <div class="np-console-log np-chat-log" id="np-chat-log" role="log" aria-live="polite"></div>
      <form class="np-console-form" id="np-chat-form" autocomplete="off" hidden>
        <input type="text" id="np-chat-input" placeholder="Message everyone (sent as /say)" aria-label="Message" maxlength="256">
        <button type="submit" class="np-btn primary">Send</button>
      </form>
      <div class="np-chat-error" id="np-chat-error" hidden></div>`;
    const log = $('np-chat-log');
    log.addEventListener('scroll', () => {
      const atBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 30;
      if (chatState.follow !== atBottom) {
        chatState.follow = atBottom;
        $('np-chat-follow').checked = atBottom;
      }
    });
    $('np-chat-follow').addEventListener('change', (e) => {
      chatState.follow = e.target.checked;
      if (chatState.follow) log.scrollTop = log.scrollHeight;
    });
    $('np-chat-private').addEventListener('change', (e) => {
      chatState.private = e.target.checked;
      renderChat(true);
    });
    $('np-chat').addEventListener('click', (e) => {
      const chip = e.target.closest('[data-chatfilter]');
      if (!chip) return;
      chatState.filter = chip.dataset.chatfilter;
      document.querySelectorAll('[data-chatfilter]').forEach((c) => c.classList.toggle('active', c === chip));
      renderChat(true);
    });
    $('np-chat-search').addEventListener('input', (e) => {
      chatState.search = e.target.value.toLowerCase();
      renderChat(true);
    });
    $('np-chat-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      const input = $('np-chat-input');
      const text = input.value.trim();
      if (!text) return;
      input.value = '';
      let error = '';
      try {
        const res = await api('api/chat/send', { method: 'POST', body: JSON.stringify({ text }) });
        error = res.error || '';
      } catch (err) {
        error = 'The server did not respond: ' + err.message;
      }
      $('np-chat-error').textContent = error;
      $('np-chat-error').hidden = !error;
      pollChat();
    });
  }

  async function pollChat() {
    clearTimeout(chatState.timer);
    if (activeTab !== 'chat') return;
    try {
      let data = await api('api/chat?after=' + chatState.last);
      $('np-chat-form').hidden = !data.send;
      if (!data.enabled) {
        $('np-chat-log').innerHTML = '<div class="np-empty">The chat is switched off with the console (web_console in the config).</div>';
        return;
      }
      if (data.latest < chatState.last) {
        // The server restarted: start over.
        chatState.lines = [];
        chatState.bySeq.clear();
        chatState.last = 0;
        data = await api('api/chat?after=0');
      }
      for (const l of data.lines) {
        // Lines still collecting recipients come again; replace them.
        const old = chatState.bySeq.get(l.seq);
        if (old) Object.assign(old, l);
        else {
          chatState.lines.push(l);
          chatState.bySeq.set(l.seq, l);
        }
      }
      chatState.last = data.next;
      if (chatState.lines.length > 1000) chatState.lines.splice(0, chatState.lines.length - 1000).forEach((l) => chatState.bySeq.delete(l.seq));
      renderChat(false);
    } catch (e) { /* the live badge shows connection problems */ }
    chatState.timer = setTimeout(pollChat, 1000);
  }

  function chatSegment(p) {
    const style = [];
    if (p.c) style.push('color:' + p.c);
    if (p.b) style.push('font-weight:700');
    if (p.i) style.push('font-style:italic');
    const deco = [p.u && 'underline', p.s && 'line-through'].filter(Boolean).join(' ');
    if (deco) style.push('text-decoration:' + deco);
    const cls = p.o ? ' class="np-chat-obf"' : '';
    const body = `<span${cls}${style.length ? ` style="${style.join(';')}"` : ''}>${esc(p.t)}</span>`;
    return p.url && /^https?:\/\//i.test(p.url) ? `<a href="${esc(p.url)}" target="_blank" rel="noopener noreferrer">${body}</a>` : body;
  }

  function chatLine(l) {
    const t = new Date(l.time).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
    const to = l.to ? `<span class="np-chat-to" title="Only these players got it">→ ${esc(l.to.join(', '))}</span>` : '';
    return `<div class="np-chat-line"><span class="np-con-time">${t}</span><span class="np-chat-msg">${l.parts.map(chatSegment).join('')}${to}</span></div>`;
  }

  function renderChat(full) {
    const log = $('np-chat-log');
    if (!log) return;
    const kinds = CHAT_FILTERS[chatState.filter];
    const q = chatState.search;
    const lines = chatState.lines.filter((l) => (!kinds || kinds.includes(l.kind)) && (chatState.private || !l.to)
      && (!q || l.text.toLowerCase().includes(q))).slice(-800);
    log.innerHTML = lines.map(chatLine).join('') || `<div class="np-empty">${chatState.lines.length ? 'No messages match.' : 'No messages since the server started.'}</div>`;
    if (chatState.follow || full) log.scrollTop = log.scrollHeight;
  }

  // ── Errors & warnings ──────────────────────────────────────────────────

  const errorsState = { level: 'all', sinceReload: false, harmless: false, data: null, timer: null };

  async function loadErrors() {
    try {
      errorsState.data = await api('api/errors?since=' + (errorsState.sinceReload ? 'reload' : 'all'));
      renderErrors();
    } catch (e) {
      $('np-errors').innerHTML = `<div class="np-empty">Could not load the log: ${esc(e.message)}</div>`;
    }
    clearTimeout(errorsState.timer);
    if (activeTab === 'server') errorsState.timer = setTimeout(loadErrors, 5000);
  }

  function renderErrors() {
    const d = errorsState.data;
    if (!d) return;
    const isError = (g) => g.level === 'ERROR' || g.level === 'FATAL';
    let groups = d.groups.filter((g) => errorsState.harmless || !g.harmless);
    if (errorsState.level === 'errors') groups = groups.filter(isError);
    if (errorsState.level === 'warnings') groups = groups.filter((g) => !isError(g));
    const chip = (key, label, value) => `<button type="button" class="np-filter ${errorsState[key] === value ? 'active' : ''}" data-errfilter="${key}" data-value="${value}">${label}</button>`;
    const kindLabel = { datapack: 'Data pack', mod: 'Mod', minecraft: 'Minecraft', other: 'Other' };
    const toolbar = `<div class="np-filters">
        ${chip('level', 'All', 'all')}${chip('level', 'Errors', 'errors')}${chip('level', 'Warnings', 'warnings')}
        <label class="np-check"><input type="checkbox" data-errtoggle="sinceReload" ${errorsState.sinceReload ? 'checked' : ''}> Since last /reload${d.reloadTime ? ` (${clock(d.reloadTime)})` : ''}</label>
        <label class="np-check"><input type="checkbox" data-errtoggle="harmless" ${errorsState.harmless ? 'checked' : ''}> Show harmless</label>
      </div>`;
    const rows = groups.map((g) => {
      const key = 'err:' + g.key;
      const first = g.example.split('\n')[0];
      return `<details class="np-item np-error" data-key="${esc(key)}" ${state.open.has(key) ? 'open' : ''}>
        <summary class="np-error-row">
          <span class="np-level ${isError(g) ? 'poor' : 'warn'}">${isError(g) ? 'Error' : 'Warning'}</span>
          <span class="np-error-msg"><span class="np-mono">${esc(first)}</span><small>${esc(g.source)} · ${esc(kindLabel[g.sourceKind] || g.sourceKind)}${g.harmless ? ' · harmless' : ''}</small></span>
          <span class="np-error-count">${num(errorsState.sinceReload ? g.sinceReload : g.count)}×</span>
          <span class="np-error-time">${clock(g.last)}</span>
        </summary>
        <pre class="np-pre">${esc(g.example)}${g.error ? '\n\n' + esc(g.error) : ''}</pre>
        <div class="np-line-meta">Logger: ${esc(g.logger)} · first seen ${dateTime(g.first)}</div>
      </details>`;
    }).join('');
    $('np-errors').innerHTML = toolbar + (rows || `<div class="np-empty">${d.groups.length ? 'Nothing matches the filter.' : 'No errors or warnings logged. 👍'}</div>`);
  }

  function setupErrors() {
    $('np-errors').addEventListener('click', (e) => {
      const f = e.target.closest('[data-errfilter]');
      if (f) {
        errorsState[f.dataset.errfilter] = f.dataset.value;
        renderErrors();
      }
    });
    $('np-errors').addEventListener('change', (e) => {
      const t = e.target.closest('[data-errtoggle]');
      if (!t) return;
      errorsState[t.dataset.errtoggle] = t.checked;
      if (t.dataset.errtoggle === 'sinceReload') loadErrors(); else renderErrors();
    });
  }

  // ── Updates ────────────────────────────────────────────────────────────

  const updatesState = { data: null, timer: null, busy: false, message: '' };
  const UPDATE_STATUS = {
    AVAILABLE: ['New version', 'good'], STAGED: ['Needs approval', 'warn'], PENDING: ['At next restart', 'good'],
    HELD: ['Held', 'warn'], FAILED: ['Failed', 'poor'], SKIPPED: ['Skipped', 'info'], IGNORED: ['Never updated', 'info'],
    NOT_FOUND: ['Not on Modrinth', 'info'], UP_TO_DATE: ['Up to date', 'info'],
  };
  const KIND_LABEL = { mod: 'Mod', datapack: 'Data pack', resourcepack: 'Resource pack' };
  const UPDATE_MODES = {
    auto: 'New versions are downloaded and installed at the next restart on their own.',
    stage: 'New versions are downloaded, then installed at the next restart once you approve them here.',
    check: 'Only checks; nothing is downloaded. Switch update_mode to "auto" or "stage" to install updates.',
    off: 'Update checks are off (update_mode "off").',
  };

  async function loadUpdates() {
    try {
      updatesState.data = await api('api/updates');
      renderUpdates();
    } catch (e) {
      $('np-updates').innerHTML = `<div class="np-empty">Could not load updates: ${esc(e.message)}</div>`;
    }
    clearTimeout(updatesState.timer);
    const checking = updatesState.data && updatesState.data.checking;
    updatesState.timer = setTimeout(loadUpdates, checking ? 2000 : 300000);
  }

  function updateBadge(d) {
    const open = d.entries.filter((e) => ['AVAILABLE', 'STAGED', 'PENDING'].includes(e.status)).length;
    setTabBadge('updates', open, d.entries.some((e) => e.status === 'STAGED' || e.status === 'FAILED'));
  }

  function updateActions(e) {
    const b = (action, label, cls) => `<button type="button" class="np-btn ${cls || ''}" data-update-action="${action}" data-key="${esc(e.key)}">${label}</button>`;
    switch (e.status) {
      case 'STAGED': return b('approve', '<i class="bi bi-check2"></i> Install at restart', 'primary') + b('skip', 'Skip this version') + b('ignore', 'Never update');
      case 'PENDING': return b('cancel', 'Don\'t install') + b('skip', 'Skip this version');
      case 'AVAILABLE': case 'HELD': case 'FAILED': return (e.latestVersion ? b('skip', 'Skip this version') : '') + b('ignore', 'Never update');
      case 'IGNORED': return b('unignore', 'Update again');
      case 'SKIPPED': case 'UP_TO_DATE': return b('ignore', 'Never update');
      default: return '';
    }
  }

  function renderUpdates() {
    const d = updatesState.data;
    if (!d) return;
    updateBadge(d);
    const status = d.checking ? 'Checking…'
      : d.lastCheck ? 'Last checked ' + dateTime(d.lastCheck) : 'Not checked yet.';
    const toolbar = `<div class="np-bloat-actions">
        <button type="button" class="np-btn primary" data-update-check ${d.checking || d.mode === 'off' || updatesState.busy ? 'disabled' : ''}><i class="bi bi-arrow-repeat"></i> Check now</button>
        <span class="np-rec-text">${esc(status)}</span></div>
      <details class="np-howto"><summary><i class="bi bi-info-circle"></i> Mode <b>${esc(d.mode)}</b> · how updates work</summary>
        <p>${esc(UPDATE_MODES[d.mode] || '')}${d.dedicated ? '' : ' In singleplayer the mod only checks; install updates yourself.'} Change it with <code>update_mode</code> in <code>config/nicecontrolcenter.json</code>.</p>
        <p>Mods, data packs and the server resource pack are checked on Modrinth and GitHub. Updates are installed at the next restart; replaced mods and data packs are kept so you can roll back.</p></details>
      ${d.lastError ? `<div class="np-update-error"><i class="bi bi-exclamation-triangle"></i> The last check failed: ${esc(d.lastError)}</div>` : ''}
      ${updatesState.message ? `<div class="np-update-error">${esc(updatesState.message)}</div>` : ''}`;

    const restores = d.pending.filter((p) => p.type === 'restore');
    const restoreList = restores.length ? `<h4 class="np-subhead">Rollbacks at the next restart</h4>` + restores.map((p) =>
      `<div class="np-update-row"><span class="np-level warn">Rollback</span>
        <span class="np-error-msg"><b>${esc(p.name)}</b><small>${esc(p.oldVersion)} → ${esc(p.newVersion)}</small></span>
        <span class="np-update-buttons"><button type="button" class="np-btn" data-update-action="cancel" data-key="${esc(p.key)}">Cancel</button></span></div>`).join('') : '';

    const rows = d.entries.map((e) => {
      const [label, cls] = UPDATE_STATUS[e.status] || [e.status, 'info'];
      const key = 'upd:' + e.key;
      const version = e.latestVersion && e.status !== 'UP_TO_DATE' && e.status !== 'NOT_FOUND'
        ? `${esc(e.installedVersion || '?')} → <b>${esc(e.latestVersion)}</b>` : esc(e.installedVersion || '');
      const source = e.source === 'modrinth' ? 'Modrinth' : e.source === 'github' ? 'GitHub' : e.source === 'url' ? 'Link' : '';
      const details = [
        e.reason ? `<p class="np-update-reason">${esc(e.reason)}</p>` : '',
        e.changelog ? `<h4 class="np-subhead">What's new${e.published ? ' · ' + dateTime(e.published) : ''}</h4><pre class="np-pre np-changelog">${esc(e.changelog)}</pre>` : '',
        `<div class="np-line-meta">${esc(e.file || '')}${e.kind !== 'resourcepack' && e.latestFile && e.latestFile !== e.file && e.latestVersion ? ' → ' + esc(e.latestFile) : ''}${e.projectUrl ? ` · <a href="${esc(e.projectUrl)}" target="_blank" rel="noopener">${source} page</a>` : ''}</div>`,
        `<div class="np-update-buttons">${updateActions(e)}</div>`,
      ].join('');
      return `<details class="np-item" data-key="${esc(key)}" ${state.open.has(key) ? 'open' : ''}>
        <summary class="np-update-row">
          <span class="np-level ${cls}">${label}</span>
          <span class="np-error-msg"><b>${esc(e.name)}</b><small>${KIND_LABEL[e.kind] || e.kind}${source ? ' · ' + source : ''}</small></span>
          <span class="np-update-version">${version}</span>
        </summary>${details}</details>`;
    }).join('');

    const backups = d.backups.length ? `<h4 class="np-subhead">Replaced versions (backups)</h4>` + d.backups.map((b) =>
      `<div class="np-update-row"><span class="np-level info">${esc(dateTime(b.time))}</span>
        <span class="np-error-msg">${b.files.map((f) => `<span><b>${esc(f.name)}</b> <small>${esc(f.oldVersion)} → ${esc(f.newVersion)}</small></span>`).join('')}</span>
        <span class="np-update-buttons"><button type="button" class="np-btn" data-update-rollback="${esc(b.id)}"><i class="bi bi-arrow-counterclockwise"></i> Roll back</button></span></div>`).join('') : '';

    const list = d.entries.length ? rows
      : `<div class="np-empty">${d.checking ? 'Looking up your mods and data packs…'
        : d.mode === 'off' ? 'Update checks are off.' : 'The first check runs a minute after the server starts, or click "Check now".'}</div>`;
    const packOpen = d.packConfigured || d.packError || state.open.has('upd:packbox');
    const packBox = d.dedicated ? `<details class="np-item np-packbox" data-key="upd:packbox" ${packOpen ? 'open' : ''}><summary class="np-pack-summary"><span class="np-caret bi bi-chevron-right"></span><b>Server resource pack</b><small>${d.packConfigured ? esc(d.packSource) : d.packSource ? 'watching the link in server.properties' : 'not set up'}</small></summary>
      <form class="np-pack-source" id="np-pack-source">
        <input type="text" name="source" value="${esc(d.packConfigured ? d.packSource : '')}" placeholder="${esc(d.packSource ? 'Using the link in server.properties: ' + d.packSource : 'https://…/pack.zip, github:owner/repo@tag or modrinth:project')}" aria-label="Resource pack source">
        <button type="submit" class="np-btn">Save &amp; check</button>
      </form>
      <p class="np-update-mode">The newest zip from this source is put into server.properties (link and SHA-1) at the next restart, like the other updates.
        Without a source, the link already in server.properties is watched and its SHA-1 kept right.
        ${d.webhook ? 'A build ping is set up (<code>/ncc updates webhook</code>).' : 'To check right after a build, run <code>/ncc updates webhook</code> for a ready-made command.'}
        ${d.packLastCheck ? ' Last checked ' + esc(dateTime(d.packLastCheck)) + '.' : ''}</p>
      ${d.packError ? `<div class="np-update-error"><i class="bi bi-exclamation-triangle"></i> ${esc(d.packError)}</div>` : ''}</details>` : '';
    $('np-updates').innerHTML = toolbar + restoreList + packBox + `<h4 class="np-subhead">Installed</h4>` + list + backups;
  }

  async function updatePost(path, body) {
    updatesState.busy = true;
    try {
      const d = await api(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });
      updatesState.message = d.error || '';
      if (d.entries) updatesState.data = d;
    } catch (e) {
      updatesState.message = 'The server did not respond: ' + e.message;
    }
    updatesState.busy = false;
    renderUpdates();
    if (updatesState.data && updatesState.data.checking) loadUpdates();
  }

  function setupUpdates() {
    $('np-updates').addEventListener('submit', (e) => {
      if (e.target.id !== 'np-pack-source') return;
      e.preventDefault();
      updatePost('api/updates/resourcepack/source', { source: e.target.elements.source.value });
    });
    $('np-updates').addEventListener('click', (e) => {
      if (e.target.closest('[data-update-check]')) updatePost('api/updates/check');
      const action = e.target.closest('[data-update-action]');
      if (action) updatePost('api/updates/action', { action: action.dataset.updateAction, key: action.dataset.key });
      const rollback = e.target.closest('[data-update-rollback]');
      if (rollback && confirm('Put these old versions back at the next restart? The newer versions will be skipped from then on.')) {
        updatePost('api/updates/rollback', { backup: rollback.dataset.updateRollback });
      }
    });
  }

  // ── Player inspector ───────────────────────────────────────────────────

  /**
   * Replaces a container's content without flicker: nothing happens if the HTML is unchanged, and
   * images that are still there (player faces) are kept instead of being loaded again.
   */
  function patchHtml(container, html) {
    if (container.dataset.html === html) return false;
    container.dataset.html = html;
    const template = document.createElement('template');
    template.innerHTML = html;
    const old = new Map();
    container.querySelectorAll('img[src]').forEach((img) => {
      if (!old.has(img.src)) old.set(img.src, []);
      old.get(img.src).push(img);
    });
    template.content.querySelectorAll('img[src]').forEach((img) => {
      const reuse = old.get(img.src);
      if (reuse && reuse.length) {
        const keep = reuse.shift();
        keep.className = img.className;
        img.replaceWith(keep);
      }
    });
    container.replaceChildren(...template.content.childNodes);
    return true;
  }

  const playersState = { data: null, selected: null, timer: null, message: '', messageAt: 0, messageError: false };

  /** A short note after an action; it fades out after 4 seconds. */
  function playerNote() {
    if (!playersState.message || Date.now() - playersState.messageAt > 4000) return '';
    return `<span class="np-note ${playersState.messageError ? 'error' : 'ok'}"><i class="bi ${playersState.messageError ? 'bi-exclamation-triangle' : 'bi-check2'}"></i> ${esc(playersState.message)}</span>`;
  }

  async function loadPlayers() {
    clearTimeout(playersState.timer);
    try {
      const q = playersState.selected ? '?key=' + encodeURIComponent(playersState.selected) : '';
      playersState.data = await api('api/players' + q);
      renderPlayerList();
      renderPlayerDetail();
    } catch (e) {
      $('np-player-list').innerHTML = `<div class="np-empty">Could not load players: ${esc(e.message)}</div>`;
    }
    if (activeTab === 'players') playersState.timer = setTimeout(loadPlayers, 2000);
  }

  const playerKey = (p) => p.uuid || p.key;
  const face = (p, size) => p.uuid
    ? `<img class="np-face" src="https://mc-heads.net/avatar/${esc(p.uuid)}/${size}" alt="" loading="lazy" width="${size}" height="${size}">`
    : `<span class="np-face np-face--none"><i class="bi bi-person"></i></span>`;

  function renderPlayerList() {
    const d = playersState.data;
    if (!d) return;
    const filter = $('np-player-search').value.trim().toLowerCase();
    const players = d.players.filter((p) => !filter || p.name.toLowerCase().includes(filter));
    const online = d.players.filter((p) => p.online).length;
    setTabBadge('players', d.unread || online, !!d.unread);
    patchHtml($('np-player-list'), `<div class="np-inspector-count">${online} online · ${d.players.length} total</div>` + (players.map((p) => {
      const sub = [p.race, p.class].filter(Boolean).join(' · ') || (p.online ? p.dimension : p.lastSeen ? 'Last seen ' + dateTime(p.lastSeen) : '');
      return `<button type="button" class="np-player-row ${playerKey(p) === playersState.selected ? 'active' : ''}" data-player="${esc(playerKey(p))}">
        ${face(p, 32)}<span class="np-player-name"><b>${esc(p.name)}${p.banned ? ' <span class="np-banned">banned</span>' : ''}</b><small>${esc(sub || '')}</small>
          ${p.client || (p.xray && p.xray.length) ? `<span class="np-player-badges">${p.client ? `<span class="np-client">${esc(p.client)}</span>` : ''}${p.xray && p.xray.length ? '<span class="np-xray" title="Mined unusually little stone per ore; see the player card">x-ray hint</span>' : ''}</span>` : ''}</span>
        ${p.unread ? `<span class="np-tab-badge warn" title="${p.unread} new ${p.unread === 1 ? 'answer' : 'answers'}">${p.unread}</span>` : `<span class="np-live-dot ${p.online ? 'on' : ''}" title="${p.online ? 'Online' : 'Offline'}"></span>`}</button>`;
    }).join('') || '<div class="np-empty">No players match.</div>'));
  }

  function tpCommand(p, loc) {
    return `execute in ${loc.dimensionId || 'minecraft:overworld'} run tp @s ${loc.x} ${loc.y} ${loc.z}`;
  }

  // Looted or expired graves are marked removed and keep who opened them; the name is the more useful part.
  function graveStatus(g) {
    if (g.openedBy === 'expired') return 'expired';
    if (g.openedBy) return g.openedByOwner ? `looted by ${esc(g.openedBy)} (owner)` : `<span class="warn">looted by ${esc(g.openedBy)}</span>`;
    return g.removed ? 'removed' : 'not looted yet';
  }

  function locTile(p, icon, title, loc, extra) {
    if (!loc) return '';
    return `<div class="np-loc-tile"><p class="np-loc-title"><i class="bi ${icon}"></i> ${esc(title)}</p>
      <p class="np-loc-coords">${loc.x}, ${loc.y}, ${loc.z}</p><p class="np-loc-sub">${esc(loc.dimension)}${extra ? ' · ' + extra : ''}</p>
      <button type="button" class="np-link-btn" data-copy="/${esc(tpCommand(p, loc))}" title="Copies a /tp command for yourself"><i class="bi bi-clipboard"></i> Copy /tp</button></div>`;
  }

  function itemTile(item) {
    const icon = item.head ? 'bi-person-bounding-box' : item.enchantments.length ? 'bi-stars' : 'bi-box-seam';
    const tip = [item.id, ...item.enchantments].join('\n');
    return `<div class="np-item-tile ${item.enchantments.length ? 'enchanted' : ''}" title="${esc(tip)}">
      <i class="bi ${icon}"></i><span class="np-item-name">${esc(item.name)}</span>${item.count > 1 ? `<span class="np-item-count">×${item.count}</span>` : ''}</div>`;
  }

  function itemSection(title, items, note) {
    if (!items || !items.length) return '';
    const sorted = [...items].sort((a, b) => (a.slot ?? 0) - (b.slot ?? 0));
    return `<h4 class="np-subhead">${esc(title)}${note ? ` <small>${esc(note)}</small>` : ''}</h4><div class="np-item-grid">${sorted.map(itemTile).join('')}</div>`;
  }

  function renderPlayerDetail() {
    const d = playersState.data;
    const box = $('np-player-detail');
    if (!d || !playersState.selected) {
      box.innerHTML = '<div class="np-empty">Pick a player.</div>';
      return;
    }
    const p = d.detail;
    if (!p) {
      box.innerHTML = '<div class="np-empty">No data for this player.</div>';
      return;
    }
    // Keep a half-typed message across the 2-second refresh.
    const typed = $('np-player-msg') ? $('np-player-msg').value : '';
    const focused = document.activeElement && document.activeElement.id === 'np-player-msg';
    const s = p.status;
    const statusBits = s ? [`<i class="bi bi-heart-fill"></i> ${s.health}/20`, `<i class="bi bi-egg-fried"></i> ${s.food}/20`,
      `<i class="bi bi-star-fill"></i> Level ${s.xpLevel}`, esc(s.gameMode), p.online && s.ping != null ? `${s.ping} ms` : ''].filter(Boolean).join(' &nbsp;·&nbsp; ') : '';
    const grave = p.grave ? locTile(p, 'bi-flower1', 'Last grave', p.grave,
      graveStatus(p.grave)) : '';
    // Messages on top, moderation at the very bottom, so Kick/Ban are never next to "Send".
    const thread = (p.messages || []).map((m) => `<div class="np-msg ${m.fromPlayer ? 'in' : 'out'}"><span>${esc(m.text)}</span><small>${m.fromPlayer ? esc(p.name) : 'Admin'} · ${clock(m.time)}</small></div>`).join('');
    const actions = p.uuid && d.actions && (p.online || thread) ? `<div class="np-player-chat">
        ${thread ? `<div class="np-msgs" id="np-msgs">${thread}</div>` : ''}
        ${p.online ? `<form class="np-player-actions" id="np-player-actions" data-uuid="${esc(p.uuid)}">
          <input type="text" id="np-player-msg" maxlength="256" placeholder="Private message to ${esc(p.name)}" value="${esc(typed)}">
          <button type="submit" class="np-btn"><i class="bi bi-send"></i> Send</button>${playersState.lastAction === 'message' ? playerNote() : ''}</form>
          <p class="np-loc-sub">Only ${esc(p.name)} sees it. They can answer privately with the [Answer] button or <code>/nccreply</code>.</p>` : '<p class="np-loc-sub">Offline; messages can be sent when they are online.</p>'}
      </div>` : '';
    const moderation = p.uuid && d.actions ? `<h4 class="np-subhead">Moderation</h4>
      <div class="np-moderation" data-uuid="${esc(p.uuid)}">
        ${p.online ? '<button type="button" class="np-btn danger" data-player-act="kick"><i class="bi bi-box-arrow-right"></i> Kick</button>' : ''}
        ${p.ban ? '<button type="button" class="np-btn" data-player-act="unban"><i class="bi bi-unlock"></i> Unban</button>'
          : '<button type="button" class="np-btn danger" data-player-act="ban"><i class="bi bi-slash-circle"></i> Ban</button>'}
        ${playersState.lastAction !== 'message' ? playerNote() : ''}</div>` : '';
    const banInfo = p.ban ? `<div class="np-update-error"><i class="bi bi-slash-circle"></i> Banned${p.ban.since ? ' since ' + esc(dateTime(p.ban.since)) : ''}${p.ban.by ? ' by ' + esc(p.ban.by) : ''}${p.ban.reason ? ': ' + esc(p.ban.reason) : ''}${p.ban.until ? ' (until ' + esc(dateTime(p.ban.until)) + ')' : ''}</div>` : '';
    const msgs = $('np-msgs');
    const atBottom = !msgs || msgs.scrollHeight - msgs.scrollTop - msgs.clientHeight < 20;
    const changed = patchHtml(box, `<div class="np-player-head">${face(p, 64)}<div>
        <h3>${esc(p.name)} <span class="np-level ${p.online ? 'good' : 'info'}">${p.online ? 'Online' : 'Offline'}</span>${p.ban ? ' <span class="np-level poor">Banned</span>' : ''}</h3>
        <p class="np-loc-sub">${[p.race, p.class].filter(Boolean).map(esc).join(' · ')}${p.lastSeen && !p.online ? (p.race || p.class ? ' · ' : '') + 'last seen ' + dateTime(p.lastSeen) : ''}</p>
        ${p.uuid ? `<p class="np-loc-sub np-mono">${esc(p.uuid)}</p>` : ''}</div></div>
      ${banInfo}
      ${actions}
      <div class="np-loc-grid">
        ${s ? locTile(p, 'bi-person-walking', p.online ? 'Current position' : 'Position when last saved', s, statusBits) : ''}
        ${locTile(p, 'bi-house', 'Home', p.home)}
        ${locTile(p, 'bi-heartbreak', 'Last death', p.lastDeath)}
        ${grave}
        ${locTile(p, 'bi-arrow-counterclockwise', '/back position', p.back)}
        ${locTile(p, 'bi-shield-check', 'Last safe position', p.lastSafePos)}
      </div>
      ${itemSection('Equipment', p.equipment)}
      ${itemSection('Inventory', p.inventory, p.online ? 'live' : 'when last saved')}
      ${itemSection('Ender chest', p.enderChest)}
      ${p.grave ? itemSection('Grave contents', p.grave.contents) : ''}
      ${p.waypoints && p.waypoints.length ? `<h4 class="np-subhead">Waypoints</h4><div class="np-loc-grid">${p.waypoints.map((w) =>
        locTile(p, w.access === 'public' ? 'bi-signpost-2' : 'bi-lock', w.name, w, esc(w.access))).join('')}</div>` : ''}
      ${p.claims && p.claims.length ? `<h4 class="np-subhead">Claims</h4><div class="np-loc-grid">${p.claims.map((c) =>
        locTile(p, 'bi-bounding-box', c.anchor, c, c.trusted.length ? 'trusted: ' + esc(c.trusted.join(', ')) : 'nobody trusted')).join('')}</div>` : ''}
      ${p.tags && p.tags.length ? `<h4 class="np-subhead">Tags</h4><div class="np-chips">${p.tags.map((t) => `<span class="np-tag">${esc(t)}</span>`).join('')}</div>` : ''}
      ${clientSection(p)}
      ${miningSection(p)}
      ${moderation}`);
    if (changed && $('np-msgs') && atBottom) $('np-msgs').scrollTop = $('np-msgs').scrollHeight;
    if (changed && focused) {
      const input = $('np-player-msg');
      input.focus();
      input.setSelectionRange(input.value.length, input.value.length);
    }
  }

  /** What the player's game reports: brand and mods seen through their network channels. */
  function clientSection(p) {
    const c = p.clientInfo;
    if (!p.online || !c) return '';
    const mods = c.mods.length ? c.mods.map((m) => `<span class="np-tag">${esc(m)}</span>`).join('') : '<span class="np-loc-sub">none recognised</span>';
    const other = c.other.length ? `<p class="np-loc-sub">Other channels: ${c.other.map(esc).join(', ')}</p>` : '';
    return `<h4 class="np-subhead">Client</h4>
      <div class="np-client-box"><p><b>${esc(c.brand || 'Unknown')}</b>${c.brandRaw && c.brandRaw !== c.brand ? ` <span class="np-loc-sub">(${esc(c.brandRaw)})</span>` : ''}</p>
        <div class="np-chips">${mods}</div>${other}
        <p class="np-loc-sub">Only mods that talk to the server show up here, and the brand is what the game says about itself. Client-only mods (Sodium, shaders, most cheat clients) can't be seen.</p></div>`;
  }

  /** The x-ray hint with the numbers behind it, so an admin can judge it. */
  function miningSection(p) {
    const m = p.mining;
    if (!m) return '';
    const per = (v) => v == null ? '–' : Math.round(v).toLocaleString('en-US');
    const row = (label, ore, base, ratio, median, min, flagged) => {
      if (ore < min) return `<div class="np-mine-row"><span>${label}</span><span class="np-loc-sub">${ore} mined; needs ${min} to judge</span></div>`;
      return `<div class="np-mine-row ${flagged ? 'flagged' : ''}"><span>${label}</span>
        <span><b>${per(ratio)}</b> blocks dug per ore <small>(${num(base)} for ${num(ore)})</small></span>
        <span class="np-loc-sub">${median != null ? `server typical: ${per(median)}` : 'not enough players to compare yet'}</span></div>`;
    };
    const flags = m.flags || [];
    return `<h4 class="np-subhead">Mining check</h4>
      <div class="np-client-box">
        ${flags.length ? `<p class="np-xray-note"><i class="bi bi-exclamation-triangle"></i> Possible x-ray: unusually little stone dug per ${flags.map((f) => f === 'diamonds' ? 'diamond' : 'ancient debris').join(' and ')}. A hint, not proof.</p>`
          : '<p class="np-loc-sub">Nothing unusual.</p>'}
        ${row('Diamonds', m.diamonds, m.stone, m.diamondRatio, m.diamondMedian, m.minDiamonds, flags.includes('diamonds'))}
        ${row('Ancient debris', m.debris, m.netherStone, m.debrisRatio, m.debrisMedian, m.minDebris, flags.includes('debris'))}
        <p class="np-loc-sub">From the game's statistics. Legit players dig through a lot of stone, deepslate or netherrack per ore; x-ray users go almost straight to it. Caves, lucky finds, and bed or TNT mining for debris can also look like this.</p>
      </div>`;
  }

  async function playerAction(uuid, action, text) {
    let error = null;
    try {
      const res = await api('api/players/action', { method: 'POST', body: JSON.stringify({ uuid, action, text }) });
      error = res.error || null;
    } catch (e) {
      error = 'The server did not respond: ' + e.message;
    }
    // A sent message shows up in the conversation, so only errors get a note there.
    playersState.lastAction = action;
    playersState.message = error || (action === 'message' ? '' : { kick: 'Kicked.', ban: 'Banned.', unban: 'Unbanned.' }[action]);
    playersState.messageError = !!error;
    playersState.messageAt = Date.now();
    if (!error && action === 'message' && $('np-player-msg')) $('np-player-msg').value = '';
    loadPlayers();
    setTimeout(renderPlayerDetail, 4100);
  }

  function setupPlayers() {
    $('np-player-search').addEventListener('input', renderPlayerList);
    $('np-player-list').addEventListener('click', (e) => {
      const row = e.target.closest('[data-player]');
      if (!row) return;
      playersState.selected = row.dataset.player;
      playersState.message = '';
      renderPlayerList();
      $('np-player-detail').innerHTML = '<div class="np-empty">Loading…</div>';
      delete $('np-player-detail').dataset.html;
      loadPlayers();
    });
    $('np-player-detail').addEventListener('submit', (e) => {
      if (e.target.id !== 'np-player-actions') return;
      e.preventDefault();
      playerAction(e.target.dataset.uuid, 'message', $('np-player-msg').value);
    });
    $('np-player-detail').addEventListener('click', (e) => {
      const b = e.target.closest('[data-player-act]');
      if (!b) return;
      const form = b.closest('[data-uuid]');
      const name = (playersState.data.detail || {}).name;
      const action = b.dataset.playerAct;
      if (action === 'unban') {
        if (confirm('Unban ' + name + '?')) playerAction(form.dataset.uuid, 'unban', '');
        return;
      }
      const reason = prompt((action === 'ban' ? 'Ban ' : 'Kick ') + name + '? Optional reason:', '');
      if (reason !== null) playerAction(form.dataset.uuid, action, reason);
    });
  }

  // ── Gamerules ──────────────────────────────────────────────────────────

  const rulesState = { data: null, message: '' };

  async function loadGamerules() {
    try {
      rulesState.data = await api('api/gamerules');
      renderGamerules();
    } catch (e) {
      $('np-gamerules').innerHTML = `<div class="np-empty">Could not load gamerules: ${esc(e.message)}</div>`;
    }
  }

  function ruleInput(r, editable) {
    const dis = editable ? '' : 'disabled';
    if (r.type === 'bool') return `<label class="np-switch"><input type="checkbox" data-rule="${esc(r.id)}" ${r.value === 'true' ? 'checked' : ''} ${dis}><span></span></label>`;
    if (r.type === 'number') return `<input type="number" data-rule="${esc(r.id)}" value="${esc(r.value)}" step="1" ${r.min != null ? `min="${r.min}"` : ''} ${r.max != null ? `max="${r.max}"` : ''} ${dis}>`;
    return `<input type="text" data-rule="${esc(r.id)}" value="${esc(r.value)}" ${dis}>`;
  }

  function renderGamerules() {
    const d = rulesState.data;
    if (!d) return;
    const cats = [...new Set(d.rules.map((r) => r.category))];
    const changed = d.rules.filter((r) => r.value !== r.defaultValue).length;
    $('np-gamerules').innerHTML = `<form id="np-rules-form">
      ${d.editable ? '' : '<div class="np-empty">Changing gamerules here is switched off (web_gamerules_edit in the config). Values are read-only.</div>'}
      <p class="np-update-mode">${changed ? `${changed} ${changed === 1 ? 'rule differs' : 'rules differ'} from the default (marked).` : 'All gamerules are at their defaults.'}</p>
      ${cats.map((c) => {
        const rules = d.rules.filter((r) => r.category === c);
        const key = 'rules:' + c;
        return `<details class="np-item np-pack" data-key="${esc(key)}" ${state.open.has(key) ? 'open' : ''}>
          <summary class="np-pack-summary"><span class="np-caret bi bi-chevron-right"></span><b>${esc(c)}</b><small>${rules.length} rules${rules.some((r) => r.value !== r.defaultValue) ? ' · changed' : ''}</small></summary>
          <div class="np-props-grid">${rules.map((r) => `<label class="np-prop ${r.value !== r.defaultValue ? 'changed' : ''}">
            <span class="np-prop-name"><span>${esc(r.name)}</span><small class="np-mono">${esc(r.id)}</small>${r.description ? `<small>${esc(r.description)}</small>` : ''}
              ${r.value !== r.defaultValue ? `<small class="np-prop-pending">Default: ${esc(r.defaultValue)}${d.editable ? ` · <a href="#" data-rule-reset="${esc(r.id)}">reset</a>` : ''}</small>` : ''}</span>
            ${ruleInput(r, d.editable)}</label>`).join('')}</div></details>`;
      }).join('')}
      ${d.editable ? `<div class="np-form-actions"><button type="submit" class="np-btn primary" disabled>Save changes</button>
        <button type="button" class="np-btn" data-rules-undo disabled>Undo</button><span class="np-form-msg">${esc(rulesState.message)}</span></div>` : ''}
    </form>`;
  }

  function ruleChanges(form) {
    const changes = {};
    const byId = Object.fromEntries(rulesState.data.rules.map((r) => [r.id, r]));
    form.querySelectorAll('[data-rule]').forEach((input) => {
      const r = byId[input.dataset.rule];
      const value = input.type === 'checkbox' ? String(input.checked) : input.value.trim();
      if (r && value !== r.value) changes[r.id] = value;
    });
    return changes;
  }

  async function saveRules(changes) {
    const form = $('np-rules-form');
    const msg = form.querySelector('.np-form-msg');
    form.querySelectorAll('.np-form-actions button').forEach((b) => (b.disabled = true));
    msg.textContent = 'Saving…';
    try {
      const res = await api('api/gamerules/save', { method: 'POST', body: JSON.stringify({ changes }) });
      if (res.error) {
        msg.textContent = res.error;
        form.querySelectorAll('.np-form-actions button').forEach((b) => (b.disabled = false));
        return;
      }
      rulesState.data = res;
      rulesState.message = 'Saved; already in effect.';
      renderGamerules();
    } catch (err) {
      msg.textContent = 'The server did not respond: ' + err.message;
      form.querySelectorAll('.np-form-actions button').forEach((b) => (b.disabled = false));
    }
  }

  function setupGamerules() {
    const box = $('np-gamerules');
    box.addEventListener('input', (e) => {
      const form = e.target.closest('#np-rules-form');
      if (!form) return;
      const count = Object.keys(ruleChanges(form)).length;
      form.querySelectorAll('.np-form-actions button').forEach((b) => (b.disabled = !count));
      rulesState.message = '';
      form.querySelector('.np-form-msg').textContent = count ? count + (count === 1 ? ' unsaved change' : ' unsaved changes') : '';
    });
    box.addEventListener('click', (e) => {
      if (e.target.closest('[data-rules-undo]')) {
        rulesState.message = '';
        renderGamerules();
      }
      const reset = e.target.closest('[data-rule-reset]');
      if (reset) {
        e.preventDefault();
        const r = rulesState.data.rules.find((x) => x.id === reset.dataset.ruleReset);
        if (r) saveRules({ [r.id]: r.defaultValue });
      }
    });
    box.addEventListener('submit', (e) => {
      e.preventDefault();
      const changes = ruleChanges(e.target);
      if (Object.keys(changes).length) saveRules(changes);
    });
  }

  // ── Scheduled commands ─────────────────────────────────────────────────

  const schedState = { data: null, editing: null, message: '', timer: null };
  const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

  async function loadSchedule() {
    clearTimeout(schedState.timer);
    try {
      schedState.data = await api('api/schedule');
      renderScheduleList();
      if (schedState.editing === null) renderScheduleEditor();
    } catch (e) {
      $('np-sched-list').innerHTML = `<div class="np-empty">Could not load the schedule: ${esc(e.message)}</div>`;
    }
    const busy = schedState.data && schedState.data.tasks.some((t) => t.progress);
    if (activeTab === 'schedule') schedState.timer = setTimeout(loadSchedule, busy ? 1500 : 5000);
  }

  function delayText(seconds) {
    if (!seconds) return 'right away';
    if (seconds % 3600 === 0) return `${seconds / 3600} h later`;
    if (seconds % 60 === 0) return `${seconds / 60} min later`;
    return `${seconds} s later`;
  }

  function scheduleText(t) {
    const at = esc(t.time);
    switch (t.repeat) {
      case 'after': {
        const before = schedState.data && schedState.data.tasks.find((x) => x.id === t.afterId);
        return before ? `After "${esc(before.name)}", ${delayText(t.afterDelay)}` : 'After a task that no longer exists';
      }
      case 'once': return `Once on ${esc(t.date ? new Date(t.date + 'T00:00').toLocaleDateString([], { dateStyle: 'medium' }) : '?')} at ${at}`;
      case 'interval': return t.every % 60 === 0 ? `Every ${t.every / 60 === 1 ? 'hour' : t.every / 60 + ' hours'}` : `Every ${t.every === 1 ? 'minute' : t.every + ' minutes'}`;
      case 'weekly': {
        const days = [...t.days].sort();
        const text = days.length === 7 ? 'Every day' : days.join(',') === '1,2,3,4,5' ? 'Weekdays' : days.join(',') === '6,7' ? 'Weekends' : days.map((d) => WEEKDAYS[d - 1]).join(', ');
        return `${text} at ${at}`;
      }
      default: return `Every day at ${at}`;
    }
  }

  function countdown(until) {
    const left = Math.max(0, Math.round((until - Date.now()) / 1000));
    const h = Math.floor(left / 3600);
    const m = Math.floor((left % 3600) / 60);
    const sec = String(left % 60).padStart(2, '0');
    return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`;
  }

  // Running tasks count down every second between refreshes.
  setInterval(() => {
    document.querySelectorAll('.np-sched-progress[data-until]').forEach((el) => {
      const b = el.querySelector('b');
      const until = Number(el.dataset.until);
      if (b && until) b.textContent = countdown(until);
    });
  }, 1000);

  function relative(ms) {
    const diff = ms - Date.now();
    const abs = Math.abs(diff);
    const text = abs < 60000 ? 'less than a minute' : abs < 3600000 ? Math.round(abs / 60000) + ' min'
      : abs < 86400000 ? Math.floor(abs / 3600000) + ' h ' + Math.round((abs % 3600000) / 60000) + ' min'
      : Math.floor(abs / 86400000) + (Math.floor(abs / 86400000) === 1 ? ' day' : ' days') + (abs % 86400000 >= 3600000 ? ' ' + Math.floor((abs % 86400000) / 3600000) + ' h' : '');
    return diff >= 0 ? 'in ' + text : text + ' ago';
  }

  /**
   * The task's commands, each once, with the result of the last run next to it: ✓ or ✗ and the
   * command's output, ⏸ for waits, and a dot for steps the last run didn't reach.
   */
  function scheduleSteps(t) {
    const steps = t.commands.filter((c) => c.trim() && !c.trim().startsWith('#'));
    const out = (t.lastOutput || []).slice();
    const strip = (c) => c.trim().replace(/^\//, '');
    let failed = 0;
    const rows = steps.map((c) => {
      const isWait = /^wait\s/i.test(c.trim());
      const shown = isWait ? '⏸ ' + esc(c.trim()) : '/' + esc(strip(c));
      const line = out[0];
      // The output lists the steps in order; take the next line if it belongs to this step.
      const matches = line && (isWait ? line.startsWith('⏸') : (line.startsWith('✓ /') || line.startsWith('✗ /')) && line.slice(3).startsWith(strip(c)));
      if (!matches) return `<div class="np-step idle"><span class="np-step-mark">·</span><span>${shown}</span></div>`;
      out.shift();
      if (isWait) return `<div class="np-step wait"><span class="np-step-mark"></span><span>${shown}</span></div>`;
      const ok = line.startsWith('✓');
      if (!ok) failed++;
      const arrow = line.indexOf(' → ');
      const result = arrow >= 0 ? line.slice(arrow + 3) : '';
      return `<div class="np-step ${ok ? 'ok' : 'fail'}"><span class="np-step-mark">${ok ? '✓' : '✗'}</span><span>${shown}${result ? `<small>${esc(result)}</small>` : ''}</span></div>`;
    });
    // Anything left (e.g. "Stopped: the server shut down") goes at the end.
    out.forEach((line) => rows.push(`<div class="np-step note"><span class="np-step-mark">!</span><span>${esc(line)}</span></div>`));
    const meta = t.progress ? 'Running now'
      : t.lastRun ? `Last run ${esc(dateTime(t.lastRun))} · ${failed ? `<span class="np-warn-text">${failed} failed</span>` : t.lastOk ? 'all worked' : '<span class="np-warn-text">stopped early</span>'}`
      : 'Not run yet';
    return `<h4 class="np-subhead">Commands</h4><p class="np-steps-meta">${meta} · ✓ worked, ✗ failed, · not reached</p><div class="np-steps">${rows.join('')}</div>`;
  }

  function renderScheduleList() {
    const d = schedState.data;
    if (!d) return;
    setTabBadge('schedule', d.tasks.filter((t) => t.enabled).length, false);
    const toolbar = `<div class="np-bloat-actions">
        ${d.editable ? `<button type="button" class="np-btn primary" data-sched="new" ${schedState.editing !== null ? 'disabled' : ''}><i class="bi bi-plus-lg"></i> New task</button>` : ''}
        <span class="np-rec-text">Times use the server's clock (${esc(d.zone)}). Runs missed while the server is off are skipped.</span></div>
      ${d.editable ? '' : '<div class="np-empty">Scheduled commands are switched off in the dashboard (web_schedule in the config); existing tasks still run.</div>'}
      ${schedState.message ? `<div class="np-update-error">${esc(schedState.message)}</div>` : ''}`;
    const rows = d.tasks.map((t) => {
      const key = 'sched:' + t.id;
      const status = t.progress ? `<span class="np-level warn">Running</span>` : !t.enabled ? `<span class="np-level info">Paused</span>`
        : t.nextRun > 0 ? `<span class="np-level good">Scheduled</span>` : t.repeat === 'after' && t.afterId ? `<span class="np-level good">Chained</span>` : `<span class="np-level warn">Not planned</span>`;
      const running = t.progress ? `<span class="np-sched-progress" data-until="${t.progressUntil || 0}">${esc(t.progress)}${t.progressUntil ? ` · next in <b>${esc(countdown(t.progressUntil))}</b>` : ''}</span>` : '';
      const waits = t.commands.filter((c) => /^wait\s/i.test(c)).length;
      const followers = d.tasks.filter((x) => x.repeat === 'after' && x.afterId === t.id);
      const then = followers.length ? `<span class="np-sched-then"><i class="bi bi-arrow-return-right"></i> then ${followers.map((x) => `"${esc(x.name)}" ${delayText(x.afterDelay)}${x.enabled ? '' : ' (paused)'}`).join(', ')}</span>` : '';
      const last = t.lastRun ? `Last run ${esc(relative(t.lastRun))}${t.lastOk ? '' : ' · <span class="np-warn-text">a command failed</span>'}` : 'Never run yet';
      return `<details class="np-item np-sched-task" data-key="${esc(key)}" ${state.open.has(key) ? 'open' : ''}>
        <summary class="np-sched-row">
          ${status}
          <span class="np-error-msg"><b>${esc(t.name)}</b><small>${scheduleText(t)} · ${t.commands.length - waits} ${t.commands.length - waits === 1 ? 'command' : 'commands'}${waits ? ` · ${waits} ${waits === 1 ? 'wait' : 'waits'}` : ''}${t.pause ? ` · ${t.pause} s between commands` : ''}</small>${then}</span>
          <span class="np-sched-when">${running || (t.enabled && t.nextRun > 0 ? `Next ${esc(relative(t.nextRun))}<small>${esc(dateTime(t.nextRun))}</small>` : t.enabled && t.repeat === 'after' ? 'Waits for the other task' : '')}<small>${last}</small></span>
        </summary>
        ${t.note ? `<p class="np-update-reason">${esc(t.note)}</p>` : ''}
        ${scheduleSteps(t)}
        ${d.editable ? `<div class="np-update-buttons">
          <button type="button" class="np-btn" data-sched="run" data-id="${esc(t.id)}"><i class="bi bi-play-fill"></i> Run now</button>
          <button type="button" class="np-btn" data-sched="toggle" data-id="${esc(t.id)}" data-on="${t.enabled ? '0' : '1'}">${t.enabled ? '<i class="bi bi-pause-fill"></i> Pause' : '<i class="bi bi-play"></i> Resume'}</button>
          <button type="button" class="np-btn" data-sched="edit" data-id="${esc(t.id)}" ${schedState.editing !== null ? 'disabled' : ''}><i class="bi bi-pencil"></i> Edit</button>
          <button type="button" class="np-btn danger" data-sched="delete" data-id="${esc(t.id)}"><i class="bi bi-trash"></i> Delete</button>
        </div>` : ''}
      </details>`;
    }).join('');
    $('np-sched-list').innerHTML = toolbar + (rows || (schedState.editing === null ? '<div class="np-empty">No scheduled tasks yet. Create one with "New task", e.g. a nightly restart warning and save.</div>' : ''));
  }

  function renderScheduleEditor() {
    const box = $('np-sched-editor');
    const t = schedState.editing;
    if (!t) {
      box.innerHTML = '';
      return;
    }
    const today = new Date();
    const iso = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`;
    const unitHours = t.repeat === 'interval' && t.every % 60 === 0;
    const delay = t.afterDelay || 0;
    const afterUnit = delay && delay % 3600 === 0 ? 3600 : delay && delay % 60 === 0 ? 60 : delay ? 1 : 60;
    const afterValue = delay / afterUnit;
    box.innerHTML = `<form class="np-sched-form" id="np-sched-form">
      <h4 class="np-subhead">${t.id ? 'Edit task' : 'New task'}</h4>
      <label class="np-sched-field"><span>Name</span><input type="text" name="name" maxlength="80" value="${esc(t.name)}" placeholder="e.g. Nightly restart warning" required></label>
      <label class="np-sched-field"><span>Commands <small>one per line, run in this order. Add a line like <code>wait 30s</code>, <code>wait 5m</code> or <code>wait 1h</code> for a pause; lines starting with # are skipped</small></span>
        <textarea name="commands" rows="7" spellcheck="false" placeholder="say The server restarts in 5 minutes&#10;wait 4m&#10;say The server restarts in 1 minute&#10;wait 1m&#10;save-all&#10;stop">${esc(t.commands.join('\n'))}</textarea></label>
      <div class="np-sched-grid">
        <label class="np-sched-field"><span>Repeat</span><select name="repeat">
          ${[['once', 'Once'], ['daily', 'Every day'], ['weekly', 'On chosen weekdays'], ['interval', 'Every few minutes or hours'], ['after', 'After another task finishes']]
            .map(([v, l]) => `<option value="${v}" ${t.repeat === v ? 'selected' : ''}>${l}</option>`).join('')}</select></label>
        <label class="np-sched-field" data-show="once"><span>Date</span><input type="date" name="date" value="${esc(t.date || iso)}"></label>
        <label class="np-sched-field" data-show="once daily weekly"><span>Time</span><input type="time" name="time" value="${esc(t.time || '04:00')}"></label>
        <label class="np-sched-field"><span>Pause between commands <small>seconds, 0 = none</small></span><input type="number" name="pause" min="0" max="3600" value="${t.pause || 0}"></label>
        <label class="np-sched-field" data-show="after"><span>Runs after</span><select name="afterId">
          ${(schedState.data ? schedState.data.tasks : []).filter((x) => x.id !== t.id).map((x) => `<option value="${esc(x.id)}" ${t.afterId === x.id ? 'selected' : ''}>${esc(x.name)}</option>`).join('') || '<option value="">Create another task first</option>'}</select></label>
        <label class="np-sched-field" data-show="after"><span>Then wait</span><span class="np-sched-every">
          <input type="number" name="afterDelay" min="0" max="10080" value="${afterValue}">
          <select name="afterUnit">${[[1, 'seconds'], [60, 'minutes'], [3600, 'hours']].map(([v, l]) => `<option value="${v}" ${afterUnit === v ? 'selected' : ''}>${l}</option>`).join('')}</select></span></label>
        <label class="np-sched-field" data-show="interval"><span>Every</span><span class="np-sched-every">
          <input type="number" name="every" min="1" max="744" value="${unitHours ? t.every / 60 : t.every}">
          <select name="unit"><option value="1" ${unitHours ? '' : 'selected'}>minutes</option><option value="60" ${unitHours ? 'selected' : ''}>hours</option></select></span></label>
      </div>
      <div class="np-sched-days" data-show="weekly">${WEEKDAYS.map((name, i) => `<label class="np-day"><input type="checkbox" name="day" value="${i + 1}" ${t.days.includes(i + 1) ? 'checked' : ''}><span>${name}</span></label>`).join('')}</div>
      <label class="np-check"><input type="checkbox" name="enabled" ${t.enabled ? 'checked' : ''}> Active</label>
      <div class="np-form-actions"><button type="submit" class="np-btn primary">Save task</button>
        <button type="button" class="np-btn" data-sched="cancel">Cancel</button><span class="np-form-msg" id="np-sched-msg"></span></div>
    </form>`;
    updateScheduleFields();
  }

  function updateScheduleFields() {
    const form = $('np-sched-form');
    if (!form) return;
    const repeat = form.elements.repeat.value;
    form.querySelectorAll('[data-show]').forEach((el) => { el.hidden = !el.dataset.show.split(' ').includes(repeat); });
  }

  async function schedPost(path, body) {
    try {
      const res = await api(path, { method: 'POST', body: JSON.stringify(body) });
      if (res.tasks) schedState.data = res;
      return res.error || null;
    } catch (e) {
      return 'The server did not respond: ' + e.message;
    }
  }

  function setupSchedule() {
    const card = $('np-schedule-card');
    card.addEventListener('change', (e) => {
      if (e.target.name === 'repeat') updateScheduleFields();
    });
    card.addEventListener('click', async (e) => {
      const b = e.target.closest('[data-sched]');
      if (!b) return;
      const action = b.dataset.sched;
      const task = schedState.data && schedState.data.tasks.find((t) => t.id === b.dataset.id);
      if (action === 'new' || action === 'edit') {
        schedState.editing = action === 'new'
          ? { id: null, name: '', commands: [], repeat: 'daily', time: '04:00', date: '', days: [1, 2, 3, 4, 5], every: 60, pause: 0, afterId: null, afterDelay: 300, enabled: true }
          : JSON.parse(JSON.stringify(task));
        renderScheduleEditor();
        renderScheduleList();
        $('np-sched-editor').scrollIntoView({ block: 'nearest' });
        return;
      }
      if (action === 'cancel') {
        schedState.editing = null;
        renderScheduleEditor();
        renderScheduleList();
        return;
      }
      if (action === 'delete' && !confirm(`Delete the task "${task.name}"?`)) return;
      const path = { run: 'api/schedule/run', toggle: 'api/schedule/enable', delete: 'api/schedule/delete' }[action];
      const body = action === 'toggle' ? { id: task.id, on: b.dataset.on === '1' } : { id: task.id };
      schedState.message = (await schedPost(path, body)) || (action === 'run' ? `"${task.name}" is running; its output appears below in a moment.` : '');
      renderScheduleList();
      if (action === 'run') setTimeout(loadSchedule, 1200);
    });
    card.addEventListener('submit', async (e) => {
      e.preventDefault();
      const form = e.target;
      const f = form.elements;
      const days = [...form.querySelectorAll('input[name=day]:checked')].map((c) => Number(c.value));
      const task = {
        id: schedState.editing.id, name: f.name.value, enabled: f.enabled.checked,
        commands: f.commands.value.split('\n').map((c) => c.trim()).filter(Boolean),
        repeat: f.repeat.value, time: f.time.value, date: f.date.value, days,
        every: Math.round(Number(f.every.value) * Number(f.unit.value)),
        pause: Math.max(0, Math.round(Number(f.pause.value) || 0)),
        afterId: f.afterId ? f.afterId.value || null : null,
        afterDelay: Math.max(0, Math.round((Number(f.afterDelay.value) || 0) * Number(f.afterUnit.value))),
      };
      $('np-sched-msg').textContent = 'Saving…';
      const error = await schedPost('api/schedule/save', { task });
      if (error) {
        $('np-sched-msg').textContent = error;
        return;
      }
      schedState.editing = null;
      schedState.message = '';
      renderScheduleEditor();
      renderScheduleList();
    });
  }

  // ── server.properties ──────────────────────────────────────────────────

  const propsState = { data: null, message: '' };

  async function loadProperties() {
    try {
      propsState.data = await api('api/properties');
      renderProperties();
    } catch (e) {
      $('np-properties').innerHTML = `<div class="np-empty">Could not load server.properties: ${esc(e.message)}</div>`;
    }
  }

  function propertyInput(f, editable) {
    const dis = editable ? '' : 'disabled';
    const name = esc(f.key);
    if (f.secret) {
      return `<input type="password" data-prop="${name}" data-secret placeholder="${f.set ? '•••••• (set) – type to replace' : 'not set'}" autocomplete="new-password" ${dis}>`;
    }
    switch (f.type) {
      case 'bool':
        return `<label class="np-switch"><input type="checkbox" data-prop="${name}" ${f.value === 'true' ? 'checked' : ''} ${dis}><span></span></label>`;
      case 'number':
        return `<input type="number" data-prop="${name}" value="${esc(f.value)}" min="${f.min}" max="${f.max}" step="1" ${dis}>`;
      case 'choice':
        return `<select data-prop="${name}" ${dis}>${f.options.map((o) => `<option value="${esc(o)}" ${o === f.value ? 'selected' : ''}>${esc(o)}</option>`).join('')}</select>`;
      default:
        return `<input type="text" data-prop="${name}" value="${esc(f.value)}" maxlength="4096" ${dis}>`;
    }
  }

  function renderProperties() {
    const d = propsState.data;
    if (!d) return;
    if (!d.dedicated) {
      $('np-properties').innerHTML = '<div class="np-empty">Singleplayer worlds have no server.properties.</div>';
      return;
    }
    const pending = d.pending || {};
    const groups = d.groups.map((g) => {
      const fields = d.fields.filter((f) => f.group === g);
      if (!fields.length) return '';
      const key = 'props:' + g;
      return `<details class="np-item np-pack" data-key="${esc(key)}" ${state.open.has(key) ? 'open' : ''}>
        <summary class="np-pack-summary"><span class="np-caret bi bi-chevron-right"></span><b>${esc(g)}</b><small>${fields.length} settings</small></summary>
        <div class="np-props-grid">${fields.map((f) => `<label class="np-prop">
          <span class="np-prop-name"><span class="np-mono">${esc(f.key)}</span>${f.description ? `<small>${esc(f.description)}</small>` : ''}
            ${pending[f.key] !== undefined ? `<small class="np-prop-pending">The updater sets this to ${esc(pending[f.key])} at the next restart.</small>` : ''}</span>
          ${propertyInput(f, d.editable)}</label>`).join('')}</div>
      </details>`;
    }).join('');
    $('np-properties').innerHTML = `<form id="np-props-form">
        ${d.editable ? '' : '<div class="np-empty">Changing server.properties here is switched off (web_properties_edit in the config). Values are read-only.</div>'}
        ${groups}
        ${d.editable ? `<div class="np-form-actions"><button type="submit" class="np-btn primary" disabled>Save changes</button>
          <button type="button" class="np-btn" data-props-reset disabled>Undo</button><span class="np-form-msg">${esc(propsState.message)}</span></div>` : ''}
      </form>`;
  }

  function propertyChanges(form) {
    const changes = {};
    const byKey = Object.fromEntries(propsState.data.fields.map((f) => [f.key, f]));
    form.querySelectorAll('[data-prop]').forEach((input) => {
      const f = byKey[input.dataset.prop];
      if (!f) return;
      if (input.dataset.secret !== undefined) {
        if (input.value !== '') changes[f.key] = input.value;
        return;
      }
      const value = input.type === 'checkbox' ? String(input.checked) : input.value;
      if (value !== f.value) changes[f.key] = value;
    });
    return changes;
  }

  function setupProperties() {
    const box = $('np-properties');
    box.addEventListener('input', (e) => {
      const form = e.target.closest('#np-props-form');
      if (!form) return;
      const count = Object.keys(propertyChanges(form)).length;
      form.querySelectorAll('.np-form-actions button').forEach((b) => (b.disabled = !count));
      propsState.message = '';
      form.querySelector('.np-form-msg').textContent = count ? count + (count === 1 ? ' unsaved change' : ' unsaved changes') : '';
    });
    box.addEventListener('click', (e) => {
      if (e.target.closest('[data-props-reset]')) {
        propsState.message = '';
        renderProperties();
      }
    });
    box.addEventListener('submit', async (e) => {
      e.preventDefault();
      const form = e.target;
      const changes = propertyChanges(form);
      if (!Object.keys(changes).length) return;
      const msg = form.querySelector('.np-form-msg');
      form.querySelectorAll('.np-form-actions button').forEach((b) => (b.disabled = true));
      msg.textContent = 'Saving…';
      try {
        const res = await api('api/properties/save', { method: 'POST', body: JSON.stringify({ changes }) });
        if (res.error) {
          msg.textContent = res.error;
          form.querySelectorAll('.np-form-actions button').forEach((b) => (b.disabled = false));
          return;
        }
        propsState.data = res;
        propsState.message = 'Saved. Most changes take effect at the next restart.';
        renderProperties();
      } catch (err) {
        msg.textContent = 'The server did not respond: ' + err.message;
        form.querySelectorAll('.np-form-actions button').forEach((b) => (b.disabled = false));
      }
    });
  }

  // ── Data pack settings ─────────────────────────────────────────────────

  const packSettings = { dialogs: [], tree: [], editable: true, loaded: false, path: [], search: '', highlight: null, expanded: new Set() };
  try { packSettings.path = JSON.parse(localStorage.getItem('np-ps-path') || '[]'); } catch (e) { /* start at the top */ }
  packSettings.path.forEach((_, i) => packSettings.expanded.add(packSettings.path.slice(0, i + 1).join('/')));

  async function loadPackSettings() {
    try {
      const data = await api('api/packsettings');
      packSettings.dialogs = data.dialogs || [];
      packSettings.tree = data.tree || [];
      packSettings.editable = data.editable;
      packSettings.loaded = true;
      renderPackSettings();
    } catch (e) {
      $('np-packsettings').innerHTML = `<div class="np-empty">Could not load the settings: ${esc(e.message)}</div>`;
    }
  }

  function settingInput(d, s) {
    const name = `${esc(d.id)}|${esc(s.key)}`;
    const disabled = packSettings.editable ? '' : 'disabled';
    switch (s.type) {
      case 'single_option':
        return `<select data-setting="${name}" ${disabled}>${s.options.map((o) => `<option value="${esc(o.id)}" ${o.id === s.value ? 'selected' : ''}>${esc(o.label)}</option>`).join('')}</select>`;
      case 'boolean':
        return `<label class="np-switch"><input type="checkbox" data-setting="${name}" data-on="${esc(s.onTrue)}" data-off="${esc(s.onFalse)}" ${s.value === s.onTrue ? 'checked' : ''} ${disabled}><span></span></label>`;
      case 'number_range':
        return `<span class="np-range"><input type="range" min="${s.min}" max="${s.max}" step="${s.step || 1}" value="${esc(s.value)}" data-mirror="${name}" ${disabled}>
          <input type="number" min="${s.min}" max="${s.max}" step="${s.step || 1}" value="${esc(s.value)}" data-setting="${name}" ${disabled}></span>`;
      default:
        return `<input type="text" value="${esc(s.value)}" data-setting="${name}" maxlength="${s.maxLength || 256}" ${disabled}>`;
    }
  }

  /** Projects with their menus, keeping only forms that have stored settings. */
  function settingsRoots() {
    const byId = Object.fromEntries(packSettings.dialogs.map((d) => [d.id, d]));
    const prune = (nodes) => nodes.map((n) => {
      if (n.dialog) return byId[n.dialog] ? n : null;
      const children = prune(n.children || []);
      return children.length ? Object.assign({}, n, { children }) : null;
    }).filter(Boolean);
    return packSettings.tree.map((t) => ({ label: t.pack.replace(/[-_]+/g, ' '), children: prune(t.children) })).filter((t) => t.children.length);
  }

  function nodeAt(roots, path) {
    let nodes = roots;
    let node = null;
    const trail = [];
    for (const i of path) {
      node = nodes[i];
      if (!node) return { node: null, trail: [] };
      trail.push(node);
      nodes = node.children || [];
    }
    return { node, trail };
  }

  function countSettings(node, byId) {
    if (node.dialog) return byId[node.dialog] ? byId[node.dialog].settings.length : 0;
    return (node.children || []).reduce((n, c) => n + countSettings(c, byId), 0);
  }

  function settingsForm(d) {
    return `<form class="np-settings-form" data-dialog="${esc(d.id)}">
      <div class="np-form-grid">${d.settings.map((x) => `<label class="np-form-row ${packSettings.highlight === x.key ? 'np-flash' : ''}" data-setting-row="${esc(x.key)}"><span>${esc(x.label)}</span>${settingInput(d, x)}</label>`).join('')}</div>
      ${packSettings.editable ? `<div class="np-form-actions"><button type="submit" class="np-btn primary" disabled>Save changes</button>
        <button type="button" class="np-btn" data-reset disabled>Undo</button><span class="np-form-msg"></span></div>` : ''}
    </form>`;
  }

  function navTree(nodes, path, current) {
    return `<ul class="np-ps-tree">${nodes.map((n, i) => {
      const p = path.concat(i);
      const key = p.join('/');
      const selected = current.join('/') === key;
      const branch = !n.dialog;
      const open = branch && packSettings.expanded.has(key);
      return `<li><div class="np-ps-row">${branch
          ? `<button type="button" class="np-ps-toggle" data-ps-toggle="${key}" aria-label="${open ? 'Close' : 'Open'} ${esc(n.label)}" aria-expanded="${open}"><i class="bi ${open ? 'bi-chevron-down' : 'bi-chevron-right'}"></i></button>`
          : '<span class="np-ps-leaf"><i class="bi bi-sliders2"></i></span>'}
          <button type="button" class="np-ps-node ${selected ? 'active' : ''} ${branch ? 'branch' : ''}" data-ps-path="${key}"><span>${esc(n.label)}</span></button></div>
        ${open ? navTree(n.children, p, current) : ''}</li>`;
    }).join('')}</ul>`;
  }

  function renderPackSettings() {
    const box = $('np-packsettings');
    const roots = settingsRoots();
    if (!roots.length) {
      box.innerHTML = '<div class="np-empty">No data pack with an in-game settings menu (Explorer\'s Eden style) is installed, or it hasn\'t stored its settings yet.</div>';
      return;
    }
    const byId = Object.fromEntries(packSettings.dialogs.map((d) => [d.id, d]));
    let { node, trail } = nodeAt(roots, packSettings.path);
    if (!node && packSettings.path.length) {
      packSettings.path = [];
      ({ node, trail } = nodeAt(roots, []));
    }
    const crumbs = [`<button type="button" class="np-crumb" data-ps-path="">All projects</button>`].concat(trail.map((n, i) =>
      `<button type="button" class="np-crumb" data-ps-path="${packSettings.path.slice(0, i + 1).join('/')}">${esc(n.label)}</button>`)).join('<i class="bi bi-chevron-right"></i>');

    let main;
    if (packSettings.search.trim()) {
      main = searchResults(roots, byId);
    } else if (node && node.dialog) {
      main = `${node.description ? `<p class="np-card-intro">${esc(node.description)}</p>` : ''}${settingsForm(byId[node.dialog])}`;
    } else {
      const children = node ? node.children : roots;
      const base = node ? packSettings.path : [];
      main = `${node && node.description ? `<p class="np-card-intro">${esc(node.description)}</p>` : ''}<div class="np-ps-tiles">${children.map((c, i) => {
        const count = countSettings(c, byId);
        return `<button type="button" class="np-ps-tile" data-ps-path="${base.concat(i).join('/')}" ${c.description ? `title="${esc(c.description)}"` : ''}>
          <i class="bi ${c.dialog ? 'bi-sliders2' : node ? 'bi-folder2' : 'bi-box-seam'}"></i>
          <span><b>${esc(c.label)}</b><small>${c.dialog ? count + (count === 1 ? ' setting' : ' settings') : c.children.length + (c.children.length === 1 ? ' entry' : ' entries') + ' · ' + count + ' settings'}</small></span>
          <i class="bi bi-chevron-right np-ps-go"></i></button>`;
      }).join('')}</div>`;
    }

    box.innerHTML = `${packSettings.editable ? '' : '<div class="np-empty">Changing settings here is switched off (web_settings_edit in the config). Values are read-only.</div>'}
      <label class="np-inspector-search np-ps-search"><i class="bi bi-search"></i><input type="search" id="np-ps-search" placeholder="Search all settings" value="${esc(packSettings.search)}" autocomplete="off"></label>
      <div class="np-ps">
        <nav class="np-ps-nav">${navTree(roots, [], packSettings.path)}</nav>
        <div class="np-ps-main"><div class="np-crumbs">${crumbs}</div>${main}</div>
      </div>`;
    if (packSettings.highlight) {
      const row = box.querySelector('.np-flash');
      if (row) row.scrollIntoView({ block: 'center' });
      packSettings.highlight = null;
    }
  }

  function searchResults(roots, byId) {
    const terms = packSettings.search.trim().toLowerCase().split(/\s+/);
    const results = [];
    const walk = (nodes, path, labels) => nodes.forEach((n, i) => {
      const p = path.concat(i);
      const names = labels.concat(n.label);
      if (n.dialog) {
        const d = byId[n.dialog];
        const pathText = names.join(' ').toLowerCase();
        d.settings.forEach((x) => {
          const haystack = x.label.toLowerCase() + ' ' + pathText;
          if (terms.every((t) => haystack.includes(t))) results.push({ p, names, setting: x });
        });
      } else {
        walk(n.children, p, names);
      }
    });
    walk(roots, [], []);
    if (!results.length) return '<div class="np-empty">Nothing matches.</div>';
    const shown = results.slice(0, 80);
    return `<p class="np-update-mode">${results.length} ${results.length === 1 ? 'setting' : 'settings'} found${results.length > shown.length ? ', showing the first ' + shown.length : ''}.</p>
      <div class="np-ps-results">${shown.map((r) => `<button type="button" class="np-ps-result" data-ps-path="${r.p.join('/')}" data-ps-highlight="${esc(r.setting.key)}">
        <span><b>${esc(r.setting.label)}</b><small>${r.names.map(esc).join(' › ')}</small></span><span class="np-ps-value">${esc(r.setting.value)}</span></button>`).join('')}</div>`;
  }

  function formValues(form) {
    const values = {};
    form.querySelectorAll('[data-setting]').forEach((input) => {
      const key = input.dataset.setting.split('|').pop();
      values[key] = input.type === 'checkbox' ? (input.checked ? input.dataset.on : input.dataset.off) : input.value;
    });
    return values;
  }

  function setupPackSettings() {
    const box = $('np-packsettings');
    box.addEventListener('click', (e) => {
      const toggle = e.target.closest('[data-ps-toggle]');
      if (toggle) {
        const key = toggle.dataset.psToggle;
        if (packSettings.expanded.has(key)) packSettings.expanded.delete(key); else packSettings.expanded.add(key);
        renderPackSettings();
        return;
      }
      const target = e.target.closest('[data-ps-path]');
      if (!target) return;
      // Clicking the open folder you're already in closes it again.
      if (target.classList.contains('np-ps-node') && target.classList.contains('active') && target.classList.contains('branch')
          && packSettings.expanded.has(target.dataset.psPath)) {
        packSettings.expanded.delete(target.dataset.psPath);
        renderPackSettings();
        return;
      }
      const dirty = box.querySelector('.np-settings-form .np-form-msg');
      if (dirty && dirty.textContent === 'Unsaved changes' && !confirm('Leave without saving your changes?')) return;
      const value = target.dataset.psPath;
      packSettings.path = value === '' ? [] : value.split('/').map(Number);
      // Open every folder on the way to the selection.
      packSettings.path.forEach((_, i) => packSettings.expanded.add(packSettings.path.slice(0, i + 1).join('/')));
      packSettings.highlight = target.dataset.psHighlight || null;
      if (target.dataset.psHighlight || target.classList.contains('np-crumb') || target.classList.contains('np-ps-node')) packSettings.search = '';
      try { localStorage.setItem('np-ps-path', JSON.stringify(packSettings.path)); } catch (err) { /* not remembered */ }
      renderPackSettings();
      const main = box.querySelector('.np-ps-main');
      if (main && !packSettings.highlight && main.getBoundingClientRect().top < 0) main.scrollIntoView({ block: 'start' });
    });
    box.addEventListener('input', (e) => {
      if (e.target.id === 'np-ps-search') {
        packSettings.search = e.target.value;
        const pos = e.target.selectionStart;
        renderPackSettings();
        const input = $('np-ps-search');
        input.focus();
        input.setSelectionRange(pos, pos);
        return;
      }
      const form = e.target.closest('form');
      if (!form) return;
      if (e.target.dataset.mirror) {
        form.querySelector(`[data-setting="${CSS.escape(e.target.dataset.mirror)}"]`).value = e.target.value;
      } else if (e.target.type === 'number') {
        const slider = form.querySelector(`[data-mirror="${CSS.escape(e.target.dataset.setting)}"]`);
        if (slider) slider.value = e.target.value;
      }
      form.querySelectorAll('button').forEach((b) => (b.disabled = false));
      form.querySelector('.np-form-msg').textContent = 'Unsaved changes';
    });
    box.addEventListener('click', (e) => {
      if (e.target.closest('[data-reset]')) {
        renderPackSettings();
      }
    });
    box.addEventListener('submit', async (e) => {
      e.preventDefault();
      const form = e.target;
      const msg = form.querySelector('.np-form-msg');
      form.querySelectorAll('button').forEach((b) => (b.disabled = true));
      msg.textContent = 'Saving…';
      try {
        const res = await api('api/packsettings/apply', { method: 'POST', body: JSON.stringify({ dialog: form.dataset.dialog, values: formValues(form) }) });
        if (res.error) {
          msg.textContent = res.error;
          form.querySelectorAll('button').forEach((b) => (b.disabled = false));
          return;
        }
        packSettings.dialogs = res.dialogs || packSettings.dialogs;
        renderPackSettings();
        const again = box.querySelector(`form[data-dialog="${CSS.escape(form.dataset.dialog)}"] .np-form-msg`);
        if (again) again.textContent = 'Saved.';
      } catch (err) {
        msg.textContent = 'The server did not respond: ' + err.message;
        form.querySelectorAll('button').forEach((b) => (b.disabled = false));
      }
    });
  }

  // ── Bloat check ────────────────────────────────────────────────────────

  function renderBloat(r) {
    if (!r) {
      $('np-bloat-status').textContent = 'Not run yet.';
      $('np-bloat').innerHTML = '';
      return;
    }
    $('np-bloat-status').textContent = 'Last run ' + dateTime(r.time);
    const list = (title, items, fmt) => `<div class="np-wg-list"><h4>${title}</h4>${items.length ? `<table class="np-table"><tbody>${items.map((i) =>
      `<tr><td class="name"><span class="np-mono">${esc(i.name)}</span>${i.source ? `<small>${esc(i.source)}</small>` : ''}</td><td class="num">${fmt(i.value)}</td></tr>`).join('')}</tbody></table>` : '<div class="np-empty">None.</div>'}</div>`;
    $('np-bloat').innerHTML = `<div class="np-wg-summary">
        <span><b>${num(r.scoreEntries)}</b> score entries in ${num(r.objectives)} objectives</span>
        <span><b>${bytes(r.storageBytes)}</b> command storage</span>
        <span><b>${num(r.distinctTags)}</b> different entity tags</span>
        <span><b>${bytes(r.worldBytes)}</b> world folder</span></div>
      <div class="np-wg-grid">
        ${list('Scoreboard objectives', r.topObjectives, (v) => num(v) + ' entries')}
        ${list('Command storage', r.storage, bytes)}
        ${list('Entity tags', r.topTags, (v) => num(v) + ' entities')}
        ${list('World folder', r.folders, bytes)}
        ${list('Largest files', r.largestFiles, bytes)}
      </div>`;
  }

  /** Starts the check and follows it until it's done; big worlds can take a while. */
  async function runBloat() {
    const button = $('np-bloat-run');
    button.disabled = true;
    try {
      let state = await api('api/bloat/run', { method: 'POST' });
      while (state.running) {
        $('np-bloat-status').textContent = `Checking… ${num(state.scannedFiles)} files looked at so far.`;
        await new Promise((r) => setTimeout(r, 1000));
        state = await api('api/bloat');
      }
      if (state.error) {
        $('np-bloat-status').textContent = 'The check failed: ' + state.error;
      } else {
        renderBloat(state.result);
        pollWindow();
      }
    } catch (e) {
      $('np-bloat-status').textContent = 'The server did not respond: ' + e.message;
    }
    button.disabled = false;
  }

  // ── Recording ──────────────────────────────────────────────────────────

  function renderRecording(status) {
    state.recording = status;
    const box = $('np-recording');
    if (status && status.active) {
      const left = Math.max(0, status.endsAt - Date.now());
      const who = status.automatic ? `automatically: ${esc(status.reason || 'lag')}` : `started by ${esc(status.startedBy)}`;
      box.innerHTML = `<span class="np-rec-dot"></span><span class="np-rec-text">Recording for ${duration(status.seconds * 1000)} (${who}), stops on its own in ${duration(left)}.</span>
        <button type="button" class="np-btn danger" id="np-rec-stop"><i class="bi bi-stop-fill"></i> Stop and build report</button>`;
    } else if (!box.querySelector('select')) {
      box.innerHTML = `<select id="np-rec-minutes" aria-label="Recording length">
          <option value="10">10 minutes</option><option value="30">30 minutes</option><option value="60" selected>1 hour</option>
          <option value="180">3 hours</option><option value="360">6 hours</option><option value="720">12 hours</option><option value="1440">24 hours</option>
        </select>
        <button type="button" class="np-btn primary" id="np-rec-start"><i class="bi bi-record-fill"></i> Start recording</button>`;
    }
  }

  function renderComparePickers(reports) {
    const options = `<option value="live">Live (last 15 min)</option>` + reports.map((r) => `<option value="${esc(r.name)}">${esc(r.name.replace('nice-control-center-', '').replace('.html', ''))}${r.automatic ? ' (lag)' : ''}</option>`).join('');
    ['np-compare-a', 'np-compare-b'].forEach((id, i) => {
      const select = $(id);
      const previous = select.value;
      select.innerHTML = options;
      if (previous && [...select.options].some((o) => o.value === previous)) select.value = previous;
      else if (i === 0 && reports.length) select.value = reports[0].name;
    });
  }

  async function runCompare() {
    const a = $('np-compare-a').value;
    const b = $('np-compare-b').value;
    const box = $('np-compare-result');
    box.innerHTML = '<div class="np-empty">Comparing…</div>';
    try {
      const r = await api(`api/compare?a=${encodeURIComponent(a)}&b=${encodeURIComponent(b)}`);
      if (r.error) { box.innerHTML = `<div class="np-empty">${esc(r.error)}</div>`; return; }
      const change = (d, lowerIsBetter = true, unit = ' ms') => {
        const better = lowerIsBetter ? d < 0 : d > 0;
        const cls = Math.abs(d) < 0.01 ? '' : better ? 'good' : 'poor';
        return `<span class="np-delta ${cls}">${d > 0 ? '+' : ''}${Math.abs(d) >= 10 ? fixed(d, 0) : fixed(d, 2)}${unit}</span>`;
      };
      const head = r.headline.map((h) => `<span class="np-compare-kpi"><small>${esc(h.label)}</small>${fixed(h.a, h.a >= 100 ? 0 : 1)} → <b>${fixed(h.b, h.b >= 100 ? 0 : 1)}</b> ${change(h.b - h.a, h.lowerIsBetter, h.unit ? ' ' + h.unit : '')}</span>`).join('');
      const sections = Object.entries(r.sections).filter(([, rows]) => rows.length).map(([title, rows]) => `<div class="np-wg-list"><h4>${esc(title)}</h4>
        <table class="np-table"><thead><tr><th></th><th class="num">${esc(r.aLabel)}</th><th class="num">${esc(r.bLabel)}</th><th class="num">Change</th></tr></thead><tbody>
        ${rows.map((d) => `<tr><td class="name">${esc(d.label)}</td><td class="num">${ms(d.a)}</td><td class="num">${ms(d.b)}</td><td class="num">${change(d.change)}</td></tr>`).join('')}
        </tbody></table></div>`).join('');
      box.innerHTML = `<div class="np-compare-head">${head}</div><div class="np-wg-grid">${sections || '<div class="np-empty">No differences.</div>'}</div>`;
    } catch (e) {
      box.innerHTML = `<div class="np-empty">The server did not respond: ${esc(e.message)}</div>`;
    }
  }

  function renderReports(reports) {
    state.reports = reports;
    renderComparePickers(reports);
    $('np-reports').innerHTML = reports.length
      ? reports.map((r) => `<div class="np-report"><span><i class="bi bi-file-earmark-bar-graph"></i> ${esc(r.name)}${r.automatic ? ' <span class="np-chip gold">Automatic: lag</span>' : ''}<small>${dateTime(r.modified)} · ${bytes(r.size)}</small></span>
        <span class="np-report-actions"><a class="np-btn" href="reports/${encodeURIComponent(r.name)}" target="_blank" rel="noopener">Open</a>
        <a class="np-btn" href="reports/${encodeURIComponent(r.name)}?download=1">Download</a></span></div>`).join('')
      : '<div class="np-empty">No reports yet.</div>';
  }

  async function loadReports() {
    try {
      const data = await api('api/recording');
      renderRecording(data.status);
      renderReports(data.reports);
    } catch (e) { /* shown by the live indicator */ }
  }

  async function recordingAction(path) {
    try {
      const data = await api(path, { method: 'POST' });
      if (data.error) alert(data.error);
    } catch (e) {
      alert('The server did not respond: ' + e.message);
    }
    $('np-recording').innerHTML = '';
    loadReports();
    setTimeout(loadReports, 3000);
  }

  // ── In-game clock (Nice Actions calendar, or the vanilla day counter) ──

  /** In-game clock: the server sends the exact minute once a second; this counts on smoothly in between. */
  const clockState = { data: null, base: 0, at: 0, rate: 0, shown: '', history: [] };

  function renderClock(c) {
    const box = $('np-clock');
    if (!c) {
      box.hidden = true;
      clockState.data = null;
      return;
    }
    const now = performance.now();
    if (c.minuteOfDay != null) {
      // Game minutes per real millisecond over the last ~10 seconds (0 when time is frozen).
      const h = clockState.history;
      const last = h[h.length - 1];
      let delta = last ? c.minuteOfDay - (last.m % 1440) : 0;
      if (delta < -720) delta += 1440;
      if (delta < 0 || delta > 120) h.length = 0;
      h.push({ t: now, m: (h.length ? h[h.length - 1].m + delta : c.minuteOfDay) });
      while (h.length > 2 && now - h[0].t > 10000) h.shift();
      const first = h[0];
      const newest = h[h.length - 1];
      clockState.rate = newest.t - first.t > 2500 ? (newest.m - first.m) / (newest.t - first.t) : clockState.rate;
    }
    clockState.data = c;
    clockState.base = c.minuteOfDay;
    clockState.at = now;
    const icon = c.weather === 'thunder' ? 'bi-cloud-lightning-rain' : c.weather === 'rain' ? 'bi-cloud-rain' : c.day ? 'bi-sun' : 'bi-moon-stars';
    const weekday = c.weekday ? `${esc(c.weekday.slice(0, 3))}, ` : '';
    box.hidden = false;
    const key = icon + weekday + c.date;
    if (box.dataset.key !== key) {
      box.dataset.key = key;
      box.innerHTML = `<i class="bi ${icon}"></i><b class="np-clock-time"></b><span class="np-clock-date">${weekday}${esc(c.date)}</span>`;
      clockState.shown = '';
    }
    box.title = [c.weekday, c.date, c.detail, c.source === 'nice_actions' ? 'From Nice Actions' : ''].filter(Boolean).join(' · ');
    tickClock();
  }

  function clockText(minute, twelveHour) {
    const m = Math.floor(((minute % 1440) + 1440) % 1440);
    const h = Math.floor(m / 60);
    const mm = String(m % 60).padStart(2, '0');
    return twelveHour ? `${h % 12 === 0 ? 12 : h % 12}:${mm} ${h < 12 ? 'AM' : 'PM'}` : `${String(h).padStart(2, '0')}:${mm}`;
  }

  function tickClock() {
    const c = clockState.data;
    if (!c) return;
    const el = document.querySelector('#np-clock .np-clock-time');
    if (!el) return;
    // Never run more than a minute ahead of the last reading.
    const ahead = Math.min(2, clockState.rate * (performance.now() - clockState.at));
    let value = clockState.base + ahead;
    // Don't tick backwards by a minute when a reading lands just behind the estimate.
    const back = ((clockState.lastValue ?? value) - value + 1440) % 1440;
    if (back > 0 && back < 3) value = clockState.lastValue;
    clockState.lastValue = value;
    const text = c.minuteOfDay == null ? c.time : clockText(value, c.twelveHour);
    if (text !== clockState.shown) {
      clockState.shown = text;
      el.textContent = text;
    }
  }
  if (!REPORT) setInterval(tickClock, 100);

  // ── Monitor on/off ─────────────────────────────────────────────────────

  function renderMonitoring(on) {
    state.monitoring = on;
    const button = $('np-monitor-toggle');
    button.hidden = false;
    button.dataset.monitor = on ? '0' : '1';
    button.title = on ? 'Stop measuring (no overhead while off)' : 'Start measuring again';
    button.innerHTML = on ? '<i class="bi bi-pause-fill"></i><span>Pause monitoring</span>' : '<i class="bi bi-play-fill"></i><span>Resume monitoring</span>';
    $('np-notice').hidden = on;
  }

  async function setMonitoring(on) {
    try {
      const data = await api('api/monitor?on=' + (on ? 1 : 0), { method: 'POST' });
      renderMonitoring(data.monitoring);
      renderClock(data.clock);
    } catch (e) {
      alert('The server did not respond: ' + e.message);
    }
  }

  // ── Polling ────────────────────────────────────────────────────────────

  function setLive(mode, text) {
    const live = $('np-live');
    live.className = 'np-live ' + mode;
    $('np-live-text').textContent = text;
  }

  function renderMeta(server, monitorSince, lagSince) {
    const tags = [
      lagSince ? `<span class="np-tag np-tag--lag" title="A lag report is being recorded"><i class="bi bi-exclamation-triangle"></i>Lag since ${new Date(lagSince).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span>` : '',
      `<span class="np-tag"><i class="bi bi-hdd-network"></i>${server.name ? mcText(server.name) : 'Server'}</span>`,
      `<span class="np-tag"><i class="bi bi-box"></i>${esc(server.version)}</span>`,
      `<span class="np-tag"><i class="bi bi-puzzle"></i>${num(server.mods)} mods</span>`,
      `<span class="np-tag"><i class="bi bi-clock-history"></i>monitoring for ${duration(Date.now() - monitorSince)}</span>`,
    ];
    $('np-meta').innerHTML = tags.join('');
  }

  async function pollLive() {
    try {
      const data = await api('api/live?after=' + state.lastT);
      state.current = data.current || null;
      if (data.points.length) {
        state.points.push(...data.points);
        if (state.points.length > MAX_POINTS) state.points.splice(0, state.points.length - MAX_POINTS);
        state.lastT = state.points[state.points.length - 1].t;
      }
      const latest = data.latest;
      renderMonitoring(data.monitoring);
      renderClock(data.clock);
      if (!data.monitoring) setLive('paused', 'Monitoring off');
      else if (latest && latest.paused) setLive('paused', 'Server paused');
      else setLive('on', 'Live');
      renderMeta(data.server, data.server.monitorSince, data.lagging ? data.lagSince : 0);
      renderReplies(data.replies || []);
      if (data.recording.active || (state.recording && state.recording.active)) {
        if (!data.recording.active && state.recording && state.recording.active) setTimeout(loadReports, 2500);
        renderRecording(data.recording);
      }
      // The tiles follow the newest second, between the window refreshes.
      if (state.view) renderStats(state.view.kpi);
      drawCharts();
      setTimeout(pollLive, 1000);
    } catch (e) {
      setLive('off', 'Disconnected, retrying…');
      setTimeout(pollLive, 3000);
    }
  }

  // ── Reply notifications ────────────────────────────────────────────────

  // Answers to admin messages pop up on every tab, one card per player, until they're read or dismissed.
  const REPLIES_KEY = 'np-replies-seen';
  const baseTitle = document.title;
  let repliesSeen = 0;
  try { repliesSeen = Number(localStorage.getItem(REPLIES_KEY)) || 0; } catch (e) { /* not remembered */ }
  let hadReplies = false;

  function openConversation(uuid) {
    playersState.selected = uuid;
    playersState.message = '';
    delete $('np-player-detail').dataset.html;
    if (activeTab === 'players') loadPlayers();
    else selectTab('players');
  }

  function renderReplies(replies) {
    const total = replies.reduce((n, r) => n + r.unread, 0);
    document.title = total ? `(${total}) ${baseTitle}` : baseTitle;
    if (total) setTabBadge('players', total, true);
    else if (hadReplies) setTabBadge('players', 0);
    hadReplies = total > 0;

    let box = $('np-toasts');
    if (!box) {
      box = document.createElement('div');
      box.id = 'np-toasts';
      box.className = 'np-toasts';
      box.setAttribute('aria-live', 'polite');
      document.body.appendChild(box);
      box.addEventListener('click', (e) => {
        const toast = e.target.closest('.np-toast');
        if (!toast) return;
        toast.remove();
        if (!e.target.closest('.np-toast-close')) openConversation(toast.dataset.uuid);
      });
    }
    const open = new Set();
    let newest = repliesSeen;
    for (const r of replies) {
      // The open conversation already shows it (and marks it read).
      if (activeTab === 'players' && playersState.selected === r.uuid) continue;
      let toast = box.querySelector(`.np-toast[data-uuid="${r.uuid}"]`);
      if (!toast && r.time <= repliesSeen) continue;
      if (!toast) {
        toast = document.createElement('div');
        toast.className = 'np-toast';
        toast.dataset.uuid = r.uuid;
        toast.setAttribute('role', 'button');
        toast.title = 'Open the conversation';
        box.appendChild(toast);
      }
      open.add(r.uuid);
      newest = Math.max(newest, r.time);
      const html = `${face(r, 32)}<div class="np-toast-body"><b>${esc(r.name)} answered${r.unread > 1 ? ` <span class="np-tab-badge warn">${r.unread}</span>` : ''}</b>
        <span>${esc(r.text)}</span></div><button type="button" class="np-toast-close" aria-label="Dismiss"><i class="bi bi-x"></i></button>`;
      if (toast.dataset.html !== html) { toast.innerHTML = html; toast.dataset.html = html; }
    }
    box.querySelectorAll('.np-toast').forEach((t) => { if (!open.has(t.dataset.uuid)) t.remove(); });
    if (newest > repliesSeen) {
      repliesSeen = newest;
      try { localStorage.setItem(REPLIES_KEY, String(newest)); } catch (e) { /* not remembered */ }
    }
  }

  async function pollWindow() {
    try {
      renderView(await api('api/window?m=' + state.window));
    } catch (e) { /* the live indicator shows the connection state */ }
    clearTimeout(pollWindow.timer);
    pollWindow.timer = setTimeout(pollWindow, 2000);
  }

  // ── Events ─────────────────────────────────────────────────────────────

  // ── Light / dark ───────────────────────────────────────────────────────

  function currentTheme() {
    return document.documentElement.dataset.theme === 'light' ? 'light' : 'dark';
  }

  function renderThemeButton() {
    const light = currentTheme() === 'light';
    const button = $('np-theme-toggle');
    button.innerHTML = light ? '<i class="bi bi-moon-stars"></i>' : '<i class="bi bi-sun"></i>';
    button.title = light ? 'Switch to dark mode' : 'Switch to light mode';
    button.setAttribute('aria-label', button.title);
  }

  function setupTheme() {
    renderThemeButton();
    $('np-theme-toggle').addEventListener('click', () => {
      const next = currentTheme() === 'light' ? 'dark' : 'light';
      document.documentElement.dataset.theme = next;
      try { localStorage.setItem('np-theme', next); } catch (e) { /* not remembered, still switches */ }
      renderThemeButton();
      readColors();
      drawCharts();
    });
    // Inside the panel: follow the panel's light/dark switch (same origin, same stored choice).
    window.addEventListener('storage', (e) => {
      if (e.key !== 'np-theme') return;
      document.documentElement.dataset.theme = e.newValue === 'light' ? 'light' : 'dark';
      renderThemeButton();
      readColors();
      drawCharts();
    });
  }

  // ── Collapsible sections ───────────────────────────────────────────────

  // Sections start unfolded (tabs keep pages short); what a viewer folds is remembered.
  const SECTIONS_KEY = 'np-sections-v2';

  function loadSectionState() {
    try {
      return JSON.parse(localStorage.getItem(SECTIONS_KEY) || '{}') || {};
    } catch (e) {
      return {};
    }
  }

  function saveSectionState(sections) {
    try {
      localStorage.setItem(SECTIONS_KEY, JSON.stringify(sections));
    } catch (e) { /* storage unavailable; folding still works for this visit */ }
  }

  function setupCollapsing() {
    const sections = loadSectionState();
    document.querySelectorAll('.np-card').forEach((card) => {
      const title = card.querySelector(':scope > .np-card-title');
      if (!title) return;
      const id = card.id || 'section-' + title.textContent.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-');
      const foldedByDefault = false;
      title.setAttribute('role', 'button');
      title.setAttribute('tabindex', '0');
      title.insertAdjacentHTML('beforeend', '<i class="bi bi-chevron-down np-collapse-caret"></i>');
      const apply = (isCollapsed) => {
        card.classList.toggle('collapsed', isCollapsed);
        title.setAttribute('aria-expanded', String(!isCollapsed));
      };
      apply(id in sections ? sections[id] : foldedByDefault);
      const toggle = () => {
        const isCollapsed = !card.classList.contains('collapsed');
        apply(isCollapsed);
        sections[id] = isCollapsed;
        saveSectionState(sections);
        if (!isCollapsed) drawCharts();
      };
      title.addEventListener('click', toggle);
      title.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          toggle();
        }
      });
    });
  }

  function setupEvents() {
    document.addEventListener('toggle', (e) => {
      const key = e.target.dataset && e.target.dataset.key;
      if (!key) return;
      if (e.target.open) state.open.add(key); else state.open.delete(key);
    }, true);

    $('np-window').addEventListener('click', (e) => {
      const button = e.target.closest('button[data-window]');
      if (!button) return;
      state.window = Number(button.dataset.window);
      document.querySelectorAll('#np-window button').forEach((b) => b.classList.toggle('active', b === button));
      drawCharts();
      pollWindow();
    });

    document.addEventListener('click', (e) => {
      const copy = e.target.closest('[data-copy]');
      if (copy) {
        navigator.clipboard.writeText(copy.dataset.copy).then(() => {
          const old = copy.innerHTML;
          copy.innerHTML = '<i class="bi bi-check2"></i> Copied';
          setTimeout(() => { copy.innerHTML = old; }, 1500);
        });
      }
      const showAll = e.target.closest('[data-show-all]');
      if (showAll) {
        const key = showAll.dataset.showAll;
        if (state.showAll.has(key)) state.showAll.delete(key); else state.showAll.add(key);
        if (state.view) renderView(state.view);
      }
      if (e.target.closest('#np-notes-link')) {
        const hasErrors = (state.view && state.view.diagnosis.notes || []).some((n) => n.category === 'Errors');
        openSection(hasErrors ? 'np-errors-card' : 'np-settings-card');
      }
      const monitor = e.target.closest('[data-monitor]');
      if (monitor) {
        setMonitoring(monitor.dataset.monitor === '1');
      }
      if (e.target.closest('#np-compare-run')) {
        runCompare();
      }
      if (e.target.closest('#np-bloat-run')) {
        runBloat();
      }
      if (e.target.closest('#np-rec-start')) {
        recordingAction('api/recording/start?minutes=' + $('np-rec-minutes').value);
      }
      if (e.target.closest('#np-rec-stop')) {
        recordingAction('api/recording/stop');
      }
    });
  }

  // ── Start ──────────────────────────────────────────────────────────────

  function startReport() {
    document.body.classList.add('report');
    const meta = REPORT.meta;
    document.title = 'Nice Control Center report · ' + dateTime(meta.start);
    setLive('report', 'Report');
    $('np-meta').innerHTML = [
      `<span class="np-tag"><i class="bi bi-hdd-network"></i>${meta.server ? mcText(meta.server) : 'Server'}</span>`,
      `<span class="np-tag"><i class="bi bi-box"></i>${esc(meta.version)}</span>`,
      `<span class="np-tag"><i class="bi bi-calendar-event"></i>${dateTime(meta.start)} – ${new Date(meta.end).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span>`,
      `<span class="np-tag"><i class="bi bi-stopwatch"></i>${duration(meta.end - meta.start)}</span>`,
      meta.startedBy ? `<span class="np-tag"><i class="bi bi-person"></i>${esc(meta.startedBy)}</span>` : '',
      meta.reason ? `<span class="np-tag np-tag--lag"><i class="bi bi-exclamation-triangle"></i>${esc(meta.reason)}</span>` : '',
    ].join('');
    state.points = REPORT.points || [];
    renderView(REPORT.view);
    drawCharts();
  }

  setupEvents();
  // Shown inside the panel (an iframe): the panel has the header and footer already.
  try { if (window.top !== window.self) document.body.classList.add('embedded'); } catch (e) { document.body.classList.add('embedded'); }
  setupTheme();
  setupPackSettings();
  setupErrors();
  if (!REPORT) {
    setupUpdates();
    setupProperties();
    setupPlayers();
    setupSchedule();
    setupGamerules();
  }
  setupCollapsing();
  setupTabs();
  setupChartHover();
  if (!REPORT) setupSubs();
  setupMoreCharts();
  requestAnimationFrame(chartLoop);
  if (REPORT) {
    startReport();
  } else {
    const startWindow = Number(new URLSearchParams(location.search).get('window'));
    if ([1, 5, 15, 60].includes(startWindow)) {
      state.window = startWindow;
      document.querySelectorAll('#np-window button').forEach((b) => b.classList.toggle('active', Number(b.dataset.window) === startWindow));
    }
    pollLive();
    pollWindow();
    loadReports();
    setInterval(loadReports, 30000);
    api('api/bloat').then((d) => (d.running ? runBloat() : renderBloat(d.result))).catch(() => {});
    loadUpdates();

  }
})();
