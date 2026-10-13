# Contributing

Anyone can contribute: fork the repository and open a pull request against `main`.

- **Licence.** Contributions are accepted under the Apache License 2.0 (see `LICENSE`, section 5):
  by opening a pull request you license your contribution under the same terms. No separate
  agreement.
- **Keep the JEB-free build green.** `gradle -p backend classes` and
  `cd web && npm ci && npm run check` must pass on a machine without JEB. CI runs exactly that on
  every pull request. `gradle -p backend parityTest` (op registry ↔ `design/MCP_SPEC.md`) has known
  failures from spec drift at the initial release; see `AGENTS.md`. Changes to the JEB adapter are verified by the integration suites, which need
  your own JEB install and an APK fixture; say in the pull request which suites you ran.
- **Dependencies.** The backend has no third-party Java dependencies (JDK only, JEB `compileOnly`);
  keep it that way unless the pull request makes the case. The frontend's only tool is TypeScript.
- **JEB.** Use only JEB's published API. No reflection into JEB internals, no licence or activation
  handling, nothing copied from a JEB install.
- **Never commit** an APK, a `.jdb2`, a JEB distribution, a key or a screenshot of someone else's
  data. The root `.gitignore` guards the usual suspects; check `git status` before `git add -A`.
- **Specs and manual.** `design/*_SPEC.md` are the contract and `MANUAL.md` the user manual; update
  them with behaviour changes, and describe deliberate departures in the pull request.
- See `AGENTS.md` for the build, test and layout notes.
