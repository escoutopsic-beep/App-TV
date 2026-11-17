/*
 * FrameXTV TV engine — injected by MainActivity into the top frame of framextv.cc.
 * Config arrives in window.__FXTV_CFG = { scale: 100, shield: true }.
 * Safe to inject more than once: later injections only re-apply the config.
 */
(function () {
  'use strict';
  if (window.top !== window) return;

  var cfg = window.__FXTV_CFG || {};
  if (window.__fxtv) { window.__fxtv.apply(cfg); window.__fxtv.boot(); return; }

  var fx = { cfg: { scale: 100, shield: true }, fsTarget: null, lastPlaying: null, booted: false };
  window.__fxtv = fx;

  var bridge = function () { return window.FrameXTVBridge || null; };

  /* ---------------- CSS (light GPU profile + D-pad focus + optional ad hiding) ---------------- */
  var BASE_CSS =
    'html,body{background:#08090b!important;overflow-x:hidden!important}' +
    '*,*::before,*::after{animation-duration:.001ms!important;animation-iteration-count:1!important;' +
    'transition-duration:.001ms!important;scroll-behavior:auto!important;backdrop-filter:none!important;' +
    '-webkit-backdrop-filter:none!important;box-shadow:none!important;text-shadow:none!important}' +
    '::-webkit-scrollbar{width:0!important;height:0!important}' +
    ':focus{outline:4px solid #d6ad55!important;outline-offset:3px!important;border-radius:6px!important}' +
    'a:focus,button:focus,[tabindex]:focus{background-color:rgba(214,173,85,.14)!important}';

  var SHIELD_CSS =
    '[id^="ad-"],[id^="ads-"],[id^="ad_"],[id*="-ad-"],[id*="_ad_"],' +
    '[class^="ad-"],[class*=" ad-"],[class~="ad"],[class~="ads"],[class*="adsbox"],[class*="ad-banner"],' +
    'ins.adsbygoogle,[class*="adsbygoogle"],[class*="overlay-ad"],[class*="promo-modal"],' +
    '[class*="sponsor"],[id*="sponsor"],' +
    'iframe[src*="doubleclick"],iframe[src*="/ads/"],iframe[src*="adserver"],iframe[src*="popads"]' +
    '{display:none!important}';

  function applyStyle() {
    var host = document.head || document.documentElement;
    if (!host) return false;
    var st = document.getElementById('fxtv-style');
    if (!st) {
      st = document.createElement('style');
      st.id = 'fxtv-style';
      host.appendChild(st);
    }
    var css = BASE_CSS + (fx.cfg.shield ? SHIELD_CSS : '');
    if (st.textContent !== css) st.textContent = css;
    return true;
  }

  /* ---------------- Page scale via viewport (keeps responsive layouts correct) ---------------- */
  function applyViewport() {
    var s = fx.cfg.scale | 0;
    var metas = document.querySelectorAll('meta[name="viewport"]');
    var i, m;
    if (!s || s >= 100) {
      for (i = 0; i < metas.length; i++) {
        m = metas[i];
        if (m.hasAttribute('data-fx-orig')) {
          m.setAttribute('content', m.getAttribute('data-fx-orig'));
          m.removeAttribute('data-fx-orig');
        }
      }
      return;
    }
    var width = Math.round((screen.width || 960) * 100 / s);
    var content = 'width=' + width;
    if (!metas.length) {
      if (!document.head) return;
      m = document.createElement('meta');
      m.setAttribute('name', 'viewport');
      document.head.appendChild(m);
      metas = [m];
    }
    for (i = 0; i < metas.length; i++) {
      m = metas[i];
      if (!m.hasAttribute('data-fx-orig')) m.setAttribute('data-fx-orig', m.getAttribute('content') || '');
      if (m.getAttribute('content') !== content) m.setAttribute('content', content);
    }
  }

  fx.apply = function (c) {
    if (c) {
      if (typeof c.scale === 'number') fx.cfg.scale = c.scale;
      if (typeof c.shield === 'boolean') fx.cfg.shield = c.shield;
    }
    applyStyle();
    applyViewport();
  };

  /* ---------------- Focusable preparation ---------------- */
  var PREP_SEL = '[role="button"],[role="link"],[role="menuitem"],[role="tab"],[onclick],.card,article';
  var NAV_SEL = 'a[href],button,input,select,textarea,summary,iframe,[tabindex],' + PREP_SEL;

  function prepOne(el) {
    if (el.hasAttribute('tabindex')) return;
    if (el.querySelector && el.querySelector('a[href],button')) return; // inner link is the stop
    el.setAttribute('tabindex', '0');
  }

  function prep(root) {
    if (!root || !root.querySelectorAll) return;
    if (root.matches && root.matches(PREP_SEL)) prepOne(root);
    var list = root.querySelectorAll(PREP_SEL), i;
    for (i = 0; i < list.length; i++) prepOne(list[i]);
    var blanks = root.querySelectorAll('a[target="_blank"]');
    for (i = 0; i < blanks.length; i++) blanks[i].removeAttribute('target');
    if (root.tagName === 'A' && root.getAttribute('target') === '_blank') root.removeAttribute('target');
  }

  function isVisible(el, r) {
    if (r.width < 4 || r.height < 4) return false;
    if (el.checkVisibility && !el.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true })) return false;
    return true;
  }

  function focusables() {
    var all = document.querySelectorAll(NAV_SEL), out = [], i, el, r;
    for (i = 0; i < all.length; i++) {
      el = all[i];
      if (el.disabled) continue;
      if (el.tabIndex < 0 && el.tagName !== 'IFRAME') continue;
      r = el.getBoundingClientRect();
      if (!isVisible(el, r)) continue;
      out.push({ el: el, r: r });
    }
    return out;
  }

  /* ---------------- Focus kind -> native side (decides who handles OK / arrows) ---------------- */
  var TEXT_INPUTS = { text: 1, search: 1, email: 1, url: 1, tel: 1, password: 1, number: 1 };

  function kindOf(el) {
    if (!el || el === document.body || el === document.documentElement) return 'none';
    var t = el.tagName;
    if (t === 'TEXTAREA' || el.isContentEditable) return 'text';
    if (t === 'INPUT') return TEXT_INPUTS[(el.type || 'text').toLowerCase()] ? 'text' : 'native';
    if (t === 'BUTTON' || t === 'SELECT' || t === 'SUMMARY' || t === 'IFRAME') return 'native';
    if (t === 'A' && el.hasAttribute('href')) return 'native';
    return 'custom';
  }

  var lastKind = null;
  function report() {
    var k = kindOf(document.activeElement);
    if (k === lastKind) return;
    lastKind = k;
    var b = bridge();
    if (b) { try { b.focusKind(k); } catch (e) {} }
  }

  /* ---------------- Spatial navigation ---------------- */
  function reveal(el) {
    var r = el.getBoundingClientRect(), vh = window.innerHeight, vw = window.innerWidth;
    var m = Math.min(120, vh * 0.15);
    if (r.top < m || r.bottom > vh - m || r.left < 0 || r.right > vw) {
      el.scrollIntoView({ block: r.height > vh - 2 * m ? 'start' : 'center', inline: 'nearest' });
    }
  }

  function focusEl(el) {
    try { el.focus({ preventScroll: true }); } catch (e) { el.focus(); }
    reveal(el);
    report();
  }

  function score(dir, c, r) {
    var ccx = c.left + c.width / 2, ccy = c.top + c.height / 2;
    var rcx = r.left + r.width / 2, rcy = r.top + r.height / 2;
    var primary, ortho, overlap;
    if (dir === 'RIGHT' || dir === 'LEFT') {
      if (dir === 'RIGHT') { if (rcx <= ccx + 2) return -1; primary = Math.max(0, r.left - c.right); }
      else { if (rcx >= ccx - 2) return -1; primary = Math.max(0, c.left - r.right); }
      overlap = Math.min(c.bottom, r.bottom) - Math.max(c.top, r.top);
      ortho = Math.abs(rcy - ccy);
    } else {
      if (dir === 'DOWN') { if (rcy <= ccy + 2) return -1; primary = Math.max(0, r.top - c.bottom); }
      else { if (rcy >= ccy - 2) return -1; primary = Math.max(0, c.top - r.bottom); }
      overlap = Math.min(c.right, r.right) - Math.max(c.left, r.left);
      ortho = Math.abs(rcx - ccx);
    }
    return primary + (overlap > 0 ? ortho * 0.3 : ortho * 2 + 150);
  }

  function firstInView(list) {
    var vh = window.innerHeight, i;
    for (i = 0; i < list.length; i++) if (list[i].r.top >= 0 && list[i].r.top < vh) return list[i].el;
    return list.length ? list[0].el : null;
  }

  fx.move = function (dir) {
    var list = focusables();
    if (!list.length) {
      if (dir === 'DOWN') window.scrollBy(0, 320);
      if (dir === 'UP') window.scrollBy(0, -320);
      return false;
    }
    var active = document.activeElement, cur = null, i;
    if (active && active !== document.body && active !== document.documentElement) {
      cur = active.getBoundingClientRect();
      if (cur.width < 1 && cur.height < 1) cur = null;
    }
    if (!cur) { var f = firstInView(list); if (f) focusEl(f); return true; }

    var best = null, bestScore = Infinity, s;
    for (i = 0; i < list.length; i++) {
      if (list[i].el === active) continue;
      s = score(dir, cur, list[i].r);
      if (s >= 0 && s < bestScore) { bestScore = s; best = list[i].el; }
    }
    if (best) { focusEl(best); return true; }
    if (dir === 'DOWN') window.scrollBy(0, 320);
    if (dir === 'UP') window.scrollBy(0, -320);
    return false;
  };

  fx.activate = function () {
    var el = document.activeElement;
    if (!el || el === document.body) return;
    var inner = (el.matches && el.matches('a[href],button')) ? el : el.querySelector('a[href],button,[role="button"]');
    (inner || el).click();
  };

  /* ---------------- Player ---------------- */
  function pickVideo() {
    var fs = document.fullscreenElement || document.webkitFullscreenElement;
    if (fs) {
      if (fs.tagName === 'VIDEO') return fs;
      var inFs = fs.querySelector && fs.querySelector('video');
      if (inFs) return inFs;
    }
    var vs = document.querySelectorAll('video'), best = null, bestArea = 0, i, v, r, a;
    for (i = 0; i < vs.length; i++) {
      v = vs[i];
      if (!v.paused && !v.ended && v.readyState > 1) return v;
      r = v.getBoundingClientRect();
      a = r.width * r.height;
      if (a > bestArea) { bestArea = a; best = v; }
    }
    return best;
  }

  function safePlay(v) { try { var p = v.play(); if (p && p.catch) p.catch(function () {}); } catch (e) {} }

  fx.player = function (cmd, val) {
    var v = pickVideo();
    if (!v) return { ok: false };
    if (cmd === 'TOGGLE') { if (v.paused) safePlay(v); else v.pause(); }
    else if (cmd === 'PLAY') safePlay(v);
    else if (cmd === 'PAUSE') v.pause();
    else if (cmd === 'SEEK') {
      var dur = isFinite(v.duration) ? v.duration : Infinity;
      v.currentTime = Math.max(0, Math.min(dur - 0.5, (v.currentTime || 0) + val));
    }
    return { ok: true, paused: v.paused, cur: v.currentTime || 0, dur: isFinite(v.duration) ? v.duration : -1 };
  };

  // Fullscreen needs a real user gesture: arm a target here, then the app sends a native F11
  // key whose (trusted) keydown performs requestFullscreen below.
  fx.armFullscreen = function () {
    var d = document;
    if (d.fullscreenElement || d.webkitFullscreenElement) {
      var exit = d.exitFullscreen || d.webkitExitFullscreen;
      if (exit) { try { var p = exit.call(d); if (p && p.catch) p.catch(function () {}); } catch (e) {} }
      return 'exited';
    }
    var t = pickVideo();
    if (!t) {
      var frames = d.querySelectorAll('iframe'), bestArea = 40000, i, r, a;
      for (i = 0; i < frames.length; i++) {
        r = frames[i].getBoundingClientRect();
        a = r.width * r.height;
        if (a > bestArea) { bestArea = a; t = frames[i]; }
      }
    }
    if (!t) return 'none';
    fx.fsTarget = t;
    var act = d.activeElement;
    if (act && act.tagName === 'IFRAME') act.blur();
    try { window.focus(); } catch (e) {}
    return 'armed';
  };

  window.addEventListener('keydown', function (e) {
    if (e.key !== 'F11' || !fx.fsTarget) return;
    var t = fx.fsTarget;
    fx.fsTarget = null;
    e.preventDefault();
    e.stopImmediatePropagation();
    var req = t.requestFullscreen || t.webkitRequestFullscreen;
    try {
      var p = req ? req.call(t) : null;
      if (p && p.catch) p.catch(function () { if (t.webkitEnterFullscreen) t.webkitEnterFullscreen(); });
      else if (!req && t.webkitEnterFullscreen) t.webkitEnterFullscreen();
    } catch (err) {}
  }, true);

  fx.pauseAll = function () {
    var vs = document.querySelectorAll('video'), i;
    for (i = 0; i < vs.length; i++) { try { vs[i].pause(); } catch (e) {} }
  };

  function playState() {
    var vs = document.querySelectorAll('video'), playing = false, i;
    for (i = 0; i < vs.length; i++) if (!vs[i].paused && !vs[i].ended) { playing = true; break; }
    if (playing === fx.lastPlaying) return;
    fx.lastPlaying = playing;
    var b = bridge();
    if (b) { try { b.playing(playing); } catch (e) {} }
  }
  ['play', 'playing', 'pause', 'ended', 'emptied'].forEach(function (ev) {
    document.addEventListener(ev, playState, true);
  });

  /* ---------------- Boot (needs <body>) ---------------- */
  var pending = [], timer = null;
  function flush() {
    timer = null;
    var nodes = pending;
    pending = [];
    if (nodes.length > 150) { prep(document.body); }
    else { for (var i = 0; i < nodes.length; i++) if (nodes[i].isConnected) prep(nodes[i]); }
  }

  fx.boot = function () {
    applyStyle();
    applyViewport();
    if (fx.booted || !document.body) return;
    fx.booted = true;
    prep(document.body);

    new MutationObserver(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        var added = muts[i].addedNodes;
        for (var j = 0; j < added.length; j++) if (added[j].nodeType === 1) pending.push(added[j]);
      }
      if (pending.length && !timer) timer = setTimeout(flush, 300);
    }).observe(document.body, { childList: true, subtree: true });

    if (document.head) {
      new MutationObserver(function () { applyStyle(); applyViewport(); })
        .observe(document.head, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });
    }

    document.addEventListener('focusin', report, true);
    document.addEventListener('focusout', function () { setTimeout(report, 0); }, true);
    window.addEventListener('blur', function () { setTimeout(report, 0); });

    if (!document.activeElement || document.activeElement === document.body) {
      var f = firstInView(focusables());
      if (f) focusEl(f);
    }
    report();
    playState();
  };

  fx.apply(cfg);
  if (document.body) fx.boot();
  else document.addEventListener('DOMContentLoaded', fx.boot);
})();
