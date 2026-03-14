# GitHub Actions CI

## Purpose

The repository runs a GitHub Actions CI pipeline for every pushed commit and every pull request update.

The workflow verifies that the Gradle project still builds and that both test layers pass.

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
