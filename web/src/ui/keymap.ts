// keymap.ts — the ONE keymap table. The '?' shortcuts sheet is rendered from it
// (it used to be hand-written HTML in index.html, duplicating every binding);
// handlers stay in the modules that own the keys. Adding a shortcut = one row
// here + the handler, and the sheet follows.
//
// Shape: groups in display order, `col` = which of the three sheet columns the
// group renders into, rows = { keys: [...printed keycaps], label }. A group
// with `cat` takes its rows from the COMMANDS table (palette.js) — every
// command of that category that has a keycap — with any static `rows`
// appended (keys that are not commands: Esc, ⌥1…9, ⌘K).
import type { Command } from './palette.js';

/** one printed row of the sheet: the keycaps and what they do */
export interface KeyRow {
  keys: string[];
  label: string;
}

/** one group of the sheet: static `rows`, COMMANDS rows via `cat`, or both */
export interface KeymapGroup {
  group: string;
  col?: number;
  cat?: string;
  rows?: KeyRow[];
}

export const KEYMAP: KeymapGroup[] = [
  { group: 'VIEWS', col: 0, cat: 'VIEW' },
  /* P0-5: open/close target had no key and no sheet row — ⌘O/⌥W are the
     COMMANDS TARGET rows; ⌥1…9 (a bare handler in palette.js initKeys, not a
     command) moves here from NAVIGATE so the three target keys read as one
     group. Column 0 was the lightest of the three. */
  { group: 'TARGETS', col: 0, cat: 'TARGET', rows: [
    { keys: ['⌥1…9'], label: 'Switch target' },
    { keys: ['1…9'],  label: 'Open a recent target (in the ＋ launcher)' },
  ] },
  { group: 'CODE VIEW', col: 0, rows: [
    { keys: ['Tab'], label: 'Cycle split → java → smali' },
    { keys: ['\\'],  label: 'Swap panes' },
    { keys: ['b'],   label: 'Smali bytes column' },
    { keys: ['s'],   label: 'Java↔smali caret sync' },
    { keys: ['c'],   label: 'Cycle coverage run' },
  ] },
  { group: 'SYMBOL TREE', col: 0, rows: [
    { keys: ['↑', '↓'],          label: 'Move cursor' },
    { keys: ['←', '→'],          label: 'Collapse / expand' },
    { keys: ['Enter'],           label: 'Open class' },
    { keys: ['Home', 'End'],     label: 'First / last row' },
    { keys: ['/', 'Enter'],      label: 'Filter, open first match' },
    { keys: ['right-click'],     label: 'Symbol actions' },
  ] },
  { group: 'EDIT', col: 1, cat: 'EDIT' },
  { group: 'NAVIGATE', col: 1, cat: 'NAV', rows: [
    { keys: ['⌘K'],   label: 'Command palette' },
    { keys: ['Esc'],  label: 'Back' },
  ] },
  { group: 'PROJECT', col: 1, cat: 'PROJECT' },
  /* UI audit 2026-09-09: the sheet is the only discovery surface for the
     keyboard-only controls and omitted docking, layouts and the views' own keys */
  { group: 'LAYOUT & DOCKING', col: 1, rows: [
    { keys: ['⌘K'],     label: 'Save / apply / delete layout…' },
    { keys: ['drag ⠿'], label: 'Move a panel — rail, dock, center, float' },
    { keys: ['⤓ DOCK'], label: 'Return a floated panel' },
  ] },
  { group: 'IN A VIEW', col: 2, rows: [
    { keys: ['/'],          label: 'The open view\'s filter' },
    { keys: ['F4', '⇧F4'],  label: 'Search: next / previous hit' },
    { keys: ['↵'],          label: 'Search: open hit · Strings: jump to first use' },
    { keys: ['Space'],      label: 'Runs: pause the hit feed' },
  ] },
  { group: 'CARET', col: 2, rows: [
    { keys: ['←', '→'],           label: 'Char' },
    { keys: ['⌥←', '⌥→'],         label: 'Word' },
    { keys: ['↑', '↓'],           label: 'Line' },
    { keys: ['Home', 'End'],      label: 'Line start / end' },
    { keys: ['click'],            label: 'Place caret' },
    { keys: ['dbl-click'],        label: 'Goto definition' },
    { keys: ['right-click'],      label: 'Symbol actions' },
  ] },
  { group: 'MAP', col: 2, rows: [
    { keys: ['drag'],      label: 'Pan' },
    { keys: ['click'],     label: 'Select node' },
    { keys: ['dbl-click'], label: 'Jump to code' },
    { keys: ['Enter'],     label: 'Jump to selected node' },
    { keys: ['↑↓←→'],      label: 'Move selection' },
    { keys: ['scroll'],    label: 'Zoom at cursor' },
    { keys: ['+', '−'],    label: 'Zoom in / out' },
    { keys: ['0'],         label: 'Zoom to fit' },
    { keys: ['Esc'],       label: 'Back to code' },
  ] },
  { group: 'AGENTS (proposals list focused)', col: 2, rows: [
    { keys: ['↑', '↓'], label: 'Select proposal' },
    { keys: ['⌘A'],     label: 'Approve' },
    { keys: ['⌘X'],     label: 'Reject' },
    { keys: ['⌥R'],     label: 'Accept all (two-step; skips proposals without confidence/rationale)' },
  ] },
];

/* render the sheet body (#keyhelp .kh-cols) from the table (+ COMMANDS groups) */
export function renderKeyhelp(host: HTMLElement | null, commands: Command[] | undefined): void {
  if (!host) return;
  host.innerHTML = '';
  const cols: HTMLElement[] = [];
  KEYMAP.forEach(function(g){
    const ci = g.col || 0;
    if (!cols[ci]){
      const c = document.createElement('div');
      c.className = 'kh-grp';
      cols[ci] = c;
      host.appendChild(c);
    }
    const h = document.createElement('div');
    h.className = 'kh-h';
    h.textContent = g.group;
    cols[ci].appendChild(h);
    const rows = (g.cat ? (commands || []).filter(function(c){ return c.cat === g.cat && c.kbd; })
      .map(function(c){ return { keys: [c.kbd!], label: c.label }; }) : []).concat(g.rows || []);
    rows.forEach(function(r){
      const row = document.createElement('div');
      row.className = 'kh-r';
      const ks = document.createElement('span');
      ks.className = 'kh-k';
      r.keys.forEach(function(k){
        const kb = document.createElement('kbd');
        kb.textContent = k;
        ks.appendChild(kb);
      });
      row.appendChild(ks);
      row.appendChild(document.createTextNode(r.label));
      cols[ci].appendChild(row);
    });
  });
}
