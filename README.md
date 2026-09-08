# 🗄️ Nuclr SQL Explorer

Browse a SQL database in [Nuclr Commander](https://nuclr.dev) as if it were a
filesystem. Schemas are folders, tables and views are folders, rows are files —
so the navigation, marking, quick view and copy keys you already use on files
work on your data too.

**Read-only by design.** This plugin never issues an `INSERT`, `UPDATE`,
`DELETE` or DDL statement. You can browse, filter, preview and export; you
cannot change anything.

```text
SQL Explorer
└── Shop (a saved connection)
    └── public (a schema)
        ├── users        (a table)
        │   ├── 1        (a row, named by its primary key)
        │   ├── 2
        │   └── 3
        └── active_users (a view, browsed the same way)
```

> 🔑 Rows are named by their primary key. A table without one falls back to the
> row's position in the listing (`00000000`, `00000001`, …).

## ✨ Features

- 🔌 **Connect** to PostgreSQL, MySQL / MariaDB, SQL Server, or anything with a
  JDBC driver you point at.
- 📂 **Navigate** connection → schema → table/view → row with the ordinary
  cursor keys.
- 👁️ **Quick view** (`Ctrl+Q`) a row as a field list or raw JSON, or a table as
  a grid.
- 🔍 **Filter and sort** any table with a `WHERE` / `ORDER BY` fragment, shown
  in the location bar so a narrowed listing is never a mystery.
- 📤 **Export to CSV** — a whole table streamed to a file, or just the rows you
  marked.
- 📋 **Copy as CSV** to the clipboard, plus tab-separated copies for pasting
  straight into a spreadsheet.
- 🔐 **Passwords in the OS credential store** — Windows Credential Manager,
  macOS Keychain, or the Linux Secret Service. Never in a config file.
- 🎨 **Theme-aware** throughout: selections pick up the same accent the file
  panel marks entries with.

## 🚀 Getting started

### 1. Open the panel

Press `Alt+F1` (left pane) or `Alt+F2` (right pane) and choose **SQL → SQL
Explorer**.

> 🐧 On Linux the window manager claims `Alt+F1`/`Alt+F2` first, so
> `Ctrl+Shift+1` and `Ctrl+Shift+2` do the same thing.

### 2. Create a connection

Press `F7`, or right-click and choose **New Connection…**.

| Field | What it is |
|---|---|
| **Name** | Whatever you want to call it in the panel |
| **Driver** | PostgreSQL, MySQL / MariaDB, SQL Server, or Custom |
| **Host / Port / Database** | Feed the **Build from fields** button |
| **JDBC URL** | What actually gets used — build it, or paste your own |
| **Username** | Leave blank and you are never asked for a password |
| **Password** | Only ever used to connect; never written to the profile |

Hit **Test Connection** to try it without saving anything, then **OK**.

> 💡 The first connection using a given driver downloads it from Maven Central
> and caches it under
> `~/.nuclr/plugins-data/dev.nuclr.plugin.core.panel.sql/drivers/`. That one
> connect needs network; later ones do not.

### 3. Browse

`Enter` on the connection to open it, then walk down through schemas and tables
to individual rows.

## ⌨️ Keys

### Function bar

| Key | Where | Does |
|---|---|---|
| `F7` | Explorer root, connections | New Connection… |
| `F4` | On a connection | Edit Connection… |
| `F8` | On a connection | Delete Connection (the profile, not the database) |
| `F5` | On a table, view or row | Copy to the other panel, as CSV |
| `Ctrl+Q` | On a table, view or row | Quick view |

### Sorting

| Key | Sorts by |
|---|---|
| `Ctrl+F3` | Name — or the row key inside a table |
| `Ctrl+F6` | Size — in a schema listing |
| `Ctrl+F7` | Unsorted — back to the order the database returned |
| `Ctrl+F12` | Sort dialog |

### Marking rows

Marking is the commander's own, and works here exactly as it does on files:

| Key | Does |
|---|---|
| `Insert` | Mark and move down |
| `Space` | Toggle the mark under the cursor |
| `+` | Mark a group by pattern |
| `*` | Invert every mark |

Marked rows are what **Export to CSV**, **Copy as CSV** and `F5` act on. With
nothing marked, they act on whatever the cursor is standing on.

## 🖱️ Right-click menu

**On a connection** — New / Edit / Duplicate / Delete Connection, and
**Disconnect**. The root listing's *Status* column tells you which connections
are currently open.

**On a table or view** — **Filter / Sort…**, **Count Rows**, **Export to
CSV…**, **Copy as CSV**.

**On rows** — **Export to CSV…** and **Copy as CSV**, acting on the marked rows.

## 👁️ Quick view (`Ctrl+Q`)

**On a row** — every column as a `name: value` list, with a **JSON** toggle for
when you want to paste it somewhere.

**On a table or view** — the first 500 rows as a grid. Select rows with click,
`Ctrl`-click and `Shift`-click, then right-click for:

| Item | Gives you |
|---|---|
| **Copy** | Tab-separated, exactly what `Ctrl+C` gives |
| **Copy with Headers** | The same, with column names on the first line |
| **Copy as CSV** | Properly quoted CSV, identical to what an export writes |
| **Select All** | Every row in the preview |

## 📏 Limits

Three caps keep the plugin honest about memory, and each one says when it is
reached rather than quietly truncating:

| Cap | Where | Why |
|---|---|---|
| **50,000 rows** | Table listing | A marker row appears at the end; add a filter to see past it |
| **500 rows** | Quick-view grid | It is a preview, not the browsing surface |
| **10,000 rows** | Whole-table **Copy as CSV** | The clipboard holds it all in memory at once — use **Export to CSV** for everything |

**Export to CSV has no cap.** It streams straight to the file, so table size is
bounded by your disk rather than by heap.

## 🔐 Credentials

Passwords go to the OS credential store through the host's
`NuclrCredentialStore`, keyed by connection. They are never written to
`profiles.json`, never logged, and never held longer than the connect needs.

If the OS store is unavailable — a locked keyring, no backend, denied access —
the plugin degrades to asking for the password each time rather than failing.
When "Remember password" cannot be honoured, it says so instead of silently
dropping it.

Only a connection with a **username** is ever asked for a password. A URL
carrying its own credentials, or a database that wants none, is left alone.

## 🔧 Drivers

| Driver | Ships with the plugin? |
|---|---|
| PostgreSQL | ⬇️ Downloaded on first use |
| MySQL / MariaDB | ⬇️ Downloaded on first use |
| SQL Server | ⬇️ Downloaded on first use |
| Custom | 📁 You pick the jar |

No JDBC driver is bundled, so the plugin stays small. Each driver is loaded in
its own child classloader and connected through `Driver.connect` directly, so
drivers cannot clash with one another or with the host.

**Use SSL** sends whichever property the selected driver actually understands —
`sslmode` for PostgreSQL, `sslMode`/`useSSL` for MySQL, `encrypt` for SQL
Server. A custom driver takes its encryption settings from the URL you wrote.

## 🏗️ Building

Needs Maven 3.9+ and JDK 25.

```bash
mvn clean verify     # compile, test, package and sign
mvn test             # tests only
```

The build produces `target/filepanel-sql-<version>.zip` plus a detached
`.zip.sig`. Drop both into Commander's `plugins/` directory and restart.

> ✍️ Signing reads a keystore path from the `jarsigner.*` properties in
> `pom.xml`. Use `mvn clean package` to build without signing.

### 🧪 Tests

The suite runs headless and needs no database server. It builds a real SQLite
database on disk and navigates it through the ordinary custom-driver path, so
listing, cancellation, CSV and lifecycle behaviour are exercised against real
JDBC rather than a mock.

SQLite is a **test-scoped dependency only** — it is not offered as a driver and
is not shipped in the bundle.

## 📦 What's in the bundle

| Entry | Type | Id |
|---|---|---|
| SQL Explorer | `FilePanel` | `dev.nuclr.plugin.core.panel.sql` |
| SQL Row Viewer | `QuickView` | `dev.nuclr.plugin.core.panel.sql.quickview` |

Built against `dev.nuclr:platform-sdk:5.0.0`.

## 📄 License

Apache License 2.0 — see [LICENSE](LICENSE).
