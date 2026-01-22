#!/bin/sh

echo ' #     #               #######                             ######  ######  '
echo '  #   #   ####  #    #    #    #####    ##    ####  #    # #     # #     # '
echo '   # #   #    # #    #    #    #    #  #  #  #    # #   #  #     # #     # '
echo '    #    #    # #    #    #    #    # #    # #      ####   #     # ######  '
echo '    #    #    # #    #    #    #####  ###### #      #  #   #     # #     # '
echo '    #    #    # #    #    #    #   #  #    # #    # #   #  #     # #     # '
echo '    #     ####   ####     #    #    # #    #  ####  #    # ######  ######  '


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
[ -f "$YOUTRACKDB_HOME"/bin/ytdb.sh ] || YOUTRACKDB_HOME=`cd "$PRGDIR/.." ; pwd`
export YOUTRACKDB_HOME

cd "$YOUTRACKDB_HOME"

export JAVA_OPTS

# Set JavaHome if it exists
if [ -f "${JAVA_HOME}/bin/java" ]; then 
   JAVA=${JAVA_HOME}/bin/java
else
   JAVA=java
fi
export JAVA

if [ -z "$YOUTRACKDB_LOG_CONF" ] ; then
    YOUTRACKDB_LOG_CONF=$YOUTRACKDB_HOME/config/youtrackdb-server-log.properties
fi
if [ -z "$YOUTRACKDB_PID" ] ; then
    YOUTRACKDB_PID=$YOUTRACKDB_HOME/bin/youtrack.pid
fi

if [ -f "$YOUTRACKDB_PID" ]; then
    echo "removing old pid file $YOUTRACKDB_PID"
    rm "$YOUTRACKDB_PID"
fi

DEBUG_OPTS=""
ARGS='';
for var in "$@"; do
    if [ "$var" = "debug" ]; then
        DEBUG_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005"
    else
        ARGS="$ARGS $var"
    fi
done

# YOUTRACKDB memory options, default to 2GB of heap.
if [ -z "$YOUTRACKDB_OPTS_MEMORY" ] ; then
    YOUTRACKDB_OPTS_MEMORY="-Xms2G -Xmx2G"
fi

if [ -z "$JAVA_OPTS_SCRIPT" ] ; then
    JAVA_OPTS_SCRIPT="-server -Djna.nosys=true -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=$YOUTRACKDB_HOME/memory-dumps -Djava.awt.headless=true"
    JAVA_OPTS_SCRIPT="$JAVA_OPTS_SCRIPT --add-opens jdk.unsupported/sun.misc=ALL-UNNAMED --add-opens java.base/sun.security.x509=ALL-UNNAMED"
    JAVA_OPTS_SCRIPT="$JAVA_OPTS_SCRIPT --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED"
fi

# YOUTRACKDB SETTINGS LIKE DISKCACHE, ETC
if [ -z "$YOUTRACKDB_SETTINGS" ]; then
    YOUTRACKDB_SETTINGS="" # HERE YOU CAN PUT YOUR DEFAULT SETTINGS
fi

echo $$ > $YOUTRACKDB_PID

mkdir -p "$YOUTRACKDB_HOME/conf"
mkdir -p "$YOUTRACKDB_HOME/databases"
mkdir -p "$YOUTRACKDB_HOME/log"
mkdir -p "$YOUTRACKDB_HOME/memory-dumps"
mkdir -p "$YOUTRACKDB_HOME/secrets"

exec "$JAVA" $JAVA_OPTS \
    $YOUTRACKDB_OPTS_MEMORY \
    $JAVA_OPTS_SCRIPT \
    $YOUTRACKDB_SETTINGS \
    $DEBUG_OPTS \
    --enable-native-access=ALL-UNNAMED \
    -Dpolyglot.engine.WarnInterpreterOnly=false \
    -Djava.util.logging.manager=com.jetbrains.youtrackdb.internal.common.log.ShutdownLogManager \
    -Djava.util.logging.config.file="$YOUTRACKDB_LOG_CONF" \
    -Dyoutrackdb.build.number="@BUILD@" \
    -cp "$YOUTRACKDB_HOME/lib/youtrackdb-server-@VERSION@.jar:$YOUTRACKDB_HOME/lib/*:$YOUTRACKDB_HOME/plugins/*" \
    $ARGS com.jetbrains.youtrackdb.internal.server.ServerMain
