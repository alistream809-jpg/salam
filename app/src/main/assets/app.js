'use strict';
const $ = (s, e = document) => e.querySelector(s), $$ = (s, e = document) => [...e.querySelectorAll(s)];
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const LANGS = [['en', 'English'], ['az', 'Azərbaycanca'], ['tr', 'Türkçe'], ['ru', 'Русский'], ['ar', 'العربية'], ['de', 'Deutsch'], ['fr', 'Français'], ['es', 'Español'], ['pt', 'Português'], ['ja', '日本語']];
const CATS = ['Action', 'Adventure', 'Racing', 'Open World', 'Survival', 'Horror', 'Simulation', 'Strategy', 'Sports', 'Multiplayer'];
let LI = 0, ST = {}, tab = 'home', sheet = false, cat = 'all', q = '', sort = 'added', offset = 0, perfTimer = null;
const PROG = {}, pend = {}; let rid = 0;
const t = k => (T[k] && (T[k][LI] || T[k][0])) || k;
const catName = c => c === 'offline' ? t('c_offline') : T['c_' + c.replace(' ', '')] ? t('c_' + c.replace(' ', '')) : c;
const emsg = c => T['e_' + c] ? t('e_' + c) : t('e_UNKNOWN');

const call = (m, a = {}) => new Promise((ok, no) => { const id = ++rid; pend[id] = { ok, no }; Native.call(id, m, JSON.stringify(a)); });
window.__res = (id, s) => { const p = pend[id]; if (!p) return; delete pend[id]; const r = JSON.parse(s); r.ok ? p.ok(r.data) : p.no(r); };
const EV = {};
window.__event = (n, s) => { if (EV[n]) EV[n](JSON.parse(s)); };
window.__back = () => { if (sheet) { closeSheet(); return true; } if (tab !== 'home') { go('home'); return true; } return false; };

const toast = m => { const e = $('#toast'); e.textContent = m; e.classList.add('on'); clearTimeout(toast.h); toast.h = setTimeout(() => e.classList.remove('on'), 3500); };
const fail = e => toast(emsg(e && e.error));
const mb = b => b >= 1073741824 ? (b / 1073741824).toFixed(2) + ' GB' : (b / 1048576).toFixed(1) + ' MB';
const tm = s => { s = Math.round(s); const h = s / 3600 | 0, m = s % 3600 / 60 | 0, x = s % 60; return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(x).padStart(2, '0'); };
const hue = s => { let h = 0; for (const c of s || '') h = (h * 31 + c.charCodeAt(0)) % 360; return h; };

function applyLang(code) {
  LI = Math.max(0, LANGS.findIndex(l => l[0] === code));
  document.documentElement.lang = code; document.documentElement.dir = code === 'ar' ? 'rtl' : 'ltr';
  call('setStrings', { n_dl: t('st_running'), n_done: t('st_done'), n_fail: t('st_failed'), n_many: t('n_many') });
}

