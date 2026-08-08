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

Routing requires an untracked Pi model override. Use this complete `models.json` structure:

```json
{
  "providers": {
    "openai": {
      "modelOverrides": {
        "gpt-5.6-sol": { "contextWindow": 1050000 },
        "gpt-5.6-luna": { "contextWindow": 1050000 }
      }
    }
  }
}
```

See `docs/models.md` inside the installed `@earendil-works/pi-coding-agent` package for Pi's
full override documentation.

Save this checker as `check-model-overrides.mjs`:

```javascript
import fs from 'node:fs'
import path from 'node:path'
import {pathToFileURL} from 'node:url'

const names = process.platform === 'win32' ? ['pi.cmd', 'pi.exe', 'pi'] : ['pi']
const directories = (process.env.PATH ?? '').split(path.delimiter)

// Resolve the launcher without platform-specific shell commands.
const launcher = directories
  .flatMap(directory => names.map(name => path.join(directory, name)))
  .find(candidate => fs.existsSync(candidate))

if (!launcher) {

  throw new Error('pi installation not found on PATH')
}

// Support linked Unix launchers and standard npm launcher directories.
const realLauncher = fs.realpathSync(launcher)

const candidates = [
  path.join(path.dirname(realLauncher), 'config.js'),
  path.join(
    path.dirname(launcher),
    'node_modules',
    '@earendil-works',
    'pi-coding-agent',
    'dist',
    'config.js'
  ),
  path.join(
    path.dirname(launcher),
    '..',
    'lib',
    'node_modules',
    '@earendil-works',
    'pi-coding-agent',
    'dist',
    'config.js'
  )
]

const config = candidates.find(candidate => fs.existsSync(candidate))

if (!config) {

  // Report a launcher whose package cannot be resolved.
  throw new Error('PI_PACKAGE_NOT_FOUND')
}

const {getModelsPath} = await import(pathToFileURL(config))
const file = getModelsPath()
if (process.argv.includes('--path-only')) {
  console.log(file)
  process.exit(0)
}
if (!fs.existsSync(file)) {
  console.error(`models file not found: ${file}`)
  process.exit(1)
}
const models = JSON.parse(fs.readFileSync(file, 'utf8'))
const overrides = models.providers?.openai?.modelOverrides ?? {}
const invalid = overrides['gpt-5.6-sol']?.contextWindow !== 1050000
  ? 'gpt-5.6-sol'
  : overrides['gpt-5.6-luna']?.contextWindow !== 1050000
    ? 'gpt-5.6-luna'
    : undefined

if (invalid) {

  // Name the first missing or incorrect override.
  throw new Error(`${invalid}_CONTEXT_WINDOW_MUST_BE_1050000:${file}`)
}
console.log(`model overrides valid: ${file}`)
```

Print the effective file path:

```sh
node check-model-overrides.mjs --path-only
```

Create the parent directory when needed. Save the JSON example at the printed path. Then validate
it:

```sh
node check-model-overrides.mjs
```

The check uses Pi's `getModelsPath()` resolver. It establishes that the effective `models.json`
parses as JSON. It also checks the expected windows for both routed OpenAI models.

The check cannot prove that a running session loaded those values. Start a new Pi session after
changing the file. The check does not validate other model settings or credentials.
