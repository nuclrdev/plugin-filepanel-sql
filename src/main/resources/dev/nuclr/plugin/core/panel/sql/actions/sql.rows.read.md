# sql.rows.read — Read rows from a table

Reads rows from one table or view without writing any SQL. It builds the `SELECT`
itself from column names it has checked against the table and values it passes as
parameters, so it can never change data. Prefer it to `sql.query` for looking at
data: it needs no approval to run in most permission modes.

- `columns` picks and orders the columns; omitted means all of them.
- `where` matches columns by equality, all conditions together:
  `{ "status": "paid", "customer_id": 42, "deleted_at": null }`. `null` means
  `IS NULL`. Ranges, `LIKE`, `OR` and joins need `sql.query`.
- `orderBy` lists columns to sort by; a leading `-` sorts that column descending:
  `["-created_at", "id"]`.
- At most `limit` rows come back (100 by default, 1000 at most). `truncated: true`
  means more follow: call again with `offset` increased by the rows returned. Use
  `orderBy` with a unique column when paging, or pages can overlap.
- Each row is a list of values in the order of `columns`. Long text is cut at 8000
  characters, small binary values come back as `base64:…`, larger ones as a size
  note, dates and times in ISO form, and exact decimals as text.

Example: `{ "connection": "shop-prod", "table": "orders", "where": { "status": "failed" }, "orderBy": ["-id"], "limit": 20 }`
