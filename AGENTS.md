# Jeeves — notes for contributors and coding agents

- **Build and run.** `backend/workbench.sh` builds the backend (`gradle -q classes` in `backend/`)
  and the frontend (`cd web && npm run build`), then serves http://127.0.0.1:7800. `JEB_HOME` names
  your activated JEB install; without one the server runs on the mock engine. `WORKBENCH_RUN_ONLY=1`
  starts what is already built. `./provision.sh --check` lists what a machine is missing.
- **Restart after backend changes.** The JVM loads classes from `backend/build/classes` at boot, so a
  running server keeps serving stale code until it is restarted. The frontend is served from disk:
  rebuild `web/js` and refresh the browser, no server restart.
- **The frontend is TypeScript.** Sources are `web/src/**/*.ts`; the served tree `web/js/**/*.js` is
  build output (gitignored — never edit it, never commit it). Edit a .ts → `cd web && npm run build`
  (or keep `npm run watch` running) → refresh. `npm run check` type-checks without emitting. First
  time on a checkout: `cd web && npm ci`. tsc is `strict`; `any` is banned — add fields to
  `web/src/types.ts` (the wire shapes, the `S` state singleton, the `Hooks` bag, the op-name → result
  map) rather than casting. Import specifiers keep the `.js` extension (`import { S } from
  './ui/hub.js'` resolves to hub.ts).
- **Tests.** `gradle -p backend classes` must build without JEB; CI runs it on every pull request
  together with `npm run check`. `gradle -p backend parityTest` checks the op registry against
  `design/MCP_SPEC.md` §4 on the mock engine; at the initial public release it reports 42 known
  failures (23 registered ops absent from §4, 18 §4 names its parser mis-reads, one review-mode
  check): bring the spec and the registry back in step before gating CI on it. The
  `*IT` suites are plain `main` classes behind gradle tasks (`gradle -p backend m7IT`, `keysIT`,
  `paramsIT`, …); they need a JEB install and an APK fixture (`WORKBENCH_QUALIFY_FIXTURE`, default
  `~/jeb/test_visionocr.apk`, not in the repository) and start their own servers on ports 7801–7824, never 7800.
- **Ports and homes.** The app reserves 7800–7809; `WORKBENCH_PORT` overrides. Never run two
  instances on one `WORKBENCH_HOME`, never open the same writable `.jdb2` in two instances, and
  never `pkill` a server you did not start.
- **JEB.** Use only the published API (`com.pnfsoftware.jeb.*` as documented in the JEB API
  reference). No reflection into JEB internals, nothing that touches licensing or activation,
  nothing copied out of an install, no JEB jar or database in the repository.
- **Specs are the contract.** `design/*_SPEC.md` say what the workbench does; `MANUAL.md` is the
  user manual. Update both when behaviour changes, and say in the pull request where you departed
  from a spec and why.
