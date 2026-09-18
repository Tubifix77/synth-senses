# Gradle wrapper

`gradle-wrapper.jar` and the `gradlew` / `gradlew.bat` scripts are **not
committed** — the JAR is a binary and this repo avoids shipping binaries.

Get them one of two ways:

**Android Studio** — just open the project. It generates the wrapper on first
Gradle sync and nothing else is needed.

**Command line**, if you have Gradle installed:

```bash
gradle wrapper --gradle-version 9.7.1
```

After either, `./gradlew installDebug` works and you can commit the wrapper
locally if you prefer (remove the `gradle/wrapper/gradle-wrapper.jar` line from
`.gitignore` first).

CI provisions Gradle directly via `gradle/actions/setup-gradle`, so the workflow
doesn't need the wrapper at all.
