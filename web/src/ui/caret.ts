// caret.ts — IDA-style keyboard caret + view modes (Java/Smali/Split) + sync band.
import { $all, S, hook, pushEscape, ESC, methodAt, escHtml } from './hub.js';
import { setCodeCrumb, focusCenter, showCenter } from './chrome.js';
import type { CodeToken } from '../types.js';

let codeEl: HTMLElement, caretEl: HTMLElement, blinkT = 0, smaliEl: HTMLElement;
let viewMode = 'split', savedFlex: string | null = null;

/* wrap every bare-text identifier in span.ident so the whole listing is
   caret-highlightable, IDA-style — not just the navigable .nav tokens. */
function wrapIdents(lc: HTMLElement){
  const IDENT = /[A-Za-z_$][A-Za-z0-9_$]*/g;
  const textNodes: Text[] = [];
  (function collect(node: Node, skip: boolean){
    for (let i = 0; i < node.childNodes.length; i++){
      const n = node.childNodes[i];
      if (n.nodeType === 3){ if (!skip) textNodes.push(n as Text); }
      else if (n.nodeType === 1){
        const s = skip || ((n as Element).classList && ((n as Element).classList.contains('s') ||
                    (n as Element).classList.contains('c') || (n as Element).classList.contains('was')));
        collect(n, s);
      }
    }
  })(lc, false);
  textNodes.forEach(function(tn){
    const text = tn.textContent!, frag = document.createDocumentFragment();
    let last = 0, m: RegExpExecArray | null, wrapped = false;
    IDENT.lastIndex = 0;
    while ((m = IDENT.exec(text))){
      if (m.index > last) frag.appendChild(document.createTextNode(text.slice(last, m.index)));
      const sp = document.createElement('span');
      sp.className = 'ident';
      sp.textContent = m[0];
      frag.appendChild(sp);
      last = m.index + m[0].length;
      wrapped = true;
    }
    if (!wrapped) return;
    if (last < text.length) frag.appendChild(document.createTextNode(text.slice(last)));
    tn.parentNode!.replaceChild(frag, tn);
  });
}

export function buildCodeModel(): void {
  S.codeLines = $all('.cl', codeEl).map(function(cl){
    const lc = cl.querySelector<HTMLElement>('.lc');
    const tokens: CodeToken[] = [];
    let off = 0;
    if (lc){
      wrapIdents(lc);
      (function walk(node: Node){
        for (let i = 0; i < node.childNodes.length; i++){
          const n = node.childNodes[i];
          if (n.nodeType === 3){ off += n.textContent!.length; }
          else if (n.nodeType === 1){
            const start = off;
            walk(n);
            if ((n as Element).classList && ((n as Element).classList.contains('nav') || (n as Element).classList.contains('ident'))){
              tokens.push({ start:start, end:off, el:n as HTMLElement, name:n.textContent! });
            }
          }
        }
      })(lc);
    }
    return { cl:cl, lc:lc, text:lc ? lc.textContent! : '', tokens:tokens };
  });
}

function measureChar(){
  const lc = S.codeLines[0] && S.codeLines[0].lc;
  if (!lc) return;
  const probe = document.createElement('span');
  probe.style.cssText = 'position:absolute;visibility:hidden;white-space:pre';
  probe.textContent = 'XXXXXXXXXX';
  lc.appendChild(probe);
  S.charW = probe.getBoundingClientRect().width / 10 || 7;
  probe.remove();
}

