# GitHub Actions CI

## Purpose

The repository runs a GitHub Actions CI pipeline for every pushed commit and every pull request update.

The workflow verifies that the Gradle project still builds and that both test layers pass.

The CI workflow does not start PostgreSQL, Redis, or other external containers for test execution.

## Workflow Behavior

The CI workflow:

- checks out the repository
- installs Temurin Java 25
- restores the Gradle dependency cache
- builds the application with `./gradlew build -x test -x integrationTest`
- runs unit tests with `./gradlew test`
- runs integration tests with `./gradlew integrationTest`

## Test Split

The Gradle verification flow is split by test class naming:

- `*Test` classes run in the standard `test` task
- `*IT` classes run in the `integrationTest` task

The `check` lifecycle task depends on `integrationTest`, so a full Gradle verification run continues to validate both layers.

## Integration Test Runtime

Integration tests are expected to run with in-memory or test-local collaborators only.

Current expectations:

- no Docker services are required in GitHub Actions
- tests that boot Spring contexts must replace external infrastructure with in-memory or no-op test beans
- the CLI integration test excludes datasource, JPA, Flyway, and Redis auto-configuration, disables JPA/Redis repository bootstrap, and relies on `market-data.finnhub.cache.enabled=false` so the Finnhub configuration uses the built-in no-op cache without requiring Redis
