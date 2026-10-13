// launcher.ts — target launcher: the strip "+" opens a real open-target dialog;
// target.open (server detects type from magic bytes). The ACCEPT hands the new
// id to the open-status card (openstatus.js) and this dialog closes — the
// analysis progress no longer lives under the file browser (2026-09-08).
import { S, pushEscape, popEscape, ESC } from './hub.js';
import { tryOp } from '../api.js';
import { trackOpen } from './openstatus.js';
import type { EscapeHandle, FsEntry, TargetInfo } from '../types.js';

/* B3 — session-scoped last-dir memory. Module state, NOT localStorage: a HANDOFF
   decision owns persistence (recents/bookmarks). null → the server's working
   directory (cwd — fs.list's blank-dir default; 2026-09-08: was user.home). */
let lastDir: string | null = null;

/* B2 — the openable HINT set. The backend detects type from magic bytes; the
   extension only drives the row dim and the "N openable" count (B7). A .aab or
   a renamed .zip is the detector's call, not ours. Dirs never dim. */
const OPENABLE = /\.(apk|dex|jar|jdb2)$/i;

/* "/a/b/c" → "/a/b" · "/a" → "/" · "~/x" → "~" · no slash → "" (unknown) */
function parentOf(p: string){
  const cut = p.replace(/\/+$/, '');
  const i = cut.lastIndexOf('/');
  return i > 0 ? cut.slice(0, i) : (i === 0 ? '/' : '');
}

