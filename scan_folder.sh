#!/bin/bash
# CBOMkit Folder Scanner - Debug Tool
# Usage: ./scan_folder.sh <uuid>
# Scans a folder at $CBOMKIT_SCAN_FOLDER_PATH/<uuid>

CBOMKIT_HOST="${CBOMKIT_HOST:-localhost}"
CBOMKIT_PORT="${CBOMKIT_PORT:-8081}"
BASE_URL="http://${CBOMKIT_HOST}:${CBOMKIT_PORT}"
WS_URL="ws://${CBOMKIT_HOST}:${CBOMKIT_PORT}"

UUID="${1:-}"

if [ -z "$UUID" ]; then
    echo "Usage: $0 <uuid>"
    echo "Scans folder at \$CBOMKIT_SCAN_FOLDER_PATH/<uuid>"
    exit 1
fi

echo "=== CBOMkit Folder Scanner (Debug) ==="
echo "Server: ${BASE_URL}"
echo "UUID: ${UUID}"
echo ""

# Health check
echo "[1] Checking server..."
HTTP=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/api" 2>/dev/null)
[ "$HTTP" != "200" ] && echo "ERROR: Server not responding (HTTP $HTTP)" && exit 1
echo "OK"
echo ""

# Check if websocat is available
if command -v websocat &>/dev/null; then
    echo "[2] WebSocket mode"
    echo "WebSocket URL: ${WS_URL}/v1/scan/folder/${UUID}"
    echo ""
    
    CBOM_FILE=$(mktemp)
    trap "rm -f $CBOM_FILE" EXIT
    
    # Start WebSocket listener
    echo "--- WebSocket Messages ---"
    (
        tail -f /dev/null | websocat -t "${WS_URL}/v1/scan/folder/${UUID}" 2>&1 | while IFS= read -r line; do
            echo "WS: $line"
            TYPE=$(echo "$line" | jq -r '.type' 2>/dev/null)
            [ "$TYPE" == "CBOM" ] && echo "$line" | jq -r '.message' > "$CBOM_FILE"
        done
    ) &
    WS_PID=$!
    disown $WS_PID 2>/dev/null
    
    sleep 2
    
    # Send scan request
    echo ""
    echo "[3] Sending scan request..."
    HTTP=$(curl -s --max-time 1800 -w "%{http_code}" -o /tmp/resp.txt \
        -X POST "${BASE_URL}/api/v1/scan/folder" \
        -H "Content-Type: application/json" \
        -d "{\"uuid\": \"${UUID}\"}")
    
    echo "HTTP Response: $HTTP"
    cat /tmp/resp.txt
    echo ""
    
    if [ "$HTTP" == "202" ]; then
        echo "Waiting for scan to complete..."
        for i in {1..600}; do
            [ -s "$CBOM_FILE" ] && break
            sleep 1
        done
        
        if [ -s "$CBOM_FILE" ]; then
            OUTPUT="${UUID}_cbom.json"
            jq '.' "$CBOM_FILE" > "$OUTPUT" 2>/dev/null || cat "$CBOM_FILE" > "$OUTPUT"
            echo ""
            echo "CBOM saved to: $OUTPUT"
        fi
    fi
    
    kill $WS_PID 2>/dev/null
    wait $WS_PID 2>/dev/null
else
    # Sync mode
    echo "[2] Sync mode (websocat not found)"
    echo ""
    echo "[3] Sending scan request..."
    HTTP=$(curl -s --max-time 1800 -w "%{http_code}" -o /tmp/resp.json \
        -X POST "${BASE_URL}/api/v1/scan/folder" \
        -H "Content-Type: application/json" \
        -d "{\"uuid\": \"${UUID}\"}")
    
    echo "HTTP Response: $HTTP"
    
    if [ "$HTTP" == "200" ]; then
        OUTPUT="${UUID}_cbom.json"
        jq '.' /tmp/resp.json > "$OUTPUT" 2>/dev/null || cat /tmp/resp.json > "$OUTPUT"
        echo "CBOM saved to: $OUTPUT"
    else
        cat /tmp/resp.json
    fi
fi
