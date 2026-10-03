# sql.connections.list — List saved database connections

Lists the database connections the user has saved in Commander's SQL panel. Call it
first: every other `sql.*` action names its connection by the `id` or `name`
returned here.

- Only saved connections can be used. To work with a new database, ask the user to
  add it in the SQL panel (F7 on the connection list).
- A connection is opened on first use. If it needs a password that is not stored,
  **the user** is asked in Commander — never you. If they dismiss the prompt, the
  action fails and says so.
- Passwords are never returned. Password-like parts of the JDBC URL
  (`password=…`, `user:secret@host`) come back as `***`.
- The `driver` tells you the SQL dialect to use with `sql.query`.
