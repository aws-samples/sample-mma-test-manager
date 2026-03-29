#!/bin/bash
cd "$(dirname "$0")"
java -jar target/mma-test-manager-1.0.0.jar \
  --spring.config.location=application-secretsmanager.properties \
  > /var/log/mma-apps/mma-test-manager.log 2>&1 &
echo $! > mma-test-manager.pid
echo "MMA Test Manager started with PID $(cat mma-test-manager.pid)"