export function renderCaret(moved: boolean, follow: boolean = true): void {
  const caret = S.caret;
  const L = S.codeLines[caret.line];
  if (!L || !L.lc) return;
  /* a row carrying a lifted agent comment (.llmsite) is two visual lines: the caret marks the
     code line only — .lc shares the row's offset parent (the row is not positioned) */
  const lifted = L.cl.classList.contains('llmsite');
  caretEl.style.top    = (lifted ? L.lc.offsetTop : L.cl.offsetTop) + 'px';
  caretEl.style.height = (lifted ? L.lc.offsetHeight : L.cl.offsetHeight) + 'px';
  caretEl.style.left   = (L.lc.offsetLeft + caret.col * S.charW) + 'px';
  $all('.cl.caret-line', codeEl).forEach(function(c){ c.classList.remove('caret-line'); });
  L.cl.classList.add('caret-line');
  $all('.tok-hot', codeEl).forEach(function(t){ t.classList.remove('tok-hot'); });
  $all('.tok-dim', codeEl).forEach(function(t){ t.classList.remove('tok-dim'); });
  let hot = null as CodeToken | null;
  L.tokens.forEach(function(t){
    if (caret.col >= t.start && caret.col <= t.end) hot = t;
  });
  if (hot){
    hot.el.classList.add('tok-hot');
    S.codeLines.forEach(function(l2){
      l2.tokens.forEach(function(t2){
        if (t2 !== hot && t2.name === hot!.name) t2.el.classList.add('tok-dim');
      });
    });
  }
  // mockup crumb: the method segment follows the caret (class crumb base is
  // stored by code.js at loadSymbol). No `moved` gate — the parked caret on
  // class open should already show its method.
  if (S.crumbBase){
    const line1 = caret.line + 1;
    const m = methodAt(S.javaMethods, line1);   // the ONE resolver (GUI review finding 11)
    setCodeCrumb(S.crumbBase + (m ? '<span class="sep">›</span><span class="cur">'
        + escHtml(m.name) + '</span>' + (m.origName ? '<span class="was"> (was: ' + escHtml(m.origName) + ')</span>' : '') : ''));
  }
  if (moved){
    caretEl.classList.add('moving');
    clearTimeout(blinkT);
    blinkT = setTimeout(function(){ caretEl.classList.remove('moving'); }, 600);
    if (follow) scheduleFollow();   // a landing the RELATION pane initiated holds its subject (relation.ts landOnRow)
  }
  syncSmali();
}
S.hooks.measureRender = function(){ measureChar(); renderCaret(false); };

/* Java caret line → smali rows. Rows carry data-j (java line): exact line match
   first (mock fixture rows are line-precise); fallback: the whole smali block of
   the method enclosing the caret line (JEB rows are method-mapped). */
function syncSmali(){
  $all('#smali .sr.synced').forEach(function(r){ r.classList.remove('synced'); });
  $all('#smali .syncg').forEach(function(g){ g.remove(); });
  if (S.syncOn === false) return;
  const jline = S.caret.line + 1;
  let rows = $all('#smali .sr[data-j="' + jline + '"]');
  if (!rows.length && S.javaMethods){
    const m = S.javaMethods.find(function(mm){
      return mm.line && mm.endLine && jline >= mm.line && jline <= mm.endLine;
    });
    if (m) rows = $all('#smali .sr[data-j="' + m.line + '"]');
  }
  rows.forEach(function(r, i){
    r.classList.add('synced');
    if (i === 0){
      const g = document.createElement('span');
      g.className = 'syncg';
      g.textContent = '⇄';
      g.title = 'linked to java line ' + jline;
      const sg = r.querySelector('.sg');
      if (sg) sg.appendChild(g);
      if (viewMode !== 'java') r.scrollIntoView({ block:'nearest' });
    }
  });
}

/* the pane headers say what the keyboard toggles did — the smali header kept
   claiming "offsets+bytes" after `b` hid the bytes (UI audit 2026-09-09) */
export function paintPaneHeads(): void {
  const sh = document.querySelector<HTMLElement>('#pane-smali .panehead .phent');
  if (sh && sh.dataset.engine) sh.textContent = (S.bytesOn === false ? 'offsets' : 'offsets+bytes') + (S.syncOn === false ? ' · sync off' : ' · ⇄ sync') + ' · ' + sh.dataset.engine;   // an empty target's header is not ours to rewrite
}

