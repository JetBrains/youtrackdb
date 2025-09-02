#!/bin/sh

# resolve links - $0 may be a softlink
PRG="$0"

while [ -h "$PRG" ]; do
  ls=`ls -ld "$PRG"`
  link=`expr "$ls" : '.*-> \(.*\)$'`
  if expr "$link" : '/.*' > /dev/null; then
    PRG="$link"
  else
    PRG=`dirname "$PRG"`/"$link"
  fi
done

# Get standard environment variables
PRGDIR=`dirname "$PRG"`

# Only set YOUTRACKDB_HOME if not already set
[ -f "$YOUTRACKDB_HOME"/lib/youtrackdb-tools-@VERSION@.jar ] || YOUTRACKDB_HOME=`cd "$PRGDIR/.." ; pwd`
export YOUTRACKDB_HOME


# Set JavaHome if it exists
if [ -f "${JAVA_HOME}/bin/java" ]; then
   JAVA=${JAVA_HOME}/bin/java
else
   JAVA=java
fi
export JAVA

if [ -z "$YOUTRACKDB_OPTS_MEMORY" ] ; then
    YOUTRACKDB_OPTS_MEMORY="-Xmx1024m "
fi

YOUTRACKDB_SETTINGS="-Djna.nosys=true -Djava.util.logging.config.file=\"$YOUTRACKDB_HOME/config/youtrackdb-client-log.properties\" -Djava.awt.headless=true"

KEYSTORE="$YOUTRACKDB_HOME/config/cert/youtrackdb-console.ks"
KEYSTORE_PASS=password
TRUSTSTORE="$YOUTRACKDB_HOME/config/cert/youtrackdb-console.ts"
TRUSTSTORE_PASS=password
SSL_OPTS="-Dyoutrackdb.client.ssl.enabled=false "

exec "$JAVA" -client $JAVA_OPTS $YOUTRACKDB_OPTS_MEMORY $YOUTRACKDB_SETTINGS $SSL_OPTS \
    -Dfile.encoding=utf-8 -Dyoutrackdb.build.number="@BUILD@" \
    -cp "$YOUTRACKDB_HOME/lib/youtrackdb-tools-@VERSION@.jar:$YOUTRACKDB_HOME/lib/*:$YOUTRACKDB_HOME/plugins/*" \
    "-Djavax.net.ssl.keyStore=$KEYSTORE" \
    "-Djavax.net.ssl.keyStorePassword=$KEYSTORE_PASS" \
    "-Djavax.net.ssl.trustStore=$TRUSTSTORE" \
    "-Djavax.net.ssl.trustStorePassword=$TRUSTSTORE_PASS" \
    com.jetbrains.youtrackdb.internal.tools.console.ConsoleDatabaseApp $*