/* ---------- components ---------- */
const firstCat = g => { const c = (g.cats || ',').split(',').filter(Boolean)[0]; return c ? catName(c) : '—'; };
function gbtn(g) {
  switch (g.state) {
    case 'installed': return `<button class="btn play" data-act="play" data-pkg="${esc(g.pkg)}">${t('play')}</button>`;
    case 'downloading': return `<button class="mini" data-act="sheet" data-dlp="${g.dlId}"><i></i><span>${t('st_running')}…</span></button>`;
    case 'downloaded': return `<button class="btn" data-act="install" data-id="${g.libId}">${t('install')}</button>`;
    default: return `<button class="btn" data-act="get" data-pkg="${esc(g.pkg)}">${t(g.state === 'update' ? 'update' : 'download')}</button>`;
  }
}
function cover(g) {
  return g.cover ? `<img loading="lazy" src="${esc(g.cover)}" alt="">` : g.icon ? `<img class="ic" loading="lazy" src="${esc(g.icon)}" alt="">` : `<b>${esc((g.name || '?')[0])}</b>`;
}
const card = g => `<article class="card" style="--h:${hue(g.pkg)}"><div class="cv">${cover(g)}</div><div class="cb"><h3>${esc(g.name)}</h3><p class="dim">${esc(g.author || '—')}</p><p class="meta"><span>${esc(firstCat(g))}</span><span>${mb(g.size || 0)}</span><span>v${esc(g.ver_name)}</span></p>${gbtn(g)}</div></article>`;
const hero = g => `<article class="hero" style="--h:${hue(g.pkg)}">${g.cover ? `<img loading="lazy" src="${esc(g.cover)}" alt="">` : ''}<div class="hb"><div><h3>${esc(g.name)}</h3><p>${esc(g.summary)}</p></div>${gbtn(g)}</div></article>`;
const row = (title, list, fn = card) => list && list.length ? `<h2>${t(title)}</h2><div class="row">${list.map(fn).join('')}</div>` : '';
const ICONS = {
  home: 'M12 3l9 8h-3v9h-5v-6h-2v6H6v-9H3z', store: 'M4 4h16l1 5a3 3 0 01-6 0 3 3 0 01-6 0 3 3 0 01-6 0zM5 12h14v8H5z',
  library: 'M4 4h4v16H4zM10 4h4v16h-4zM16 5l4 1-3 14-4-1z', github: 'M11 3h2v9l3-3 1.4 1.4L12 16l-5.4-5.6L8 9l3 3zM5 18h14v3H5z',
  fps: 'M12 4a9 9 0 00-9 9h2a7 7 0 0114 0h2a9 9 0 00-9-9zm1 9.3l4-5-5.2 3.7a2 2 0 101.2 1.3z', settings: 'M4 7h9v2H4zM15 5h2v6h-2zM17 7h3v2h-3zM4 15h3v2H4zM9 13h2v6H9zM11 15h9v2h-9z'
};
const skeleton = () => '<div class="sk"></div><div class="sk"></div><div class="sk"></div>';

/* ---------- shell / navigation ---------- */
function shell() {
  $('#app').innerHTML = `<nav id="nav">${Object.keys(ICONS).map(k => `<button data-act="go" data-tab="${k}"><svg viewBox="0 0 24 24"><path d="${ICONS[k]}"/></svg>${esc(t(k))}</button>`).join('')}</nav><main id="main"></main><div id="sheet"></div>`;
}
async function go(to) {
  clearInterval(perfTimer); tab = to;
  $$('#nav button').forEach(b => b.classList.toggle('on', b.dataset.tab === to));
  const m = $('#main'); m.innerHTML = skeleton();
  try { await ({ home: pHome, store: pStore, library: pLib, github: pGh, fps: pFps, settings: pSet })[to](); } catch (e) { m.innerHTML = `<div class="center dim">${esc(emsg(e && e.error))}</div>`; }
  m.firstElementChild && m.firstElementChild.classList.add('page');
}
let soon; const refreshSoon = () => { clearTimeout(soon); soon = setTimeout(async () => { const m = $('#main'); if (!m || tab === 'fps' || tab === 'settings' || tab === 'github') return; const y = m.scrollTop; await go(tab); m.scrollTop = y; }, 300); };

/* ---------- Home ---------- */
const catalogBusy = () => `<div class="center"><div class="spin"></div><p class="dim" id="cprog">${t('c_loading')}</p></div>`;
async function pHome() {
  const h = await call('catalogHome'), badge = await activeCount();
  const empty = !h.featured.length && !h.added.length;
  $('#main').innerHTML = `<div><div class="top"><h1>SpaceHub</h1><button class="ib" data-act="gostore" aria-label="search">⌕</button><button class="ib" data-act="sheet">⇩${badge ? `<b>${badge}</b>` : ''}</button></div>
  ${empty ? catalogBusy() : `${h.featured.length ? `<h2>${t('h_featured')}</h2><div class="row">${h.featured.map(hero).join('')}</div>` : ''}
  ${row('h_played', h.played)}${row('h_rec', h.recommended)}${row('h_added', h.added)}${row('h_updated', h.updated)}
  <h2>${t('h_cats')}</h2><div class="chips">${[...CATS, 'offline'].map(c => `<button class="chip" data-act="cat" data-cat="${c}">${esc(catName(c))} · ${h.counts[c] || 0}</button>`).join('')}</div>`}</div>`;
}
const activeCount = async () => (await call('downloads')).filter(d => ['running', 'queued'].includes(d.state)).length;

