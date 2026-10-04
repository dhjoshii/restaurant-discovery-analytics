/* ==========================================================================
   Zaika — frontend for the Scala/MongoDB Restaurant Discovery & Analytics API
   Vanilla JS, no build step. Every value from the API is HTML-escaped
   before it reaches the DOM.
   ========================================================================== */
(() => {
  'use strict';

  // ============================================================== utilities
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
  const nfmt = new Intl.NumberFormat('en-US');
  const inrFmt = new Intl.NumberFormat('en-IN');
  const nf = (n) => nfmt.format(n);
  const inr = (n) => (n > 0 ? `₹${inrFmt.format(Math.round(n))}` : '—');
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
  const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ESC[c]);
  const icon = (id, cls = 'ico') => `<svg class="${cls}" aria-hidden="true"><use href="#i-${id}"/></svg>`;
  const fixed = (v, d = 1) => (v === null || v === undefined ? '–' : Number(v).toFixed(d));
  const debounce = (fn, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };
  const cssVar = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  // rAF with a timer fallback: rAF pauses in hidden tabs and UI state must never depend on it alone.
  const raf = (cb) => {
    let done = false;
    const id = requestAnimationFrame((t) => { if (!done) { done = true; cb(t); } });
    setTimeout(() => { if (!done) { done = true; cancelAnimationFrame(id); cb(performance.now()); } }, 100);
  };
  const nextFrame = (fn) => raf(() => raf(fn));
  const storage = {
    get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* private mode */ } },
  };
  const qs = (obj) => {
    const p = new URLSearchParams();
    Object.entries(obj).forEach(([k, v]) => { if (v !== '' && v !== null && v !== undefined) p.set(k, v); });
    const s = p.toString();
    return s ? `?${s}` : '';
  };

  const API = '/api';
  const COLLECTION = 'india_restaurants';
  const DEFAULT_FILTERS = { name: '', city: '', cuisine: '', locality: '', minRating: '', maxCost: '', online: '', table: '', sort: 'rating' };

  // ================================================================== state
  const state = { meta: null, filters: { ...DEFAULT_FILTERS }, page: 1, pageSize: 12, viz: { city: '', cuisine: '' }, current: null, lastFocus: null };

  // ==================================================================== API
  let inflight = 0;
  let bannerTimer = null;
  function slowBanner(on) {
    let el = $('#slowBanner');
    if (on && !el) {
      el = document.createElement('div');
      el.id = 'slowBanner';
      el.className = 'banner';
      el.textContent = 'Waking up the kitchen… connecting to the database';
      document.body.appendChild(el);
    } else if (!on && el) el.remove();
  }
  async function api(path, options = {}) {
    inflight += 1;
    if (!bannerTimer) bannerTimer = setTimeout(() => slowBanner(true), 2500);
    try {
      const res = await fetch(path, { ...options, headers: { Accept: 'application/json', ...(options.body ? { 'Content-Type': 'application/json' } : {}) } });
      let data = null;
      try { data = await res.json(); } catch (e) { /* not JSON */ }
      if (!res.ok) {
        const err = new Error((data && data.error) || `Request failed (${res.status})`);
        err.details = (data && data.details) || [];
        err.status = res.status;
        throw err;
      }
      return data;
    } catch (e) {
      if (!e.status) { e.details = e.details || []; if (e.message === 'Failed to fetch') e.message = 'Network error — is the server running?'; }
      throw e;
    } finally {
      inflight -= 1;
      if (inflight === 0) { clearTimeout(bannerTimer); bannerTimer = null; slowBanner(false); }
    }
  }

  // ================================================================= toasts
  function toast(title, message = '', type = 'ok') {
    const el = document.createElement('div');
    el.className = `toast${type === 'error' ? ' is-error' : ''}`;
    el.setAttribute('role', type === 'error' ? 'alert' : 'status');
    el.innerHTML = `${icon(type === 'error' ? 'alert' : 'check')}<div><b>${esc(title)}</b>${message ? `<span>${esc(message)}</span>` : ''}</div>`;
    $('#toasts').appendChild(el);
    setTimeout(() => { el.classList.add('is-out'); el.addEventListener('animationend', () => el.remove(), { once: true }); }, type === 'error' ? 6000 : 3800);
  }

  // ========================================================== rough sketch
  const hasRough = () => typeof window.rough !== 'undefined';
  let seedCounter = 1;
  /** Draws every <svg data-rough="bar"> inside `root` with Rough.js (hachure fill, ink outline). */
  function paintRough(root = document) {
    if (!hasRough()) return;
    document.documentElement.classList.add('has-rough');
    const ink = cssVar('--ink');
    $$('svg[data-rough="bar"]', root).forEach((svg) => {
      const host = svg.parentElement;
      const w = host.offsetWidth;
      const h = host.offsetHeight;
      if (!w || !h) return;
      const seed = Number(svg.dataset.seed || (svg.dataset.seed = String(seedCounter++)));
      svg.setAttribute('viewBox', `0 0 ${w} ${h}`);
      svg.innerHTML = '';
      const pct = Math.max(0, Math.min(100, Number(svg.dataset.pct) || 0));
      const bw = pct === 0 ? 0 : Math.max(4, ((w - 4) * pct) / 100);
      if (bw > 0) {
        svg.appendChild(window.rough.svg(svg).rectangle(2, 3, bw, h - 6, {
          fill: cssVar(svg.dataset.color || '--chart'), fillStyle: 'hachure', hachureGap: 4.2, hachureAngle: -41, fillWeight: 1.4,
          stroke: ink, strokeWidth: 1.4, roughness: 1.3, bowing: 1.1, seed,
        }));
      }
    });
  }
  const repaintAll = debounce(() => paintRough(document), 180);
  let lastWidth = window.innerWidth;
  window.addEventListener('resize', () => { if (window.innerWidth !== lastWidth) { lastWidth = window.innerWidth; repaintAll(); } });
  window.addEventListener('load', () => paintRough(document));

  // ================================================================== theme
  $('#themeToggle').addEventListener('click', () => {
    const next = document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    $('meta[name="theme-color"]').setAttribute('content', next === 'dark' ? '#1b130e' : '#f6ecd9');
    storage.set('zaika-theme', next);
    paintRough(document);
  });

  // ================================================================= motion
  $$('[data-split]').forEach((el) => {
    const words = el.textContent.trim().split(/\s+/);
    el.setAttribute('aria-label', el.textContent.trim());
    el.innerHTML = words.map((w, i) => `<span class="word" aria-hidden="true"><span style="--i:${i}">${esc(w)}</span></span>`).join(' ');
  });

  const revealObserver = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return;
      const el = entry.target;
      el.classList.add('is-in');
      if (el.dataset.viz) $('.viz-body', el).classList.add('is-drawn');
      if (el.id === 'stats') countUpAll();
      revealObserver.unobserve(el);
    });
  }, { threshold: 0.12, rootMargin: '0px 0px -40px 0px' });
  const observeReveals = (root = document) => $$('[data-reveal], [data-split], .doodle', root).forEach((el) => { if (!el.classList.contains('is-in')) revealObserver.observe(el); });
  observeReveals();
  revealObserver.observe($('#stats'));

  const progressBar = $('.scroll-progress span');
  const nav = $('#nav');
  const parallax = $$('[data-parallax]');
  let ticking = false;
  function onScroll() {
    if (ticking) return;
    ticking = true;
    raf(() => {
      const y = window.scrollY;
      const max = document.documentElement.scrollHeight - window.innerHeight;
      progressBar.style.transform = `scaleX(${max > 0 ? Math.min(1, y / max) : 0})`;
      nav.classList.toggle('is-scrolled', y > 24);
      if (!reduceMotion) parallax.forEach((el) => { el.style.transform = `translate3d(0, ${(y * Number(el.dataset.parallax)).toFixed(1)}px, 0)`; });
      ticking = false;
    });
  }
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();

  const sectionObserver = new IntersectionObserver((entries) => {
    entries.forEach((e) => { if (e.isIntersecting) $$('.nav-links a').forEach((a) => a.classList.toggle('is-active', a.dataset.nav === e.target.id)); });
  }, { rootMargin: '-45% 0px -50% 0px' });
  ['explore', 'analytics', 'indexes'].forEach((id) => sectionObserver.observe(document.getElementById(id)));

  document.addEventListener('pointerdown', (e) => {
    const btn = e.target.closest('.btn, .chip, .icon-btn');
    if (!btn || reduceMotion) return;
    const rect = btn.getBoundingClientRect();
    const size = Math.max(rect.width, rect.height) * 2;
    const r = document.createElement('span');
    r.className = 'ripple';
    r.style.cssText = `width:${size}px;height:${size}px;left:${e.clientX - rect.left - size / 2}px;top:${e.clientY - rect.top - size / 2}px`;
    btn.style.overflow = 'hidden';
    btn.appendChild(r);
    r.addEventListener('animationend', () => r.remove(), { once: true });
  });

  if (!reduceMotion) {
    $('.hero').addEventListener('pointermove', (e) => {
      const cx = e.clientX / window.innerWidth - 0.5;
      const cy = e.clientY / window.innerHeight - 0.5;
      $$('#heroArt .note').forEach((n) => {
        const d = Number(n.dataset.depth);
        n.style.transform = `translate3d(${(cx * d).toFixed(1)}px, ${(cy * d).toFixed(1)}px, 0) rotate(${(cx * 3).toFixed(2)}deg)`;
      });
    });
  }

  // ========================================================== ink cursor
  // A precise chilli dot + a hand-drawn ring that trails behind it, snaps around
  // buttons, labels cards and sprinkles spice. Mouse/trackpad only; touch devices
  // and reduced-motion users keep the native cursor.
  (function inkCursor() {
    if (reduceMotion || !window.matchMedia('(hover: hover) and (pointer: fine)').matches || window.matchMedia('(forced-colors: active)').matches) return;
    const SNAP = '.btn, .icon-btn, .chip, .viz-tabs button, .mini-toggle button, .nav-links a';
    const HOVER = 'a[href], button, [role="button"], summary, label.switch, .bar-row, tr.is-link, select';
    const TEXT = 'input:not([type="range"]):not([type="checkbox"]), textarea, [contenteditable="true"], .code';
    const LABELS = { card: 'view', rank: 'view', 'is-link': 'open' };
    const SPICES = ['--turmeric', '--chilli', '--cardamom'];

    const dot = document.createElement('div');
    const ring = document.createElement('div');
    dot.className = 'cursor-dot';
    ring.className = 'cursor-ring';
    ring.innerHTML = '<span class="cursor-label"></span>';
    dot.setAttribute('aria-hidden', 'true');
    ring.setAttribute('aria-hidden', 'true');
    document.body.append(dot, ring);
    const label = ring.firstChild;

    const pool = Array.from({ length: 18 }, () => {
      const s = document.createElement('span');
      s.className = 'spice';
      s.setAttribute('aria-hidden', 'true');
      document.body.appendChild(s);
      return s;
    });
    let poolIdx = 0;
    let lastSpice = { x: 0, y: 0 };

    const pos = { x: -100, y: -100 };
    const cur = { x: -100, y: -100, w: 38, h: 38 };
    let snapEl = null;
    let running = false;

    function sprinkle(x, y) {
      if (Math.hypot(x - lastSpice.x, y - lastSpice.y) < 34) return;
      lastSpice = { x, y };
      const s = pool[poolIdx++ % pool.length];
      s.style.setProperty('--c', `var(${SPICES[poolIdx % SPICES.length]})`);
      s.style.setProperty('--dx', `${(Math.random() * 16 - 8).toFixed(1)}px`);
      s.style.left = `${x}px`;
      s.style.top = `${y}px`;
      s.classList.remove('is-on');
      void s.offsetWidth; // restart the CSS animation
      s.classList.add('is-on');
    }

    function frame() {
      let tx = pos.x, ty = pos.y, tw = 38, th = 38;
      if (snapEl && snapEl.isConnected) {
        const r = snapEl.getBoundingClientRect();
        tx = r.left + r.width / 2 + (pos.x - (r.left + r.width / 2)) * 0.12;
        ty = r.top + r.height / 2 + (pos.y - (r.top + r.height / 2)) * 0.12;
        tw = r.width + 14;
        th = r.height + 14;
      }
      const k = snapEl ? 0.24 : 0.18;
      cur.x += (tx - cur.x) * k;
      cur.y += (ty - cur.y) * k;
      cur.w += (tw - cur.w) * 0.22;
      cur.h += (th - cur.h) * 0.22;
      // `translate`, not `transform`: the CSS `scale` states would otherwise scale the offset too.
      ring.style.translate = `${(cur.x - cur.w / 2).toFixed(1)}px ${(cur.y - cur.h / 2).toFixed(1)}px`;
      ring.style.width = `${cur.w.toFixed(1)}px`;
      ring.style.height = `${cur.h.toFixed(1)}px`;
      const settled = Math.abs(tx - cur.x) < 0.2 && Math.abs(ty - cur.y) < 0.2 && Math.abs(tw - cur.w) < 0.2 && Math.abs(th - cur.h) < 0.2;
      if (settled && !snapEl) { running = false; return; }
      requestAnimationFrame(frame);
    }
    function wake() { if (!running) { running = true; requestAnimationFrame(frame); } }

    function classify(target) {
      const el = target instanceof Element ? target : null;
      const isText = !!(el && el.closest(TEXT));
      snapEl = !isText && el ? el.closest(SNAP) : null;
      const labelled = !isText && el ? el.closest('.card, .rank, tr.is-link') : null;
      const key = labelled ? ['card', 'rank', 'is-link'].find((c) => labelled.classList.contains(c)) : null;
      label.textContent = key ? LABELS[key] : '';
      ring.classList.toggle('is-snap', !!snapEl);
      ring.classList.toggle('is-label', !!key);
      ring.classList.toggle('is-hover', !snapEl && !key && !isText && !!(el && el.closest(HOVER)));
      document.documentElement.classList.toggle('cursor-text', isText);
    }

    document.addEventListener('pointermove', (e) => {
      if (e.pointerType !== 'mouse') return;
      pos.x = e.clientX;
      pos.y = e.clientY;
      dot.style.translate = `${pos.x}px ${pos.y}px`;
      document.documentElement.classList.add('has-cursor', 'cursor-in');
      classify(e.target);
      if (!snapEl && !document.documentElement.classList.contains('cursor-text')) sprinkle(pos.x, pos.y);
      wake();
    }, { passive: true });
    document.addEventListener('pointerdown', () => ring.classList.add('is-down'));
    document.addEventListener('pointerup', () => ring.classList.remove('is-down'));
    document.documentElement.addEventListener('mouseleave', () => document.documentElement.classList.remove('cursor-in'));
    window.addEventListener('blur', () => document.documentElement.classList.remove('cursor-in'));
    window.addEventListener('scroll', () => { if (snapEl) wake(); }, { passive: true });
  })();

  let statsVisible = false;
  function countUpAll() {
    statsVisible = true;
    const o = state.meta && state.meta.overview;
    if (!o) return;
    $$('[data-count]').forEach((el) => countUp(el, Number(o[el.dataset.count] || 0)));
  }
  function countUp(el, target) {
    if (reduceMotion) { el.textContent = nf(target); return; }
    const from = Number(el.textContent.replace(/[^\d]/g, '')) || 0;
    if (from === target) return;
    const start = performance.now();
    const dur = from ? 700 : 1500;
    const step = (now) => {
      const t = Math.min(1, (now - start) / dur);
      const eased = t === 1 ? 1 : 1 - Math.pow(2, -10 * t);
      el.textContent = nf(Math.round(from + (target - from) * eased));
      if (t < 1) raf(step);
    };
    raf(step);
  }

  const tip = document.createElement('div');
  tip.className = 'tooltip';
  tip.setAttribute('role', 'tooltip');
  document.body.appendChild(tip);
  function showTip(el, x, y) {
    tip.innerHTML = `<b>${esc(el.dataset.tipTitle)}</b><span>${esc(el.dataset.tipText || '')}</span>`;
    tip.classList.add('is-on');
    placeTip(x, y);
  }
  function placeTip(x, y) {
    const w = tip.offsetWidth;
    const h = tip.offsetHeight;
    tip.style.left = `${Math.min(window.innerWidth - w - 8, Math.max(8, x + 14))}px`;
    tip.style.top = `${y - h - 12 < 8 ? y + 18 : y - h - 12}px`;
  }
  document.addEventListener('pointerover', (e) => { const el = e.target.closest('[data-tip-title]'); if (el) showTip(el, e.clientX, e.clientY); });
  document.addEventListener('pointermove', (e) => { if (tip.classList.contains('is-on') && e.target.closest('[data-tip-title]')) placeTip(e.clientX, e.clientY); });
  document.addEventListener('pointerout', (e) => { const el = e.target.closest('[data-tip-title]'); if (el && !el.contains(e.relatedTarget)) tip.classList.remove('is-on'); });
  document.addEventListener('focusin', (e) => { const el = e.target.closest('[data-tip-title]'); if (el) { const r = el.getBoundingClientRect(); showTip(el, r.left + r.width / 2, r.top); } });
  document.addEventListener('focusout', (e) => { if (e.target.closest('[data-tip-title]')) tip.classList.remove('is-on'); });
  window.addEventListener('scroll', () => tip.classList.remove('is-on'), { passive: true });

  // ============================================================ chart parts
  /** Horizontal sketched bars. `domainMax` fixes the scale (e.g. 5 for ratings, 100 for %). */
  function barList(items, { domainMax = null, color = '--chart' } = {}) {
    if (!items.length) return '<p class="muted">Nothing to show for this scope.</p>';
    const max = domainMax || Math.max(...items.map((d) => d.value)) || 1;
    return `<ol class="bars">${items.map((d, i) => {
      const pct = Math.max(0, Math.min(100, (d.value / max) * 100));
      return `<li class="bar-row" tabindex="0" data-tip-title="${esc(d.label)}" data-tip-text="${esc(d.tip)}">
        <span class="bar-label">${esc(d.label)}</span>
        <span class="bar-track"><span class="bar-draw" style="--i:${i}"><span class="bar-fallback" style="--w:${pct}%"></span><svg data-rough="bar" data-color="${color}" data-pct="${pct}" aria-hidden="true"></svg></span></span>
        <span class="bar-value">${esc(d.display)}</span>
      </li>`;
    }).join('')}</ol>`;
  }

  function dataTable(rows, cols, link) {
    if (!rows.length) return '<p class="muted">No rows.</p>';
    return `<div class="table-scroll"><table class="data-table"><thead><tr>${cols.map((c) => `<th class="${c.num ? 'num' : ''}">${esc(c.l)}</th>`).join('')}</tr></thead>
      <tbody>${rows.map((r) => `<tr ${link ? `class="is-link" data-open="${esc(r[link])}" tabindex="0"` : ''}>${cols.map((c) => {
        const raw = r[c.k];
        const val = c.f ? c.f(raw, r) : typeof raw === 'number' ? nf(raw) : Array.isArray(raw) ? raw.join(', ') : raw;
        return `<td class="${c.num ? 'num' : ''}">${esc(val ?? '–')}</td>`;
      }).join('')}</tr>`).join('')}</tbody></table></div>`;
  }

  function pipelineView(pipeline) {
    const html = esc(JSON.stringify(pipeline, null, 2))
      .replace(/(&quot;\$[\w.]+&quot;)(\s*:)/g, '<span class="op">$1</span>$2')
      .replace(/(&quot;[^&]*?&quot;)(\s*:)/g, (m, k, c) => `<span class="k">${k}</span>${c}`)
      .replace(/(:\s*)(&quot;.*?&quot;)/g, '$1<span class="s">$2</span>')
      .replace(/(:\s*|\[\s*|,\s*)(-?\d+(?:\.\d+)?)/g, '$1<span class="n">$2</span>');
    return `<pre class="code" tabindex="0"><span class="muted">db.${COLLECTION}.aggregate(</span>\n${html}\n<span class="muted">)</span></pre>`;
  }

  const switchField = (name, label, checked) =>
    `<label class="switch"><input type="checkbox" name="${name}" ${checked ? 'checked' : ''}><span class="switch-track" aria-hidden="true"></span><span>${esc(label)}</span></label>`;
  const options = (list, selected, allLabel) =>
    `${allLabel !== undefined ? `<option value="">${esc(allLabel)}</option>` : ''}${list.map((o) => {
      const [v, l] = Array.isArray(o) ? o : [o, o];
      return `<option value="${esc(v)}" ${String(v) === String(selected) ? 'selected' : ''}>${esc(l)}</option>`;
    }).join('')}`;

  // ======================================================= rating helpers
  const bandFor = (r) => (r >= 4.5 ? 'Excellent' : r >= 4 ? 'Very Good' : r >= 3.5 ? 'Good' : r >= 2.5 ? 'Average' : 'Poor');
  const bandShort = { Excellent: 'excellent', 'Very Good': 'very good', Good: 'good', Average: 'average', Poor: 'poor', 'Not rated': 'new' };
  const ratingBadge = (r, size = '') => (r.rated
    ? `<span class="badge ${size}" data-band="${esc(r.ratingText)}" title="${fixed(r.rating)} / 5 · ${esc(r.ratingText)}">${fixed(r.rating)}<small>${esc(bandShort[r.ratingText] || '')}</small></span>`
    : `<span class="badge ${size}" title="Not rated yet">–<small>new</small></span>`);
  const priceSymbol = (p) => '₹'.repeat(Math.max(1, Math.min(4, p || 1)));

  // ================================================================== META
  let metaRetry = null;
  async function loadMeta() {
    try {
      const meta = await api(`${API}/meta`);
      state.meta = meta;
      $('#liveDot').className = 'live-dot is-live';
      $('#liveText').textContent = `Live · ${meta.namespace}${meta.server ? ` · MongoDB ${meta.server}` : ''}`;
      const o = meta.overview;
      $('#heroLead').textContent = `Search, curate and analyse ${nf(o.restaurants)} restaurants across ${o.cities} Indian cities — ratings, cuisines, cost for two and more. Built with Scala 3 on MongoDB.`;
      $('#cuisineList').innerHTML = meta.cuisines.map((c) => `<option value="${esc(c)}">`).join('');
      $('#cityList').innerHTML = meta.cities.map((c) => `<option value="${esc(c)}">`).join('');
      renderChips(meta);
      renderFilters();
      renderVizFilters();
      if (statsVisible) countUpAll();
      return true;
    } catch (e) {
      $('#liveDot').className = 'live-dot is-down';
      $('#liveText').textContent = 'Database unreachable — retrying…';
      clearTimeout(metaRetry);
      metaRetry = setTimeout(() => loadMeta().then((ok) => ok && reloadData()), 8000);
      return false;
    }
  }

  function renderChips(meta) {
    const cuisines = ['North Indian', 'Biryani', 'South Indian', 'Street Food', 'Mughlai'].filter((c) => meta.cuisines.includes(c)).slice(0, 4);
    const cities = ['New Delhi', 'Mumbai', 'Bangalore', 'Kolkata', 'Hyderabad', 'Chennai'].filter((c) => meta.cities.includes(c)).slice(0, 4);
    const chips = [
      ...cuisines.map((c) => ({ label: c, kind: 'cuisine', set: { cuisine: c } })),
      ...cities.map((c) => ({ label: c, kind: 'city', set: { city: c } })),
      { label: '4.5 ★ +', kind: 'rating', set: { minRating: '4.5' } },
    ];
    $('#quickChips').innerHTML = chips.map((c, i) => `<button class="chip" type="button" data-chip="${i}">${esc(c.label)} <small>${esc(c.kind)}</small></button>`).join('');
    $('#quickChips').onclick = (e) => {
      const b = e.target.closest('[data-chip]');
      if (!b) return;
      state.filters = { ...DEFAULT_FILTERS, ...chips[Number(b.dataset.chip)].set };
      renderFilters();
      search();
      $('#explore').scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth' });
    };
  }

  // =============================================================== EXPLORE
  const form = $('#filters');
  const fld = (n) => form.elements.namedItem(n);

  function renderFilters() {
    if (!state.meta) return;
    const f = state.filters;
    const meta = state.meta;
    form.innerHTML = `
      <label class="field f-2 f-name"><span>Name</span><span class="input-wrap">${icon('search')}<input name="name" type="search" placeholder="Restaurant name" value="${esc(f.name)}"></span></label>
      <label class="field f-1"><span>City</span><select name="city">${options(meta.cities, f.city, 'All cities')}</select></label>
      <label class="field f-1"><span>Cuisine</span><select name="cuisine">${options(meta.cuisines, f.cuisine, 'All cuisines')}</select></label>
      <label class="field f-1"><span>Locality</span><input name="locality" placeholder="e.g. Connaught Place" value="${esc(f.locality)}"></label>
      <label class="field f-1"><span>Sort</span><select name="sort">${options(meta.sorts.map((s) => [s.key, s.label]), f.sort)}</select></label>
      <label class="field f-1"><span>Rating</span><select name="minRating">${options([['3.5', '3.5 ★ and up'], ['4', '4.0 ★ and up'], ['4.5', '4.5 ★ and up']], f.minRating, 'Any rating')}</select></label>
      <label class="field f-1"><span>Budget for two</span><select name="maxCost">${options([['300', 'up to ₹300'], ['500', 'up to ₹500'], ['1000', 'up to ₹1,000'], ['2000', 'up to ₹2,000']], f.maxCost, 'Any budget')}</select></label>
      <div class="f-row">
        <div class="f-toggles">${switchField('online', 'Online delivery', f.online === '1')}${switchField('table', 'Table booking', f.table === '1')}</div>
        <button class="btn btn-sm" type="reset">${icon('reset')}<span>Reset</span></button>
      </div>`;
  }
  function readForm() {
    const f = {};
    Object.keys(DEFAULT_FILTERS).forEach((k) => {
      const el = fld(k);
      if (!el) { f[k] = state.filters[k] ?? ''; return; }
      f[k] = el.type === 'checkbox' ? (el.checked ? '1' : '') : String(el.value).trim();
    });
    state.filters = f;
  }

  let searchSeq = 0;
  async function search({ resetPage = true } = {}) {
    if (!state.meta) return;
    if (form.elements.length) readForm();
    if (resetPage) state.page = 1;
    const seq = ++searchSeq;
    const cards = $('#cards');
    if (cards.children.length) cards.classList.add('is-loading');
    cards.setAttribute('aria-busy', 'true');
    try {
      const data = await api(`${API}/restaurants${qs({ ...state.filters, page: state.page, pageSize: state.pageSize, explain: 1 })}`);
      if (seq !== searchSeq) return;
      renderResults(data);
    } catch (e) {
      if (seq !== searchSeq) return;
      cards.innerHTML = `<div class="empty"><h3>Something went wrong</h3><p>${esc(e.message)}</p>${e.details.length > 1 ? `<p>${e.details.map(esc).join('<br>')}</p>` : ''}</div>`;
      $('#resultSummary').textContent = 'Search failed.';
      $('#planChip').hidden = true;
      $('#pager').hidden = true;
    } finally {
      if (seq === searchSeq) { cards.classList.remove('is-loading'); cards.setAttribute('aria-busy', 'false'); }
    }
  }

  function card(r, i) {
    const extra = r.cuisines.length > 3 ? `<span class="tag">+${r.cuisines.length - 3}</span>` : '';
    return `<article class="card" tabindex="0" role="button" style="--i:${i}" data-id="${esc(r.restaurantId)}" aria-label="Open ${esc(r.displayName)}">
      <div class="card-top"><div><h3>${esc(r.displayName)}</h3><p class="card-sub">${esc(r.locality)} · ${esc(r.city)}</p></div>${ratingBadge(r)}</div>
      <div class="tags">${r.cuisines.slice(0, 3).map((c) => `<span class="tag">${esc(c)}</span>`).join('')}${extra}</div>
      <div class="card-foot"><span><b>${inr(r.costForTwo)}</b> for two</span><span>${nf(r.votes)} votes</span>${r.hasOnlineDelivery ? `<span class="tag tag-good" title="Online delivery">${icon('bike')}</span>` : ''}</div>
    </article>`;
  }

  function renderResults(data) {
    $('#cards').innerHTML = data.items.length
      ? data.items.map(card).join('')
      : `<div class="empty"><h3>Khaali plate!</h3><p>No restaurants match ${esc(data.criteria.summary)}. Try loosening a filter.</p></div>`;
    const from = data.total === 0 ? 0 : (data.page - 1) * data.pageSize + 1;
    const to = Math.min(data.total, data.page * data.pageSize);
    $('#resultSummary').innerHTML = data.total === 0 ? 'No matches.' : `Showing <strong>${nf(from)}–${nf(to)}</strong> of <strong>${nf(data.total)}</strong> · ${esc(data.criteria.summary)}`;
    const plan = data.plan;
    const chip = $('#planChip');
    if (plan) {
      chip.innerHTML = `${plan.usesIndex ? `<span class="tag-ix">IXSCAN</span> ${esc(plan.indexes.join(', '))}` : '<span class="tag-scan">COLLSCAN</span>'} · keys ${nf(plan.keysExamined)} · docs ${nf(plan.docsExamined)} · ${nf(plan.millis)} ms`;
      chip.title = `Winning plan: ${plan.stages.join(' → ')}`;
      chip.hidden = false;
    } else chip.hidden = true;
    const pager = $('#pager');
    pager.hidden = data.totalPages <= 1;
    $('#pagerInfo').textContent = `Page ${data.page} of ${nf(data.totalPages)}`;
    $('[data-page="prev"]', pager).disabled = data.page <= 1;
    $('[data-page="next"]', pager).disabled = data.page >= data.totalPages;
  }

  const skeletons = () => { $('#cards').innerHTML = Array.from({ length: 6 }, () => '<div class="skeleton"></div>').join(''); };

  const debouncedSearch = debounce(() => search(), 380);
  form.addEventListener('input', (e) => { if (e.target.matches('input')) debouncedSearch(); });
  form.addEventListener('change', (e) => { if (e.target.matches('select')) search(); });
  form.addEventListener('submit', (e) => { e.preventDefault(); search(); });
  form.addEventListener('reset', (e) => { e.preventDefault(); state.filters = { ...DEFAULT_FILTERS }; renderFilters(); search(); });
  $('#pager').addEventListener('click', (e) => {
    const b = e.target.closest('[data-page]');
    if (!b || b.disabled) return;
    state.page += b.dataset.page === 'next' ? 1 : -1;
    search({ resetPage: false }).then(() => $('#explore').scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth' }));
  });
  $('#cards').addEventListener('click', (e) => { const c = e.target.closest('.card'); if (c) openDrawer(c.dataset.id); });
  $('#cards').addEventListener('keydown', (e) => { const c = e.target.closest('.card'); if (c && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); openDrawer(c.dataset.id); } });
  $('#heroSearch').addEventListener('submit', (e) => {
    e.preventDefault();
    state.filters = { ...DEFAULT_FILTERS, name: $('#heroQuery').value.trim() };
    renderFilters();
    search();
    $('#explore').scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth' });
  });

  // ================================================================ DRAWER
  const drawer = $('#drawer');
  const scrim = $('#scrim');
  const lockScroll = (on) => { document.documentElement.style.overflow = on ? 'hidden' : ''; };

  async function openDrawer(restaurantId) {
    if (drawer.hidden) state.lastFocus = document.activeElement;
    state.current = null;
    const stale = $('.drawer-actions', drawer);
    if (stale) stale.remove();
    drawer.hidden = false;
    scrim.hidden = false;
    lockScroll(true);
    $('#drawerContent').innerHTML = '<div class="skeleton" style="height:120px;margin-bottom:14px"></div><div class="skeleton" style="height:260px"></div>';
    nextFrame(() => { drawer.classList.add('is-on'); scrim.classList.add('is-on'); });
    try {
      renderDrawer(await api(`${API}/restaurants/${encodeURIComponent(restaurantId)}`));
    } catch (e) {
      $('#drawerContent').innerHTML = `<button class="icon-btn drawer-close" type="button" data-close-drawer aria-label="Close">${icon('x')}</button><div class="empty"><h3>Not found</h3><p>${esc(e.message)}</p></div>`;
    }
  }

  function drawerHtml(r) {
    const coord = r.coord;
    const mapQuery = coord ? `${coord.lat},${coord.lon}` : `${r.name} ${r.fullAddress}`;
    return `
      <p class="d-eyebrow"><span class="tag">${esc(r.city)}</span><span class="tag">${priceSymbol(r.priceRange)}</span><span class="tag">${esc(r.ratingText)}</span></p>
      <h2 class="d-title" id="drawerTitle">${esc(r.displayName)}</h2>
      <p class="d-address">${icon('pin')}<span>${esc(r.fullAddress || 'Address not recorded')}<br><a href="https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(mapQuery)}" target="_blank" rel="noopener">Open in Google Maps ↗</a></span></p>
      <div class="d-summary">
        ${ratingBadge(r, 'badge-lg')}
        <div class="kv"><span>Rating</span><b>${r.rated ? `${fixed(r.rating)} / 5` : 'Not rated'}</b> <small>${nf(r.votes)} votes</small></div>
        <div class="kv"><span>Cost for two</span><b>${inr(r.costForTwo)}</b> <small>${priceSymbol(r.priceRange)} price range</small></div>
      </div>
      <h3 class="d-section-title">Cuisines</h3>
      <div class="tags">${r.cuisines.map((c) => `<span class="tag">${esc(c)}</span>`).join('') || '<span class="muted">Not recorded</span>'}</div>
      <h3 class="d-section-title">Services</h3>
      <div class="services">
        <span class="service ${r.hasOnlineDelivery ? 'on' : ''}">${icon('bike')} Online delivery: ${r.hasOnlineDelivery ? 'yes' : 'no'}</span>
        <span class="service ${r.hasTableBooking ? 'on' : ''}">${icon('table')} Table booking: ${r.hasTableBooking ? 'yes' : 'no'}</span>
      </div>
      <h3 class="d-section-title">Identifiers</h3>
      <dl class="d-ids"><dt>restaurant_id</dt><dd>${esc(r.restaurantId)}</dd><dt>_id</dt><dd>${esc(r.id || '–')}</dd>${coord ? `<dt>coord</dt><dd>${coord.lat.toFixed(5)}, ${coord.lon.toFixed(5)}</dd>` : ''}</dl>`;
  }

  function renderDrawer(r) {
    state.current = r;
    $('#drawerContent').innerHTML = `<button class="icon-btn drawer-close" type="button" data-close-drawer aria-label="Close details">${icon('x')}</button>${drawerHtml(r)}`;
    let actions = $('.drawer-actions', drawer);
    if (!actions) { actions = document.createElement('div'); actions.className = 'drawer-actions'; drawer.appendChild(actions); }
    actions.innerHTML = `
      <button class="btn btn-sm" type="button" data-action="edit">${icon('edit')}<span>Edit</span></button>
      <button class="btn btn-sm" type="button" data-action="rate">${icon('star')}<span>Rate it</span></button>
      <button class="btn btn-sm btn-danger" type="button" data-action="delete">${icon('trash')}<span>Delete</span></button>`;
    $('[data-close-drawer]', drawer).focus();
  }

  function closeDrawer() {
    drawer.classList.remove('is-on');
    scrim.classList.remove('is-on');
    setTimeout(() => { drawer.hidden = true; scrim.hidden = true; if ($('#modalLayer').hidden) lockScroll(false); }, 500);
    state.current = null;
    if (state.lastFocus && document.contains(state.lastFocus)) state.lastFocus.focus({ preventScroll: true });
  }
  scrim.addEventListener('click', closeDrawer);
  drawer.addEventListener('click', (e) => {
    if (e.target.closest('[data-close-drawer]')) { closeDrawer(); return; }
    const a = e.target.closest('[data-action]');
    if (!a || !state.current) return;
    if (a.dataset.action === 'edit') openEntityForm(state.current);
    if (a.dataset.action === 'rate') openRateForm(state.current);
    if (a.dataset.action === 'delete') openDeleteConfirm(state.current);
  });

  // ================================================================= MODAL
  const layer = $('#modalLayer');
  const modal = $('#modal');
  let modalReturnFocus = null;

  function openModal(html, { small = false } = {}) {
    modalReturnFocus = document.activeElement;
    modal.className = `modal sk tape${small ? ' modal-sm' : ''}`;
    modal.innerHTML = html;
    layer.hidden = false;
    lockScroll(true);
    nextFrame(() => layer.classList.add('is-on'));
    const first = $('input:not([type="checkbox"]), select, button.btn-primary', modal);
    if (first) setTimeout(() => first.focus(), 80);
  }
  function closeModal() {
    layer.classList.remove('is-on');
    setTimeout(() => { layer.hidden = true; modal.innerHTML = ''; if (drawer.hidden) lockScroll(false); }, 300);
    if (modalReturnFocus && document.contains(modalReturnFocus)) modalReturnFocus.focus({ preventScroll: true });
  }
  layer.addEventListener('click', (e) => { if (e.target === layer || e.target.closest('[data-close-modal]')) closeModal(); });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') { if (!layer.hidden) closeModal(); else if (!drawer.hidden) closeDrawer(); return; }
    if (e.key !== 'Tab') return;
    const container = !layer.hidden ? modal : !drawer.hidden ? drawer : null;
    if (!container) return;
    const focusable = $$('a[href], button:not([disabled]), input, select, textarea, [tabindex="0"]', container).filter((el) => el.offsetParent !== null);
    if (!focusable.length) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  });

  const errorBlock = (err) => `<div class="form-errors" role="alert"><ul>${(err.details && err.details.length ? err.details : [err.message]).map((d) => `<li>${esc(d)}</li>`).join('')}</ul></div>`;
  async function submitWith(btn, fn) {
    btn.disabled = true;
    const label = btn.innerHTML;
    btn.innerHTML = '<span>Saving…</span>';
    try { await fn(); } finally { btn.disabled = false; btn.innerHTML = label; }
  }

  function entityFormHtml(r) {
    const v = (x) => esc(x ?? '');
    return `
      <h2 id="modalTitle">${r ? 'Edit restaurant' : 'Add a restaurant'}</h2>
      <p class="modal-sub">${r ? `Updating <span class="mono">#${esc(r.restaurantId)}</span> with a MongoDB <code>$set</code>.` : `Inserted into <code>${COLLECTION}</code> with <code>insertOne</code>; the restaurant_id is generated.`}</p>
      <div id="formErrors"></div>
      <form id="entityForm" class="form-grid" novalidate>
        <label class="field span-4"><span>Name *</span><input name="name" required maxlength="120" value="${v(r && r.name)}" placeholder="e.g. Saffron Dhaba"></label>
        <label class="field span-2"><span>City *</span><input name="city" required maxlength="60" list="cityList" value="${v(r && r.city)}" placeholder="e.g. New Delhi"></label>
        <label class="field span-3"><span>Locality *</span><input name="locality" required maxlength="120" value="${v(r && r.locality)}" placeholder="e.g. Connaught Place"></label>
        <label class="field span-3"><span>Street address</span><input name="address" maxlength="250" value="${v(r && r.address)}" placeholder="e.g. N-12, Middle Circle"></label>
        <label class="field span-6"><span>Cuisines * <small>comma separated</small></span><input name="cuisines" required list="cuisineList" value="${v(r && r.cuisines.join(', '))}" placeholder="e.g. North Indian, Mughlai"></label>
        <label class="field span-2"><span>Cost for two (₹) *</span><input name="costForTwo" type="number" min="0" max="100000" value="${v(r && r.costForTwo)}" placeholder="e.g. 800"></label>
        <label class="field span-2"><span>Price range</span><select name="priceRange">${options([['1', '₹ budget'], ['2', '₹₹'], ['3', '₹₹₹'], ['4', '₹₹₹₹ fine dining']], r ? r.priceRange : '', r ? undefined : 'Derive from cost')}</select></label>
        <div class="field span-2" style="justify-content:flex-end;gap:10px">${switchField('onlineDelivery', 'Online delivery', r && r.hasOnlineDelivery)}${switchField('tableBooking', 'Table booking', r && r.hasTableBooking)}</div>
        <p class="form-divider">Location <small class="muted">(optional)</small></p>
        <label class="field span-3"><span>Latitude</span><input name="latitude" inputmode="decimal" value="${v(r && r.coord && r.coord.lat)}" placeholder="e.g. 28.6315"></label>
        <label class="field span-3"><span>Longitude</span><input name="longitude" inputmode="decimal" value="${v(r && r.coord && r.coord.lon)}" placeholder="e.g. 77.2167"></label>
      </form>
      <div class="modal-actions">
        <button class="btn" type="button" data-close-modal>Cancel</button>
        <button class="btn btn-primary" type="submit" form="entityForm">${icon(r ? 'check' : 'plus')}<span>${r ? 'Save changes' : 'Add restaurant'}</span></button>
      </div>`;
  }

  /** New restaurant → every field; edit → only the fields that changed. */
  function collect(formEl, r) {
    const fd = Object.fromEntries(new FormData(formEl).entries());
    Object.keys(fd).forEach((k) => { fd[k] = String(fd[k]).trim(); });
    fd.onlineDelivery = formEl.elements.namedItem('onlineDelivery').checked ? 'true' : 'false';
    fd.tableBooking = formEl.elements.namedItem('tableBooking').checked ? 'true' : 'false';
    if (!r) return fd;
    const original = {
      name: r.name, city: r.city, locality: r.locality, address: r.address, cuisines: r.cuisines.join(', '),
      costForTwo: String(r.costForTwo), priceRange: String(r.priceRange),
      onlineDelivery: String(r.hasOnlineDelivery), tableBooking: String(r.hasTableBooking),
      latitude: r.coord ? String(r.coord.lat) : '', longitude: r.coord ? String(r.coord.lon) : '',
    };
    const changed = {};
    Object.keys(original).forEach((k) => { if ((fd[k] ?? '') !== original[k]) changed[k] = fd[k] ?? ''; });
    if (changed.cuisines !== undefined && fd.cuisines.split(',').map((s) => s.trim()).filter(Boolean).join(', ') === original.cuisines) delete changed.cuisines;
    if (changed.latitude !== undefined || changed.longitude !== undefined) { changed.latitude = fd.latitude; changed.longitude = fd.longitude; }
    return changed;
  }

  function openEntityForm(existing = null) {
    openModal(entityFormHtml(existing));
    $('#entityForm').addEventListener('submit', (e) => {
      e.preventDefault();
      const payload = collect(e.target, existing);
      submitWith($('button[type="submit"]', modal), async () => {
        try {
          let saved;
          if (existing) {
            if (!Object.keys(payload).length) { closeModal(); toast('Nothing to save', 'No fields were changed.'); return; }
            saved = await api(`${API}/restaurants/${encodeURIComponent(existing.restaurantId)}`, { method: 'PUT', body: JSON.stringify(payload) });
            toast('Restaurant updated', `${saved.displayName} · ${Object.keys(payload).length} field(s) changed`);
          } else {
            saved = await api(`${API}/restaurants`, { method: 'POST', body: JSON.stringify(payload) });
            toast('Restaurant added', `${saved.displayName} · restaurant_id ${saved.restaurantId}`);
          }
          closeModal();
          afterWrite();
          if (drawer.hidden) openDrawer(saved.restaurantId); else renderDrawer(saved);
        } catch (err) {
          $('#formErrors').innerHTML = errorBlock(err);
          modal.scrollTop = 0;
        }
      });
    });
  }

  function openRateForm(r) {
    openModal(`
      <h2 id="modalTitle">Rate ${esc(r.displayName)}</h2>
      <p class="modal-sub">Your score joins the running average inside MongoDB with an atomic pipeline update. Currently ${r.rated ? `<strong>${fixed(r.rating)}</strong> from ${nf(r.votes)} votes` : 'not rated'}.</p>
      <div id="formErrors"></div>
      <form id="rateForm" novalidate>
        <div class="rating-picker field">
          <input name="rating" type="range" min="1" max="5" step="0.5" value="4" aria-label="Rating from 1 to 5">
          <output id="ratingOut">4.0</output>
        </div>
        <p class="hint">1 = poor · 3 = average · 4 = very good · 5 = excellent</p>
      </form>
      <div class="modal-actions">
        <button class="btn" type="button" data-close-modal>Cancel</button>
        <button class="btn btn-primary" type="submit" form="rateForm">${icon('star')}<span>Add rating</span></button>
      </div>`, { small: true });
    const input = $('[name="rating"]', modal);
    input.addEventListener('input', () => { $('#ratingOut', modal).textContent = Number(input.value).toFixed(1); });
    $('#rateForm').addEventListener('submit', (e) => {
      e.preventDefault();
      submitWith($('button[type="submit"]', modal), async () => {
        try {
          const saved = await api(`${API}/restaurants/${encodeURIComponent(r.restaurantId)}/ratings`, { method: 'POST', body: JSON.stringify({ rating: input.value }) });
          toast('Thanks for rating!', `${saved.displayName} is now ${fixed(saved.rating)} from ${nf(saved.votes)} votes`);
          closeModal();
          renderDrawer(saved);
          afterWrite();
        } catch (err) {
          $('#formErrors').innerHTML = errorBlock(err);
        }
      });
    });
  }

  function openDeleteConfirm(r) {
    openModal(`
      <h2 id="modalTitle">Delete restaurant?</h2>
      <p class="modal-sub"><strong>${esc(r.displayName)}</strong> (<span class="mono">#${esc(r.restaurantId)}</span>) will be removed with <code>findOneAndDelete</code>. This cannot be undone.</p>
      <div id="formErrors"></div>
      <div class="modal-actions">
        <button class="btn" type="button" data-close-modal>Keep it</button>
        <button class="btn btn-danger" type="button" id="confirmDelete">${icon('trash')}<span>Delete restaurant</span></button>
      </div>`, { small: true });
    $('#confirmDelete').addEventListener('click', (e) => {
      submitWith(e.currentTarget, async () => {
        try {
          const res = await api(`${API}/restaurants/${encodeURIComponent(r.restaurantId)}`, { method: 'DELETE' });
          toast('Restaurant deleted', `${res.deleted.displayName} was removed.`);
          closeModal();
          closeDrawer();
          afterWrite();
        } catch (err) {
          $('#formErrors').innerHTML = errorBlock(err);
        }
      });
    });
  }

  $$('[data-action="add"]').forEach((b) => b.addEventListener('click', () => openEntityForm()));

  function afterWrite() { search({ resetPage: false }); loadMeta(); loadAllViz(); }

  // ============================================================= ANALYTICS
  const VIZ = [
    {
      key: 'cities', title: 'Restaurants by city', sub: 'Top 10 cities in the dataset',
      url: () => `${API}/analytics/cities?limit=10`,
      chart: (rows) => barList(rows.map((r) => ({ label: r.city, value: r.restaurants, display: nf(r.restaurants), tip: `${nf(r.restaurants)} restaurants · avg rating ${fixed(r.avgRating, 2)} · avg ${inr(r.avgCost)} for two` }))),
      cols: [{ k: 'city', l: 'City' }, { k: 'restaurants', l: 'Restaurants', num: true }, { k: 'avgRating', l: 'Avg rating', num: true, f: (v) => fixed(v, 2) }, { k: 'avgCost', l: 'Avg cost for two', num: true, f: (v) => inr(v) }],
    },
    {
      key: 'cuisines', title: 'Most loved cuisines', sub: 'Restaurants serving each cuisine · top 10', scoped: true,
      url: (v) => `${API}/analytics/cuisines${qs({ limit: 10, city: v.city })}`,
      chart: (rows) => barList(rows.map((r) => ({ label: r.cuisine, value: r.restaurants, display: nf(r.restaurants), tip: `${nf(r.restaurants)} restaurants · avg rating ${fixed(r.avgRating, 2)}` }))),
      cols: [{ k: 'cuisine', l: 'Cuisine' }, { k: 'restaurants', l: 'Restaurants', num: true }, { k: 'avgRating', l: 'Avg rating', num: true, f: (v) => fixed(v, 2) }],
    },
    {
      key: 'cityRatings', title: 'Best-rated cities', sub: 'Average rating, cities with ≥ 20 rated restaurants · scale 0–5',
      url: () => `${API}/analytics/city-ratings?limit=10&minRated=20`,
      chart: (rows) => barList(rows.map((r) => ({ label: r.city, value: r.avgRating, display: fixed(r.avgRating, 2), tip: `${fixed(r.avgRating, 2)} average over ${nf(r.rated)} rated restaurants · ${nf(r.votes)} votes` })), { domainMax: 5, color: '--chart-2' }),
      cols: [{ k: 'city', l: 'City' }, { k: 'avgRating', l: 'Avg rating', num: true, f: (v) => fixed(v, 2) }, { k: 'rated', l: 'Rated', num: true }, { k: 'votes', l: 'Votes', num: true }],
    },
    {
      key: 'cityCosts', title: 'Cost for two by city', sub: 'Average ₹ for two, cities with ≥ 20 restaurants',
      url: () => `${API}/analytics/city-costs?limit=10&minRestaurants=20`,
      chart: (rows) => barList(rows.map((r) => ({ label: r.city, value: r.avgCost, display: inr(r.avgCost), tip: `average ${inr(r.avgCost)} · cheapest ${inr(r.minCost)} · priciest ${inr(r.maxCost)}` }))),
      cols: [{ k: 'city', l: 'City' }, { k: 'avgCost', l: 'Average', num: true, f: (v) => inr(v) }, { k: 'minCost', l: 'Cheapest', num: true, f: (v) => inr(v) }, { k: 'maxCost', l: 'Priciest', num: true, f: (v) => inr(v) }, { k: 'restaurants', l: 'Restaurants', num: true }],
    },
    {
      key: 'ratings', title: 'Rating bands', sub: 'Share of restaurants in each Zomato band', scoped: true,
      url: (v) => `${API}/analytics/ratings${qs({ city: v.city })}`,
      chart: (rows) => barList(rows.map((r) => ({ label: r.band, value: r.restaurants, display: `${fixed(r.percent)}%`, tip: `${nf(r.restaurants)} restaurants` })), { color: '--chart-2' }),
      cols: [{ k: 'band', l: 'Band' }, { k: 'restaurants', l: 'Restaurants', num: true }, { k: 'percent', l: 'Share', num: true, f: (v) => `${fixed(v)}%` }],
    },
    {
      key: 'services', title: 'Online delivery by city', sub: 'Share of restaurants delivering online · scale 0–100%',
      url: () => `${API}/analytics/services?limit=10&minRestaurants=20`,
      chart: (rows) => barList(rows.map((r) => ({ label: r.city, value: r.onlineDelivery, display: `${fixed(r.onlineDelivery)}%`, tip: `${fixed(r.onlineDelivery)}% online delivery · ${fixed(r.tableBooking)}% table booking · ${nf(r.restaurants)} restaurants` })), { domainMax: 100 }),
      cols: [{ k: 'city', l: 'City' }, { k: 'restaurants', l: 'Restaurants', num: true }, { k: 'onlineDelivery', l: 'Online delivery', num: true, f: (v) => `${fixed(v)}%` }, { k: 'tableBooking', l: 'Table booking', num: true, f: (v) => `${fixed(v)}%` }],
    },
    {
      key: 'top', title: 'Top-rated restaurants', sub: 'Highest rating with at least 100 diner votes', scoped: true, wide: true,
      url: (v) => `${API}/analytics/top-rated${qs({ city: v.city, cuisine: v.cuisine, limit: 10, minVotes: 100 })}`,
      chart: (rows) => (rows.length ? `<ol class="rank-list">${rows.map((r, i) => `
        <li class="rank" tabindex="0" role="button" data-open="${esc(r.restaurantId)}">
          <span class="rank-no">${String(i + 1).padStart(2, '0')}</span>
          <span class="rank-name"><b>${esc(r.name)}</b><span>${esc(r.locality)}, ${esc(r.city)} · ${esc(r.cuisines.slice(0, 2).join(', '))}</span></span>
          <span class="rank-meta">${nf(r.votes)} votes<br>${inr(r.costForTwo)} for two</span>
          ${ratingBadge({ rated: true, rating: r.rating, ratingText: bandFor(r.rating) }, 'badge-sm')}
        </li>`).join('')}</ol>` : '<p class="muted">No restaurant with 100+ votes matches this scope.</p>'),
      cols: [{ k: 'name', l: 'Name' }, { k: 'city', l: 'City' }, { k: 'locality', l: 'Locality' }, { k: 'rating', l: 'Rating', num: true, f: (v) => fixed(v) }, { k: 'votes', l: 'Votes', num: true }, { k: 'costForTwo', l: 'Cost for two', num: true, f: (v) => inr(v) }],
      link: 'restaurantId',
    },
  ];

  const vizCards = {};
  const vizData = {};

  function renderVizFilters() {
    if (!state.meta) return;
    $('#vizFilters').innerHTML = `
      <label class="field"><span>City scope</span><select data-viz-filter="city">${options(state.meta.cities, state.viz.city, 'All of India')}</select></label>
      <label class="field"><span>Cuisine scope <small>(top-rated only)</small></span><select data-viz-filter="cuisine">${options(state.meta.cuisines, state.viz.cuisine, 'All cuisines')}</select></label>
      <p class="viz-note">Cards marked <span class="scope-tag">scoped</span> follow these filters.</p>`;
  }
  $('#vizFilters').addEventListener('change', (e) => {
    const s = e.target.closest('[data-viz-filter]');
    if (!s) return;
    state.viz[s.dataset.vizFilter] = s.value;
    VIZ.filter((d) => d.scoped).forEach((d) => loadViz(d.key));
  });

  function buildVizGrid() {
    const grid = $('#vizGrid');
    grid.innerHTML = VIZ.map((d, i) => `
      <article class="viz-card sk ${d.wide ? 'viz-wide' : ''}" data-reveal style="--d:${(i % 2) * 80}ms" data-viz="${d.key}">
        <header class="viz-head">
          <div><h3>${esc(d.title)}</h3><p>${esc(d.sub)}</p></div>
          ${d.scoped ? '<span class="scope-tag">scoped</span>' : ''}
        </header>
        <div class="viz-tabs" role="tablist"></div>
        <div class="viz-body"><div class="skeleton" style="height:220px"></div></div>
      </article>`).join('');
    VIZ.forEach((d) => { vizCards[d.key] = { el: $(`[data-viz="${d.key}"]`, grid), def: d, tab: 'chart' }; renderVizTabs(d.key); });
    observeReveals(grid);
  }

  function renderVizTabs(key) {
    const c = vizCards[key];
    $('.viz-tabs', c.el).innerHTML = ['chart', 'table', 'pipeline'].map((t) => `<button type="button" role="tab" aria-selected="${c.tab === t}" data-tab="${t}">${t[0].toUpperCase() + t.slice(1)}</button>`).join('');
  }

  function renderViz(key) {
    const c = vizCards[key];
    const data = vizData[key];
    if (!c || !data) return;
    renderVizTabs(key);
    const body = $('.viz-body', c.el);
    body.classList.remove('is-drawn');
    if (c.tab === 'chart') body.innerHTML = c.def.chart(data.rows);
    else if (c.tab === 'table') body.innerHTML = dataTable(data.rows, c.def.cols, c.def.link);
    else body.innerHTML = pipelineView(data.pipeline);
    nextFrame(() => { paintRough(body); if (c.el.classList.contains('is-in')) body.classList.add('is-drawn'); });
  }

  async function loadViz(key) {
    const c = vizCards[key];
    const body = $('.viz-body', c.el);
    body.classList.add('is-loading');
    try {
      vizData[key] = await api(c.def.url(state.viz));
      renderViz(key);
      if (!state.viz.city && !state.viz.cuisine) updateNotes();
    } catch (e) {
      body.innerHTML = `<p class="muted">Could not load: ${esc(e.message)}</p>`;
    } finally {
      body.classList.remove('is-loading');
    }
  }
  const loadAllViz = () => VIZ.forEach((d) => loadViz(d.key));

  $('#vizGrid').addEventListener('click', (e) => {
    const cardEl = e.target.closest('[data-viz]');
    if (!cardEl) return;
    const key = cardEl.dataset.viz;
    const tab = e.target.closest('[data-tab]');
    if (tab) { vizCards[key].tab = tab.dataset.tab; renderViz(key); return; }
    const open = e.target.closest('[data-open]');
    if (open) openDrawer(open.dataset.open);
  });
  $('#vizGrid').addEventListener('keydown', (e) => {
    const open = e.target.closest('[data-open]');
    if (open && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); openDrawer(open.dataset.open); }
  });

  // hero sticky notes reuse the first, unscoped analytics results
  function setNote(id, title, sub, big) {
    const set = (sel, v) => { const el = $(sel); if (el && v !== undefined && v !== null) el.textContent = v; };
    set(`#note${id}Title`, title);
    set(`#note${id}Sub`, sub);
    set(`#note${id}Big`, big);
  }
  function updateNotes() {
    const top = vizData.top && vizData.top.rows[0];
    const cuisine = vizData.cuisines && vizData.cuisines.rows[0];
    const bands = vizData.ratings && vizData.ratings.rows;
    if (top) setNote('A', top.name, `${top.locality}, ${top.city} · ${nf(top.votes)} votes`, `${fixed(top.rating)} ★`);
    if (cuisine && state.meta) setNote('B', cuisine.cuisine, `on the menu at ${nf(cuisine.restaurants)} restaurants (${fixed((cuisine.restaurants / state.meta.overview.restaurants) * 100)}%)`);
    if (bands) {
      const get = (b) => (bands.find((x) => x.band === b) || { restaurants: 0 }).restaurants;
      const rated = bands.filter((x) => x.band !== 'Not rated').reduce((s, x) => s + x.restaurants, 0);
      if (rated) setNote('C', null, null, `${fixed(((get('Excellent') + get('Very Good')) / rated) * 100)}%`);
    }
    if (top && cuisine && bands) $('#heroArt').classList.add('is-ready');
  }

  // =============================================================== INDEXES
  async function loadIndexes() {
    try {
      const data = await api(`${API}/indexes`);
      const compound = data.indexes.filter((i) => i.compound).length;
      $('#indexSummary').innerHTML = `<strong>${data.indexes.length}</strong> indexes on <code>${esc(data.namespace)}</code> · <strong>${compound}</strong> compound`;
      $('#indexGrid').innerHTML = data.indexes.map((ix, i) => `
        <article class="index-card" data-reveal style="--d:${i * 60}ms">
          <p class="index-name">${esc(ix.name)}</p>
          <div class="index-keys">${ix.keys.map((k) => `<span class="key-pill">${esc(k.field)} <i>${k.direction === 1 ? '↑ 1' : k.direction === -1 ? '↓ -1' : esc(k.direction)}</i></span>`).join('')}</div>
          <div class="index-tags">
            <span class="itag ${ix.compound ? 'itag-compound' : ''}">${ix.compound ? 'compound' : 'single field'}</span>
            ${ix.unique ? '<span class="itag itag-unique">unique</span>' : ''}
            ${ix.multikey ? '<span class="itag itag-multikey">multikey</span>' : ''}
          </div>
          <p class="index-purpose">${esc(ix.purpose)}</p>
        </article>`).join('');
      observeReveals($('#indexGrid'));
      const chips = data.presets.map((p, i) => `<button class="chip" type="button" role="tab" aria-selected="${i === 0}" data-preset="${esc(p.key)}">${esc(p.label)}</button>`);
      chips.push('<button class="chip" type="button" role="tab" aria-selected="false" data-preset="">Your Explore filters</button>');
      $('#labPresets').innerHTML = chips.join('');
      if (data.presets.length) runExplain(data.presets[0].key);
    } catch (e) {
      $('#indexSummary').textContent = `Could not load indexes: ${e.message}`;
    }
  }

  async function runExplain(presetKey) {
    const result = $('#labResult');
    result.style.opacity = '0.5';
    try {
      const params = presetKey ? { preset: presetKey } : { ...state.filters };
      const data = await api(`${API}/indexes/explain${qs(params)}`);
      const a = data.indexed;
      const b = data.collectionScan;
      const maxDocs = Math.max(a.docsExamined, b.docsExamined, a.keysExamined, b.keysExamined, 1);
      const saved = b.docsExamined > 0 ? 100 - (a.docsExamined * 100) / b.docsExamined : 0;
      const stageList = (p) => p.stages.map((s) => `<span class="stage ${s.includes('IXSCAN') ? 'ix' : s === 'COLLSCAN' ? 'scan' : ''}">${esc(s)}</span>`).join('<span class="stage-arrow">→</span>');
      const metric = (label, value, color) => {
        const pct = value ? Math.max(1, (value / maxDocs) * 100) : 0;
        return `<div class="metric"><dt>${label}</dt><span class="bar-track"><span class="bar-draw is-static"><span class="bar-fallback" style="--w:${pct}%"></span><svg data-rough="bar" data-color="${color}" data-pct="${pct}" aria-hidden="true"></svg></span></span><dd>${nf(value)}</dd></div>`;
      };
      const planBlock = (p, title, scan) => `
        <div class="plan ${scan ? 'is-scan' : ''}">
          <h4><span class="dot"></span>${title}</h4>
          <div class="stages">${stageList(p)}</div>
          <dl class="metrics">
            ${metric('Keys examined', p.keysExamined, '--chart-2')}
            ${metric('Docs examined', p.docsExamined, '--chart')}
            <div class="metric"><dt>Returned</dt><span></span><dd>${nf(p.returned)}</dd></div>
            <div class="metric"><dt>Time</dt><span></span><dd>${nf(p.millis)} ms</dd></div>
          </dl>
          ${!scan && p.indexes.length ? `<p class="hint">Index: <span class="mono">${esc(p.indexes.join(', '))}</span></p>` : ''}
        </div>`;
      result.innerHTML = `
        <p class="lab-headline">${a.usesIndex
          ? `Using <em class="mono">${esc(a.indexes[0])}</em>, MongoDB read <em>${nf(a.keysExamined)}</em> index keys and fetched <em>${nf(a.docsExamined)}</em> documents — a collection scan reads all <em>${nf(b.docsExamined)}</em>${saved > 0 ? ` (${saved.toFixed(1)}% fewer documents)` : ''}.`
          : 'The planner chose a collection scan for this query (no matching index).'}</p>
        <div class="lab-cols">${planBlock(a, 'Planner’s choice', !a.usesIndex)}${planBlock(b, 'Forced collection scan', true)}</div>
        <details class="lab-filter"><summary>Filter document · ${esc(data.label)} · sort ${esc(data.sort)}</summary><pre class="code">${esc(JSON.stringify(a.filter, null, 2))}</pre></details>`;
      nextFrame(() => paintRough(result));
    } catch (e) {
      result.innerHTML = `<p class="muted">Explain failed: ${esc(e.message)}</p>`;
    } finally {
      result.style.opacity = '';
    }
  }
  $('#labPresets').addEventListener('click', (e) => {
    const c = e.target.closest('[data-preset]');
    if (!c) return;
    $$('#labPresets .chip').forEach((x) => x.setAttribute('aria-selected', String(x === c)));
    if (form.elements.length) readForm();
    runExplain(c.dataset.preset);
  });
  $('#ensureIndexes').addEventListener('click', (e) => {
    submitWith(e.currentTarget, async () => {
      try {
        const data = await api(`${API}/indexes/ensure`, { method: 'POST' });
        const created = data.results.filter((r) => r.status.startsWith('created')).length;
        const failed = data.results.filter((r) => r.status === 'failed');
        if (failed.length) toast('Some indexes failed', failed.map((f) => `${f.name}: ${f.detail}`).join(' · '), 'error');
        else toast('Indexes verified', `${data.results.length} application indexes present · ${created} created now`);
        loadIndexes();
      } catch (err) {
        toast('Could not verify indexes', err.message, 'error');
      }
    });
  });

  // ================================================================== BOOT
  function reloadData() { search(); loadAllViz(); loadIndexes(); }

  skeletons();
  buildVizGrid();
  loadMeta().then((ok) => { if (ok) reloadData(); });
})();
