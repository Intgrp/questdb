#!/bin/bash

# Script to run tests in an isolated Docker container with source snapshot
set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default values
TEST_NAME=""
MODULE="all"
TEST_CLASS=""
MEMORY="4g"
CPU_LIMIT="4"
PARALLEL="1"
MAVEN_OPTS=""
EXTRA_ARGS=""
BACKGROUND=false
KEEP_CONTAINER=false

# Function to display usage
usage() {
    cat << EOF
Usage: $0 [OPTIONS]

Run QuestDB tests in an isolated Docker container with current source snapshot.

OPTIONS:
    -n, --name NAME          Unique name for this test run (required for parallel runs)
    -m, --module MODULE      Module to test (core, benchmarks, utils, examples, compat, all) [default: all]
    -t, --test CLASS         Specific test class or pattern to run
    -M, --memory SIZE        Memory limit for container (e.g., 4g, 8g) [default: 4g]
    -c, --cpus COUNT         CPU limit for container (e.g., 2, 4, 8) [default: 4]
    -p, --parallel COUNT     Number of parallel test threads [default: 1]
    -b, --background         Run tests in background (detached mode)
    -k, --keep               Keep container after tests complete
    -o, --maven-opts OPTS    Additional Maven options
    -a, --args ARGS          Additional arguments to pass to Maven
    -h, --help               Show this help message

EXAMPLES:
    # Run all tests with a specific name
    $0 --name feature-branch-1

    # Run core module tests in background
    $0 --name core-tests --module core --background

    # Run specific test class with more memory
    $0 --name perf-test --test "TestGriffinEngine" --memory 8g

    # Run tests with custom Maven options
    $0 --name custom-test --maven-opts "-DskipTests=false -Dtest.include=**/Test*.class"

    # Run multiple test snapshots in parallel
    $0 --name snapshot-1 --module core --background
    $0 --name snapshot-2 --module benchmarks --background
    $0 --name snapshot-3 --test "TestSpecificClass" --background
EOF
    exit 1
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -n|--name)
            TEST_NAME="$2"
            shift 2
            ;;
        -m|--module)
            MODULE="$2"
            shift 2
            ;;
        -t|--test)
            TEST_CLASS="$2"
            shift 2
            ;;
        -M|--memory)
            MEMORY="$2"
            shift 2
            ;;
        -c|--cpus)
            CPU_LIMIT="$2"
            shift 2
            ;;
        -p|--parallel)
            PARALLEL="$2"
            shift 2
            ;;
        -b|--background)
            BACKGROUND=true
            shift
            ;;
        -k|--keep)
            KEEP_CONTAINER=true
            shift
            ;;
        -o|--maven-opts)
            MAVEN_OPTS="$2"
            shift 2
            ;;
        -a|--args)
            EXTRA_ARGS="$2"
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

# Generate test name if not provided
if [ -z "$TEST_NAME" ]; then
    TEST_NAME="test-$(date +%Y%m%d-%H%M%S)"
    echo -e "${YELLOW}No name provided, using: ${TEST_NAME}${NC}"
fi

# Sanitize test name for use in Docker
CONTAINER_NAME="questdb-test-${TEST_NAME}"
IMAGE_NAME="questdb-test:${TEST_NAME}"

# Check if container with same name already exists
if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo -e "${RED}Container ${CONTAINER_NAME} already exists!${NC}"
    echo "Please use a different name or remove the existing container with:"
    echo "  docker rm -f ${CONTAINER_NAME}"
    exit 1
fi

# Change to project root directory for git operations
cd "$(dirname "$0")/.." || exit 1

# Get current git info for reference
GIT_BRANCH=$(git branch --show-current 2>/dev/null || echo "unknown")
GIT_COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")
GIT_STATUS=$(git status --porcelain | wc -l)

echo -e "${BLUE}=== QuestDB Isolated Test Runner ===${NC}"
echo -e "Test Name:    ${GREEN}${TEST_NAME}${NC}"
echo -e "Git Branch:   ${GIT_BRANCH}"
echo -e "Git Commit:   ${GIT_COMMIT}"
echo -e "Uncommitted:  ${GIT_STATUS} files"
echo -e "Module:       ${MODULE}"
[ -n "$TEST_CLASS" ] && echo -e "Test Class:   ${TEST_CLASS}"
echo -e "Memory:       ${MEMORY}"
echo -e "CPUs:         ${CPU_LIMIT}"
echo -e "Parallel:     ${PARALLEL}"
echo -e "Background:   ${BACKGROUND}"
echo ""