/* ---------- Store ---------- */
async function pStore() {
  $('#main').innerHTML = `<div><div class="top"><h1>${t('store')}</h1><button class="ib" data-act="refreshcat" title="${esc(t('c_refresh'))}">⟳</button></div>
  <input type="search" id="q" placeholder="${esc(t('s_search'))}" value="${esc(q)}">
  <div class="chips">${['all', ...CATS, 'offline'].map(c => `<button class="chip ${c === cat ? 'on' : ''}" data-act="cat2" data-cat="${c}">${esc(c === 'all' ? t('all') : catName(c))}</button>`).join('')}</div>
  <div class="chips">${[['added', 's_new'], ['updated', 's_upd'], ['name', 's_name']].map(([k, l]) => `<button class="chip ${k === sort ? 'on' : ''}" data-act="sort" data-sort="${k}">${t(l)}</button>`).join('')}</div>
  <div class="grid" id="list"></div><div class="center" id="more"></div><p class="dim center">${t('c_source')}</p></div>`;
  $('#q').addEventListener('input', e => { q = e.target.value; clearTimeout(pStore.h); pStore.h = setTimeout(() => load(true), 250); });
  load(true);
}
async function load(reset) {
  if (reset) offset = 0;
  const rows = await call('catalogQuery', { q, cat, sort, offset });
  const l = $('#list'); if (!l) return;
  if (reset) l.innerHTML = '';
  l.insertAdjacentHTML('beforeend', rows.map(card).join(''));
  offset += rows.length;
  $('#more').innerHTML = rows.length === 30 ? `<button class="btn" data-act="more">${t('more')}</button>` : (!l.children.length ? (ST.catalogCount ? t('s_none') : catalogBusy()) : '');
}

/* ---------- Library ---------- */
const lrow = (g, kind) => {
  const upd = g.installedCode >= 0 && g.installedCode < g.ver_code;
  const main = kind === 'installed' ? `<button class="btn play" data-act="play" data-pkg="${esc(g.pkg)}">${t('play')}</button>`
    : g.fileMissing ? '' : `<button class="btn" data-act="install" data-id="${g.id}">${t(upd ? 'update' : 'install')}</button>`;
  const bd = kind === 'installed' ? ['', t('b_installed')] : kind === 'pending' ? ['w', t('pending')] : ['w', t('b_downloaded')];
  return `<div class="lrow" style="--h:${hue(g.pkg)}">${g.icon ? `<img src="${esc(g.icon)}" alt="">` : `<div class="ph">${esc((g.name || '?')[0])}</div>`}
  <div class="in"><h3>${esc(g.name)}</h3><p class="meta"><span>v${esc(g.ver_name)}</span><span>${mb(g.size || 0)}</span></p><span class="badge ${bd[0]}">${bd[1]}</span></div>
  <div class="acts">${main}<div style="display:flex;gap:6px"><button class="btn ghost sm" data-act="appinfo" data-pkg="${esc(g.pkg)}">ℹ</button><button class="btn ghost sm" data-act="libremove" data-id="${g.id}">✕</button></div></div></div>`;
};
async function pLib() {
  const L = await call('library');
  const sec = (k, key) => L[k].length ? `<h2>${t(key)} · ${L[k].length}</h2>${L[k].map(g => lrow(g, k)).join('')}` : '';
  const none = !L.installed.length && !L.downloaded.length && !L.pending.length;
  $('#main').innerHTML = `<div><h1>${t('library')}</h1>${none ? `<p class="dim center">${t('l_empty')}</p>` : sec('installed', 'l_installed') + sec('downloaded', 'l_downloaded') + sec('pending', 'l_pending')}</div>`;
}

