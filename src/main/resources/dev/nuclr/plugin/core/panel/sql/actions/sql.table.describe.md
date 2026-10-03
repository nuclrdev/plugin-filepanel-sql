# sql.table.describe — Describe a table

Returns what a table or view looks like: its columns, primary key and the foreign
keys it holds. Use it before writing SQL against a table you have not seen.

- `schema` may be left out when the connection has a single user schema; otherwise
  the error lists the schemas to choose from.
- Names are matched exactly first, then ignoring case if that is unambiguous. The
  result spells them as the database does — use that spelling in `sql.query`.
- `foreignKeys` are the references *from* this table to others, one entry per
  column of a key.
- `countRows: true` adds `rowCount` from `SELECT COUNT(*)`. That reads the whole
  table on many databases, so ask for it only when you need the exact figure;
  `estimatedRows` is free.

Example: `{ "connection": "shop-prod", "schema": "public", "table": "orders" }`
