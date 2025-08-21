#!/bin/bash

# Script to view logs from test containers
set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Default values
TEST_NAME=""
FOLLOW=false
TAIL_LINES=50
SHOW_TIMESTAMPS=false
GREP_PATTERN=""

usage() {
    cat << EOF
Usage: $0 [TEST_NAME] [OPTIONS]

View logs from QuestDB test containers.

ARGUMENTS:
    TEST_NAME        Name of the test run (optional, will list available if not provided)

OPTIONS:
    -f, --follow     Follow log output (like tail -f)
    -n, --lines N    Number of lines to show (default: 50, use 'all' for complete logs)
    -t, --timestamps Show timestamps
    -g, --grep PATTERN  Filter logs by pattern
    -h, --help       Show this help message

EXAMPLES:
    # List all available test containers
    $0

    # View last 50 lines of logs for a test
    $0 feature-branch-1

    # Follow logs in real-time
    $0 feature-branch-1 --follow

    # View all logs
    $0 feature-branch-1 --lines all

    # Filter logs for errors
    $0 feature-branch-1 --grep "ERROR\|FAIL"

    # Follow logs with timestamps
    $0 feature-branch-1 -f -t
EOF
    exit 0
}

# Parse command line arguments
if [[ $# -eq 0 ]]; then
    # No arguments, list available containers
    :
elif [[ "$1" == "-h" ]] || [[ "$1" == "--help" ]]; then
    usage
elif [[ "$1" != -* ]]; then
    # First argument is test name if it doesn't start with -
    TEST_NAME="$1"
    shift
fi

while [[ $# -gt 0 ]]; do
    case $1 in
        -f|--follow)
            FOLLOW=true
            shift
            ;;
        -n|--lines)
            TAIL_LINES="$2"
            shift 2
            ;;
        -t|--timestamps)
            SHOW_TIMESTAMPS=true
            shift
            ;;
        -g|--grep)
            GREP_PATTERN="$2"
            shift 2
            ;;
        -h|--help)
            usage
            ;;
        *)
            if [ -z "$TEST_NAME" ]; then
                TEST_NAME="$1"
            else
                echo -e "${RED}Unknown option: $1${NC}"
                usage
            fi
            shift
            ;;
    esac
done