/* ---------- GitHub Actions (APK artifacts only) ---------- */
let ghRepo = '';
async function pGh() {
  const has = await call('gh.hasToken');
  $('#main').innerHTML = `<div><h1>GitHub Actions APK</h1><p class="dim">${t('g_note')}</p>
  <div class="panel"><b>${t('g_token')}</b> <span class="badge ${has ? '' : 'w'}">${has ? '✓' : '—'}</span><p class="dim" style="font-size:12px">${t('g_token_note')}</p>
  <input type="password" id="tok" autocomplete="off" style="margin:8px 0"><div style="display:flex;gap:8px"><button class="btn sm" data-act="savetok">${t('g_save')}</button>${has ? `<button class="btn ghost sm" data-act="clrtok">${t('g_clear')}</button>` : ''}</div></div>
  <div class="panel"><input type="text" id="repo" placeholder="${esc(t('g_repo'))}" value="${esc(ghRepo)}" autocapitalize="none" autocorrect="off" style="margin-bottom:8px"><button class="btn" data-act="ghload">${t('g_load')}</button></div>
  <div id="ghout"></div></div>`;
}
async function ghLoad() {
  ghRepo = $('#repo').value.trim(); const out = $('#ghout'); out.innerHTML = '<div class="spin"></div>';
  try {
    const r = await call('gh.workflows', { repo: ghRepo });
    out.innerHTML = `<h2>${t('g_workflows')}</h2><div class="chips"><button class="chip" data-act="ghruns" data-wf="">${t('g_runs')}</button>${r.workflows.map(w => `<button class="chip" data-act="ghruns" data-wf="${esc(w.file)}">${esc(w.file)}</button>`).join('')}</div><div id="runs"></div>`;
    if (r.selected) ghRuns(r.selected);
  } catch (e) { out.innerHTML = ''; fail(e); }
}
async function ghRuns(wf) {
  const el = $('#runs'); el.innerHTML = '<div class="spin"></div>';
  try {
    const runs = await call('gh.runs', { repo: ghRepo, workflow: wf });
    el.innerHTML = `<h2>${t('g_runs')}</h2>` + runs.map(r => {
      const ok = r.status === 'completed' && r.conclusion === 'success';
      const label = r.status !== 'completed' ? t('r_progress') : r.conclusion === 'success' ? t('r_success') : r.conclusion === 'cancelled' ? t('st_cancelled') : r.conclusion === 'failure' ? t('st_failed') : r.conclusion;
      return `<div class="panel" data-run="${r.id}"><div class="line" style="padding:0"><div><b>#${r.number}</b> ${esc(r.title)}<p class="dim" style="font-size:12px">${esc(r.branch)} · ${new Date(r.created).toLocaleString()}</p></div><span class="badge ${ok ? '' : r.status !== 'completed' ? 'w' : 'e'}">${esc(label)}</span></div>
      ${ok ? `<button class="btn sm" style="margin-top:8px" data-act="ghart" data-run="${r.id}">${t('g_get')}</button><div class="arts"></div>` : `<p class="dim" style="font-size:12px;margin-top:6px">${t('g_failed_run')}</p>`}</div>`;
    }).join('');
  } catch (e) { el.innerHTML = ''; fail(e); }
}
async function ghArts(btn) {
  const box = btn.parentElement.querySelector('.arts');
  try {
    const a = await call('gh.artifacts', { repo: ghRepo, run: +btn.dataset.run });
    box.innerHTML = a.length ? a.map(x => `<div class="line"><div>${esc(x.name)}<p class="dim" style="font-size:12px">${mb(x.size)}</p></div>${x.expired ? `<span class="badge e">${t('g_expired')}</span>` : `<button class="btn sm" data-act="ghdl" data-id="${x.id}" data-name="${esc(x.name)}" data-size="${x.size}">${t('download')}</button>`}</div>`).join('') : `<p class="dim">${t('g_noart')}</p>`;
  } catch (e) { fail(e); }
}

