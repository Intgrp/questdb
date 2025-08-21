# Isolated Test Runner for QuestDB

This solution allows you to run QuestDB tests in isolated Docker containers with snapshots of your source code, enabling you to continue development while tests run in the background.

## Features

- **Complete isolation**: Tests run on a snapshot of code at the time you start them
- **Parallel execution**: Run multiple test snapshots simultaneously with different configurations
- **Background execution**: Tests run in detached containers while you continue working
- **Result persistence**: Test results and logs saved to host filesystem
- **Resource management**: Configure memory and CPU limits for test containers
- **Test filtering**: Run specific modules, packages, or test classes

## Quick Start

### Basic Usage

From the project root directory:

```bash
# Run all tests with a unique name
./local-tests/run-isolated-tests.sh --name my-feature-test

# Run tests in background
./local-tests/run-isolated-tests.sh --name background-test --background

# Run specific module tests
./local-tests/run-isolated-tests.sh --name core-test --module core

# Run specific test class
./local-tests/run-isolated-tests.sh --name specific-test --test "TestGriffinEngine"
```

### Monitoring Tests

```bash
# Check status of all test containers
./local-tests/test-status.sh

# Watch status continuously
./local-tests/test-status.sh --watch

# View logs from a specific test
./local-tests/test-logs.sh my-feature-test

# Follow logs in real-time
./local-tests/test-logs.sh my-feature-test --follow

# Filter logs for errors
./local-tests/test-logs.sh my-feature-test --grep "ERROR\|FAIL"
```

### Cleanup

```bash
# Clean specific test
./local-tests/test-cleanup.sh my-feature-test

# Clean all stopped containers
./local-tests/test-cleanup.sh --all

# Force clean all (including running)
./local-tests/test-cleanup.sh --all --force

# Clean everything (containers, images, results)
./local-tests/test-cleanup.sh --all --force --images --results

# Dry run to see what would be cleaned
./local-tests/test-cleanup.sh --all --dry-run
```

## Parallel Test Execution

Run multiple test snapshots simultaneously:

```bash
# Snapshot 1: Current state - test core module
./local-tests/run-isolated-tests.sh --name snapshot-1 --module core --background

# Make some code changes...

# Snapshot 2: New state - test benchmarks
./local-tests/run-isolated-tests.sh --name snapshot-2 --module benchmarks --background

# Make more changes...

# Snapshot 3: Latest state - run specific tests
./local-tests/run-isolated-tests.sh --name snapshot-3 --test "TestSpecificClass" --background

# Monitor all running tests
./local-tests/test-status.sh --watch
```


## Configuration Options

### run-isolated-tests.sh Options

- `-n, --name NAME`: Unique name for test run (required for parallel runs)
- `-m, --module MODULE`: Module to test (core, benchmarks, utils, examples, compat, all)
- `-t, --test CLASS`: Specific test class or pattern
- `-M, --memory SIZE`: Memory limit (e.g., 4g, 8g)
- `-p, --parallel COUNT`: Number of parallel test threads
- `-b, --background`: Run tests in background
- `-k, --keep`: Keep container after tests complete
- `-o, --maven-opts OPTS`: Additional Maven options
- `-a, --args ARGS`: Additional Maven arguments


## Results

Test results are stored in `test-results/<test-name>/`:
- `metadata.txt`: Test run information
- `test.log`: Complete test output (when using docker-compose)
- `surefire-reports/`: Maven Surefire test reports

## Tips

1. **Naming Convention**: Use descriptive names for your test runs (e.g., `feature-xyz-before-refactor`, `performance-baseline`)

2. **Resource Management**: Adjust memory limits based on your test requirements:
   - Unit tests: 2-4GB
   - Integration tests: 4-6GB
   - Performance tests: 8GB+

3. **Cleanup Strategy**: Regularly clean up old test containers and results to save disk space

4. **Parallel Testing**: Your system can handle multiple test containers based on available resources. Monitor system resources when running many parallel tests.

## Troubleshooting

### Container already exists
```bash
# Remove existing container
docker rm -f questdb-test-<name>
# Or use cleanup script
./test-cleanup.sh <name>
```

### Out of disk space
```bash
# Clean all test artifacts
./test-cleanup.sh --all --force --images --results

# Prune Docker system
docker system prune -a
```

### Tests failing in container but not locally
- Check memory limits (increase with `-M` flag)
- Verify all dependencies are in the Docker image
- Check for environment-specific configurations

## Requirements

- Docker installed and running
- Sufficient disk space for Docker images and test results
- Adequate system memory for running test containers