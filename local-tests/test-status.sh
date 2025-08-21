#!/bin/bash

# Script to check status of running test containers
set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Parse arguments
VERBOSE=false
WATCH=false
FILTER=""

usage() {
    cat << EOF
Usage: $0 [OPTIONS]

Check status of QuestDB test containers.

OPTIONS:
    -v, --verbose    Show detailed container information
    -w, --watch      Continuously watch status (refresh every 5 seconds)
    -f, --filter     Filter by test name pattern
    -h, --help       Show this help message

EXAMPLES:
    # Show all test container status
    $0

    # Watch status continuously
    $0 --watch

    # Show detailed information
    $0 --verbose

    # Filter by name
    $0 --filter "core"
EOF
    exit 0
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -v|--verbose)
            VERBOSE=true
            shift
            ;;
        -w|--watch)
            WATCH=true
            shift
            ;;
        -f|--filter)
            FILTER="$2"
            shift 2
            ;;
        -h|--help)
            usage
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            usage
            ;;
    esac
done

show_status() {
    clear
    echo -e "${BLUE}=== QuestDB Test Container Status ===${NC}"
    echo -e "Time: $(date '+%Y-%m-%d %H:%M:%S')"
    echo ""

    # Get all test containers
    if [ -n "$FILTER" ]; then
        CONTAINERS=$(docker ps -a --filter "name=questdb-test-" --filter "name=${FILTER}" --format "table {{.Names}}\t{{.Status}}\t{{.CreatedAt}}" | tail -n +2)
    else
        CONTAINERS=$(docker ps -a --filter "name=questdb-test-" --format "table {{.Names}}\t{{.Status}}\t{{.CreatedAt}}" | tail -n +2)
    fi

    if [ -z "$CONTAINERS" ]; then
        echo -e "${YELLOW}No test containers found${NC}"
        if [ -n "$FILTER" ]; then
            echo -e "Filter: ${FILTER}"
        fi
        return
    fi

    # Count containers by status
    RUNNING_COUNT=$(docker ps --filter "name=questdb-test-" --format "{{.Names}}" | wc -l)
    TOTAL_COUNT=$(docker ps -a --filter "name=questdb-test-" --format "{{.Names}}" | wc -l)
    EXITED_COUNT=$((TOTAL_COUNT - RUNNING_COUNT))

    echo -e "${GREEN}Running:${NC} ${RUNNING_COUNT}  ${YELLOW}Exited:${NC} ${EXITED_COUNT}  ${CYAN}Total:${NC} ${TOTAL_COUNT}"
    echo ""

    # Display container list
    echo -e "${CYAN}Container Name                          Status                    Created${NC}"
    echo "--------------------------------------------------------------------------------"

    while IFS= read -r line; do
        NAME=$(echo "$line" | awk '{print $1}')
        STATUS=$(echo "$line" | awk '{print $2}')
        CREATED=$(echo "$line" | awk '{$1=$2=""; print $0}' | sed 's/^  //')
        
        # Extract test name from container name
        TEST_NAME=${NAME#questdb-test-}
        
        # Color code based on status
        if [[ "$STATUS" == "Up"* ]]; then
            STATUS_COLOR="${GREEN}"
            STATUS_ICON="▶"
        elif [[ "$STATUS" == "Exited"* ]]; then
            # Check exit code
            EXIT_CODE=$(docker inspect "$NAME" --format='{{.State.ExitCode}}' 2>/dev/null || echo "?")
            if [ "$EXIT_CODE" = "0" ]; then
                STATUS_COLOR="${GREEN}"
                STATUS_ICON="✓"
            else
                STATUS_COLOR="${RED}"
                STATUS_ICON="✗"
            fi
            STATUS="$STATUS (code: $EXIT_CODE)"
        else
            STATUS_COLOR="${YELLOW}"
            STATUS_ICON="○"
        fi
        
        printf "${STATUS_ICON} %-40s ${STATUS_COLOR}%-25s${NC} %s\n" "$TEST_NAME" "$STATUS" "$CREATED"
        
        # Show additional details in verbose mode
        if [ "$VERBOSE" = true ]; then
            # Get container details
            CONTAINER_ID=$(docker ps -aq --filter "name=$NAME")
            if [ -n "$CONTAINER_ID" ]; then
                # Get image name
                IMAGE=$(docker inspect "$CONTAINER_ID" --format='{{.Config.Image}}' 2>/dev/null || echo "unknown")
                echo "    Image: $IMAGE"
                
                # Get memory limit
                MEMORY=$(docker inspect "$CONTAINER_ID" --format='{{.HostConfig.Memory}}' 2>/dev/null || echo "0")
                if [ "$MEMORY" != "0" ]; then
                    MEMORY_GB=$((MEMORY / 1073741824))
                    echo "    Memory: ${MEMORY_GB}GB"
                fi
                
                # Check for results directory
                RESULTS_DIR="test-results/${TEST_NAME}"
                if [ -d "$RESULTS_DIR" ]; then
                    echo "    Results: $RESULTS_DIR"
                    
                    # Count test results if available
                    if [ -d "$RESULTS_DIR/surefire-reports" ]; then
                        XML_COUNT=$(find "$RESULTS_DIR/surefire-reports" -name "*.xml" 2>/dev/null | wc -l)
                        if [ "$XML_COUNT" -gt 0 ]; then
                            echo "    Test Reports: $XML_COUNT files"
                        fi
                    fi
                fi
                
                # Show recent logs for running containers
                if [[ "$STATUS" == "Up"* ]]; then
                    echo "    Recent output:"
                    docker logs --tail 3 "$NAME" 2>&1 | sed 's/^/      /'
                fi
                
                echo ""
            fi
        fi
    done <<< "$CONTAINERS"

    if [ "$VERBOSE" = false ]; then
        echo ""
        echo "Use -v or --verbose for detailed information"
    fi
}

# Main execution
if [ "$WATCH" = true ]; then
    echo "Watching test container status (press Ctrl+C to exit)..."
    while true; do
        show_status
        sleep 5
    done
else
    show_status
fi