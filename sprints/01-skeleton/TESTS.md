# Sprint 01 · Tests

One test, and it is about the build rather than the application.

| Test | File | Proves |
|---|---|---|
| `theBuildRunsTests` | `BuildSmokeTest.java` | Surefire discovers and runs JUnit 5 tests, and Maven compiled against Java 21 |

## How to read a Surefire failure

You will see a lot of these over the next nineteen sprints. The useful parts:

```
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
[ERROR] theBuildRunsTests  Time elapsed: 0.01 s  <<< FAILURE!
org.opentest4j.AssertionFailedError: expected: <21> but was: <17>
	at com.expensetracker.BuildSmokeTest.theBuildRunsTests(BuildSmokeTest.java:10)
```

- **`Failures`** means an assertion did not hold — your logic is wrong.
- **`Errors`** means an exception escaped — something threw before it could assert.
  These two are counted separately because they usually mean different kinds of bug.
- The first stack frame naming *your* package is the line that matters. Everything
  above it is JUnit's own machinery.

## Deliberately break it

Worth two minutes, because it teaches you what the failure modes look like before one
surprises you:

1. Change the assertion to `assertEquals(17, ...)`. Run. Read the failure message —
   note it is a `Failure`, not an `Error`.
2. Change it to `assertEquals(21, 1 / 0)`. Run. Note it is now an `Error`, with an
   `ArithmeticException`, and the message looks completely different.
3. Delete the `@Test` annotation. Run. Note that the test does not fail — it simply
   **disappears**, and `Tests run: 0` still reports `BUILD SUCCESS`. This is the most
   dangerous of the three, and the reason to check the test count and not just the
   colour of the build.

Put it back to `assertEquals(21, Runtime.version().feature())` before moving on.