/* ---------- FPS Booster ---------- */
async function pFps() {
  $('#main').innerHTML = `<div><h1>FPS Booster</h1><p class="dim">${t('f_note')}</p><div id="pf"></div></div>`;
  await paintPerf(); perfTimer = setInterval(paintPerf, 3000);
}
const badge = () => `<span class="badge">${t('f_measured')}</span>`;
async function paintPerf() {
  if (tab !== 'fps') return;
  const [p, games] = await Promise.all([call('perf'), call('perf.games')]);
  const bar = pc => `<div class="bar"><i style="width:${pc}%"></i></div>`;
  const rp = Math.round((p.ram.total - p.ram.avail) * 100 / p.ram.total), sp = Math.round((p.storage.total - p.storage.avail) * 100 / p.storage.total);
  const th = p.thermal < 0 ? '—' : t(p.thermal <= 0 ? 'th_ok' : p.thermal <= 2 ? 'th_warm' : 'th_hot');
  const fr = p.cpu.freqMhz.filter(x => x != null);
  $('#pf').innerHTML = `<div class="tiles">
  <div class="panel tile"><p class="dim">${t('f_ram')} ${badge()}</p><p class="big">${mb(p.ram.avail)}</p><p class="dim">${t('f_free')} / ${mb(p.ram.total)}</p>${bar(rp)}</div>
  <div class="panel tile"><p class="dim">${t('f_storage')} ${badge()}</p><p class="big">${mb(p.storage.avail)}</p><p class="dim">${t('f_free')} / ${mb(p.storage.total)}</p>${bar(sp)}</div>
  <div class="panel tile"><p class="dim">${t('f_battery')} ${badge()}</p><p class="big">${p.battery.pct >= 0 ? p.battery.pct + '%' : '—'}${p.battery.charging ? ' ⚡' : ''}</p><p class="dim">${t('f_temp')}: ${p.battery.tempC == null ? '—' : p.battery.tempC.toFixed(1) + ' °C'}</p>${bar(Math.max(0, p.battery.pct))}</div>
  <div class="panel tile"><p class="dim">${t('f_thermal')} ${badge()}</p><p class="big">${th}</p><p class="dim">${t('f_psave')}: ${p.powerSave ? 'ON' : 'OFF'}</p></div>
  <div class="panel tile"><p class="dim">CPU ${badge()}</p><p class="big">${p.cpu.cores} ${t('f_cores')}</p><p class="dim">${esc(p.cpu.abis[0] || '')}${fr.length ? ' · ' + fr.map(x => x + ' MHz').join(', ') : ''}</p><p class="dim" style="font-size:12px">${t('f_cpu_na')}</p></div>
  <div class="panel tile"><p class="dim">${t('f_display')} ${badge()}</p><p class="big">${p.hz.toFixed(0)} Hz</p><p class="dim">${esc(p.device)} · Android API ${p.sdk}</p></div></div>
  <h2>${t('f_tips')}</h2><div class="panel">${p.tips.length ? p.tips.map(x => `<p>⚠ ${t('w_' + x)}</p>`).join('') : `<p>✓ ${t('w_ok')}</p>`}<p class="dim" style="font-size:12px;margin-top:8px">${t('f_bg')}</p>
  <div style="display:flex;gap:8px;margin-top:8px;flex-wrap:wrap"><button class="btn ghost sm" data-act="psettings" data-kind="battery">${t('f_batt_set')}</button><button class="btn ghost sm" data-act="psettings" data-kind="power">${t('f_psave')}</button></div></div>
  <h2>${t('f_games')}</h2>${games.length ? games.map(g => `<div class="panel"><div class="line" style="padding-top:0"><h3>${esc(g.name)}</h3><button class="btn play sm" data-act="play" data-pkg="${esc(g.pkg)}">${t('play')}</button></div>
  <div class="line"><span>${t('f_pre')}</span><label class="sw"><input type="checkbox" data-pre="${esc(g.pkg)}" ${g.pre ? 'checked' : ''}><span></span></label></div>
  <div class="line"><span>${t('f_shortcut')}</span><button class="btn ghost sm" data-act="shortcut" data-pkg="${esc(g.pkg)}">＋</button></div></div>`).join('') : `<p class="dim">${t('f_nogames')}</p>`}`;
}

