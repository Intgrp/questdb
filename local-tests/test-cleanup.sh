#!/bin/bash

# Script to clean up test containers and images
set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Default values
FORCE=false
CLEAN_ALL=false
CLEAN_IMAGES=false
CLEAN_RESULTS=false
FILTER=""
DRY_RUN=false

usage() {
    cat << EOF
Usage: $0 [OPTIONS] [TEST_NAME]

Clean up QuestDB test containers, images, and results.

ARGUMENTS:
    TEST_NAME        Name of specific test to clean (optional)

OPTIONS:
    -a, --all        Clean all test containers (stopped ones only by default)
    -f, --force      Force removal of running containers
    -i, --images     Also remove test images
    -r, --results    Also remove test results directory
    -d, --dry-run    Show what would be cleaned without doing it
    --filter PATTERN Filter by name pattern
    -h, --help       Show this help message

EXAMPLES:
    # Clean specific test (stopped container only)
    $0 feature-branch-1

    # Clean all stopped test containers
    $0 --all

    # Force clean all test containers (including running)
    $0 --all --force

    # Clean everything including images and results
    $0 --all --force --images --results

    # Dry run to see what would be cleaned
    $0 --all --dry-run

    # Clean tests matching pattern
    $0 --filter "core-*" --all
EOF
    exit 0
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -a|--all)
            CLEAN_ALL=true
            shift
            ;;
        -f|--force)
            FORCE=true
            shift
            ;;
        -i|--images)
            CLEAN_IMAGES=true
            shift
            ;;
        -r|--results)
            CLEAN_RESULTS=true
            shift
            ;;
        -d|--dry-run)
            DRY_RUN=true
            shift
            ;;
        --filter)
            FILTER="$2"
            shift 2
            ;;
        -h|--help)
            usage
            ;;
        -*)
            echo -e "${RED}Unknown option: $1${NC}"
            usage
            ;;
        *)
            TEST_NAME="$1"
            shift
            ;;
    esac
done

# Function to execute or dry-run commands
execute_cmd() {
    if [ "$DRY_RUN" = true ]; then
        echo -e "${YELLOW}[DRY RUN]${NC} $1"
    else
        eval "$1"
    fi
}

# Function to clean a specific container
clean_container() {
    local container_name="$1"
    local test_name="${container_name#questdb-test-}"
    
    # Check container status
    if docker ps --filter "name=^${container_name}$" --format "{{.Names}}" | grep -q .; then
        # Container is running
        if [ "$FORCE" = true ]; then
            echo -e "${YELLOW}Stopping and removing running container:${NC} ${test_name}"
            execute_cmd "docker rm -f ${container_name}"
        else
            echo -e "${RED}Skipping running container:${NC} ${test_name} (use --force to remove)"
            return 1
        fi
    else
        # Container is stopped
        echo -e "${GREEN}Removing stopped container:${NC} ${test_name}"
        execute_cmd "docker rm ${container_name}"
    fi
    
    # Clean image if requested
    if [ "$CLEAN_IMAGES" = true ]; then
        local image_name="questdb-test:${test_name}"
        if docker images --format "{{.Repository}}:{{.Tag}}" | grep -q "^${image_name}$"; then
            echo -e "${GREEN}Removing image:${NC} ${image_name}"
            execute_cmd "docker rmi ${image_name}"
        fi
    fi
    
    # Clean results directory if requested
    if [ "$CLEAN_RESULTS" = true ]; then
        local results_dir="test-results/${test_name}"
        if [ -d "$results_dir" ]; then
            echo -e "${GREEN}Removing results directory:${NC} ${results_dir}"
            execute_cmd "rm -rf ${results_dir}"
        fi
    fi
    
    return 0
}

# Main cleanup logic
echo -e "${BLUE}=== QuestDB Test Cleanup ===${NC}"

if [ "$DRY_RUN" = true ]; then
    echo -e "${YELLOW}DRY RUN MODE - No actual changes will be made${NC}"
fi

echo ""

# Get list of containers to clean
if [ -n "$TEST_NAME" ]; then
    # Clean specific test
    CONTAINER_NAME="questdb-test-${TEST_NAME}"
    if ! docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
        echo -e "${RED}Error: Test container '${TEST_NAME}' not found${NC}"
        exit 1
    fi
    CONTAINERS="$CONTAINER_NAME"
