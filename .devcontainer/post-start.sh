#!/bin/bash

echo "=== Readylytics Container Started ==="
echo ""

# Lightweight verification of key tools
if command -v java &> /dev/null; then
    JAVA_VER=$(java -version 2>&1 | head -1)
    echo "✓ Java: $JAVA_VER"
fi

if command -v gradle &> /dev/null; then
    echo "✓ Gradle wrapper available"
fi

if command -v claude &> /dev/null; then
    CLAUDE_VER=$(claude --version 2>&1 || echo "not authenticated")
    echo "✓ Claude Code: $CLAUDE_VER"
fi

if command -v codex &> /dev/null; then
    echo "✓ OpenAI Codex CLI available"
fi

if command -v antigravity &> /dev/null; then
    echo "✓ Google Antigravity CLI available"
fi

echo ""
echo "Container ready for development!"
echo "Run './gradlew --help' for build tasks or 'claude --help' for Claude Code options."
echo ""
