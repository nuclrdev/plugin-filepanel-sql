# sql.tables.list — List tables in a database

Lists the tables and views of a saved connection.

- Without `schema`, every schema is listed except the database's own system ones
  (`information_schema`, `pg_catalog`, `mysql`, `sys`, …). Name one to list it,
  system schemas included.
- `schemas` always holds every schema of the connection, so one call tells you
  which `schema` values exist.
- `nameLike` is a SQL `LIKE` pattern: `%` matches any run of characters, `_` any one
  character. `order%` finds `orders` and `order_items`.
- `estimatedRows` is the database's own cheap estimate (PostgreSQL, MySQL/MariaDB,
  SQL Server); it is `null` elsewhere and can be stale. For an exact count use
  `sql.table.describe` with `countRows: true`.
- At most `limit` tables come back (500 by default); `truncated` says when there
  were more. Narrow with `schema` or `nameLike`.

Example: `{ "connection": "shop-prod", "schema": "public", "nameLike": "order%" }`