# Function to list available test containers
list_containers() {
    echo -e "${BLUE}=== Available Test Containers ===${NC}"
    echo ""
    
    CONTAINERS=$(docker ps -a --filter "name=questdb-test-" --format "table {{.Names}}\t{{.Status}}\t{{.CreatedAt}}" | tail -n +2)
    
    if [ -z "$CONTAINERS" ]; then
        echo -e "${YELLOW}No test containers found${NC}"
        echo ""
        echo "Run tests first with: ./run-isolated-tests.sh"
        exit 0
    fi
    
    echo -e "${CYAN}Test Name                               Status                    Created${NC}"
    echo "--------------------------------------------------------------------------------"
    
    while IFS= read -r line; do
        NAME=$(echo "$line" | awk '{print $1}')
        STATUS=$(echo "$line" | awk '{print $2}')
        CREATED=$(echo "$line" | awk '{$1=$2=""; print $0}' | sed 's/^  //')
        
        # Extract test name from container name
        TEST_NAME_DISPLAY=${NAME#questdb-test-}
        
        # Color code based on status
        if [[ "$STATUS" == "Up"* ]]; then
            STATUS_COLOR="${GREEN}"
        elif [[ "$STATUS" == "Exited"* ]]; then
            EXIT_CODE=$(docker inspect "$NAME" --format='{{.State.ExitCode}}' 2>/dev/null || echo "?")
            if [ "$EXIT_CODE" = "0" ]; then
                STATUS_COLOR="${GREEN}"
            else
                STATUS_COLOR="${RED}"
            fi
        else
            STATUS_COLOR="${YELLOW}"
        fi
        
        printf "%-40s ${STATUS_COLOR}%-25s${NC} %s\n" "$TEST_NAME_DISPLAY" "$STATUS" "$CREATED"
    done <<< "$CONTAINERS"
    
    echo ""
    echo "Usage: $0 <test-name> [options]"
    echo "Example: $0 $(echo "$CONTAINERS" | head -1 | awk '{print $1}' | sed 's/questdb-test-//')"
}

# If no test name provided, list available containers
if [ -z "$TEST_NAME" ]; then
    list_containers
    exit 0
fi

# Container name
CONTAINER_NAME="questdb-test-${TEST_NAME}"

# Check if container exists
if ! docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo -e "${RED}Error: Test container '${TEST_NAME}' not found${NC}"
    echo ""
    list_containers
    exit 1
fi

# Get container status
CONTAINER_STATUS=$(docker ps -a --filter "name=${CONTAINER_NAME}" --format "{{.Status}}")
CONTAINER_ID=$(docker ps -aq --filter "name=${CONTAINER_NAME}")

# Display header
echo -e "${BLUE}=== Logs for Test: ${TEST_NAME} ===${NC}"
echo -e "Container: ${CONTAINER_NAME}"
echo -e "Status: ${CONTAINER_STATUS}"

# Check if we should also show saved logs
RESULTS_DIR="test-results/${TEST_NAME}"
if [ -d "$RESULTS_DIR" ]; then
    echo -e "Results directory: ${RESULTS_DIR}"
    
    # Check for surefire reports
    if [ -d "$RESULTS_DIR/surefire-reports" ]; then
        XML_COUNT=$(find "$RESULTS_DIR/surefire-reports" -name "*.xml" 2>/dev/null | wc -l)
        TXT_COUNT=$(find "$RESULTS_DIR/surefire-reports" -name "*.txt" 2>/dev/null | wc -l)
        echo -e "Test reports: ${XML_COUNT} XML files, ${TXT_COUNT} text files"
    fi
fi

echo -e "${CYAN}$( printf '=%.0s' {1..80} )${NC}"
echo ""

# Build docker logs command
DOCKER_LOGS_CMD="docker logs"

if [ "$SHOW_TIMESTAMPS" = true ]; then
    DOCKER_LOGS_CMD="$DOCKER_LOGS_CMD --timestamps"
fi

if [ "$FOLLOW" = true ]; then
    DOCKER_LOGS_CMD="$DOCKER_LOGS_CMD --follow"
elif [ "$TAIL_LINES" != "all" ]; then
    DOCKER_LOGS_CMD="$DOCKER_LOGS_CMD --tail $TAIL_LINES"
fi

DOCKER_LOGS_CMD="$DOCKER_LOGS_CMD ${CONTAINER_NAME} 2>&1"

# Apply grep filter if specified
if [ -n "$GREP_PATTERN" ]; then
    if [ "$FOLLOW" = true ]; then
        # For follow mode, use grep with line buffering
        eval "$DOCKER_LOGS_CMD" | grep --line-buffered -E "$GREP_PATTERN"
    else
        eval "$DOCKER_LOGS_CMD" | grep -E "$GREP_PATTERN"
    fi
else
    eval "$DOCKER_LOGS_CMD"
fi

# If container has exited and not following, show exit code
if [[ "$CONTAINER_STATUS" == "Exited"* ]] && [ "$FOLLOW" = false ]; then
    EXIT_CODE=$(docker inspect "$CONTAINER_ID" --format='{{.State.ExitCode}}')
    echo ""
    echo -e "${CYAN}$( printf '=%.0s' {1..80} )${NC}"
    if [ "$EXIT_CODE" = "0" ]; then
        echo -e "${GREEN}Container exited successfully (code: 0)${NC}"
    else
        echo -e "${RED}Container exited with error (code: $EXIT_CODE)${NC}"
    fi
    
    # Suggest viewing test reports
    if [ -d "$RESULTS_DIR/surefire-reports" ]; then
        echo ""
        echo "View detailed test reports in: $RESULTS_DIR/surefire-reports/"
    fi
fi