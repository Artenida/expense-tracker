# Sprint 01 · Skeleton

**Time:** ~1 hour · **Prerequisites:** none · **Produces:** a project that builds and tests green with no application code in it

---

## Purpose

Get the build working *before* there is any code to blame. If `mvn clean test` is
green on an empty project, then every future failure is your code — not a missing
dependency, not a wrong Java version, not a plugin misconfiguration. Debugging a
broken build and broken code at the same time is twice as hard as debugging either.

This sprint is also where you meet the conventions that the next nineteen assume: where
files live, how packages map to directories, and how a test finds the class it tests.

## New concepts

### 1. The Maven standard layout

Maven is a build tool that works by convention. You do not tell it where your source
code is; you put it where it expects:

```
pom.xml                      the build definition
src/main/java/               application code      → compiled into the app
src/main/resources/          non-code files        → copied into the app
src/test/java/               test code             → compiled, run, NOT shipped
src/test/resources/          test fixtures         → available to tests only
target/                      everything Maven produces (never committed)
```

The split that matters: **`src/test/java` can see `src/main/java`, but not the other
way round.** Test code depends on application code. Application code never mentions a
test. This is enforced by the build, not by discipline.

### 2. Packages are directories

A Java package is a namespace, and the compiler requires the directory path to match
it exactly. A file declaring `package com.expensetracker.domain;` **must** live at
`src/main/java/com/expensetracker/domain/`. There is no configuration for this — get
it wrong and the compiler refuses.

Two things follow that are worth internalising now:

- A test for `com.expensetracker.domain.Expense` normally lives in the *same* package
  under `src/test/java`. That lets it reach package-private members. Same namespace,
  different source root.
- The package tree *is* the architecture diagram. When PLAN.md says "`ui` depends on
  `service`", it means classes in `com.expensetracker.ui` have `import` lines pointing
  at `com.expensetracker.service` — and never the reverse. You will be able to read the
  dependency direction straight off the import block at the top of any file.

### 3. What a dependency actually is

Each `<dependency>` in `pom.xml` names a jar by three coordinates — `groupId`,
`artifactId`, `version`. Maven downloads it to `~/.m2/repository` and puts it on the
**classpath**: the list of places the JVM searches when your code says
`import org.junit.jupiter.api.Test`.

The `<scope>` controls *which* classpath. `test` scope means the jar is available when
compiling and running tests, and absent from the shipped application. JUnit is
`test`-scoped; JavaFX and SQLite are not.

## What you build

Nothing that does anything. Specifically:

- `pom.xml` with Java 21, JUnit 5, JavaFX, SQLite, and two plugins
- The empty package tree for all seven packages
- `.gitignore`
- One test that asserts `true`, purely to prove the test runner runs

## Definition of done

- [ ] `mvn clean test` exits 0 and reports `Tests run: 1`
- [ ] `mvn -version` reports Java 21
- [ ] `git status` shows no `target/` and no `.db` files
- [ ] You can name what each of the five top-level `pom.xml` sections does

---

## Reading check

Answer without looking at your files:

1. You add `src/main/java/com/expensetracker/domain/Expense.java` but write
   `package com.expensetracker.model;` at the top. What happens, and why?
2. Your test class imports `org.junit.jupiter.api.Test`. Why does that import resolve
   in `src/test/java` but fail in `src/main/java`?
3. `target/` is in `.gitignore`. Someone clones the repo and it is not there. How do
   they get a working build?
4. What is the difference between `groupId` and `artifactId`, and why are both needed?