export function initLauncher(onOpened: (tid: string, info: TargetInfo) => unknown): void {
  const modal = document.getElementById('launcher')!;
  const path = document.getElementById('launch-path') as HTMLInputElement;
  const err = document.getElementById('launch-err')!;
  const openBtn = document.getElementById('launch-open') as HTMLButtonElement;
  const browseBtn = document.getElementById('launch-browse')!;
  const fsPane = document.getElementById('launch-fs')!;
  const fsDir = document.getElementById('launch-fsdir')!;
  const fsList = document.getElementById('launch-fslist')!;
  const upBtn = document.getElementById('launch-up') as HTMLButtonElement;
  let esc: EscapeHandle | null = null;
  /* the gesture counter: hide() bumps it, doOpen compares — a target.open
     answer that lands after an Escape belongs to an ABANDONED gesture */
  let openSeq = 0;

  function show(){
    /* no project open → the PROJECT gesture comes first (2026-09-07): the
       projmenu opens with its guide strip — pick an ON DISK project or create
       one — and afterProjectChange resumes into this dialog. The backend still
       auto-creates a project on a bare target.open: the gate is a GUIDE, the
       op is the safety net. */
    if (!(S.state && S.state.activeProject)){
      S.hooks.needProjectThenOpen = true;
      const menu = document.getElementById('projmenu');
      if (menu && menu.hidden){
        /* DEFER a tick: this show() runs INSIDE the +-click, which then bubbles
           to the document outside-click closer — an immediate projchip.click()
           opens the menu and the same gesture slams it shut (verified live).
           After the bubble, the open sticks. */
        setTimeout(function(){
          /* projchip is a TOGGLE, not an open: if anything opened the menu
             inside this tick (⌘⇧O, a chip click), clicking again would CLOSE
             it — and closeProjMenu would silently cancel the guided flow
             (r5 F3) */
          const m = document.getElementById('projmenu');
          const chip = document.getElementById('projchip');
          if (chip && m && m.hidden) chip.click();
        }, 0);
      } else {
        const g = document.getElementById('pm-guide');
        if (g) g.hidden = false;  // menu already open: just show the guide
      }
      return;
    }
    err.textContent = '';
    /* an Escape mid-target.open left OPEN disabled for the reopened dialog
       (the answer used to re-enable it, into a hidden modal) — a new gesture
       starts usable; the in-flight open gets its card regardless (doOpen) */
    openBtn.disabled = false;
    modal.hidden = false;
    fsPane.hidden = true;
    path.value = '';
    path.focus();
    loadRecent();
    if (!esc) esc = pushEscape(function(){ if (modal.hidden) return false; hide(); }, ESC.modal);
  }

  /* zone 1: the target store's recent entries (target.recent) — a row opens its store copy,
     1–9 from the path field pick a row; an entry whose artifact left the store is listed
     but not openable (audit decision 18) */
  let recent: { key: string; name?: string; storePath?: string; projects?: string[]; present?: boolean; open?: string; lastUsedIso?: string }[] = [];
  function ago(iso?: string){
    if (!iso) return '';
    const ms = Date.now() - Date.parse(iso);
    if (!(ms >= 0)) return '';
    const m = Math.round(ms / 60000);
    if (m < 60) return m + ' min ago';
    const h = Math.round(m / 60);
    if (h < 48) return h + ' h ago';
    return Math.round(h / 24) + ' d ago';
  }
  async function loadRecent(){
    const zone = document.getElementById('launch-recent')!;
    const list = document.getElementById('launch-recent-list')!;
    const r = await tryOp('target.recent', {});
    recent = (r.data || []) as typeof recent;
    if (r.error || !recent.length){ zone.hidden = true; list.innerHTML = ''; return; }
    list.innerHTML = '';
    recent.forEach(function(t, i){
      const row = document.createElement('div');
      row.className = 'wbm-recent-row' + (t.present === false ? ' gone' : '');
      row.innerHTML = '<kbd>' + (i + 1) + '</kbd><span class="nm"></span><span class="pj"></span><span class="ag"></span>' +
        (t.open ? '<span class="open">OPEN NOW</span>' : '') + (t.present === false ? '<span class="gone">not in the store</span>' : '');
      row.querySelector('.nm')!.textContent = t.name || t.key;
      row.querySelector('.pj')!.textContent = (t.projects || []).join(' · ');
      row.querySelector('.ag')!.textContent = ago(t.lastUsedIso);
      row.title = (t.storePath || '') + (t.open ? ' — open now: a click takes you there' : '');
      if (t.present !== false && t.storePath) row.addEventListener('click', function(){ openRecent(i); });
      list.appendChild(row);
    });
    zone.hidden = false;
  }
  function openRecent(i: number){
    const t = recent[i];
    if (!t || t.present === false || !t.storePath) return;
    path.value = t.storePath;
    doOpen();
  }
  function hide(){
    modal.hidden = true;
    openSeq++;                     // whatever target.open is in flight no longer owns this dialog
    popEscape(esc);
    esc = null;
  }

  document.getElementById('tadd')!.addEventListener('click', show);
  S.hooks.openLauncher = show;   // the projmenu's resume handle (guided flow)
  document.getElementById('launch-cancel')!.addEventListener('click', hide);
  modal.addEventListener('click', function(e){ if (e.target === modal) hide(); });

  async function doOpen(){
    const p = path.value.trim();
    if (!p){ err.textContent = 'path required'; return; }
    err.textContent = '';
    openBtn.disabled = true;
    /* the ACCEPT contract (2026-09-08): target.open answers as soon as the
       target is registered and its analysis thread has started (state
       loading — or ready at once for a dedupe onto a live session). From
       there the open-status card owns the target.info poll and the READY →
       strip chain (openstatus.js, onOpened rides along) and THIS DIALOG
       CLOSES. It stays open only for a pre-accept refusal (E_SYNTAX /
       E_NOTFOUND / E_UNSUPPORTED / E_BUSY), inline in #launch-err.
       Abandoned gesture: an Escape during the await bumped openSeq (hide).
       An accept still gets its card — the server DID open it, and losing
       that was the orphan hole — but it must not hide a launcher the user
       has since reopened; a refusal must not land in that new gesture's
       error line either. */
    const seq = openSeq;
    const t0 = Date.now();
    /* rekey: this is the user's own gesture — a saved database that shares its JEB key with an
       open target is analysed again under a key of its own instead of the open being refused
       (the answer's `rekeyed` + note say so on the open card) */
    const r = await tryOp('target.open', { path: p, rekey: true });
    const abandoned = seq !== openSeq;
    if (r.error){
      if (abandoned){ console.warn('target.open (abandoned gesture)', r.error.message); return; }
      err.textContent = r.error.message;                 // E_SYNTAX / E_NOTFOUND / E_UNSUPPORTED
      openBtn.disabled = false;
      return;
    }
    /* B3: the server accepted the path — its dir is where the user works.
       Relative paths carry no dir we can trust; '~' is expanded server-side. */
    const pd = parentOf(p);
    if (pd && (pd[0] === '/' || pd[0] === '~')) lastDir = pd;
    if (!abandoned){ openBtn.disabled = false; hide(); }
    trackOpen(r.data, { startedAt: t0, onReady: onOpened });
  }

  openBtn.addEventListener('click', doOpen);
  path.addEventListener('keydown', function(e){
    if (e.key === 'Enter'){ e.preventDefault(); doOpen(); }
    /* 1–9 with an empty path field pick a RECENT row (zone 1's number keys) */
    if (!path.value && /^[1-9]$/.test(e.key) && !e.metaKey && !e.ctrlKey && !e.altKey && recent[+e.key - 1]){
      e.preventDefault();
      openRecent(+e.key - 1);
    }
  });

  /* BROWSE pane — server-side fs.list: a pick is a PATH, not an upload. Click a
     dir to descend, click a file to fill the input, double-click to open it.
     A review on 2026-09-08
     judged the v0 mouse-only and forgetful; B1-B3 and B5-B7 are closed below
     (B4's ~ expansion shipped server-side). */
  const fsCount = document.getElementById('launch-fscount')!;
  const dotsBtn = document.getElementById('launch-dots')!;

  function humanSize(n: number){
    if (n >= 1 << 30) return (n / (1 << 30)).toFixed(1) + 'G';
    if (n >= 1 << 20) return (n / (1 << 20)).toFixed(1) + 'M';
    if (n >= 1024) return Math.round(n / 1024) + 'K';
    return n + 'B';
  }

  let fsCur = '', fsParent = '';   // listed dir + its parent ('' at the root)
  let fsEntries: FsEntry[] = [], fsTrunc = false;   // the raw fs.list page; render() filters it
  let showDots = false;            // B2: dotfiles hidden by default
  let items: FsEntry[] = [], rows: HTMLDivElement[] = [];       // rendered (visible) entries ↔ their option rows
  let cur = -1;                    // B1: index of the highlighted row, -1 = none
  let navSeq = 0, navBusy = false; // B5: only the newest fs.list response renders
  let guardUntil = 0;              // B5: pointer actions ignored until this instant
  let typed = '', typedAt = 0;     // B1: type-ahead buffer (700ms idle resets it)

  /* B1 — the list is ONE tab stop (role=listbox + aria-activedescendant) rather
     than roving tabindex on rows: focus lives on the container, so the re-render
     after a descent never drops focus on <body> (P1-2's failure mode), and no
     per-row data-tip can flicker along under a keyboard cursor. */
  function select(i: number, scroll?: boolean){
    if (cur >= 0 && rows[cur]){
      rows[cur].classList.remove('sel');
      rows[cur].removeAttribute('aria-selected');
    }
    cur = rows.length ? Math.max(0, Math.min(i, rows.length - 1)) : -1;
    if (cur < 0){ fsList.removeAttribute('aria-activedescendant'); return; }
    const row = rows[cur];
    row.classList.add('sel');
    row.setAttribute('aria-selected', 'true');
    fsList.setAttribute('aria-activedescendant', row.id);
    if (scroll !== false) row.scrollIntoView({ block: 'nearest' });
  }

  /* a course room's browsable ROOTS (its own files and the course folders): chips above the list,
     each a jump — from an empty workspace there is otherwise no way to the course folder, since UP
     stops at a root (persona pass 2026-10-09 M8). Standalone: fs.list sends none, nothing is shown. */
  let rootsRow: HTMLDivElement | null = null;
  function paintRoots(roots: string[] | undefined){
    if (!roots || roots.length < 2){ if (rootsRow){ rootsRow.remove(); rootsRow = null; } return; }
    if (!rootsRow){
      rootsRow = document.createElement('div');
      rootsRow.className = 'ln-roots';
      fsList.parentElement?.insertBefore(rootsRow, fsList);
    }
    rootsRow.textContent = '';
    roots.forEach((root, i) => {
      const chip = document.createElement('button');
      chip.type = 'button';
      chip.className = 'btn ln-root' + (fsCur === root || fsCur.startsWith(root + '/') ? ' on' : '');
      chip.textContent = i === 0 ? 'YOUR FILES' : 'COURSE · ' + (root.split('/').pop() || root);
      chip.setAttribute('data-tip', root);
      chip.addEventListener('click', () => { void fsNav(root, true); });
      rootsRow!.appendChild(chip);
    });
  }

  /* hilite: path of the entry to land the cursor on (the dir we just left on
     ↑ UP, the typed file on BROWSE, the row under the cursor on a · toggle) */
  function render(hilite?: string | null){
    fsList.textContent = '';
    items = []; rows = []; cur = -1;
    let hidden = 0, openable = 0;
    for (const e of fsEntries){
      if (!showDots && e.name[0] === '.'){ hidden++; continue; }   // B2
      const ok = !e.dir && OPENABLE.test(e.name);
      if (ok) openable++;
      const row = document.createElement('div');
      row.className = 'fsrow' + (e.dir ? ' fsd' : (ok ? '' : ' fsdim'));
      row.setAttribute('role', 'option');
      row.id = 'lfs-' + rows.length;
      row.dataset.i = String(rows.length);
      const nm = document.createElement('span');
      nm.className = 'fsnm';
      nm.textContent = e.dir ? e.name + '/' : e.name;
      row.appendChild(nm);
      if (!e.dir){
        const sz = document.createElement('span');
        sz.className = 'fssz';
        sz.textContent = humanSize(e.size);
        row.appendChild(sz);
      }
      items.push(e); rows.push(row);
      fsList.appendChild(row);
    }
    if (!rows.length){
      const none = document.createElement('div');
      none.className = 'fsrow fsmore';
      none.textContent = hidden ? 'only dotfiles here — · shows them' : 'empty';
      fsList.appendChild(none);
    }
    if (fsTrunc){
      const more = document.createElement('div');
      more.className = 'fsrow fsmore';
      more.textContent = '… listing capped at 2000 — type the path';
      fsList.appendChild(more);
    }
    /* B7 — the bar says how much is here and how much of it we can open;
       "N hidden" is the honesty cue that the · toggle has something to reveal */
    fsCount.textContent = (fsTrunc ? '2000+' : rows.length) +
      (rows.length === 1 && !fsTrunc ? ' entry' : ' entries') +
      ' · ' + openable + ' openable' + (hidden ? ' · ' + hidden + ' hidden' : '');
    const j = hilite ? items.findIndex(function(e){ return e.path === hilite; }) : -1;
    select(j >= 0 ? j : 0);
  }

  async function fsNav(dir: string | null | undefined, focusList?: boolean, hilite?: string): Promise<void>{
    err.textContent = '';
    const seq = ++navSeq;
    navBusy = true;
    const r = await tryOp('fs.list', dir ? { dir: dir } : {});
    if (seq !== navSeq) return;   // B5: superseded — the newer call owns navBusy
    navBusy = false;
    if (r.error){
      /* a FILE path in the input + BROWSE → land in its parent dir, not an error */
      if (dir && /not a directory/.test(r.error.message))
        return fsNav(parentOf(dir) || null, focusList, dir.replace(/\/+$/, ''));
      /* B3: the remembered dir vanished (ejected, deleted) → forget it, land at the default (cwd) */
      if (dir && dir === lastDir){ lastDir = null; return fsNav(null, focusList); }
      /* the message AND the detail: a course room's refusal says where one may browse in the detail */
      err.textContent = r.error.message + (r.error.payload?.error?.detail ? ' — ' + r.error.payload.error.detail : '');
      return;
    }
    fsPane.hidden = false;
    fsDir.textContent = r.data.dir;
    fsDir.setAttribute('data-tip', r.data.dir);   // B6: the shell tip, not title=
    fsCur = r.data.dir;
    fsParent = r.data.parent || '';
    upBtn.disabled = !fsParent;
    paintRoots(r.data.roots as string[] | undefined);
    fsEntries = r.data.entries;
    fsTrunc = !!r.data.truncated;
    lastDir = fsCur;                              // B3: any navigation is remembered
    render(hilite);
    /* B5: the 2nd click of a double-click on a dir lands on whatever row the
       fresh listing put under the pointer — and a dblclick there would OPEN a
       file. Pointer actions sleep 300ms after a render; the keyboard is exempt
       (a repeated ⏎ is intent, and navSeq already collapses in-flight repeats). */
    guardUntil = Date.now() + 300;
    if (focusList) fsList.focus();
  }

  function pick(i: number){
    path.value = items[i].path;
    err.textContent = '';
  }
  function up(){ if (fsParent) fsNav(fsParent, false, fsCur); }

  function rowAt(ev: Event){
    const t = ev.target as HTMLElement | null;
    const row = t && t.closest ? t.closest<HTMLElement>('.fsrow[data-i]') : null;
    return row ? +row.dataset.i! : -1;
  }
  fsList.addEventListener('click', function(ev){
    const i = rowAt(ev);
    if (i < 0 || navBusy || Date.now() < guardUntil) return;   // B5
    select(i);
    if (items[i].dir) fsNav(items[i].path); else pick(i);
  });
  fsList.addEventListener('dblclick', function(ev){
    const i = rowAt(ev);
    if (i < 0 || navBusy || Date.now() < guardUntil) return;   // B5
    if (!items[i].dir){ pick(i); doOpen(); }
  });

  /* B1 — type-ahead: a growing prefix searches from the cursor row inclusive
     ("de" stays on Desktop instead of skipping to Developer); a repeated single
     char cycles through that char's matches, wrapping. */
  function typeAhead(ch: string){
    const now = Date.now();
    typed = (now - typedAt < 700 ? typed : '') + ch.toLowerCase();
    typedAt = now;
    const n = rows.length;
    if (!n) return;
    const rep = typed.length > 1 && typed.split('').every(function(c){ return c === typed[0]; });
    const q = rep ? typed[0] : typed;
    const from = rep ? cur + 1 : Math.max(cur, 0);
    for (let s = 0; s < n; s++){
      const i = (from + s) % n;
      if (items[i].name.toLowerCase().startsWith(q)){ select(i); return; }
    }
  }

  /* B1 — keys while the list has focus. ⏎ on a dir descends; ⏎ on a FILE fills
     the input and hands focus to OPEN — a fill+open double action would surprise,
     ⏎ ⏎ is the deliberate form. Esc is untouched: the modal's pushEscape owns it. */
  fsList.addEventListener('keydown', function(e){
    if (e.metaKey || e.ctrlKey || e.altKey) return;
    const k = e.key, n = rows.length;
    if (k === 'ArrowDown') select(cur + 1);
    else if (k === 'ArrowUp') select(cur - 1);
    else if (k === 'Home') select(0);
    else if (k === 'End') select(n - 1);
    else if (k === 'PageDown' || k === 'PageUp'){
      const rh = (cur >= 0 && rows[cur].offsetHeight) || 16;
      const page = Math.max(1, Math.floor(fsList.clientHeight / rh) - 1);
      select(cur + (k === 'PageDown' ? page : -page));
    }
    else if (k === 'Enter'){
      if (cur < 0) return;
      if (items[cur].dir) fsNav(items[cur].path);
      else { pick(cur); openBtn.focus(); }
    }
    else if (k === 'Backspace' || k === 'ArrowLeft') up();
    else if (k === 'ArrowRight'){
      if (cur < 0 || !items[cur].dir) return;
      fsNav(items[cur].path);
    }
    else if (k.length === 1) typeAhead(k);
    else return;
    e.preventDefault();
    /* the document-level shortcuts (palette.js) gate on INPUT/TEXTAREA targets —
       a focused listbox div reads as "not typing", so a type-ahead 't' would
       open the tag dialog and '/' would steal focus to the tree filter */
    e.stopPropagation();
  });

  /* B2 — the · toggle: client-side, the entries are already here */
  dotsBtn.addEventListener('click', function(){
    showDots = !showDots;
    dotsBtn.classList.toggle('on', showDots);
    dotsBtn.setAttribute('aria-pressed', String(showDots));
    dotsBtn.setAttribute('data-tip', showDots ? 'hide dotfiles' : 'show dotfiles (hidden by default)');
    if (!fsPane.hidden) render(cur >= 0 ? items[cur].path : null);
  });

  upBtn.addEventListener('click', up);
  browseBtn.addEventListener('click', function(){
    if (!fsPane.hidden){ fsPane.hidden = true; return; }
    const v = path.value.trim();
    /* typed dir → list it; typed file → its parent, file highlighted (in fsNav);
       blank → B3 lastDir, else the server's cwd. Focus lands in the list so ↑↓ work at once. */
    fsNav(v || lastDir || null, true);
  });
}
