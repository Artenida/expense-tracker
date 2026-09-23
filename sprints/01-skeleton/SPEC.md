# Sprint 01 · Build specification

## 1. Clean the directory

Delete the stray `main.js` in the project root — it is left over from something else
and has nothing to do with this project.

```sh
rm main.js
git init
```

## 2. `.gitignore`

```gitignore
target/
data/
*.db
*.db-shm
*.db-wal
.idea/
*.iml
.DS_Store
```

`*.db-shm` and `*.db-wal` are SQLite's write-ahead-log sidecar files. They appear from
sprint 06 once WAL mode is on, and they should never be committed.

## 3. `pom.xml`

Create at the project root.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.expensetracker</groupId>
  <artifactId>expense-tracker</artifactId>
  <version>1.0-SNAPSHOT</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <javafx.version>21.0.4</javafx.version>
    <junit.version>5.11.3</junit.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.openjfx</groupId>
      <artifactId>javafx-controls</artifactId>
      <version>${javafx.version}</version>
    </dependency>

    <dependency>
      <groupId>org.xerial</groupId>
      <artifactId>sqlite-jdbc</artifactId>
      <version>3.46.1.3</version>
    </dependency>

    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>${junit.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.5.2</version>
        <configuration>
          <excludedGroups>ui</excludedGroups>
        </configuration>
      </plugin>

      <plugin>
        <groupId>org.openjfx</groupId>
        <artifactId>javafx-maven-plugin</artifactId>
        <version>0.0.8</version>
        <configuration>
          <mainClass>com.expensetracker.Launcher</mainClass>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

### Notes on the choices

| Line | Why |
|---|---|
| `maven.compiler.release` not `source`/`target` | `release` also checks you are not calling APIs newer than 21. `source`/`target` do not, so code can compile and then fail at runtime. |
| No `javafx-fxml` | The spec says build layouts in Java. Adding the dependency invites you to use it. |
| `excludedGroups>ui` | From sprint 13 the TestFX smoke tests carry `@Tag("ui")`. Excluding them keeps `mvn clean test` green even when the windowing toolkit misbehaves. |
| `mainClass` is `Launcher`, not `App` | Sprint 20 explains this. It costs nothing to get right now and is annoying to change later. |

**JavaFX has no `<classifier>`.** OpenJFX's own POM detects your OS and architecture and
pulls the right native jar. That works on the machine that builds it — and it means the
build output is not portable to a different architecture. Fine for now; sprint 20
revisits it.

## 4. Package tree

Create these directories. Git does not track empty directories, so add a `.gitkeep` to
each, or just create them as you reach the sprint that needs them.

```
src/main/java/com/expensetracker/
    domain/     store/     service/     ui/     ui/model/     ui/task/     io/
src/main/resources/
    db/
src/test/java/com/expensetracker/
    domain/     store/     service/
```

## 5. The smoke test

`src/test/java/com/expensetracker/BuildSmokeTest.java`

```java
package com.expensetracker;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildSmokeTest {

    @Test
    void theBuildRunsTests() {
        assertEquals(21, Runtime.version().feature());
    }
}
```

Asserting the Java version rather than `assertTrue(true)` makes the test earn its
place: it fails loudly if Maven ever picks up a different JDK.

Note the class is **package-private** (no `public`). JUnit 5 does not require public
test classes or methods, and leaving `public` off is the convention — it signals
"nothing outside this package should reference this".

## 6. Verify

```sh
mvn clean test
```

Expect `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

The first run downloads a few hundred megabytes into `~/.m2/repository`. Subsequent
runs are seconds.
