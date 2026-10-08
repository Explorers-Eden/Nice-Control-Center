(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  // Blocks to map coordinates: x → lng, z → -lat (north is up).
  const ll = (x, z) => L.latLng(-z, x);

  const map = L.map('map', { crs: L.CRS.Simple, minZoom: -5, maxZoom: 3, zoomSnap: 1, attributionControl: false, zoomControl: true });
  const layers = {
    players: L.layerGroup().addTo(map),
    claims: L.layerGroup().addTo(map),
    hubs: L.layerGroup().addTo(map),
    border: L.layerGroup().addTo(map),
    trim: L.layerGroup().addTo(map),
  };
  const labels = { players: 'Players', claims: 'Claims', hubs: 'Hubs', border: 'World border' };
  let info = null;
  let dim = null;
  let tiles = null;
  let live = null;

  function hash() {
    const c = map.getCenter();
    history.replaceState(null, '', `#${dim.key}/${Math.round(c.lng)}/${Math.round(-c.lat)}/${map.getZoom()}`);
  }

  function setDim(key, x, z, zoom) {
    dim = info.dimensions.find((d) => d.key === key) || info.dimensions[0];
    if (tiles) map.removeLayer(tiles);
    tiles = L.tileLayer(`tiles/${dim.key}/{z}/{x}_{y}.png`, {
      tileSize: 512, minZoom: -5, maxZoom: 3, minNativeZoom: -5, maxNativeZoom: 0, noWrap: true, keepBuffer: 2,
      errorTileUrl: 'data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==',
    }).addTo(map);
    tiles.bringToBack();
    const spawn = dim.spawn || { x: 0, z: 0 };
    map.setView(ll(x ?? spawn.x, z ?? spawn.z), zoom ?? -1);
    document.querySelectorAll('#mp-dims button').forEach((b) => b.classList.toggle('active', b.dataset.dim === dim.key));
    drawBorder();
    drawLive();
  }

  function drawBorder() {
    layers.border.clearLayers();
    const b = dim.border;
    // The default border is ~60 million blocks wide; only draw borders someone actually set.
    if (!b || b.size >= 59999968) return;
    const h = b.size / 2;
    L.rectangle([ll(b.x - h, b.z - h), ll(b.x + h, b.z + h)], { color: '#ff9090', weight: 2, dashArray: '6 6', fill: false, interactive: false }).addTo(layers.border);
  }

  function drawLive() {
    if (!live || !dim) return;
    for (const k of ['players', 'claims', 'hubs', 'trim']) layers[k].clearLayers();
    for (const c of live.claims.filter((c) => c.dim === dim.id)) {
      const r = Math.max(c.radius || 0, 1);
      L.rectangle([ll(c.x - r, c.z - r), ll(c.x + r + 1, c.z + r + 1)], { color: '#b6c8ff', weight: 1.5, fillOpacity: 0.12 })
        .bindPopup(`<b>${esc(c.owners.join(', ') || 'Claim')}</b>${esc(c.anchor || '')}<br><small>${c.x}, ${c.z} · radius ${c.radius}</small>`)
        .addTo(layers.claims);
    }
    for (const h of live.hubs.filter((h) => h.dim === dim.id)) {
      L.marker(ll(h.x + 0.5, h.z + 0.5), { icon: L.divIcon({ className: 'mp-hub', html: '<i class="bi bi-geo-alt-fill"></i>', iconSize: [24, 24], iconAnchor: [12, 22] }) })
        .bindPopup(`<b>${esc(h.name)}</b>${h.description ? esc(h.description) + '<br>' : ''}<small>${h.owner ? 'by ' + esc(h.owner) + ' · ' : ''}${h.x}, ${h.y}, ${h.z}</small>`)
        .addTo(layers.hubs);
    }
    for (const p of live.players.filter((p) => p.dim === dim.id)) {
      L.marker(ll(p.x + 0.5, p.z + 0.5), { icon: L.icon({ iconUrl: `https://mc-heads.net/avatar/${encodeURIComponent(p.uuid)}/24`, iconSize: [24, 24], className: 'mp-head' }) })
        .bindTooltip(esc(p.name), { direction: 'top', offset: [0, -12] })
        .addTo(layers.players);
    }
    if (live.trim && live.trim.dim === dim.id) {
      const t = live.trim;
      // What stays: the green outline. What goes: red.
      if (t.shape === 'circle') L.circle(ll(t.x, t.z), { radius: t.radius, color: '#6fe89b', weight: 2, fill: false, interactive: false }).addTo(layers.trim);
      else L.rectangle([ll(t.minX, t.minZ), ll(t.maxX + 1, t.maxZ + 1)], { color: '#6fe89b', weight: 2, fill: false, interactive: false }).addTo(layers.trim);
      for (const r of t.regions || []) {
        L.rectangle([ll(r[0] * 512, r[1] * 512), ll(r[0] * 512 + 512, r[1] * 512 + 512)], { color: '#ff9090', weight: 0, fillOpacity: 0.35, interactive: false }).addTo(layers.trim);
      }
      for (const c of t.chunks || []) {
        L.rectangle([ll(c[0] * 16, c[1] * 16), ll(c[0] * 16 + 16, c[1] * 16 + 16)], { color: '#ff9090', weight: 0, fillOpacity: 0.25, interactive: false }).addTo(layers.trim);
      }
    }
    $('mp-players').innerHTML = live.players.length
      ? `<b>Online (${live.players.length})</b>` + live.players.map((p) => `<button type="button" data-player="${esc(p.uuid)}"><img src="https://mc-heads.net/avatar/${encodeURIComponent(p.uuid)}/18" alt="">${esc(p.name)}<small>${p.dim === dim.id ? `${p.x}, ${p.z}` : esc(p.dim.split(':').pop())}</small></button>`).join('')
      : (live.online ? '' : '<div class="mp-offline">The server is offline right now.</div>');
  }

  async function poll() {
    try {
      live = await (await fetch('data/live.json', { cache: 'no-store' })).json();
      drawLive();
    } catch (e) { /* next time */ }
    setTimeout(poll, 5000);
  }

  map.on('mousemove', (e) => {
    $('mp-coords').textContent = `x ${Math.floor(e.latlng.lng)} · z ${Math.floor(-e.latlng.lat)}`;
  });
  map.on('moveend', () => dim && hash());

  $('mp-players').addEventListener('click', (e) => {
    const b = e.target.closest('[data-player]');
    if (!b || !live) return;
    const p = live.players.find((x) => x.uuid === b.dataset.player);
    if (!p) return;
    const key = info.dimensions.find((d) => d.id === p.dim)?.key;
    if (key && key !== dim.key) setDim(key, p.x, p.z, 1);
    else map.setView(ll(p.x, p.z), Math.max(map.getZoom(), 1));
  });

  (async () => {
    try {
      info = await (await fetch('data/info.json', { cache: 'no-store' })).json();
    } catch (e) {
      $('mp-title').textContent = 'The map is switched off';
      return;
    }
    if (!info.dimensions) {
      $('mp-title').textContent = 'The map is switched off';
      return;
    }
    document.title = info.title;
    $('mp-title').textContent = info.title;
    $('mp-dims').innerHTML = info.dimensions.map((d) => `<button type="button" data-dim="${esc(d.key)}">${esc(d.name)}</button>`).join('');
    $('mp-dims').addEventListener('click', (e) => { const b = e.target.closest('[data-dim]'); if (b) setDim(b.dataset.dim); });
    $('mp-layers').innerHTML = Object.entries(labels).map(([k, l]) => `<label><input type="checkbox" data-layer="${k}" checked> ${l}</label>`).join('');
    $('mp-layers').addEventListener('change', (e) => {
      const k = e.target.dataset.layer;
      if (e.target.checked) layers[k].addTo(map); else map.removeLayer(layers[k]);
    });
    const [key, x, z, zoom] = location.hash.slice(1).split('/');
    setDim(key, x === undefined ? undefined : Number(x), z === undefined ? undefined : Number(z), zoom === undefined ? undefined : Number(zoom));
    poll();
  })();
})();