/* RELATION follows the caret: debounced hook fired on every caret move. */
let followT = 0;
function scheduleFollow(){
  clearTimeout(followT);
  followT = setTimeout(function(){ hook('caretFollow'); }, 250);
}
/* a follow already armed by the click BEFORE a RELATION landing would fire on
   the landed token and re-target the pane the landing is holding
   (fix-review relation r2 M1): the landing cancels it */
export function cancelFollow(){ clearTimeout(followT); followT = 0; }

function isWordCh(ch: string){ return /[A-Za-z0-9_$]/.test(ch); }
function lineLen(){ return S.codeLines[S.caret.line].text.length; }

function moveLeft(){
  const c = S.caret;
  if (c.col > 0) c.col--;
  else if (c.line > 0){ c.line--; c.col = lineLen(); }
  c.goal = c.col;
}
function moveRight(){
  const c = S.caret;
  if (c.col < lineLen()) c.col++;
  else if (c.line < S.codeLines.length - 1){ c.line++; c.col = 0; }
  c.goal = c.col;
}
function moveUp(){
  const c = S.caret;
  if (c.line > 0){ c.line--; c.col = Math.min(c.goal, lineLen()); }
}
function moveDown(){
  const c = S.caret;
  if (c.line < S.codeLines.length - 1){ c.line++; c.col = Math.min(c.goal, lineLen()); }
}
function wordRight(){
  const caret = S.caret;
  const t = S.codeLines[caret.line].text, n = t.length;
  let c = caret.col;
  if (c >= n){ moveRight(); return; }
  while (c < n && t[c] === ' ') c++;
  if (c < n){
    if (isWordCh(t[c])){ while (c < n && isWordCh(t[c])) c++; }
    else c++;
  }
  caret.col = c; caret.goal = c;
}
function wordLeft(){
  const caret = S.caret;
  const t = S.codeLines[caret.line].text;
  let c = caret.col;
  if (c <= 0){ moveLeft(); return; }
  while (c > 0 && t[c - 1] === ' ') c--;
  if (c > 0){
    if (isWordCh(t[c - 1])){ while (c > 0 && isWordCh(t[c - 1])) c--; }
    else c--;
  }
  caret.col = c; caret.goal = c;
}
function caretHome(){
  const t = S.codeLines[S.caret.line].text;
  let i = 0;
  while (i < t.length && t[i] === ' ') i++;
  S.caret.col = i; S.caret.goal = i;
}
function caretEnd(){ S.caret.col = lineLen(); S.caret.goal = S.caret.col; }

/* ==================================================================
   VIEW MODES — Tab cycles split → java → smali → split (no ⇧Tab)
================================================================== */
function setMode(m: string){
  viewMode = m;
  // the psplit drag's inline flex pins pane-java at its split percentage —
  // in single mode that leaves half the center blank. Save/clear on the way
  // in, restore on the way back to split. savedFlex is a NULL sentinel, not
  // '': cycling java→smali must not re-capture the very override we set.
  const pj = document.getElementById('pane-java')!;
  if (m !== 'split'){
    if (savedFlex === null) savedFlex = pj.style.flex || '';
    pj.style.flex = '1 1 auto';
  } else if (savedFlex !== null){
    pj.style.flex = savedFlex;
    savedFlex = null;
  }
  S.codewrap!.className = 'mode-' + m + (S.codewrap!.classList.contains('swapped') ? ' swapped' : '');
  if (S.caretReady){ measureChar(); renderCaret(false); }
  // focus follows the visible pane (M4): Tab must always have a live target
  if (m === 'smali') smaliEl.focus({ preventScroll: true });
  else codeEl.focus({ preventScroll: true });
}

