# Project Guide

## Project overview
- `investingService` is a Java/Spring Boot service for investment portfolio workflows.
- The current implemented domain is portfolio rebalancing, including core rebalancing logic and a CLI entry path.
- The codebase includes both application startup and CLI startup paths:
  - Web application entry: `src/main/java/com/investing/service/investingservice/InvestingServiceApplication.java`
  - CLI configuration: `src/main/java/com/investing/service/investingservice/InvestingCliApplication.java`

## Tech stack
- Java 25 toolchain
- Gradle build
- Spring Boot 4
- Spring Data JPA
- Flyway
- GraphQL
- Spring WebFlux
- PostgreSQL
- Lombok

## Source layout
- Main code: `src/main/java`
- Tests: `src/test/java`
- Configuration: `src/main/resources`
- Feature and context documentation: `documentation/features`

## Working rules for agents
- Keep changes aligned with the existing architecture and package structure.
- Prefer feature-focused implementation work over speculative refactors.
- Minimize 3rd party calls whenever possible and prefer smart caching before adding repeated external requests.
- Keep the `documentation` folder up to date whenever behavior, workflows, contracts, or feature scope changes.
- Create, update, or remove documents in `documentation` when the implementation requires it.

## Testing policy
- Do not create unit tests.
- Create only feature-related integration tests.
- Minimize rebuilds and test runs during implementation; prefer running them only after the feature is polished and ready for validation.
- Add or update integration tests only after the feature implementation is finished.
- After finishing a feature, run all integration tests.
- If any integration test fails, fix the failures before considering the work complete.
- If an integration test failure reveals a real bug in the implementation, fix the bug rather than weakening or bypassing the test.

## Completion checklist
- Implement the feature.
- Polish the feature implementation before triggering rebuilds or test runs.
- Update integration tests for the feature if needed.
- Run all integration tests and fix failures.
- Update `documentation` so it matches the final behavior.
