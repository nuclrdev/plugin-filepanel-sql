# sql.query — Run SQL on a database

Runs any SQL on a saved connection — `SELECT`, `INSERT`/`UPDATE`/`DELETE`, DDL,
stored procedures — and returns every result: rows for a query, an `updateCount`
for anything else.

This action can do anything the connection's user can, so Commander may ask the
user to approve it first. Say plainly what the SQL is for. To look at a table's
data, `sql.rows.read` is usually enough and safer.

**Know what you are running on:**

- Write SQL in the database's own dialect; `sql.connections.list` shows the driver.
- **Auto-commit is on.** Every statement is committed as soon as it finishes; there
  is no rollback afterwards. To change several things atomically, run the whole
  transaction in one call where the database allows it (`BEGIN; …; COMMIT;`), and
  never leave one open: the connection is shared by later calls.
- Prefer a `SELECT` showing what a change would touch before running the change
  itself, especially for `UPDATE` and `DELETE`. Always give them a `WHERE`.
- Several statements in one string work only where the driver allows it
  (PostgreSQL and SQL Server do; MySQL does not by default).

**Limits:**

- At most `maxRows` rows per result set (200 by default, 5000 at most), and about a
  million characters in all; `truncated` says when rows were left out. Add a
  `LIMIT`/`TOP` or narrow the query rather than raising `maxRows`.
- `timeoutSeconds` (60 by default) asks the database to stop the statement after
  that long; a cancelled call stops it too. Statements that had already finished
  stay committed.
- Values come back as in `sql.rows.read`: long text cut at 8000 characters, small
  binary values as `base64:…`, dates in ISO form, exact decimals as text.

Example: `{ "connection": "shop-prod", "sql": "SELECT status, COUNT(*) FROM orders GROUP BY status" }`
