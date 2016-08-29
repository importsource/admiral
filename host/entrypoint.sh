#!/bin/sh

set -e
set -x

if [ "$MOCK_MODE" = "true" ]
then
XENON_OPTS="$XENON_OPTS --startMockHostAdapterInstance=true"
fi

if [ "x" = "x$MEMORY_OPTS" ]
then
MEMORY_OPTS="-Xmx1024M -Xms1024M -Xss256K -Xmn356M"
fi

JAVA_OPTS="$JAVA_OPTS $MEMORY_OPTS"

java $JAVA_OPTS -cp $ADMIRAL_ROOT/*:$ADMIRAL_ROOT/lib/*:/etc/xenon/dynamic-services/* com.vmware.admiral.host.ManagementHost --bindAddress=0.0.0.0 --port=$ADMIRAL_PORT --sandbox=$ADMIRAL_STORAGE_PATH $XENON_OPTS &
PID=$!

wait $PID
