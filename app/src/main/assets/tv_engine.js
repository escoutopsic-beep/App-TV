/*
 * FrameXTV TV engine.
 *
 * Runs in every frame (top page and embedded players):
 *   - Player agent: finds the <video>, reads/sets subtitles, audio, quality and speed through the
 *     player's own API (JW Player, Video.js, hls.js) or the native media element, and executes
 *     play/pause/seek. Commands travel top -> frames with postMessage; answers go straight to the
 *     app through window.FrameXTVBridge (available in every frame).
 * Runs only in the top frame of framextv.cc:
 *   - D-pad spatial navigation, focus styling, ad hiding, page scale.
 *
 * Config arrives in window.__FXTV_CFG = { scale: 100, shield: true }.
 * Safe to inject more than once: later injections only re-apply the config.
 */
(function () {
  'use strict';

  var IS_TOP = window.top === window;
  var bridge = function () { return window.FrameXTVBridge || null; };

  /* ======================================================================== player agent */

  var A = window.__fxtvAgent;
  if (!A) {
    A = window.__fxtvAgent = { id: Math.random().toString(36).slice(2, 10) };
    setupAgent(A);
  }

  function setupAgent(A) {
    /* ---- Protection (every frame, before the page's own scripts) ---- */
    // Pop-ups / pop-unders: window.open becomes a no-op and cannot be restored by the page.
    var noOpen = function () { return null; };
    try { Object.defineProperty(window, 'open', { configurable: false, writable: false, value: noOpen }); }
    catch (e) { try { window.open = noOpen; } catch (e2) {} }

    // Links that leave the site (or the player's own domain) are swallowed before they act.
    var OWN_HOST = /(^|\.)framextv\.cc$/i;
    var hereHost = location.hostname;
    function leavesSite(href) {
      try {
        var u = new URL(href, location.href);
        if (u.protocol !== 'https:' && u.protocol !== 'http:') return true;
        return !(OWN_HOST.test(u.hostname) || u.hostname === hereHost);
      } catch (e) { return true; }
    }
    document.addEventListener('click', function (e) {
      var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
      if (!a) return;
      var raw = a.getAttribute('href') || '';
      if (raw.charAt(0) === '#' || /^\s*javascript:/i.test(raw)) return;
      if (leavesSite(a.href)) { e.preventDefault(); e.stopImmediatePropagation(); }
    }, true);

    // Capture hls.js instances (when the page loads hls.js as a global) to reach audio/quality/subs.
    var HlsRef;
    function patchHls(H) {
      try {
        if (H && H.prototype && H.prototype.attachMedia && !H.prototype.__fxPatched) {
          var orig = H.prototype.attachMedia;
          H.prototype.attachMedia = function (media) {
            try { if (media) media.__fxHls = this; } catch (e) {}
            return orig.apply(this, arguments);
          };
          H.prototype.__fxPatched = true;
        }
      } catch (e) {}
    }
    try {
      Object.defineProperty(window, 'Hls', {
        configurable: true,
        get: function () { patchHls(HlsRef); return HlsRef; },
        set: function (H) { HlsRef = H; patchHls(H); }
      });
    } catch (e) {}

    function report(method, obj) {
      var b = bridge();
      if (b && b[method]) { try { b[method](JSON.stringify(obj)); } catch (e) {} }
    }

    function pickVideo() {
      var d = document;
      var fs = d.fullscreenElement || d.webkitFullscreenElement;
      if (fs) {
        if (fs.tagName === 'VIDEO') return fs;
        var inFs = fs.querySelector && fs.querySelector('video');
        if (inFs) return inFs;
      }
      var vs = d.querySelectorAll('video'), best = null, bestArea = 0, i, v, r, a;
      for (i = 0; i < vs.length; i++) {
        v = vs[i];
        if (!v.paused && !v.ended && v.readyState > 1) return v;
        r = v.getBoundingClientRect();
        a = r.width * r.height;
        if (a > bestArea) { bestArea = a; best = v; }
      }
      return best || vs[0] || null;
    }
    A.pickVideo = pickVideo;

    function area(v) { var r = v.getBoundingClientRect(); return Math.round(r.width * r.height); }
    function safePlay(v) { try { var p = v.play(); if (p && p.catch) p.catch(function () {}); } catch (e) {} }

    /* ---- player APIs ---- */
    function jwFor(v) {
      var J = window.jwplayer;
      if (typeof J !== 'function') return null;
      try {
        var p = J();
        if (!p || typeof p.getState !== 'function') return null;
        var c = p.getContainer && p.getContainer();
        if (c && v && !c.contains(v)) return null;
        return p;
      } catch (e) { return null; }
    }

    function vjsFor(v) {
      var V = window.videojs;
      if (!V || typeof V.getPlayers !== 'function') return null;
      try {
        var ps = V.getPlayers();
        for (var k in ps) {
          var p = ps[k];
          if (p && p.el && p.el() && p.el().contains(v)) return p;
        }
      } catch (e) {}
      return null;
    }

    function hlsFor(v) {
      if (v.__fxHls) return v.__fxHls;
      var h = window.hls;
      if (h && h.media === v) return h;
      return null;
    }

    function arr(list) {
      var out = [];
      if (!list) return out;
      for (var i = 0; i < list.length; i++) out.push(list[i]);
      return out;
    }

    function trackName(t, i) {
      var n = (t && (t.label || t.name || t.language || t.lang || t.srclang)) || '';
      return n ? String(n) : 'Faixa ' + (i + 1);
    }

    function qualityName(q, i) {
      if (q.label) return String(q.label);
      if (q.height) return q.height + 'p';
      if (q.bitrate) return Math.round(q.bitrate / 1000) + ' kbps';
      return 'Nível ' + (i + 1);
    }

    var SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 2];

    /** Build the options menu for this frame's video. Each section remembers which API serves it. */
    function collectSections(v) {
      var secs = {}, jw = jwFor(v), vj = vjsFor(v), hl = hlsFor(v), list, cur, items, i;

      // Subtitles
      try {
        if (jw && jw.getCaptionsList) {
          list = jw.getCaptionsList() || [];
          if (list.length > 1) {
            cur = jw.getCurrentCaptions();
            items = [];
            for (i = 0; i < list.length; i++) items.push({ label: i === 0 ? 'Desligadas' : trackName(list[i], i - 1), value: i, selected: i === cur });
            secs.sub = { src: 'jw', items: items };
          }
        }
        if (!secs.sub && hl && hl.subtitleTracks && hl.subtitleTracks.length) {
          items = [{ label: 'Desligadas', value: -1, selected: hl.subtitleTrack === -1 }];
          for (i = 0; i < hl.subtitleTracks.length; i++) items.push({ label: trackName(hl.subtitleTracks[i], i), value: i, selected: hl.subtitleTrack === i });
          secs.sub = { src: 'hls', items: items };
        }
        if (!secs.sub) {
          var tt = vj ? arr(vj.textTracks()) : arr(v.textTracks), subs = [];
          for (i = 0; i < tt.length; i++) if (tt[i].kind === 'subtitles' || tt[i].kind === 'captions') subs.push(i);
          if (subs.length) {
            var anyOn = false;
            items = [];
            for (i = 0; i < subs.length; i++) {
              var on = tt[subs[i]].mode === 'showing';
              anyOn = anyOn || on;
              items.push({ label: trackName(tt[subs[i]], i), value: subs[i], selected: on });
            }
            items.unshift({ label: 'Desligadas', value: -1, selected: !anyOn });
            secs.sub = { src: vj ? 'vjs' : 'native', items: items };
          }
        }
      } catch (e) {}

      // Audio
      try {
        if (jw && jw.getAudioTracks) {
          list = jw.getAudioTracks() || [];
          if (list.length > 1) {
            cur = jw.getCurrentAudioTrack();
            items = [];
            for (i = 0; i < list.length; i++) items.push({ label: trackName(list[i], i), value: i, selected: i === cur });
            secs.audio = { src: 'jw', items: items };
          }
        }
        if (!secs.audio && hl && hl.audioTracks && hl.audioTracks.length > 1) {
          items = [];
          for (i = 0; i < hl.audioTracks.length; i++) items.push({ label: trackName(hl.audioTracks[i], i), value: i, selected: hl.audioTrack === i });
          secs.audio = { src: 'hls', items: items };
        }
        if (!secs.audio) {
          var at = vj && vj.audioTracks ? arr(vj.audioTracks()) : arr(v.audioTracks);
          if (at.length > 1) {
            items = [];
            for (i = 0; i < at.length; i++) items.push({ label: trackName(at[i], i), value: i, selected: !!at[i].enabled });
            secs.audio = { src: vj && vj.audioTracks ? 'vjs' : 'native', items: items };
          }
        }
      } catch (e) {}

      // Quality
      try {
        if (jw && jw.getQualityLevels) {
          list = jw.getQualityLevels() || [];
          if (list.length > 1) {
            cur = jw.getCurrentQuality();
            items = [];
            for (i = 0; i < list.length; i++) items.push({ label: qualityName(list[i], i), value: i, selected: i === cur });
            secs.quality = { src: 'jw', items: items };
          }
        }
        if (!secs.quality && hl && hl.levels && hl.levels.length > 1) {
          var auto = hl.autoLevelEnabled;
          items = [{ label: 'Automática', value: -1, selected: auto }];
          for (i = 0; i < hl.levels.length; i++) items.push({ label: qualityName(hl.levels[i], i), value: i, selected: !auto && hl.currentLevel === i });
          secs.quality = { src: 'hls', items: items };
        }
        if (!secs.quality && vj && vj.qualityLevels) {
          var ql = vj.qualityLevels(), n = ql ? ql.length : 0;
          if (n > 1) {
            var enabled = 0;
            for (i = 0; i < n; i++) if (ql[i].enabled) enabled++;
            items = [{ label: 'Automática', value: -1, selected: enabled === n }];
            for (i = 0; i < n; i++) items.push({ label: qualityName(ql[i], i), value: i, selected: enabled === 1 && ql[i].enabled });
            secs.quality = { src: 'vjs', items: items };
          }
        }
      } catch (e) {}

      // Speed (always available)
      var rate = v.playbackRate || 1;
      try { if (jw && jw.getPlaybackRate) rate = jw.getPlaybackRate(); } catch (e) {}
      items = [];
      for (i = 0; i < SPEEDS.length; i++) items.push({ label: (SPEEDS[i] === 1 ? 'Normal' : String(SPEEDS[i]).replace('.', ',') + 'x'), value: SPEEDS[i], selected: Math.abs(rate - SPEEDS[i]) < 0.01 });
      secs.speed = { src: jw ? 'jw' : 'native', items: items };

      return secs;
    }

    function applySetting(v, key, src, value) {
      var jw = jwFor(v), vj = vjsFor(v), hl = hlsFor(v), i;
      if (key === 'sub') {
        if (src === 'jw' && jw) jw.setCurrentCaptions(value);
        else if (src === 'hls' && hl) { hl.subtitleTrack = value; if (value >= 0) hl.subtitleDisplay = true; }
        else {
          var tt = src === 'vjs' && vj ? arr(vj.textTracks()) : arr(v.textTracks);
          for (i = 0; i < tt.length; i++) {
            if (tt[i].kind !== 'subtitles' && tt[i].kind !== 'captions') continue;
            tt[i].mode = i === value ? 'showing' : 'disabled';
          }
        }
      } else if (key === 'audio') {
        if (src === 'jw' && jw) jw.setCurrentAudioTrack(value);
        else if (src === 'hls' && hl) hl.audioTrack = value;
        else {
          var at = src === 'vjs' && vj ? arr(vj.audioTracks()) : arr(v.audioTracks);
          for (i = 0; i < at.length; i++) at[i].enabled = i === value;
        }
      } else if (key === 'quality') {
        v.__fxManualQ = true;
        if (src === 'jw' && jw) jw.setCurrentQuality(value);
        else if (src === 'hls' && hl) hl.currentLevel = value;
        else if (src === 'vjs' && vj && vj.qualityLevels) {
          var ql = vj.qualityLevels();
          for (i = 0; i < ql.length; i++) ql[i].enabled = value < 0 || i === value;
        }
      } else if (key === 'speed') {
        if (src === 'jw' && jw && jw.setPlaybackRate) jw.setPlaybackRate(value);
        else if (vj) vj.playbackRate(value);
        else v.playbackRate = value;
      }
    }

    function doCmd(cmd, val) {
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
    }
    A.doCmd = doCmd;

    /** A frame only answers for a "real" player, not for tiny previews. */
    function mainVideo() {
      var v = pickVideo();
      if (!v) return null;
      if (!IS_TOP && area(v) < 40000 && v.paused) return null;
      return v;
    }

    /* ---- Always-highest quality (setting "maxQuality"; a manual choice in the menu wins) ---- */
    var initCfg = window.__FXTV_CFG || {};
    A.maxQuality = initCfg.maxQuality !== false;

    function levelScore(q) {
      var h = q.height || 0;
      if (!h) {
        var m = String(q.label || '').match(/(\d{3,4})\s*p/i);
        if (m) h = +m[1];
        else if (/4k|uhd|2160/i.test(String(q.label || ''))) h = 2160;
      }
      return h * 1e7 + (q.bitrate || 0);
    }

    function bestIndex(list, skipAuto) {
      var best = -1, bs = -1, i, sc;
      for (i = 0; i < list.length; i++) {
        if (skipAuto && /auto/i.test(String(list[i].label || ''))) continue;
        sc = levelScore(list[i]);
        if (sc > bs) { bs = sc; best = i; }
      }
      return best;
    }

    function applyMaxQuality(v) {
      if (!A.maxQuality || !v || v.__fxManualQ) return;
      var jw = jwFor(v), hl = hlsFor(v), vj = vjsFor(v), best, i;
      try {
        if (jw && jw.getQualityLevels) {
          var list = jw.getQualityLevels() || [];
          best = bestIndex(list, true);
          if (best >= 0 && jw.getCurrentQuality() !== best) jw.setCurrentQuality(best);
        } else if (hl && hl.levels && hl.levels.length > 1) {
          best = bestIndex(hl.levels, false);
          if (best >= 0 && (hl.autoLevelEnabled || hl.currentLevel !== best)) {
            hl.autoLevelCapping = -1;
            hl.nextLevel = best; // switches at the next segment, without stopping playback
          }
        } else if (vj && vj.qualityLevels) {
          var ql = vj.qualityLevels(), arrQ = [];
          for (i = 0; i < ql.length; i++) arrQ.push(ql[i]);
          best = bestIndex(arrQ, false);
          if (best >= 0) for (i = 0; i < ql.length; i++) ql[i].enabled = i === best;
        }
      } catch (e) {}
    }

    function onMediaReady(e) {
      var v = e.target;
      if (!v || v.tagName !== 'VIDEO' || !A.maxQuality || v.__fxManualQ) return;
      applyMaxQuality(v);
      // Quality lists often arrive a little after playback starts: try again shortly (twice max).
      if ((v.__fxMaxTries || 0) < 2) {
        v.__fxMaxTries = (v.__fxMaxTries || 0) + 1;
        setTimeout(function () { applyMaxQuality(v); }, 2500);
      }
    }
    document.addEventListener('loadedmetadata', onMediaReady, true);
    document.addEventListener('playing', onMediaReady, true);

    function handle(msg) {
      var v;
      if (msg.op === 'cfg') {
        A.maxQuality = msg.maxQuality !== false;
        if (A.maxQuality) applyMaxQuality(pickVideo());
        return;
      }
      if (msg.op === 'query') {
        v = mainVideo();
        if (!v) return;
        report('playerOptions', {
          req: msg.req, frame: A.id, playing: !v.paused, area: area(v), sections: collectSections(v)
        });
      } else if (msg.op === 'set') {
        if (msg.target !== A.id) return;
        v = pickVideo();
        if (!v) return;
        try { applySetting(v, msg.key, msg.src, msg.value); } catch (e) {}
      } else if (msg.op === 'cmd') {
        v = mainVideo();
        if (!v) return;
        var r = doCmd(msg.cmd, msg.val);
        r.req = msg.req;
        report('playerState', r);
      }
    }

    function forward(msg) {
      for (var i = 0; i < window.frames.length; i++) {
        try { window.frames[i].postMessage(msg, '*'); } catch (e) {}
      }
    }

    /** Handle here and pass the message down to every child frame. */
    A.dispatch = function (msg) {
      msg.__fxtv = 1;
      try { handle(msg); } catch (e) {}
      forward(msg);
    };
    A.forward = function (msg) { msg.__fxtv = 1; forward(msg); };

    window.addEventListener('message', function (e) {
      var d = e.data;
      if (!d || d.__fxtv !== 1 || e.source !== window.parent || window.parent === window) return;
      A.dispatch(d);
    });
  }

  if (!IS_TOP) return;

  /* ======================================================================== top frame */

  var cfg = window.__FXTV_CFG || {};
  if (window.__fxtv) { window.__fxtv.apply(cfg); window.__fxtv.boot(); return; }

  var fx = { cfg: { scale: 100, shield: true }, fsTarget: null, lastPlaying: null, booted: false };
  window.__fxtv = fx;

  /* ---------------- CSS: D-pad focus only (site animations left untouched; ads are cut at the network) ---------------- */
  var BASE_CSS =
    'html,body{background:#08090b!important;overflow-x:hidden!important}' +
    '::-webkit-scrollbar{width:0!important;height:0!important}' +
    ':focus{outline:4px solid #d6ad55!important;outline-offset:3px!important;border-radius:6px!important}' +
    'a:focus,button:focus,[tabindex]:focus{background-color:rgba(214,173,85,.14)!important}';

  // Only cheap, tag-bound selectors (ads are mainly cut at the network level).
  var SHIELD_CSS =
    'ins.adsbygoogle,iframe[src*="doubleclick"],iframe[src*="/ads/"],iframe[src*="adserver"],' +
    'iframe[src*="popads"],iframe[id^="google_ads"],div[id^="div-gpt-ad"]{display:none!important}';

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

  function usable(el) {
    return !el.disabled && (el.tabIndex >= 0 || el.tagName === 'IFRAME');
  }

  /** Style-based visibility is costly, so it is only checked for the few best candidates. */
  function styleVisible(el) {
    return !el.checkVisibility || el.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true });
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
      el.scrollIntoView({ block: r.height > vh - 2 * m ? 'start' : 'center', inline: 'nearest', behavior: 'instant' });
    }
  }

  function scrollPage(dir) {
    if (dir === 'DOWN') window.scrollBy({ top: 320, behavior: 'instant' });
    if (dir === 'UP') window.scrollBy({ top: -320, behavior: 'instant' });
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

  /** Focus the first usable element visible on screen. */
  function focusFirstInView() {
    var all = document.querySelectorAll(NAV_SEL), vh = window.innerHeight, i, el, r;
    for (i = 0; i < all.length; i++) {
      el = all[i];
      if (!usable(el)) continue;
      r = el.getBoundingClientRect();
      if (r.width < 4 || r.height < 4 || r.top < 0 || r.top >= vh) continue;
      if (styleVisible(el)) { focusEl(el); return true; }
    }
    return false;
  }

  fx.move = function (dir) {
    var active = document.activeElement, cur = null, i, el, r, sc;
    if (active && active !== document.body && active !== document.documentElement) {
      cur = active.getBoundingClientRect();
      if (cur.width < 1 && cur.height < 1) cur = null;
    }
    if (!cur) { if (!focusFirstInView()) scrollPage(dir); return true; }

    // Geometry first (cheap), style check only on the best few.
    var all = document.querySelectorAll(NAV_SEL), cands = [];
    for (i = 0; i < all.length; i++) {
      el = all[i];
      if (el === active || !usable(el)) continue;
      r = el.getBoundingClientRect();
      if (r.width < 4 || r.height < 4) continue;
      sc = score(dir, cur, r);
      if (sc >= 0) cands.push({ s: sc, el: el });
    }
    cands.sort(function (a, b) { return a.s - b.s; });
    for (i = 0; i < cands.length && i < 12; i++) {
      if (styleVisible(cands[i].el)) { focusEl(cands[i].el); return true; }
    }
    scrollPage(dir);
    return false;
  };

  fx.activate = function () {
    var el = document.activeElement;
    if (!el || el === document.body) return;
    var inner = (el.matches && el.matches('a[href],button')) ? el : el.querySelector('a[href],button,[role="button"]');
    (inner || el).click();
  };

  /* ---------------- Player (top frame first, then embedded players) ---------------- */

  /** Run a command on the top-frame video; if there is none, hand it to the embedded players. */
  fx.cmd = function (cmd, val, req) {
    var r = A.doCmd(cmd, val);
    if (r.ok) return r;
    A.forward({ op: 'cmd', cmd: cmd, val: val, req: req });
    return { ok: false, forwarded: true };
  };

  /** Ask every frame for its player options; answers arrive through FrameXTVBridge.playerOptions. */
  fx.queryPlayer = function (req) { A.dispatch({ op: 'query', req: req }); };

  fx.setPlayer = function (frame, key, src, value) {
    A.dispatch({ op: 'set', target: frame, key: key, src: src, value: value });
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
    var t = A.pickVideo();
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
    A.forward({ op: 'cmd', cmd: 'PAUSE', val: 0, req: 'bg' });
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
      new MutationObserver(function () {
        if (!document.getElementById('fxtv-style')) applyStyle();
        if (fx.cfg.scale < 100) applyViewport();
      }).observe(document.head, { childList: true });
    }

    document.addEventListener('focusin', report, true);
    document.addEventListener('focusout', function () { setTimeout(report, 0); }, true);
    window.addEventListener('blur', function () { setTimeout(report, 0); });

    if (!document.activeElement || document.activeElement === document.body) focusFirstInView();
    report();
    playState();
  };

  fx.apply(cfg);
  if (document.body) fx.boot();
  else document.addEventListener('DOMContentLoaded', fx.boot);
})();