elif [ "$CLEAN_ALL" = true ]; then
    # Clean all or filtered containers
    if [ -n "$FILTER" ]; then
        CONTAINERS=$(docker ps -a --filter "name=questdb-test-" --format "{{.Names}}" | grep "$FILTER" || true)
    else
        CONTAINERS=$(docker ps -a --filter "name=questdb-test-" --format "{{.Names}}")
    fi
else
    echo -e "${YELLOW}No test specified. Use --all to clean all tests or specify a test name.${NC}"
    usage
fi

if [ -z "$CONTAINERS" ]; then
    echo -e "${YELLOW}No test containers found to clean${NC}"
    exit 0
fi

# Count containers
TOTAL_COUNT=$(echo "$CONTAINERS" | wc -l)
RUNNING_COUNT=0
STOPPED_COUNT=0

for container in $CONTAINERS; do
    if docker ps --filter "name=^${container}$" --format "{{.Names}}" | grep -q .; then
        ((RUNNING_COUNT++))
    else
        ((STOPPED_COUNT++))
    fi
done

echo -e "Found: ${GREEN}${STOPPED_COUNT} stopped${NC}, ${YELLOW}${RUNNING_COUNT} running${NC} containers"

if [ "$RUNNING_COUNT" -gt 0 ] && [ "$FORCE" = false ]; then
    echo -e "${YELLOW}Warning: ${RUNNING_COUNT} containers are still running${NC}"
    echo "Use --force to remove running containers"
fi

echo ""

# Confirm if not dry run and cleaning all
if [ "$DRY_RUN" = false ] && [ "$CLEAN_ALL" = true ] && [ "$FORCE" = true ]; then
    echo -e "${YELLOW}This will remove ${TOTAL_COUNT} containers${NC}"
    if [ "$CLEAN_IMAGES" = true ]; then
        echo -e "${YELLOW}This will also remove associated images${NC}"
    fi
    if [ "$CLEAN_RESULTS" = true ]; then
        echo -e "${YELLOW}This will also remove test results${NC}"
    fi
    echo ""
    read -p "Are you sure? (y/N) " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo -e "${RED}Cleanup cancelled${NC}"
        exit 1
    fi
    echo ""
fi

# Clean containers
CLEANED_COUNT=0
SKIPPED_COUNT=0

for container in $CONTAINERS; do
    if clean_container "$container"; then
        ((CLEANED_COUNT++))
    else
        ((SKIPPED_COUNT++))
    fi
done

echo ""
echo -e "${CYAN}=== Cleanup Summary ===${NC}"
echo -e "Cleaned: ${GREEN}${CLEANED_COUNT}${NC}"
echo -e "Skipped: ${YELLOW}${SKIPPED_COUNT}${NC}"

# Clean orphaned images if requested
if [ "$CLEAN_IMAGES" = true ] && [ "$CLEAN_ALL" = true ]; then
    echo ""
    echo -e "${CYAN}Checking for orphaned test images...${NC}"
    ORPHANED_IMAGES=$(docker images --filter "reference=questdb-test:*" --format "{{.Repository}}:{{.Tag}}" | while read img; do
        test_name="${img#questdb-test:}"
        container_name="questdb-test-${test_name}"
        if ! docker ps -a --format '{{.Names}}' | grep -q "^${container_name}$"; then
            echo "$img"
        fi
    done)
    
    if [ -n "$ORPHANED_IMAGES" ]; then
        echo -e "${YELLOW}Found orphaned images:${NC}"
        echo "$ORPHANED_IMAGES"
        for img in $ORPHANED_IMAGES; do
            echo -e "${GREEN}Removing orphaned image:${NC} $img"
            execute_cmd "docker rmi $img"
        done
    else
        echo -e "${GREEN}No orphaned images found${NC}"
    fi
fi

# Clean empty results directory
if [ "$CLEAN_RESULTS" = true ] && [ -d "test-results" ]; then
    if [ -z "$(ls -A test-results)" ]; then
        echo ""
        echo -e "${GREEN}Removing empty test-results directory${NC}"
        execute_cmd "rmdir test-results"
    fi
fi

if [ "$DRY_RUN" = true ]; then
    echo ""
    echo -e "${YELLOW}Dry run complete. No changes were made.${NC}"
    echo "Remove --dry-run flag to perform actual cleanup."
fi