# Create results directory
RESULTS_DIR="test-results/${TEST_NAME}"
mkdir -p "${RESULTS_DIR}"

# Save test metadata
cat > "${RESULTS_DIR}/metadata.txt" << EOF
Test Name: ${TEST_NAME}
Started: $(date)
Git Branch: ${GIT_BRANCH}
Git Commit: ${GIT_COMMIT}
Uncommitted Files: ${GIT_STATUS}
Module: ${MODULE}
Test Class: ${TEST_CLASS}
Memory: ${MEMORY}
CPUs: ${CPU_LIMIT}
Parallel: ${PARALLEL}
Maven Opts: ${MAVEN_OPTS}
Extra Args: ${EXTRA_ARGS}
EOF

# Build Docker image with current source
echo -e "${YELLOW}Building Docker image with current source snapshot...${NC}"
docker build -f local-tests/Dockerfile.test -t "${IMAGE_NAME}" . || {
    echo -e "${RED}Failed to build Docker image${NC}"
    exit 1
}

# Prepare Maven command
MAVEN_CMD="mvn test -Dmaven.test.failure.ignore=true"

# Add module-specific options
# If a test class is specified but no module, default to core
if [ -n "$TEST_CLASS" ] && [ "$MODULE" = "all" ]; then
    MODULE="core"
fi

if [ "$MODULE" != "all" ]; then
    MAVEN_CMD="$MAVEN_CMD -pl ${MODULE}"
fi

# Add test class filter if specified
if [ -n "$TEST_CLASS" ]; then
    MAVEN_CMD="$MAVEN_CMD -Dtest=${TEST_CLASS}"
fi

# Add parallel execution
if [ "$PARALLEL" -gt 1 ]; then
    MAVEN_CMD="$MAVEN_CMD -DforkCount=${PARALLEL} -DreuseForks=true"
fi

# Add custom Maven options
if [ -n "$MAVEN_OPTS" ]; then
    MAVEN_CMD="$MAVEN_CMD ${MAVEN_OPTS}"
fi

# Add extra arguments
if [ -n "$EXTRA_ARGS" ]; then
    MAVEN_CMD="$MAVEN_CMD ${EXTRA_ARGS}"
fi

# Prepare Docker run command
DOCKER_RUN="docker run"
DOCKER_RUN="$DOCKER_RUN --name ${CONTAINER_NAME}"
DOCKER_RUN="$DOCKER_RUN --memory=${MEMORY}"
DOCKER_RUN="$DOCKER_RUN --cpus=${CPU_LIMIT}"
DOCKER_RUN="$DOCKER_RUN -v $(pwd)/${RESULTS_DIR}:/test-results"
DOCKER_RUN="$DOCKER_RUN -e MAVEN_OPTS=-Xmx${MEMORY}"

if [ "$BACKGROUND" = true ]; then
    DOCKER_RUN="$DOCKER_RUN -d"
else
    DOCKER_RUN="$DOCKER_RUN -it"
fi

if [ "$KEEP_CONTAINER" = false ]; then
    DOCKER_RUN="$DOCKER_RUN --rm"
fi

DOCKER_RUN="$DOCKER_RUN ${IMAGE_NAME}"
DOCKER_RUN="$DOCKER_RUN /bin/bash -c"

# Full command to run in container
CONTAINER_CMD="${MAVEN_CMD} && cp -r target/surefire-reports /test-results/ 2>/dev/null || true"

# Run tests
echo -e "${YELLOW}Starting test execution...${NC}"
echo -e "${BLUE}Command: ${MAVEN_CMD}${NC}"
echo ""

if [ "$BACKGROUND" = true ]; then
    CONTAINER_ID=$(${DOCKER_RUN} "${CONTAINER_CMD}")
    echo -e "${GREEN}Tests started in background${NC}"
    echo -e "Container ID: ${CONTAINER_ID:0:12}"
    echo -e "Container Name: ${CONTAINER_NAME}"
    echo ""
    echo "Monitor with:"
    echo "  ./test-logs.sh ${TEST_NAME}"
    echo "  ./test-status.sh"
    echo ""
    echo "Results will be available in: ${RESULTS_DIR}"
else
    ${DOCKER_RUN} "${CONTAINER_CMD}"
    echo ""
    echo -e "${GREEN}Tests completed${NC}"
    echo "Results available in: ${RESULTS_DIR}"
fi

# Save completion metadata
if [ "$BACKGROUND" = false ]; then
    echo "Completed: $(date)" >> "${RESULTS_DIR}/metadata.txt"
fi