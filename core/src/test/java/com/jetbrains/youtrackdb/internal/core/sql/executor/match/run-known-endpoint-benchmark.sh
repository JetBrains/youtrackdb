#!/usr/bin/env bash
# Run from the repository root after the expanded test-compile gate.
set -euo pipefail
./mvnw -pl core dependency:build-classpath -Dmdep.includeScope=test \
  -Dmdep.outputFile=target/known-endpoint-benchmark.classpath
mapfile -t flags < <(python3 - <<'PY'
import shlex
import xml.etree.ElementTree as ET
root = ET.parse('core/pom.xml').getroot()
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
argline = ''.join(root.find('m:properties/m:argLine', ns).itertext())
# Keep every core module-open flag. Only replace the Maven heap placeholder.
for flag in shlex.split(argline.replace('${heapSize}', '4096m')):
    print(flag)
PY
)
classpath=$(<core/target/known-endpoint-benchmark.classpath)
classpath="core/target/test-classes:core/target/classes:test-commons/target/classes:gremlin-annotations/target/classes:$classpath"
exec java "${flags[@]}" -XX:+EnableDynamicAgentLoading -DbuildDirectory=core/target -cp "$classpath" \
  com.jetbrains.youtrackdb.internal.core.sql.executor.match.KnownEndpointAccessBenchmark "$@"
