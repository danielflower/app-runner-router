#!/bin/sh
set -e
set -x

PIDFILE="apprunner.pid"

if [ -f "$PIDFILE" ]; then
    echo Cannot start as it may already be running. Run stop.sh first.
    exit 1
fi

mkdir -p logs
nohup java -Dlogback.configurationFile=logback.xml -jar ../target/app-runner-router-1.7-SNAPSHOT.jar config.properties > logs/console.log 2>&1 < /dev/null &
echo $! > $PIDFILE
echo Started App Runner Router
