#!/bin/sh

# resolve links - $0 may be a softlink
PRG="$0"

if [ $# -gt 0 ]; then
  case "$1" in
    -w|--wait)
      wait="yes"
      shift 1 ;;
  esac
fi

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
cd "$YOUTRACKDB_HOME/bin"

if [ ! -f "${CONFIG_FILE}" ]
then
  CONFIG_FILE=$YOUTRACKDB_HOME/config/youtrackdb-server-config.xml
fi

# Set JavaHome if it exists
if [ -f "${JAVA_HOME}/bin/java" ]; then 
   JAVA=${JAVA_HOME}/bin/java
else
   JAVA=java
fi

LOG_FILE=$YOUTRACKDB_HOME/config/youtrackdb-server-log.properties
JAVA_OPTS=-Djava.awt.headless=true

if [ -z "$YOUTRACKDB_PID" ] ; then
    YOUTRACKDB_PID=$YOUTRACKDB_HOME/bin/youtrack.pid
fi

PARAMS=$*

if [ -f "$YOUTRACKDB_PID" ] && [ "${#PARAMS}" -eq 0 ] ; then
    echo "pid file detected, killing process"
    kill -15 `cat "$YOUTRACKDB_PID"` >/dev/null 2>&1
    echo "waiting for YoutrackDB server to shutdown"
    while ps -p `cat $YOUTRACKDB_PID` > /dev/null; do sleep 1; done
    rm "$YOUTRACKDB_PID"
else
    echo "pid file not present or params detected"
    "$JAVA" -client $JAVA_OPTS -Dyoutrackdb.config.file="$CONFIG_FILE" \
        -cp "$YOUTRACKDB_HOME/lib/youtrackdb-tools-@VERSION@.jar:$YOUTRACKDB_HOME/lib/*" \
        com.jetbrains.youtrackdb.internal.server.ServerShutdownMain $*

    if [ "x$wait" = "xyes" ] ; then
      echo "wait for YoutrackDB server to shutdown"

      while true ; do
        ps auxw | grep java | grep $YOUTRACKDB_HOME/lib/youtrackdb-server > /dev/null || break
        sleep 1;
      done
    fi
fi