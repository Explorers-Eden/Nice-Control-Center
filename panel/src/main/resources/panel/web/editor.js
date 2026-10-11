(() => {
  'use strict';

  // The file editor in its own window (opened from Files and Configs): syntax highlighting, a form view
  // for simple configs, format checks before saving with a jump to the faulty line.

  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const path = decodeURIComponent(location.hash.slice(1));
  const changes = 'BroadcastChannel' in window ? new BroadcastChannel('ncc-files') : null;

  async function api(url, body) {
    const res = await fetch(url, body === undefined ? {} : {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-NCC': '1' }, body: JSON.stringify(body),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      const err = new Error(res.status === 401 ? 'Not logged in: log in to the panel first, then open the file again.' : data.error || res.statusText);
      err.data = data;
      throw err;
    }
    return data;
  }

  const extOf = (p) => (p.match(/\.([^./]+)$/) || [])[1]?.toLowerCase() || '';

  /** CodeMirror mode by file type; anything unknown is plain text. */
  function modeFor(p) {
    const ext = extOf(p);
    if (['json', 'json5', 'mcmeta', 'jsonc'].includes(ext)) return { name: 'javascript', json: true };
    if (['js', 'mjs'].includes(ext)) return 'javascript';
    if (['yml', 'yaml'].includes(ext)) return 'yaml';
    if (ext === 'toml') return 'toml';
    if (['properties', 'cfg', 'conf', 'ini', 'lang'].includes(ext)) return 'properties';
    if (['xml', 'html', 'svg'].includes(ext)) return 'xml';
    if (['sh', 'bash'].includes(ext)) return 'shell';
    return null;
  }

  /** Fields a form view can show: key/value lines (properties, flat TOML) or a JSON object of plain values. */
  function parseForm(p, text, flatJson) {
    const ext = extOf(p);
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

  const flatJsonObject = (text) => {
    try {
      const o = JSON.parse(text);
      return o && typeof o === 'object' && !Array.isArray(o) && Object.values(o).every((v) => v === null || typeof v !== 'object');
    } catch (e) {
      return false;
    }
  };

  let cm = null;
  let original = '';
  let mode = 'text';
  let form = null;
  let editable = false;

  function note(text, ok) {
    $('ed-msg').className = 'np-form-msg ' + (ok === true ? 'ok' : ok === false ? 'error' : '');
    $('ed-msg').textContent = text;
  }

  function dirty() {
    return cm && (mode === 'form' ? fromForm() : cm.getValue()) !== original;
  }

  function markDirty() {
    $('ed-dirty').hidden = !dirty();
  }

  function renderForm() {
    const groups = new Map();
    form.fields.forEach((f, i) => {
      const g = f.section || '';
      if (!groups.has(g)) groups.set(g, []);
      groups.get(g).push([f, i]);
    });
    $('ed-form').innerHTML = [...groups].map(([g, list]) => (g ? `<h5>${esc(g)}</h5>` : '') + list.map(([f, i]) => {
      const label = `<span>${esc(f.key)}${f.comment ? `<span class="pn-comment">${esc(f.comment)}</span>` : ''}</span>`;
      if (f.type === 'bool') return `<label class="np-form-row">${label}<span class="np-switch"><input type="checkbox" data-field="${i}" ${f.value ? 'checked' : ''} ${editable ? '' : 'disabled'}><span></span></span></label>`;
      return `<label class="np-form-row">${label}<input type="${f.type === 'number' ? 'number' : 'text'}" step="any" data-field="${i}" value="${esc(f.value)}" ${editable ? '' : 'readonly'}></label>`;
    }).join('')).join('');
  }

  /** The form's values written back into the text, keeping comments and layout. */
  function fromForm() {
    $('ed-form').querySelectorAll('[data-field]').forEach((input) => {
      const f = form.fields[Number(input.dataset.field)];
      f.value = f.type === 'bool' ? input.checked : f.type === 'number' ? (input.value === '' ? 0 : Number(input.value)) : input.value;
    });
    const text = cm.getValue();
    if (form.kind === 'json') {
      const obj = {};
      form.fields.forEach((f) => { obj[f.key] = f.value; });
      return JSON.stringify(obj, null, form.indent) + (text.endsWith('\n') ? '\n' : '');
    }
    const lines = text.split('\n');
    for (const f of form.fields) {
      const token = form.kind === 'toml' && f.type === 'text' ? JSON.stringify(f.value) : String(f.value);
      lines[f.line] = f.prefix + token + f.suffix;
    }
    return lines.join('\n');
  }

  function setMode(next) {
    if (next === mode) return;
    if (next === 'form') {
      form = parseForm(path, cm.getValue(), extOf(path) === 'json' && flatJsonObject(cm.getValue()));
      if (!form) return note('This file can only be edited as text right now (check it for errors).', false);
      renderForm();
    } else if (form) {
      cm.setValue(fromForm());
    }
    mode = next;
    $('ed-code').hidden = mode !== 'text';
    $('ed-form').hidden = mode !== 'form';
    $('ed-modes').querySelectorAll('[data-mode]').forEach((b) => b.classList.toggle('active', b.dataset.mode === mode));
    if (mode === 'text') cm.refresh();
  }

  function goToLine(line, column) {
    const pos = { line: line - 1, ch: Math.max(0, (column || 1) - 1) };
    cm.focus();
    cm.setSelection(pos, { line: pos.line, ch: pos.ch + 1 });
    cm.scrollIntoView(pos, 120);
  }

  async function save(forced) {
    if (!editable) return;
    if (mode === 'form') cm.setValue(fromForm());
    const text = cm.getValue();
    try {
      await api('api/files/write', { path, text, force: !!forced });
      original = text;
      $('ed-force').hidden = true;
      note('Saved.', true);
      markDirty();
      if (changes) changes.postMessage({ saved: path });
    } catch (err) {
      const d = err.data || {};
      note(d.line ? `Line ${d.line}${d.column ? ', column ' + d.column : ''}: ${err.message}` : err.message, false);
      $('ed-force').hidden = !d.line && !/JSON|TOML|YAML|expected|Expected/.test(err.message);
      if (d.line) {
        if (mode === 'form') setMode('text');
        goToLine(d.line, d.column);
      }
    }
  }

  async function open() {
    document.title = path.split('/').pop() + ' – Editor';
    $('ed-name').textContent = path;
    let data;
    let me;
    try {
      [data, me] = await Promise.all([api('api/files/read?path=' + encodeURIComponent(path)), api('api/me')]);
    } catch (err) {
      note(err.message, false);
      $('ed-save').hidden = true;
      return;
    }
    if (data.text === null) {
      note(`${path} isn't a text file (or is over 5 MB), so it can't be edited here. Download it instead.`, false);
      $('ed-save').hidden = true;
      return;
    }
    const perms = me.permissions || [];
    editable = perms.includes('*') || perms.includes('files.write') || (perms.includes('files.config') && data.path.startsWith('config/'));
    original = data.text;
    cm = CodeMirror($('ed-code'), {
      value: data.text,
      mode: modeFor(path),
      lineNumbers: true,
      matchBrackets: true,
      styleActiveLine: true,
      indentUnit: /\n\t/.test(data.text) ? 4 : 2,
      indentWithTabs: /\n\t/.test(data.text),
      tabSize: 4,
      readOnly: editable ? false : 'nocursor',
      lineWrapping: false,
      theme: 'ncc',
      extraKeys: {
        'Ctrl-S': () => save(false),
        'Cmd-S': () => save(false),
        Tab: (c) => (c.somethingSelected() ? c.indentSelection('add') : c.replaceSelection(c.getOption('indentWithTabs') ? '\t' : ' '.repeat(c.getOption('indentUnit')), 'end')),
      },
    });
    cm.on('change', markDirty);
    $('ed-save').hidden = !editable;
    $('ed-modes').hidden = !(() => { try { return parseForm(path, data.text, data.flatJson); } catch (e) { return false; } })();
    $('ed-info').textContent = `${data.format || 'text'} · ${new Date(data.time).toLocaleString()}${editable ? '' : ' · read-only'}`;
    cm.focus();
  }

  $('ed-modes').addEventListener('click', (e) => { const b = e.target.closest('[data-mode]'); if (b) setMode(b.dataset.mode); });
  $('ed-form').addEventListener('input', markDirty);
  $('ed-save').addEventListener('click', () => save(false));
  $('ed-force').addEventListener('click', () => { if (confirm('Save even though the file has errors? The mod may fail to load it.')) save(true); });
  $('ed-close').addEventListener('click', () => window.close());
  document.addEventListener('keydown', (e) => {
    if (mode === 'form' && (e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
      e.preventDefault();
      save(false);
    }
  });
  window.addEventListener('beforeunload', (e) => {
    if (dirty()) {
      e.preventDefault();
      e.returnValue = '';
    }
  });

  if (!path) note('No file given.', false);
  else open();
})();