export function initCaret(): void {
  codeEl = document.getElementById('code')!;
  S.codewrap = document.getElementById('codewrap')!;
  smaliEl = document.getElementById('smali')!;
  if ((S.syncOn as boolean | undefined) === undefined) S.syncOn = true;
  if ((S.bytesOn as boolean | undefined) === undefined) S.bytesOn = true;

  caretEl = document.createElement('div');
  caretEl.className = 'caretbar';
  codeEl.appendChild(caretEl);

  // view-mode keys live on #codewrap — focusable in EVERY mode (M4 fix).
  S.codewrap!.addEventListener('keydown', function(e){
    const k = e.key;
    /* the shortcuts sheet owns every key while open — its window-capture gate
       (palette.js) stops propagation before this listener runs, and #codewrap is
       inert anyway. Belt-and-braces for any path that leaves focus here. */
    if (!document.getElementById('keyhelp')!.hidden) return;
    /* Escape is NOT handled here — the code view registers with the central
       escape dispatcher below (hub.js), which owns precedence app-wide. */
    if (k === 'Tab'){
      e.preventDefault();
      // ONE hotkey, one cycle (user contract): split → java → smali → split.
      // No ⇧Tab.
      setMode(viewMode === 'split' ? 'java' : viewMode === 'java' ? 'smali' : 'split');
      return;
    }
    /* codehead-strip replacements (panel removed — keyboard-only controls) */
    if (k === '\\' && !e.metaKey && !e.ctrlKey && !e.altKey){
      e.preventDefault();
      S.codewrap!.classList.toggle('swapped');
      return;
    }
    if (k === 'b' && !e.metaKey && !e.ctrlKey && !e.altKey){
      e.preventDefault();
      S.bytesOn = !S.bytesOn;
      smaliEl.classList.toggle('nobytes', !S.bytesOn);
      paintPaneHeads();
      return;
    }
    if (k === 's' && !e.metaKey && !e.ctrlKey && !e.altKey){
      e.preventDefault();
      S.syncOn = !S.syncOn;
      paintPaneHeads();
      if (S.caretReady) renderCaret(false);
      return;
    }
    if (k === 'c' && !e.metaKey && !e.ctrlKey && !e.altKey){
      e.preventDefault();
      hook('covCycle');
      return;
    }
    if (viewMode === 'smali' && (k === 'ArrowUp' || k === 'ArrowDown' || k === 'PageUp' || k === 'PageDown')){
      e.preventDefault();
      const lh = 17;
      smaliEl.scrollBy({ top: k === 'ArrowUp' ? -lh : k === 'ArrowDown' ? lh
        : (k === 'PageUp' ? -1 : 1) * smaliEl.clientHeight * 0.9 });
      return;
    }
    if (e.target !== codeEl) return;               // caret keys belong to the java pane only
    if      (k === 'ArrowLeft')  { e.preventDefault(); e.altKey ? wordLeft()  : moveLeft(); }
    else if (k === 'ArrowRight') { e.preventDefault(); e.altKey ? wordRight() : moveRight(); }
    else if (k === 'ArrowUp')    { e.preventDefault(); moveUp(); }
    else if (k === 'ArrowDown')  { e.preventDefault(); moveDown(); }
    else if (k === 'Home')       { e.preventDefault(); caretHome(); }
    else if (k === 'End')        { e.preventDefault(); caretEnd(); }
    else return;
    renderCaret(true);
    if (S.codeLines[S.caret.line]) S.codeLines[S.caret.line].cl.scrollIntoView({ block: 'nearest' });
  });

  codeEl.addEventListener('click', function(e){
    codeEl.focus();
    const cl = (e.target as HTMLElement).closest<HTMLElement>('.cl');
    if (cl) placeCaretAt(cl, e.clientX);
  });

  codeEl.addEventListener('dblclick', function(e){
    const tok = (e.target as HTMLElement).closest('.nav');
    if (tok){
      tok.classList.add('tok-flash');
      setTimeout(function(){ tok.classList.remove('tok-flash'); }, 350);
      hook('gotoDef', tok.getAttribute('data-sym'));   // QA 8: dblclick → definition
    }
  });

  const psplit = document.getElementById('psplit')!;
  psplit.addEventListener('pointerdown', function(e){
    e.preventDefault();
    psplit.classList.add('on');
    psplit.setPointerCapture(e.pointerId);
    const move = function(ev: PointerEvent){
      const wrap = S.codewrap!.getBoundingClientRect();
      let pct = (ev.clientX - wrap.left) / wrap.width * 100;
      if (S.codewrap!.classList.contains('swapped')) pct = 100 - pct;
      pct = Math.max(15, Math.min(85, pct));
      document.getElementById('pane-java')!.style.flex = '0 0 ' + pct + '%';
      if (S.caretReady){ measureChar(); renderCaret(false); }
    };
    const up = function(){
      psplit.classList.remove('on');
      psplit.removeEventListener('pointermove', move);
      psplit.removeEventListener('pointerup', up);
    };
    psplit.addEventListener('pointermove', move);
    psplit.addEventListener('pointerup', up);
  });
  psplit.addEventListener('dblclick', function(){
    document.getElementById('pane-java')!.style.flex = '';
    if (S.caretReady){ measureChar(); renderCaret(false); }
  });

  /* code view Esc (WORKBENCH_SPEC §6.1): cameFromMap → return to the map,
     otherwise navBack. Registered with the central dispatcher at view
     priority — it declines (returns false) unless focus really sits in the
     code panes, exactly the scope the old #codewrap listener had, so the
     field-scoped Escapes (tree filter, pane views) keep theirs. */
  pushEscape(function(){
    const a = document.activeElement;
    if (S.codewrap!.style.display === 'none') return false;   // another surface owns the center
    if (!a || !S.codewrap!.contains(a)) return false;
    if (S.cameFromMap){
      S.cameFromMap = false;
      showCenter('map');                         // QA 22: focusMap re-asserts the selection ring + inspector
      /* mapReselect dropped: it ran the SAME selectNode again — a duplicated
         map.node + code.decompile per Esc, seconds at GMS scale (map-r5 F6) */
      return;
    }
    hook('navBack');
  }, ESC.view);
}

