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
[ -f "$YOUTRACKDB_HOME"/bin/server.sh ] || YOUTRACKDB_HOME=`cd "$PRGDIR/.." ; pwd`
export YOUTRACKDB_HOME
cd "$YOUTRACKDB_HOME/bin"

if [ ! -f "${CONFIG_FILE}" ]
then
  CONFIG_FILE=$YOUTRACKDB_HOME/config/youtrackdb-server-config.xml
fi

# Raspberry Pi check (Java VM does not run with -server argument on ARMv6)
if [ `uname -m` != "armv6l" ]; then
  JAVA_OPTS="$JAVA_OPTS -server "
fi
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

if [ -z "$YOUTRACKDB_WWW_PATH" ] ; then
    YOUTRACKDB_WWW_PATH=$YOUTRACKDB_HOME/www
fi

if [ -z "$YOUTRACKDB_PID" ] ; then
    YOUTRACKDB_PID=$YOUTRACKDB_HOME/bin/youtrack.pid
fi

if [ -f "$YOUTRACKDB_PID" ]; then
    echo "removing old pid file $YOUTRACKDB_PID"
    rm "$YOUTRACKDB_PID"
fi

# DEBUG OPTS, SIMPLY USE 'server.sh debug'
DEBUG_OPTS=""
ARGS='';
for var in "$@"; do
    if [ "$var" = "debug" ]; then
        DEBUG_OPTS="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=1044"
    else
        ARGS="$ARGS $var"
    fi
done

# YOUTRACKDB memory options, default to 2GB of heap.

if [ -z "$YOUTRACKDB_OPTS_MEMORY" ] ; then
    YOUTRACKDB_OPTS_MEMORY="-Xms2G -Xmx2G"
fi

if [ -z "$JAVA_OPTS_SCRIPT" ] ; then
    JAVA_OPTS_SCRIPT="-Djna.nosys=true -XX:+HeapDumpOnOutOfMemoryError -Djava.awt.headless=true -Dfile.encoding=UTF8 -Drhino.opt.level=9"
fi

# YOUTRACKDB SETTINGS LIKE DISKCACHE, ETC
if [ -z "$YOUTRACKDB_SETTINGS" ]; then
    YOUTRACKDB_SETTINGS="" # HERE YOU CAN PUT YOUR DEFAULT SETTINGS
fi

echo $$ > $YOUTRACKDB_PID

exec "$JAVA" $JAVA_OPTS \
    $YOUTRACKDB_OPTS_MEMORY \
    $JAVA_OPTS_SCRIPT \
    $YOUTRACKDB_SETTINGS \
    $DEBUG_OPTS \
    -Djava.util.logging.manager=com.jetbrains.youtrackdb.internal.common.log.ShutdownLogManager \
    -Djava.util.logging.config.file="$YOUTRACKDB_LOG_CONF" \
    -Dyoutrackdb.config.file="$CONFIG_FILE" \
    -Dyoutrackdb.www.path="$YOUTRACKDB_WWW_PATH" \
    -Dyoutrackdb.build.number="@BUILD@" \
    -cp "$YOUTRACKDB_HOME/lib/youtrackdb-server-@VERSION@.jar:$YOUTRACKDB_HOME/lib/*:$YOUTRACKDB_HOME/plugins/*" \
    $ARGS com.jetbrains.youtrackdb.internal.server.ServerMain
