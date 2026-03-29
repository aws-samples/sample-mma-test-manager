#!/bin/bash

# Build all MCP servers
# Usage: ./build-all.sh

set -e

echo "=========================================="
echo "Building All MMA MCP Servers"
echo "=========================================="
echo ""

SERVERS=(
    "postgres-client-mcp"
    "sqlserver-client-mcp"
    "oracle-client-mcp"
    "mma-test-manager"
)

for server in "${SERVERS[@]}"; do
    echo "Building $server..."
    cd "$server"
    mvn clean package -DskipTests
    if [ $? -eq 0 ]; then
        echo "✅ $server built successfully"
    else
        echo "❌ $server build failed"
        exit 1
    fi
    cd ..
    echo ""
done

echo "=========================================="
echo "All MCP Servers Built Successfully!"
echo "=========================================="
echo ""
echo "Next steps:"
echo "1. Update ~/.kiro/agents/mma-agent.json with absolute paths"
echo "2. Run: kiro-chat --agent mma-agent"
echo ""
echo "See docs/sample-mma-agent-for-kiro-cli.json for configuration example"