/* pointer → caret: `cl` is the .cl row under the pointer, clientX picks the
   column (the same math the click always used). Shared by the click handler
   and the context menu (P0.2), which must park the caret on the right-clicked
   token BEFORE it offers the caret-symbol actions — F2 / ; / t / ⌘D / F9 all
   read symbolAtCaret. False when the row is not in the current model (a row
   from a listing that was re-rendered under the pointer). */
export function placeCaretAt(cl: HTMLElement, clientX: number): boolean {
  for (let i = 0; i < S.codeLines.length; i++){
    if (S.codeLines[i].cl !== cl) continue;
    const rect = S.codeLines[i].lc!.getBoundingClientRect();
    const col = Math.round((clientX - rect.left) / S.charW);
    S.caret.line = i;
    S.caret.col = Math.max(0, Math.min(col, S.codeLines[i].text.length));
    S.caret.goal = S.caret.col;
    renderCaret(true);
    return true;
  }
  return false;
}

/* caret (re)init after code (re)render: park on the given token name/line */
export function startCaret(tokenName: string | null | undefined, tokenLine: number | null | undefined): void {
  buildCodeModel();
  measureChar();
  const L = S.codeLines[(tokenLine || 17) - 1];
  S.caret.line = (tokenLine || 17) - 1;
  S.caret.col = 0;
  if (L && tokenName){
    L.tokens.forEach(function(t){ if (t.name === tokenName) S.caret.col = t.start; });
  }
  S.caret.goal = S.caret.col;
  S.caretReady = true;
  renderCaret(false);
  /* focus the surface the user is LOOKING at: a class can load while the map (or a
     pane view) still covers the center, and #code is display:none there — focusing
     it parked focus on <body> and killed the visible surface's keys (F6). */
  focusCenter();
}
