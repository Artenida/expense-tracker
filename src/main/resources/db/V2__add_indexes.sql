-- The note column is unused by the application. It is here because ALTER TABLE is not
-- idempotent: a migrator that re-ran V2 would fail loudly, which gives spec test 11 teeth.
ALTER TABLE expenses ADD COLUMN note TEXT;

CREATE INDEX idx_expenses_spent_on ON expenses(spent_on);
CREATE INDEX idx_expenses_category ON expenses(category);
