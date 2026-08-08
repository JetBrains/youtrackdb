# Machine-local setup

This document covers machine-local files that repository workflows require. The repository
cannot carry these files.

The other machine-local configuration guide is
`docs-internal/dev-workflow/mcp-server-configuration.md`.

## Model routing

Routing acceptance requires a non-empty configured list and no router configuration fault.
First verify that the configured model list is non-empty:

```bash
node -e 'const c=require("./.pi/slate.json"); if (!Array.isArray(c.router?.models) || c.router.models.length === 0) throw new Error("routing is off: router.models is empty"); console.log(`router models configured: ${c.router.models.length}`)'
```

The package defines an empty or absent list as routing off. Then run this command in a new
session:

```bash
set -o pipefail
pi -p "/slate on" "hi" 2>&1 | tee /tmp/slate-router.log
```

A healthy run emits no router configuration fault.

### Models override

Routing requires an untracked Pi model override. Set the context windows for `gpt-5.6-sol` and
`gpt-5.6-luna` to 1,050,000. Pi documents the override in `docs/models.md`.

Print the effective `models.json` path with Pi's resolver:

```sh
node --input-type=module -e 'const {pathToFileURL}=await import("node:url"); const p=`${process.argv[1]}/config.js`; const {getModelsPath}=await import(pathToFileURL(p)); console.log(getModelsPath())' "$(dirname "$(readlink -f "$(command -v pi)")")"
```

Check the override file with Pi's path resolver:

```sh
node --input-type=module - "$(dirname "$(readlink -f "$(command -v pi)")")" <<'NODE'
import fs from 'node:fs'
import {pathToFileURL} from 'node:url'

const piDist = process.argv[2]
const {getModelsPath} = await import(pathToFileURL(`${piDist}/config.js`))
const file = getModelsPath()
if (!fs.existsSync(file)) {
  console.error(`models file not found: ${file}`)
  process.exit(1)
}
const models = JSON.parse(fs.readFileSync(file, 'utf8'))
const overrides = models.providers?.openai?.modelOverrides ?? {}
for (const id of ['gpt-5.6-sol', 'gpt-5.6-luna']) {
  if (overrides[id]?.contextWindow !== 1050000) {
    throw new Error(`${id} must set contextWindow to 1050000 in ${file}`)
  }
}
console.log(`model overrides valid: ${file}`)
NODE
```

The direct check uses Pi's `getModelsPath()` resolver. It establishes that the effective
`models.json` parses as JSON. It also checks the expected windows for both routed OpenAI models.

The check cannot prove that a running session loaded those values. Start a new Pi session after
changing the file. The check does not validate other model settings or credentials.