/* ---------- Settings ---------- */
async function pSet() {
  ST = await call('state');
  const sw = (k, label) => `<div class="line"><span>${t(label)}</span><label class="sw"><input type="checkbox" data-pref="${k}" ${ST[k] ? 'checked' : ''}><span></span></label></div>`;
  $('#main').innerHTML = `<div><h1>${t('settings')}</h1>
  <div class="panel"><b>${t('set_profile')}</b><div class="line"><input type="text" id="pname" placeholder="${esc(t('set_name'))}" value="${esc(ST.name)}" maxlength="30"></div></div>
  <div class="panel"><b>${t('set_dl')}</b>${sw('wifi_only', 'set_wifi')}${sw('auto_install', 'set_auto')}</div>
  <div class="panel"><div class="line"><b>${t('set_lang')}</b><select data-lang>${LANGS.map(l => `<option value="${l[0]}" ${l[0] === ST.lang ? 'selected' : ''}>${l[1]}</option>`).join('')}</select></div>
  <div class="line"><b>${t('set_theme')}</b><select data-theme><option value="nebula">Nebula</option><option value="midnight">Midnight</option><option value="oled">OLED</option></select></div></div>
  <div class="panel"><b>${t('set_storage')}</b><div class="line"><span>${t('set_used')}</span><b>${mb(ST.apkBytes)}</b></div><button class="btn ghost sm" data-act="clearfiles">${t('set_clear')}</button></div>
  <div class="panel"><b>${t('set_notif')}</b>${sw('notif', 'set_notif_dl')}${ST.notifGranted ? '' : `<button class="btn ghost sm" data-act="notifperm">${t('set_allow')}</button>`}</div>
  <div class="panel"><b>${t('set_fps')}</b>${sw('pre_default', 'f_pre')}</div>
  <div class="panel"><b>${t('set_about')}</b><p class="dim" style="margin:6px 0">${t('about_text')}</p><div class="line"><span>${t('set_version')}</span><b>${esc(ST.version)}</b></div><p class="dim" style="font-size:12px">${esc(ST.device)} · API ${ST.sdk}</p></div></div>`;
  $('[data-theme]').value = ST.theme;
}

/* ---------- Downloads sheet ---------- */
async function openSheet() { sheet = true; $('#sheet').classList.add('on'); await paintSheet(); }
function closeSheet() { sheet = false; $('#sheet').classList.remove('on'); }
async function paintSheet() {
  if (!sheet) return;
  const d = await call('downloads');
  $('#sheet').innerHTML = `<div class="sh"><div class="top"><h1>${t('d_title')}</h1><button class="ib" data-act="closesheet">✕</button></div>${d.length ? d.map(x => {
    const p = PROG[x.id] || { bytes: x.bytes, total: x.total, bps: x.bps || 0 }, pc = p.total > 0 ? Math.floor(p.bytes * 100 / p.total) : 0;
    const act = ['running', 'queued'].includes(x.state) ? `<button class="btn ghost sm" data-act="dl" data-a="pause" data-id="${x.id}">${t('pause')}</button><button class="btn ghost sm" data-act="dl" data-a="cancel" data-id="${x.id}">${t('cancel')}</button>`
      : ['paused', 'failed'].includes(x.state) ? `<button class="btn sm" data-act="dl" data-a="start" data-id="${x.id}">${t('resume')}</button><button class="btn ghost sm" data-act="dl" data-a="cancel" data-id="${x.id}">${t('cancel')}</button>` : `<button class="btn ghost sm" data-act="dlrm" data-id="${x.id}">${t('remove')}</button>`;
    return `<div class="panel" data-dlrow="${x.id}"><div class="line" style="padding-top:0"><b>${esc(x.title)}</b><span class="badge ${x.state === 'failed' ? 'e' : x.state === 'done' ? '' : 'w'}">${t('st_' + x.state)}</span></div>
    ${['done', 'cancelled'].includes(x.state) ? '' : `<div class="bar"><i style="width:${pc}%"></i></div><p class="dim" style="font-size:12px"><span class="px">${pc}% · ${mb(p.bytes)} / ${p.total > 0 ? mb(p.total) : '?'}</span><span class="sp">${x.state === 'running' ? ` · ${t('speed')}: ${mb(p.bps)}/s${p.bps > 0 && p.total > 0 ? ` · ${t('eta')}: ${tm((p.total - p.bytes) / p.bps)}` : ''}` : ''}</span></p>`}
    ${x.error ? `<p style="color:#ff9a9a;font-size:12px">${esc(emsg(x.error))}</p>` : ''}<div style="display:flex;gap:8px;margin-top:6px">${act}</div></div>`;
  }).join('') : `<p class="dim center">${t('d_none')}</p>`}</div>`;
}

