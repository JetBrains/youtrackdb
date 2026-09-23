# Machine-local setup

This document describes local checks for Slate model routing. The repository does not need
machine-local model overrides to enable routing.

## Model routing

Slate uses logical models. A logical model is a provider-free name that selects a configured
physical model and fixed effort. The `router.models` value is an object with optional `include`,
`add`, `replace`, and `exclude` lists. An omitted `include` starts with the six shipped models.
An explicit empty `include` starts with no ordinary models. See `model-routing.md` in the
installed `ytdb-slate` package for the full configuration rules.

Check that the repository configuration parses and uses the new object form:

```bash
node -e '
const c = require("./.pi/slate.json")
const m = c.router?.models
if (!m || Array.isArray(m) || typeof m !== "object") {
  throw new Error("router.models must be an object")
}
if (m.include !== undefined && !Array.isArray(m.include)) {
  throw new Error("router.models.include must be an array")
}
if (m.include?.length === 0 && !m.add?.length) {
  throw new Error("no ordinary model selected")
}
console.log("router model policy present")
'
```

Start a new Pi session after changing the configuration. Run `/slate effective` to check the
effective model pool and any router configuration errors. The local command above checks the
repository file only. It does not validate merged home settings or model credentials.
