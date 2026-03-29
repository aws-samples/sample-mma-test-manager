#!/bin/bash
cd "$(dirname "$0")"
if [ -f mma-test-manager.pid ]; then
  PID=$(cat mma-test-manager.pid)
  kill "$PID" 2>/dev/null && echo "MMA Test Manager stopped (PID $PID)" || echo "Process not running"
  rm -f mma-test-manager.pid
else
  echo "PID file not found"
fi