/* ---------- events from native ---------- */
EV.progress = o => {
  PROG[o.id] = o; const pc = o.total > 0 ? Math.floor(o.bytes * 100 / o.total) : 0;
  $$(`[data-dlp="${o.id}"]`).forEach(e => { $('i', e).style.width = pc + '%'; $('span', e).textContent = pc + '%'; });
  const r = $(`[data-dlrow="${o.id}"]`);
  if (r) {
    $('.bar i', r).style.width = pc + '%';
    $('.px', r).textContent = `${pc}% · ${mb(o.bytes)} / ${o.total > 0 ? mb(o.total) : '?'}`;
    $('.sp', r).textContent = ` · ${t('speed')}: ${mb(o.bps)}/s` + (o.bps > 0 && o.total > 0 ? ` · ${t('eta')}: ${tm((o.total - o.bytes) / o.bps)}` : '');
  }
};
EV.download = o => {
  delete PROG[o.id]; if (o.state === 'done') toast(t('added')); if (o.state === 'failed') toast(emsg(o.error));
  if (sheet) paintSheet(); refreshSoon();
};
EV.library = () => refreshSoon();
EV.resume = async () => { ST = await call('state'); refreshSoon(); };
EV.install = o => { if (o.status !== 0) toast(emsg({ 2: 'BLOCKED', 3: 'ABORTED', 4: 'INVALID', 5: 'CONFLICT', 6: 'STORAGE', 7: 'SDK' }[o.status] || 'UNKNOWN')); refreshSoon(); };
EV.perm = async () => { ST = await call('state'); if (tab === 'settings') pSet(); };
EV.catalog = async o => {
  if (o.stage === 'count') { const e = $('#cprog'); if (e) e.textContent = t('c_count').replace('{n}', o.n); }
  if (o.stage === 'done') { ST = await call('state'); if (o.error) toast(emsg(o.error)); if (['home', 'store'].includes(tab)) go(tab); }
};

