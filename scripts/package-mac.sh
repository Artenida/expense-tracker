#!/usr/bin/env bash
# Builds build/dist/ExpenseTracker-<version>.dmg: the app with its own Java runtime, so
# the user needs no JDK. Pass --app-image for a quick .app without the disk image.
#
# The bundle carries JavaFX's native libraries for the architecture that built it (this
# machine's), so an Intel build does not run on Apple Silicon and vice versa.

# Exit on an error, an unset variable, or a failure anywhere in a pipeline - otherwise a
# failed mvn is followed by jpackage on a stale jar, and you debug the wrong artifact.
set -euo pipefail

cd "$(dirname "$0")/.."

VERSION="1.0.0"
NAME="ExpenseTracker"
JAR="expense-tracker-1.0-SNAPSHOT.jar"
TYPE="dmg"
# The JDK modules the bundled runtime keeps; by default jpackage bundles the whole JDK
# for a non-modular app (~170 MB of runtime). From
#   jdeps --multi-release 21 --print-module-deps --ignore-missing-deps target/$JAR
# plus jdk.localedata, which jdeps cannot see: without it month names and number formats
# fall back to the root locale, and the app reads differently from `mvn javafx:run`.
# All of jdk.localedata is 43 MB, so only these locales are kept - add yours to ship wider.
MODULES="java.base,java.desktop,java.sql,jdk.jfr,jdk.unsupported,jdk.localedata"
LOCALES="en,sq"
if [[ "${1:-}" == "--app-image" ]]; then
  TYPE="app-image"
fi

mvn -q clean package

# The icon, from the committed source PNG. The @2x sizes are for Retina displays.
rm -rf build/icon.iconset
mkdir -p build/icon.iconset
for size in 16 32 128 256 512; do
  sips -z "$size" "$size" packaging/icon-1024.png --out "build/icon.iconset/icon_${size}x${size}.png" >/dev/null
  sips -z $((size * 2)) $((size * 2)) packaging/icon-1024.png --out "build/icon.iconset/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns build/icon.iconset -o build/ExpenseTracker.icns

# Only the shaded jar. --input target would also bundle original-*.jar, the unshaded copy
# the shade plugin leaves behind.
rm -rf build/input build/dist
mkdir -p build/input build/dist
cp "target/$JAR" build/input/

jpackage \
  --type "$TYPE" \
  --name "$NAME" \
  --app-version "$VERSION" \
  --input build/input \
  --main-jar "$JAR" \
  --main-class com.expensetracker.Launcher \
  --icon build/ExpenseTracker.icns \
  --dest build/dist \
  --mac-package-identifier com.expensetracker.app \
  --vendor "Artenida" \
  --java-options "-Xmx512m" \
  --add-modules "$MODULES" \
  --jlink-options "--strip-debug --no-man-pages --no-header-files --strip-native-commands --include-locales=$LOCALES"

if [[ "$TYPE" == "dmg" ]]; then
  echo "built: build/dist/$NAME-$VERSION.dmg"
else
  echo "built: build/dist/$NAME.app"
fi
