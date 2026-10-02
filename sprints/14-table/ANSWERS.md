# Sprint 14 · Reading check answers

Answers to the four questions at the end of `README.md`.

---

## 1. `setCellValueFactory` returns a property rather than a `String`. What does the table do with the property that it could not do with a value?

### It listens to it

A cell value factory answers one question per visible cell: "which observable holds this
column's value for this row?" The table takes the property it gets back, displays its
current value, and **adds a listener** to it. If the property's value later changes, the cell
updates itself. Nobody calls `refresh()`, and the table does not poll.

A plain `String` is a snapshot. Once the cell has shown it, the table has no way to find out
it is out of date. The only fix would be to rebuild the cell, or to call `table.refresh()`,
which redraws every visible cell to catch one change.

### Cells are reused, so the listener moves

A `TableView` creates cells only for the rows on screen. While you scroll, it gives an
existing cell a new row, and that cell drops its listener on the old row's property and adds
one to the new row's. This is why the factory is called many times, and why it must be
cheap: return a property the row already has, and do not build a new one on every call.

### What that means here

`ExpenseRow` is immutable, so its properties never change after construction. The listening
does nothing useful today. The pattern still matters, because it is how `TableView` works.
And if a row ever does change in place, for example a "saving..." flag in a later sprint, the
display follows with no extra code.

## 2. You bind the comparator the wrong way round. It compiles. What is the symptom?

```java
table.comparatorProperty().bind(sorted.comparatorProperty());   // backwards
```

### The premise is wrong: with JavaFX 21 it does not compile

I checked this against the JavaFX 21.0.4 jars this project uses:

```
error: cannot find symbol
  symbol:   method bind(ObjectProperty<Comparator<? super String>>)
  location: class ReadOnlyObjectProperty<Comparator<String>>
```

`TableView.comparatorProperty()` returns a `ReadOnlyObjectProperty`, and a read-only property
has no `bind` method. The table works out its comparator itself, from the columns in its sort
order, and no one outside it can set it. The type system makes the table the only possible
**source**, which is the direction the correct code uses. `SortedList.comparatorProperty()` is
an ordinary `ObjectProperty`, so it is the only side that can follow.

That is the best kind of rule: one the compiler enforces. The README was probably written
against an older API, or about a different pair of properties.

### The mistakes that do compile

There are two ways to get the wiring wrong that the compiler cannot catch. Both give the same
symptom.

```java
SortedList<ExpenseRow> sorted = new SortedList<>(rows);
table.setItems(sorted);                                // 1. no binding at all

sorted.setComparator(table.getComparator());           // 2. a one-off copy, not a binding
```

**The symptom: clicking a header does not sort.** Clicking a header changes the table's sort
order, and the table then asks its sort policy to apply it. For a `SortedList`, the default
policy cannot reorder the list itself. A `SortedList` is a view and cannot be written to, so
the policy only succeeds if the list's comparator is **bound** to the table's. If it is not,
the policy reports failure, the table undoes the sort order change, and the rows stay as they
were. Version 2 is the more misleading one. It reads like a binding, but it copies the table's
comparator once, at construction, when it is `null`, and never again.

Version 1 sometimes looks "fixed" by passing `rows` to `setItems` directly. Sorting then
works, and the bug moves to README §5: the next `setAll` throws the user's sort order away.

Clicking a header is still the check, as `TESTS.md` says. It just catches a different mistake
from the one the question describes.

## 3. `ExpenseRow` wraps an `Expense` and also keeps `source()`. Why not rebuild the `Expense` from the four displayed strings when the dialog needs it?

Because the strings do not contain enough to rebuild it, and what they do contain has been
converted for display.

1. **Identity is not displayed.** The table shows no `id`, so a rebuilt object would need a
   new one. Sprint 17 saves an edit with `ExpenseService.update(edited)`, and the store finds
   the row by id. With a new id the update throws `ExpenseNotFoundException`, or, if the code
   falls back to `add`, it saves a duplicate and leaves the original.
2. **History is not displayed.** `createdAt` is not a column. `Expense.edit(...)` exists to
   keep both `id` and `createdAt` and replace only what the user can change. That only works on
   the original object.
3. **The display strings lose information.** `"Groceries"` is a display name, and turning it
   back into an enum means `Category.parse`, which expects `GROCERIES`. That works only by
   accident, and fails as soon as a display name differs from its constant, like a future
   `"Eating out"` for `EATING_OUT`. `"24.90"` is the result of `Money.format`. A string meant
   for people is not a reliable input.
4. **It would be two-way conversion with nothing checking it.** Every display rule would need
   an exact inverse, kept in step forever.

`source()` avoids all of it. The row is a view of an object, not a replacement for it. That
is also why the test uses `assertSame` and not `assertEquals`: an equal copy would pass
`equals` and still not be the object the dialog is meant to edit.

## 4. `rows.setAll(list)` versus `rows.clear(); rows.addAll(list);` — what does the user see differently?

### One change against two

`setAll` makes a single replacement and fires **one** change event: "these rows were replaced
by those". `clear()` and `addAll()` fire **two**: first "everything was removed", then
"these were added".

Each event makes the table react, and between the two the list really is empty. So the user
can see:

- **A flicker.** For a moment the table is empty and shows the placeholder, "No expenses for
  this selection", before the rows reappear. On a fast machine it is only a single frame. With
  a slow load or many rows it is plainly visible.
- **The selection is lost.** When the rows are removed, the selected row disappears from the
  list, so the selection model clears it. `setAll` can do the same for replaced rows, but it
  avoids the brief empty state that sends the selection, the focus and any listeners through
  "nothing".
- **The scroll position resets.** An empty table has nothing to scroll, so after `clear()` the
  scroll bar returns to the top, and `addAll` adds the rows below that.

It also costs more. Two events mean listeners such as `SortedList`, the table's skin, and in
later sprints the summary bindings, all run twice, once to handle "empty" and once to handle
"full".

### When it matters

Today the load happens once, at startup. From sprint 16 every change of filter reloads the
table, and from sprint 18 every edit does too. Using `setAll` from the start means the table
does not flicker on every reload.