/* ---------- actions ---------- */
async function playGame(pkg, force) {
  try {
    const r = await call('play', { pkg, force });
    if (r.warnings && r.warnings.length) {
      const m = document.createElement('div'); m.className = 'modal';
      m.innerHTML = `<div class="panel">${r.warnings.map(w => `<p>⚠ ${t('w_' + w)}</p>`).join('')}<div style="display:flex;gap:8px;margin-top:12px"><button class="btn play" data-act="playforce" data-pkg="${esc(pkg)}">${t('playany')}</button><button class="btn ghost" data-act="closemodal">${t('cancel')}</button></div></div>`;
      document.body.appendChild(m);
    }
  } catch (e) { fail(e); }
}
const ACT = {
  go: d => go(d.tab), sheet: openSheet, closesheet: closeSheet, closemodal: () => $('.modal') && $('.modal').remove(),
  gostore: () => { go('store'); },
  cat: d => { cat = d.cat; q = ''; go('store'); },
  cat2: d => { cat = d.cat; pStore(); },
  sort: d => { sort = d.sort; pStore(); },
  more: () => load(false),
  refreshcat: () => { call('catalogRefresh'); toast(t('c_loading')); },
  get: async d => {
    try { if (!ST.notifGranted) call('notifPerm'); await call('download', { pkg: d.pkg }); toast(t('st_queued')); refreshSoon(); } catch (e) { fail(e); }
  },
  install: async d => {
    try { await call('install', { id: +d.id }); refreshSoon(); }
    catch (e) { fail(e); if (e.error === 'NEED_PERM') call('installSettings'); }
  },
  play: d => playGame(d.pkg, false),
  playforce: d => { ACT.closemodal(); playGame(d.pkg, true); },
  appinfo: d => call('appInfo', { pkg: d.pkg }),
  libremove: async d => { await call('libRemove', { id: +d.id }); go('library'); },
  dl: async d => { await call('dlAction', { action: d.a, id: +d.id }); setTimeout(paintSheet, 400); },
  dlrm: async d => { await call('dlRemove', { id: +d.id }); paintSheet(); },
  savetok: async () => { await call('gh.setToken', { token: $('#tok').value }); pGh(); },
  clrtok: async () => { await call('gh.setToken', { token: '' }); pGh(); },
  ghload: ghLoad, ghruns: d => ghRuns(d.wf), ghart: (d, el) => ghArts(el),
  ghdl: async d => {
    try { await call('gh.download', { repo: ghRepo, artifact: +d.id, name: d.name, size: +d.size }); toast(t('st_queued')); openSheet(); } catch (e) { fail(e); }
  },
  psettings: d => call('perf.settings', { kind: d.kind }),
  shortcut: async d => { try { await call('perf.shortcut', { pkg: d.pkg }); } catch (e) { fail(e); } },
  clearfiles: async () => { await call('clearFiles'); pSet(); },
  notifperm: () => call('notifPerm')
};
document.addEventListener('click', e => { const el = e.target.closest('[data-act]'); if (el && ACT[el.dataset.act]) ACT[el.dataset.act](el.dataset, el); });
document.addEventListener('change', async e => {
  const el = e.target;
  if (el.dataset.pref) { ST[el.dataset.pref] = el.checked; call('setPref', { key: el.dataset.pref, value: el.checked }); }
  else if (el.dataset.pre) call('perf.setPre', { pkg: el.dataset.pre, value: el.checked });
  else if ('lang' in el.dataset) { await call('setPref', { key: 'lang', value: el.value }); ST.lang = el.value; applyLang(el.value); shell(); go('settings'); }
  else if ('theme' in el.dataset) { await call('setPref', { key: 'theme', value: el.value }); document.documentElement.dataset.theme = el.value; }
  else if (el.id === 'pname') call('setPref', { key: 'name', value: el.value });
});

/* ---------- boot ---------- */
function langPicker() {
  $('#app').innerHTML = `<div class="center" style="margin:auto;max-width:420px"><h1>SpaceHub</h1><p class="dim">Choose your language · Dilinizi seçin</p><div class="langs">${LANGS.map(l => `<button class="btn" data-act="pick" data-code="${l[0]}">${l[1]}</button>`).join('')}</div></div>`;
}
ACT.pick = async d => { await call('setPref', { key: 'lang', value: d.code }); ST.lang = d.code; applyLang(d.code); start(); };
function start() {
  shell(); go('home');
  if (!ST.catalogCount || Date.now() - ST.catalogAt > 864e5) call('catalogRefresh');
  if (!ST.notifGranted && ST.sdk >= 33) setTimeout(() => call('notifPerm'), 2500);
}
(async function boot() {
  ST = await call('state');
  document.documentElement.dataset.theme = ST.theme || 'nebula';
  if (!ST.lang) return langPicker();
  applyLang(ST.lang); start();
})();
