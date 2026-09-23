# Sprint 06 · Tests

`src/test/java/com/expensetracker/store/SchemaMigratorTest.java` and `DatabaseTest.java`.

Covers **spec test 11**.

---

## `@TempDir` — the rule for every database test from here on

```java
class SchemaMigratorTest {

    @TempDir Path tempDir;        // JUnit creates it per test, deletes it after

    private Database freshDatabase() {
        return new Database(tempDir.resolve("test.db"));
    }
}
```

**Never share a database file between tests, and never point a test at your real data.**
A shared file makes tests order-dependent: one passes alone and fails in a suite, for
reasons that take hours to find. `@TempDir` gives each test method its own directory,
and JUnit deletes it afterwards whether the test passed or not.

## Migration

| Test | Proves |
|---|---|
| `freshDatabaseStartsAtVersionZero` | `currentVersion()` is `0` before anything runs — the `sqlite_master` check works |
| `migrateCreatesTheFile` | `Files.exists(path)` afterwards |
| `migrateReachesTargetVersion` | Returns `2`, and `currentVersion()` then returns `2` |
| `migrateCreatesAllThreeTables` | Query `sqlite_master` for `expenses`, `budgets`, `schema_version` |
| `v2AddsTheNoteColumn` | `PRAGMA table_info(expenses)` contains a row named `note` |
| `v2AddsBothIndexes` | `sqlite_master WHERE type='index'` contains both names |

## Spec test 11 — idempotence

```java
@Test
void runningMigrationsTwiceAppliesNothingTheSecondTime() {
    Database db = freshDatabase();

    new SchemaMigrator(db).migrate();
    new SchemaMigrator(db).migrate();      // must not throw

    try (Connection c = db.open();
         Statement s = c.createStatement();
         ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM schema_version")) {
        rs.next();
        assertEquals(2, rs.getInt(1));     // two rows, not four
    }
}
```

The `COUNT(*)` is the real assertion. "Does not throw" would also pass if the migrator
re-ran everything and happened to get away with it. Counting the rows proves it
genuinely skipped.

This is why V2 contains an `ALTER TABLE`: with only `CREATE INDEX IF NOT EXISTS`, a
migrator that ignored `schema_version` entirely would still pass "does not throw". The
`ALTER TABLE` fails loudly on a second run, so the test has teeth.

### The partial-upgrade test

Worth writing, because it is the scenario migrations actually exist for:

```java
@Test
void aDatabaseAtVersionOneIsUpgradedToTwo() {
    Database db = freshDatabase();
    // build a v1-only database by hand
    runScript(db, "/db/V1__create_tables.sql");
    insertVersionRow(db, 1);

    assertEquals(2, new SchemaMigrator(db).migrate());
    assertTrue(columnsOf(db, "expenses").contains("note"));
}
```

## Database

| Test | Proves |
|---|---|
| `openReturnsAUsableConnection` | `SELECT 1` succeeds |
| `parentDirectoryIsCreated` | Point at `tempDir/a/b/c/test.db`; all three levels appear |
| `pathIsAbsolute` | `path().isAbsolute()` — the status bar depends on this |
| `walModeIsEnabled` | `PRAGMA journal_mode` returns `wal` |
| `systemPropertyOverridesTheDefault` | Set `expenses.db`, call `resolveDefault()`, assert, then **clear the property** |

The last one is a trap in the making. A test that sets a system property and does not
clear it leaks into every test that runs after it in the same JVM. Use
`@AfterEach` with `System.clearProperty("expenses.db")`, or JUnit's
`@ClearSystemProperty`. Global mutable state is exactly as dangerous in tests as it is
in application code.

## Error handling

| Test | Proves |
|---|---|
| `openingAnUnwritableLocationThrowsStoreException` | Point at a path under a read-only directory; assert `StoreException`, **not** `SQLException` |
| `theCauseIsPreserved` | `assertNotNull(thrown.getCause())` and that it is a `SQLException` |

The second test is the one that pays off at three in the morning. A `StoreException`
saying "failed to open database" with no cause tells you nothing; with the cause you get
SQLite's error code and know whether the file was locked, missing or unreadable.

## A note on what you are not testing

There is no test that the *content* of `V1__create_tables.sql` is correct SQL — if it
were not, every other test in this file would fail. Tests that can only fail alongside
ten other tests are not worth writing. Sprint 07 tests the schema properly, by using it.
