# Sprint 20 · Build specification

---

## 1. The shade plugin

Add to `pom.xml`:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-shade-plugin</artifactId>
  <version>3.6.0</version>
  <executions>
    <execution>
      <phase>package</phase>
      <goals><goal>shade</goal></goals>
      <configuration>
        <createDependencyReducedPom>false</createDependencyReducedPom>
        <transformers>
          <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
            <mainClass>com.expensetracker.Launcher</mainClass>
          </transformer>
        </transformers>
        <filters>
          <filter>
            <artifact>*:*</artifact>
            <excludes>
              <exclude>META-INF/*.SF</exclude>
              <exclude>META-INF/*.DSA</exclude>
              <exclude>META-INF/*.RSA</exclude>
              <exclude>module-info.class</exclude>
            </excludes>
          </filter>
        </filters>
      </configuration>
    </execution>
  </executions>
</plugin>
```

| Setting | Why |
|---|---|
| `mainClass` = `Launcher` | The whole point. `App` here reproduces the error. |
| Excluding `*.SF`, `*.DSA`, `*.RSA` | Signature files from signed dependencies. Merging jars invalidates them, and the JVM then refuses the jar with `SecurityException: Invalid signature file digest`. |
| Excluding `module-info.class` | Several dependencies ship one. In a shaded jar they conflict, and none of them applies once everything is on the classpath. |
| `createDependencyReducedPom=false` | Stops the plugin writing a `dependency-reduced-pom.xml` into your project root. |

Verify before going further:

```sh
mvn clean package
java -jar target/expense-tracker-1.0-SNAPSHOT.jar
```

**This must work before you touch `jpackage`.** If it fails here, the cause is the jar,
and debugging that inside a bundle is much harder.

---

## 2. The icon

macOS wants `.icns`. From a 1024×1024 PNG:

```sh
mkdir -p build/icon.iconset
for size in 16 32 128 256 512; do
  sips -z $size $size icon-1024.png --out build/icon.iconset/icon_${size}x${size}.png
  sips -z $((size*2)) $((size*2)) icon-1024.png --out build/icon.iconset/icon_${size}x${size}@2x.png
done
iconutil -c icns build/icon.iconset -o build/ExpenseTracker.icns
```

`sips` and `iconutil` ship with macOS. The `@2x` variants are for Retina displays; skip
them and the icon looks soft.

Any simple 1024×1024 PNG will do — a banknote, a chart, a wallet.

---

## 3. `jpackage`

`scripts/package-mac.sh`:

```sh
#!/usr/bin/env bash
set -euo pipefail

VERSION="1.0.0"
NAME="ExpenseTracker"
JAR="expense-tracker-1.0-SNAPSHOT.jar"

mvn clean package

rm -rf build/dist
mkdir -p build/dist

jpackage \
  --type dmg \
  --name "$NAME" \
  --app-version "$VERSION" \
  --input target \
  --main-jar "$JAR" \
  --main-class com.expensetracker.Launcher \
  --icon build/ExpenseTracker.icns \
  --dest build/dist \
  --mac-package-identifier com.expensetracker.app \
  --vendor "Your Name" \
  --java-options "-Xmx512m"

echo "built: build/dist/$NAME-$VERSION.dmg"
```

| Flag | Notes |
|---|---|
| `--type dmg` | Use `app-image` while iterating — much faster, produces `.app` without the disk image |
| `--input target` | The directory of jars. `target` also contains `classes/` and the original jar; `jpackage` only takes `.jar` files from it. |
| `--main-class` | `Launcher`. Redundant with the manifest, and explicit is better. |
| `--mac-package-identifier` | Reverse-DNS, unverified unless you code-sign. |
| `--java-options` | Passed to the bundled JVM at launch. |

`set -euo pipefail` at the top of any build script: exit on error, on an unset variable,
and on a failure anywhere in a pipeline. Without it a failing `mvn` is followed by a
`jpackage` on a stale jar, and you debug the wrong artifact.

### Iterating

```sh
jpackage --type app-image --name ExpenseTracker --input target \
         --main-jar "$JAR" --main-class com.expensetracker.Launcher --dest build/dist
open build/dist/ExpenseTracker.app
```

Seconds instead of a minute. Switch to `--type dmg` for the final artifact.

---

## 4. Verifying it runs without a JDK

The bundle carries its own JVM, so this should work — but "should" is why you check:

```sh
env -i HOME="$HOME" /Applications/ExpenseTracker.app/Contents/MacOS/ExpenseTracker
```

`env -i` clears the environment, so no `JAVA_HOME` and no `PATH` entry can help. If it
starts, nothing external is needed.

`HOME` is kept because the database path resolution needs it.

---

## 5. The database in the right place

```sh
ls -la ~/Library/Application\ Support/ExpenseTracker/
```

`expenses.db` should be there after the first run, plus `-wal` and `-shm` from sprint
06's WAL mode.

The status bar shows that absolute path — sprint 13's `toAbsolutePath()`, finally
earning its keep, because in a bundle "which database?" is otherwise unanswerable.

**Try a clean first run:**

```sh
rm -rf ~/Library/Application\ Support/ExpenseTracker/
open /Applications/ExpenseTracker.app
```

The directory is recreated, migrations run, `schema v2` in the status bar. That is the
specification's *"the app creates and migrates its own database on first run"*, in the
environment a real user would be in.

---

## 6. Gatekeeper

An unsigned app triggers macOS's quarantine on first open: *"cannot be opened because the
developer cannot be verified."*

Right-click → Open, once, gets past it. Or:

```sh
xattr -dr com.apple.quarantine /Applications/ExpenseTracker.app
```

Proper signing needs an Apple Developer account and notarisation. Out of scope, and worth
knowing it is the reason distributing a Mac app to other people is more than a build
step.

---

## 7. `.gitignore`

```gitignore
build/
*.icns
*.dmg
```

The `.iconset` intermediates and the built artifacts are all reproducible. Commit the
source PNG and the script.

---

## 8. The project README

This is the last thing you write, and it is the first thing anyone else reads. It should
cover:

- **What it is**, in two sentences
- **Running from source** — `mvn javafx:run`
- **Running the tests** — `mvn clean test`, and `-DexcludedGroups=` for the tagged ones
- **Building the bundle** — `./scripts/package-mac.sh`
- **Where the database lives**, and the `-Dexpenses.db` override
- **The SQL-versus-streams three sentences** from sprint 11, with your measured numbers
- **The decisions**: UUID ids, `12,50` rejected, CSV without an `id` column and what that
  means for re-import
- **The architecture**, in one diagram: `ui → service → domain`, `store → domain`

The decisions section is the part that will save a future reader — including you — the
most time. Each of those is something that looks like an oversight until you know it was
a choice.
