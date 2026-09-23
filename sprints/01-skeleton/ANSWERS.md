# Sprint 01 · Reading check answers

Answers to the four questions at the end of `README.md`.

---

## Four things to know first

**1. Java code gets translated before it runs.**
You write `.java` files (text a human reads). A program called the **compiler**
(`javac`) translates them into `.class` files (instructions the computer runs).
"Building" means running that translation.

**2. A "package" is just a folder — with a label inside.**
Every Java file starts with a line like `package com.expensetracker.domain;`. That is
the file announcing its own address. The folders on disk must match that address
exactly:

```
package com.expensetracker.domain;          ← the label inside the file
src/main/java/com/expensetracker/domain/    ← the folder it must sit in
```

Two ways of saying the same address. They have to agree.

**3. The "classpath" is a list of places to look.**
Your code uses tools written by other people (JUnit for testing, SQLite for the
database). The classpath is the list of those toolboxes the compiler is allowed to
open. If a toolbox is not on the list, it is as if it does not exist — even when it is
installed on your computer.

**4. Maven fetches the toolboxes and runs the build.**
`pom.xml` is a shopping list. Maven reads it, downloads what is named, and hands the
right toolboxes to the compiler.

---

## 1. File in `domain/` but declaring `package com.expensetracker.model;`

**It breaks — because the compiler finds files by their address.**

Think of a postal address. When another file says "I need `Expense` from `domain`",
the compiler does not search the whole computer. It goes straight to the `domain`
folder, the way a postman goes straight to one house.

It arrives, opens the file, and the file says "actually I live in `model`". The
compiler is stuck: right house, wrong person. So it stops:

```
error: cannot access Expense
  bad source file: src/com/expensetracker/domain/Expense.java
    file does not contain class com.expensetracker.domain.Expense
```

**The lesson:** the folder structure is not decoration or tidiness. It is the *map* the
compiler uses to find things. Break the map and it cannot find anything.

> Curiosity: if that file is completely alone and nothing refers to it, it compiles
> fine — nobody ever looked for it, so nobody noticed the wrong address. The moment
> anything needs it, it fails. In this project it would always fail, because Maven
> compiles everything in `src/main/java` together.

## 2. Why `import org.junit.jupiter.api.Test` works in `src/test/java` but not `src/main/java`

**Because `pom.xml` hands the testing toolbox only to the tests.**

JUnit carries this line and the other dependencies do not:

```xml
<scope>test</scope>
```

`scope` means "when is this allowed?", and `test` means "only while testing". So:

| Source folder | Toolboxes it gets |
|---|---|
| `src/main/java` (the app) | JavaFX, SQLite |
| `src/test/java` (the tests) | JavaFX, SQLite, **and JUnit** |

Import JUnit in main code and it fails with "package does not exist" — not because it
is forbidden, but because that toolbox was never handed over.

**Why do it this way?**

- Tests may look at the app; the app must never look at its tests. This makes the wrong
  direction physically impossible rather than a rule to remember.
- When the app ships, JUnit is not inside it. Testing tools are for the developer, not
  the user — no point shipping the scaffolding with the building.

## 3. `target/` is in `.gitignore`. How does someone who clones the repo build it?

**They run `mvn clean test`, and it rebuilds itself.**

`target/` holds the `.class` files — the translated version of the code. The principle:

> Never save anything you can recreate from something you already saved.

The `.java` files are the original. `target/` is only those files translated. Saving
both stores the same information twice, and the moment the original changes the copy is
stale and lying. This is also why deleting it (`clean`) is routine rather than a
recovery step.

The downloaded toolboxes work the same way. Nobody commits them. `pom.xml` *names*
them, and Maven fetches them into a shared folder (`~/.m2/repository`). That is why the
first build takes minutes and later ones take seconds — the second time, everything is
already there.

**Recipe analogy:** you share the recipe, not the cooked meal. Whoever wants the meal
cooks it from the recipe. `.java` and `pom.xml` are the recipe; `target/` is the meal.

## 4. The difference between `groupId` and `artifactId`, and why both are needed

- **`groupId`** = **who made it** → `org.xerial`
- **`artifactId`** = **which of their things** → `sqlite-jdbc`

**Why both?** Because names repeat. Many organisations publish something called `core`
or `utils` or `api`. Saying "I need `core`" is like saying "I need the Smith house" —
which Smith? Naming the maker removes the ambiguity. It is a first name and a surname:
many people are called Anna, far fewer are a specific Anna from a specific family.

And the two names are literally the folder path where the jar is stored:

```
~/.m2/repository/org/xerial/sqlite-jdbc/3.46.1.3/sqlite-jdbc-3.46.1.3.jar
                 └ who made it ┘└ which thing ┘└ version ┘
```

Exactly like packages in question 1: **a name that doubles as a place to look.** Once
that pattern is visible, Java packages and Maven dependencies stop being two separate
things to memorise.

---

## One bonus, worth noticing

`pom.xml` declares **one** JavaFX dependency, but seven jars arrive:

```
javafx-controls-21.0.4.jar   javafx-controls-21.0.4-mac.jar
javafx-graphics-21.0.4.jar   javafx-graphics-21.0.4-mac.jar
javafx-base-21.0.4.jar       javafx-base-21.0.4-mac.jar
sqlite-jdbc-3.46.1.3.jar
```

`javafx-controls` needs `javafx-graphics`, which needs `javafx-base`, and each has a
native `-mac` variant chosen for this machine. Maven followed that chain without being
asked — **transitive dependency resolution**.

Those `-mac` jars are exactly what `SPEC.md` means when it says the build output is not
portable to a different architecture. Sprint 20 revisits it.
