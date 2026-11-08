# Contributing to AppleCraft

Thanks for wanting to contribute! Any help, big or small, is always appreciated.

All contributions should be made in accordance with our [Code of Conduct](./CODE_OF_CONDUCT.md).

## Ways to contribute

- **Report a bug**: [open a bug report](https://github.com/LeonimusTTV/AppleCraft/issues/new?template=bug_report.yml)
- **Suggest a feature**: [open a feature request](https://github.com/LeonimusTTV/AppleCraft/issues/new?template=feature_request.yml)
- **Fix a bug or improve the mod**: for small fixes, feel free to open a PR directly; for larger changes, open an issue first to discuss
- **Translate the mod**: add a file in `common/src/main/resources/assets/applecraft/lang/` (copy `en_us.json`)

## Getting started

See the [Development](README.md#development) section of the README. In short: JDK 25 (plus JDK 21 for NeoForge's tooling), run the Worker locally with `npm run dev` in `applecraft-api`, then `./gradlew :fabric:runClient` or `./gradlew :neoforge:runClient`.

Code that doesn't depend on the mod loader goes in `common/`. The `fabric/` and `neoforge/` folders should stay as small as possible.

## Pull requests

Changes go through pull requests targeting the `main` branch: pushing directly to `main` is disabled.

### PR title & commit format

PR titles **must** follow the [Conventional Commits](https://www.conventionalcommits.org/) format:

```
type: short description
```

This is enforced automatically: PRs with non-conforming titles fail the CI check and can't be merged. The PR is also labeled automatically from its type, and that label decides where it shows up in the release notes.

Allowed types:

| Type                     | When to use                               |
| ------------------------ | ----------------------------------------- |
| `feat` / `feature`       | New feature                               |
| `fix` / `bug` / `hotfix` | Bug fix                                   |
| `chore`                  | Routine tasks, maintenance                |
| `docs`                   | Documentation changes                     |
| `refactor`               | Code refactoring without behaviour change |
| `build`                  | Build system or tool changes              |
| `ci`                     | CI/CD changes                             |
| `test`                   | Adding or updating tests                  |
| `style`                  | Code style/formatting (no logic change)   |
| `deps`                   | Dependency updates                        |

> Individual commits within the PR don't need to follow this format, but keeping them consistent is encouraged.

## License

By contributing, you agree that your contributions are licensed under the project's [PolyForm Noncommercial 1.0.0](LICENSE) license.
