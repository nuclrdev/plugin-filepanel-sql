/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.

*/
package dev.nuclr.plugin.core.panel.sql;

import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrContextMenuItem;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.platform.plugin.QuickViewNuclrPlugin;
import dev.nuclr.plugin.core.panel.sql.connection.ConnectionProfile;
import dev.nuclr.plugin.core.panel.sql.connection.ConnectionProfileStore;
import dev.nuclr.plugin.core.panel.sql.connection.ConnectionRegistry;
import dev.nuclr.plugin.core.panel.sql.connection.CredentialStore;
import dev.nuclr.plugin.core.panel.sql.csv.CsvExport;
import dev.nuclr.plugin.core.panel.sql.panel.ConnectionEditorDialog;
import dev.nuclr.plugin.core.panel.sql.resource.ResourceKind;
import dev.nuclr.plugin.core.panel.sql.resource.SqlNuclrResource;
import lombok.extern.slf4j.Slf4j;

/**
 * Browses a SQL database as a read-only virtual filesystem:
 * {@code Explorer root -> Connection -> Schema -> Table/View -> Row}.
 *
 * <p>Like every {@link FilePanelNuclrPlugin}, this class is a pure data/behaviour
 * provider — the host (commander's own {@code FilePanel}/{@code FilePanelTableModel})
 * owns the actual {@code JTable}; there is no plugin-side panel to build here. The only
 * Swing UI this plugin owns is the modal dialogs it pops up from {@link #act}.
 */
@Slf4j
public final class SqlFilePanelPlugin implements FilePanelNuclrPlugin {

	/** Rows fetched per table listing before the cap marker is appended. */
	private static final int ROW_CAP = 50_000;

	/**
	 * Rows a whole-table "Copy as CSV" will put on the clipboard. Unlike a file
	 * export, which streams, the clipboard holds the lot in memory at once — so this
	 * is bounded, and says so when it stops early.
	 */
	private static final int ClipboardRowCap = 10_000;

	private static final String ActionNewConnection = "sql.newConnection";
	private static final String ActionEditConnection = "sql.editConnection";
	private static final String ActionDeleteConnection = "sql.deleteConnection";
	private static final String ActionDisconnect = "sql.disconnect";
	private static final String ActionFilterSort = "sql.filterSort";
	private static final String ActionDuplicateConnection = "sql.duplicateConnection";
	private static final String ActionCountRows = "sql.countRows";
	private static final String ActionExportCsv = "sql.exportCsv";
	private static final String ActionCopyCsv = "sql.copyCsv";
	/** Must match Commander's F5 action protocol value (see {@code LocalFileSystemPlugin}). */
	private static final String ActionCopy = "filepanel.copy";
	private static final String ActionAcceptCopy = "accept.copy";

	private record QueryState(String where, String orderBy) {
	}

	/** The user dismissed the password prompt; the listing simply comes back empty. */
	private static final class ConnectCancelled extends Exception {

		private static final long serialVersionUID = 1L;

		ConnectCancelled() {
			super("cancelled", null, false, false);
		}
	}

	/** What {@link SqlFilePanelPlugin#promptForPassword} came back with. */
	private record PasswordPrompt(String password, boolean remember) {
	}

	private final String uuid = UUID.randomUUID().toString();
	private final Map<String, QueryState> queryStateByTableUuid = new ConcurrentHashMap<>();

	private NuclrPluginContext context;
	private ConnectionProfileStore profileStore;
	private CredentialStore credentialStore;
	private ConnectionRegistry connectionRegistry;

	private volatile boolean focused;
	private volatile SqlNuclrResource currentResource;

	// ------------------------------------------------------------------
	// Lifecycle
	// ------------------------------------------------------------------

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
		this.profileStore = new ConnectionProfileStore(context.getSettings());
		this.credentialStore = new CredentialStore(context.getCredentialStore());
		this.connectionRegistry = new ConnectionRegistry();
		this.currentResource = SqlNuclrResource.explorerRoot();
		log.info("SQL Explorer panel plugin loaded");
	}

	@Override
	public void init() {
		log.info("SQL Explorer panel plugin inited");
	}

	@Override
	public void unload() {
		connectionRegistry.disconnectAll();
		log.info("SQL Explorer panel plugin unloaded");
	}

	@Override
	public NuclrPluginContext getContext() {
		return context;
	}

	@Override
	public String uuid() {
		return uuid;
	}

	@Override
	public boolean onFocusGained() {
		focused = true;
		return true;
	}

	@Override
	public void onFocusLost() {
		focused = false;
	}

	@Override
	public boolean isFocused() {
		return focused;
	}

	@Override
	public void closeResource() {
		// Navigation is driven entirely by openResource; nothing to release per-resource.
	}

	@Override
	public NuclrResource getCurrentResource() {
		return currentResource;
	}

	@Override
	public boolean supports(NuclrResource resource) {
		return resource instanceof SqlNuclrResource;
	}

	// ------------------------------------------------------------------
	// Alt+F1 / Alt+F2 drive picker
	// ------------------------------------------------------------------

	@Override
	public MenuItemsHolder getPluginMenuItems() {
		var item = new MenuItem();
		item.setText("SQL Explorer");
		item.setPath(SqlNuclrResource.explorerRoot());
		item.setUuid(PluginIds.PLUGIN_ID + ":explorer");

		var holder = new MenuItemsHolder();
		holder.setTitle("SQL");
		holder.setMenuItems(List.of(item));
		return holder;
	}

	// ------------------------------------------------------------------
	// Listing
	// ------------------------------------------------------------------

	@Override
	public NuclrResourceData openResource(NuclrResource resourceToOpen, AtomicBoolean cancelled) {
		return openResource(resourceToOpen, cancelled, null);
	}

	@Override
	public NuclrResourceData openResource(NuclrResource resourceToOpen, AtomicBoolean cancelled, EntrySink sink) {
		if (!(resourceToOpen instanceof SqlNuclrResource sql)) {
			return null;
		}
		try {
			return switch (sql.getKind()) {
				case EXPLORER_ROOT -> listConnections(sink);
				case CONNECTION -> listSchemas(sql, cancelled, sink);
				case SCHEMA -> listTablesAndViews(sql, cancelled, sink);
				case TABLE, VIEW -> listRows(sql, cancelled, sink);
				case ROW, ROW_LIMIT_MARKER -> null;
			};
		} catch (ConnectCancelled cancelledByUser) {
			return new NuclrResourceData();
		} catch (Exception e) {
			log.error("Failed to open SQL resource {}: {}", sql.getFullPath(), e.getMessage(), e);
			showErrorDialog("SQL Explorer", "Could not open \"" + sql.getName() + "\": " + e.getMessage());
			return new NuclrResourceData();
		}
	}

	private NuclrResourceData listConnections(EntrySink sink) {
		currentResource = SqlNuclrResource.explorerRoot();
		var data = new NuclrResourceData();
		// "Status" earns its column: Disconnect is offered on the right-click menu, and
		// without this there is no way to tell which connections it would apply to.
		List<String> columns = List.of("Name", "Status", "Driver", "Host");
		data.getColumnNames().addAll(columns);
		if (sink != null) {
			sink.columns(columns);
		}
		for (ConnectionProfile profile : profileStore.loadAll()) {
			var entry = SqlNuclrResource.connection(profile);
			entry.getMetadata().put("Status", connectionRegistry.isConnected(profile.getId()) ? "Connected" : "");
			data.getEntries().add(entry);
			if (sink != null) {
				sink.add(entry);
			}
		}
		return data;
	}

	private NuclrResourceData listSchemas(SqlNuclrResource connectionRes, AtomicBoolean cancelled, EntrySink sink)
			throws Exception {
		currentResource = connectionRes;
		String profileId = connectionRes.getProfileId();
		ConnectionProfile profile = profileStore.findById(profileId);
		if (profile == null) {
			return new NuclrResourceData();
		}

		ConnectionRegistry.Session session = ensureConnected(profileId);
		Connection connection = session.connection();

		var data = new NuclrResourceData();
		List<String> columns = List.of("Name");
		data.getColumnNames().addAll(columns);
		if (sink != null) {
			sink.columns(columns);
		}

		var up = SqlNuclrResource.parentEntry(SqlNuclrResource.explorerRoot());
		data.getEntries().add(up);
		if (sink != null) {
			sink.add(up);
		}

		List<String> schemaNames = new ArrayList<>();
		try (ResultSet rs = connection.getMetaData().getSchemas()) {
			while (rs.next()) {
				if (cancelled != null && cancelled.get()) {
					return data;
				}
				schemaNames.add(rs.getString("TABLE_SCHEM"));
			}
		} catch (SQLException e) {
			log.debug("getSchemas() unavailable for {}: {}", profile.getName(), e.getMessage());
		}
		if (schemaNames.isEmpty()) {
			// Drivers with no real schema concept, or where the database already is the
			// schema (MySQL): fall back to the connection's own catalog.
			String catalog = null;
			try {
				catalog = connection.getCatalog();
			} catch (SQLException ignored) {
				// fall through to the "main" default below
			}
			schemaNames.add(catalog != null && !catalog.isBlank() ? catalog : "main");
		}

		for (String schemaName : schemaNames) {
			if (cancelled != null && cancelled.get()) {
				break;
			}
			var entry = SqlNuclrResource.schema(profileId, schemaName);
			data.getEntries().add(entry);
			if (sink != null) {
				sink.add(entry);
			}
		}
		return data;
	}

	private NuclrResourceData listTablesAndViews(SqlNuclrResource schemaRes, AtomicBoolean cancelled, EntrySink sink)
			throws Exception {
		currentResource = schemaRes;
		String profileId = schemaRes.getProfileId();
		String schemaName = schemaRes.getSchemaName();
		ConnectionProfile profile = profileStore.findById(profileId);
		if (profile == null) {
			return new NuclrResourceData();
		}

		ConnectionRegistry.Session session = ensureConnected(profileId);
		Connection connection = session.connection();

		var data = new NuclrResourceData();
		List<String> columns = List.of("Name", "Type", "Size", "Date");
		data.getColumnNames().addAll(columns);
		if (sink != null) {
			sink.columns(columns);
		}

		var up = SqlNuclrResource.parentEntry(SqlNuclrResource.connection(profile));
		data.getEntries().add(up);
		if (sink != null) {
			sink.add(up);
		}

		Map<String, Long> rowCounts = TableStats.estimate(connection, profile.getJdbcUrl(), schemaName);

		String catalog = null;
		try {
			catalog = connection.getCatalog();
		} catch (SQLException ignored) {
			// left null; most drivers accept a null catalog filter fine
		}

		DatabaseMetaData meta = connection.getMetaData();
		try (ResultSet rs = meta.getTables(catalog, schemaName, "%", new String[] { "TABLE", "VIEW" })) {
			while (rs.next()) {
				if (cancelled != null && cancelled.get()) {
					break;
				}
				String tableName = rs.getString("TABLE_NAME");
				boolean isView = "VIEW".equalsIgnoreCase(rs.getString("TABLE_TYPE"));
				String quotedRef = quote(session.quoteString(), schemaName) + "." + quote(session.quoteString(), tableName);
				var entry = SqlNuclrResource.table(
						profileId, schemaName, tableName, isView, quotedRef,
						() -> connectionRegistry.getOpenConnection(profileId));
				entry.getMetadata().put("Type", isView ? "View" : "Table");
				Long count = rowCounts.get(tableName);
				if (count != null) {
					entry.getMetadata().put("Size", count.toString());
				}
				data.getEntries().add(entry);
				if (sink != null) {
					sink.add(entry);
				}
			}
		}
		return data;
	}

	private NuclrResourceData listRows(SqlNuclrResource tableRes, AtomicBoolean cancelled, EntrySink sink)
			throws Exception {
		currentResource = tableRes;
		String profileId = tableRes.getProfileId();
		ConnectionRegistry.Session session = ensureConnected(profileId);
		Connection connection = session.connection();

		var data = new NuclrResourceData();
		var up = SqlNuclrResource.parentEntry(SqlNuclrResource.schema(profileId, tableRes.getSchemaName()));
		data.getEntries().add(up);

		List<String> pkColumns = primaryKeyColumns(connection, tableRes.getSchemaName(), tableRes.getTableName());
		QueryState state = queryState(tableRes.getUuid());

		StringBuilder sql = new StringBuilder("SELECT * FROM ").append(tableRes.getQuotedTableRef());
		if (state.where() != null) {
			sql.append(" WHERE ").append(state.where());
		}
		if (state.orderBy() != null) {
			sql.append(" ORDER BY ").append(state.orderBy());
		}

		try (Statement statement = connection.createStatement()) {
			statement.setMaxRows(ROW_CAP + 1);
			try (ResultSet rs = statement.executeQuery(sql.toString())) {
				ResultSetMetaData meta = rs.getMetaData();
				int columnCount = meta.getColumnCount();
				List<String> columns = new ArrayList<>(columnCount);
				for (int i = 1; i <= columnCount; i++) {
					columns.add(meta.getColumnLabel(i));
				}
				data.getColumnNames().addAll(columns);
				if (sink != null) {
					sink.columns(columns);
					sink.add(up);
				}

				int rowIndex = 0;
				boolean truncated = false;
				while (rs.next()) {
					if (cancelled != null && cancelled.get()) {
						break;
					}
					if (rowIndex >= ROW_CAP) {
						truncated = true;
						break;
					}
					Map<String, Object> values = new LinkedHashMap<>();
					for (int i = 1; i <= columnCount; i++) {
						values.put(columns.get(i - 1), rs.getObject(i));
					}
					String rowKey = pkColumns.isEmpty()
							? String.format("%08d", rowIndex)
							: pkColumns.stream().map(c -> String.valueOf(values.get(c))).collect(Collectors.joining("|"));
					var rowRes = SqlNuclrResource.row(
							profileId, tableRes.getSchemaName(), tableRes.getTableName(), rowKey, columns, values);
					data.getEntries().add(rowRes);
					if (sink != null) {
						sink.add(rowRes);
					}
					rowIndex++;
				}
				if (truncated) {
					var marker = SqlNuclrResource.rowLimitMarker(
							profileId, tableRes.getSchemaName(), tableRes.getTableName(), ROW_CAP);
					data.getEntries().add(marker);
					if (sink != null) {
						sink.add(marker);
					}
				}
			}
		}
		return data;
	}

	private static List<String> primaryKeyColumns(Connection connection, String schema, String table) {
		var byKeySeq = new java.util.TreeMap<Short, String>();
		try (ResultSet rs = connection.getMetaData().getPrimaryKeys(null, schema, table)) {
			while (rs.next()) {
				byKeySeq.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
			}
		} catch (SQLException e) {
			log.debug("Could not read primary key for {}.{}: {}", schema, table, e.getMessage());
		}
		return new ArrayList<>(byKeySeq.values());
	}

	private static String quote(String quoteChar, String identifier) {
		return quoteChar + identifier.replace(quoteChar, quoteChar + quoteChar) + quoteChar;
	}

	private QueryState queryState(String tableUuid) {
		return queryStateByTableUuid.computeIfAbsent(tableUuid, k -> new QueryState(null, null));
	}

	/**
	 * The live session for {@code profileId}, connecting on first use.
	 *
	 * <p>Only a profile that carries a username is ever asked for a password: a URL that
	 * embeds its own credentials, or a database that wants none, has nothing to prompt
	 * for, and prompting anyway would put a pointless dialog in front of every single
	 * connect. Leaving the prompt blank means "no password" rather than cancelling, so a
	 * trust/peer-authenticated login still gets through.
	 */
	private ConnectionRegistry.Session ensureConnected(String profileId) throws Exception {
		ConnectionRegistry.Session existing = connectionRegistry.get(profileId);
		if (existing != null) {
			return existing;
		}
		ConnectionProfile profile = profileStore.findById(profileId);
		if (profile == null) {
			throw new IllegalStateException("Unknown connection: " + profileId);
		}
		String password = credentialStore.loadPassword(profileId);
		boolean remember = false;
		if (password == null && profile.needsPassword()) {
			PasswordPrompt prompt = promptForPassword(profile);
			if (prompt == null) {
				throw new ConnectCancelled();
			}
			password = prompt.password();
			remember = prompt.remember();
		}

		// A single error dialog for the whole failure is raised by openResource; adding one
		// here as well would make every failed connect pop two.
		ConnectionRegistry.Session session = connectionRegistry.connect(profile, password);
		if (remember && password != null) {
			credentialStore.savePassword(profileId, password.toCharArray());
		}
		return session;
	}

	// ------------------------------------------------------------------
	// Location / selection text
	// ------------------------------------------------------------------

	@Override
	public String getCurrentLocationDisplayText() {
		SqlNuclrResource r = currentResource;
		if (r == null) {
			return "SQL Explorer";
		}
		return switch (r.getKind()) {
			case EXPLORER_ROOT -> "SQL Explorer";
			case CONNECTION -> r.getName();
			case SCHEMA -> connectionDisplayName(r.getProfileId()) + " / " + r.getSchemaName();
			case TABLE, VIEW -> connectionDisplayName(r.getProfileId()) + " / " + r.getSchemaName() + " / "
					+ r.getTableName() + filterSuffix(r);
			default -> r.getFullPath();
		};
	}

	/**
	 * A table listing narrowed by Filter / Sort looks exactly like a small table, so the
	 * location bar spells out that a filter is on and what it is.
	 */
	private String filterSuffix(SqlNuclrResource tableResource) {
		QueryState state = queryStateByTableUuid.get(tableResource.getUuid());
		if (state == null || (state.where() == null && state.orderBy() == null)) {
			return "";
		}
		var parts = new ArrayList<String>(2);
		if (state.where() != null) {
			parts.add("WHERE " + state.where());
		}
		if (state.orderBy() != null) {
			parts.add("ORDER BY " + state.orderBy());
		}
		return " [" + String.join("; ", parts) + "]";
	}

	@Override
	public String getSelectionSummaryText(List<NuclrResource> selectedResources) {
		if (selectedResources == null || selectedResources.isEmpty()) {
			return "";
		}
		long rows = selectedResources.stream()
				.filter(r -> r instanceof SqlNuclrResource sql && sql.getKind() == ResourceKind.ROW)
				.count();
		if (rows > 0) {
			return rows + (rows == 1 ? " row selected" : " rows selected");
		}
		return selectedResources.size() + (selectedResources.size() == 1 ? " item selected" : " items selected");
	}

	private String connectionDisplayName(String profileId) {
		ConnectionProfile profile = profileStore.findById(profileId);
		return profile != null ? profile.getName() : profileId;
	}

	// ------------------------------------------------------------------
	// Menus (F-key bar + right-click) and actions
	// ------------------------------------------------------------------

	/**
	 * What the F-key bar and the context menu should describe: the resource under the
	 * cursor, or — when there is nothing under it — wherever the panel is standing.
	 *
	 * <p>The host asks about the focused resource, and a listing with nothing in it has
	 * none. Every level below the root always carries its {@code ".."} entry, so the one
	 * listing that can actually come up empty is the explorer root before any connection
	 * has been saved — precisely when "New Connection" is the only thing the user needs.
	 * Without this fallback the bar shows no F7 there, and an unbound slot fires nothing.
	 */
	private SqlNuclrResource menuTarget(NuclrResource resource) {
		return resource instanceof SqlNuclrResource sql ? sql : currentResource;
	}

	/** Whether the cursor really is on a saved connection, rather than us having fallen back. */
	private static boolean isConnectionFocused(NuclrResource resource) {
		return resource instanceof SqlNuclrResource sql && sql.getKind() == ResourceKind.CONNECTION;
	}

	/** Event-type prefix the commander parses into one of its own sort comparators. */
	private static final String SortEventPrefix = "filepanel.sort:";

	private static NuclrMenuResource sort(String label, String functionKey, String criterion, String columnLabel) {
		return new NuclrMenuResource(label, functionKey,
				SortEventPrefix + criterion + (columnLabel == null ? "" : ":" + columnLabel));
	}

	/**
	 * The sorts this panel can back, shown on the function bar under Ctrl exactly as the
	 * local-filesystem panel's are. Which ones make sense depends on the listing rather
	 * than on the entry under the cursor, so they come from where the panel is standing:
	 * a schema listing has a Size column worth sorting on, a row listing does not.
	 *
	 * <p>"Unsort" matters more here than in a file panel — it restores the order the
	 * database returned the rows in, which is the order the query actually asked for.
	 */
	private void addSortItems(List<NuclrMenuResource> items) {
		SqlNuclrResource here = currentResource;
		if (here == null) {
			return;
		}
		switch (here.getKind()) {
			case EXPLORER_ROOT -> items.add(sort("Name", "Ctrl+F3", "name", "Name"));
			case CONNECTION -> items.add(sort("Name", "Ctrl+F3", "name", "Name"));
			case SCHEMA -> {
				items.add(sort("Name", "Ctrl+F3", "name", "Name"));
				items.add(sort("Size", "Ctrl+F6", "size", "Size"));
			}
			// A row's name is its key, and the listing's columns are the table's own, so
			// there is no column for the commander's generic comparators to point at.
			case TABLE, VIEW -> items.add(sort("Key", "Ctrl+F3", "name", null));
			default -> {
				return;
			}
		}
		items.add(sort("Unsort", "Ctrl+F7", "unsorted", null));
		items.add(sort("Sort", "Ctrl+F12", "dialog", null));
	}

	@Override
	public List<NuclrMenuResource> menuItems(NuclrResource resource) {
		var items = new ArrayList<NuclrMenuResource>();
		addSortItems(items);
		SqlNuclrResource target = menuTarget(resource);
		if (target == null) {
			return items;
		}
		switch (target.getKind()) {
			case EXPLORER_ROOT, CONNECTION -> {
				items.add(new NuclrMenuResource("New Connection", "F7", ActionNewConnection));
				// Editing and deleting need a connection to act on, and the cursor is what
				// names it — offering them with nothing selected would only bind dead keys.
				if (isConnectionFocused(resource)) {
					items.add(new NuclrMenuResource("Edit Connection", "F4", ActionEditConnection));
					items.add(new NuclrMenuResource("Delete Connection", "F8", ActionDeleteConnection));
				}
			}
			case TABLE, VIEW, ROW -> items.add(new NuclrMenuResource("Copy", "F5", ActionCopy));
			default -> {
				// SCHEMA and the row-limit marker offer nothing on the function-key bar.
			}
		}
		return items;
	}

	@Override
	public List<NuclrContextMenuItem> contextMenuItems(NuclrResource focusedResource, List<NuclrResource> selectedResources) {
		// A right-click on empty panel space focuses nothing, so fall back to where the
		// panel is standing — that keeps "New Connection…" reachable on an empty root and
		// offers the level's own items everywhere else, rather than one blanket entry.
		SqlNuclrResource target = menuTarget(focusedResource);
		if (target == null) {
			return List.of();
		}
		var newConnection = NuclrContextMenuItem.builder().label("New Connection…").actionType(ActionNewConnection).build();
		return switch (target.getKind()) {
			case EXPLORER_ROOT -> List.of(newConnection);
			case CONNECTION -> {
				if (!isConnectionFocused(focusedResource)) {
					yield List.of(newConnection);
				}
				String profileId = ((SqlNuclrResource) focusedResource).getProfileId();
				yield List.of(
						newConnection,
						NuclrContextMenuItem.builder().label("Edit Connection…").actionType(ActionEditConnection).build(),
						NuclrContextMenuItem.builder().label("Duplicate Connection").actionType(ActionDuplicateConnection)
								.build(),
						NuclrContextMenuItem.builder().label("Delete Connection…").actionType(ActionDeleteConnection)
								.destructive(true).build(),
						NuclrContextMenuItem.separator(),
						NuclrContextMenuItem.builder().label("Disconnect").actionType(ActionDisconnect)
								.enabled(connectionRegistry.isConnected(profileId)).build());
			}
			case TABLE, VIEW -> List.of(
					NuclrContextMenuItem.builder().label("Filter / Sort…").actionType(ActionFilterSort).build(),
					NuclrContextMenuItem.builder().label("Count Rows").actionType(ActionCountRows).build(),
					NuclrContextMenuItem.separator(),
					NuclrContextMenuItem.builder().label("Export to CSV…").actionType(ActionExportCsv).build(),
					NuclrContextMenuItem.builder().label("Copy as CSV").actionType(ActionCopyCsv).build());
			case ROW -> List.of(
					NuclrContextMenuItem.builder().label("Export to CSV…").actionType(ActionExportCsv).build(),
					NuclrContextMenuItem.builder().label("Copy as CSV").actionType(ActionCopyCsv).build());
			default -> List.of();
		};
	}

	@Override
	public void act(BaseNuclrPlugin other, String actionType, List<NuclrResource> selectedResources,
			NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {
		switch (actionType) {
			case ActionNewConnection -> handleNewConnection(data);
			case ActionEditConnection -> handleEditConnection(focusedResource, data);
			case ActionDeleteConnection -> handleDeleteConnection(focusedResource, selectedResources, data);
			case ActionDisconnect -> handleDisconnect(focusedResource);
			case ActionFilterSort -> handleFilterSort(focusedResource, data);
			case ActionDuplicateConnection -> handleDuplicateConnection(focusedResource, data);
			case ActionCountRows -> handleCountRows(focusedResource);
			case ActionExportCsv -> handleExportCsv(selectedResources, focusedResource);
			case ActionCopyCsv -> handleCopyCsv(selectedResources, focusedResource);
			case ActionCopy -> handleCopy(other, selectedResources, focusedResource, data, callback);
			default -> {
				// Database content is read-only: no other action is ever offered, so nothing
				// else should reach here — but no-op rather than fail if it somehow does.
			}
		}
	}

	private void handleCopy(BaseNuclrPlugin other, List<NuclrResource> selectedResources, NuclrResource focusedResource,
			Map<String, Object> data, NuclrPluginCallback callback) {
		if (other == null || other.uuid().equals(this.uuid) || other instanceof QuickViewNuclrPlugin) {
			return; // no in-place copy target for a read-only virtual panel
		}
		other.act(null, ActionAcceptCopy, selectedResources, focusedResource, data, callback);
	}

	private void handleNewConnection(Map<String, Object> data) {
		ConnectionEditorDialog.Result result = ConnectionEditorDialog.showNew(activeWindow());
		if (result == null) {
			return;
		}
		ConnectionProfile profile = profileStore.add(result.profile());
		if (result.rememberPassword() && result.password() != null) {
			warnIfNotStored(credentialStore.savePassword(profile.getId(), result.password().toCharArray()));
		}
		putRefresh(data);
	}

	private void handleEditConnection(NuclrResource focusedResource, Map<String, Object> data) {
		String profileId = connectionIdOf(focusedResource);
		if (profileId == null) {
			return;
		}
		ConnectionProfile existing = profileStore.findById(profileId);
		if (existing == null) {
			return;
		}
		boolean hasStoredPassword = credentialStore.hasPassword(profileId);
		ConnectionEditorDialog.Result result = ConnectionEditorDialog.showEdit(activeWindow(), existing, hasStoredPassword);
		if (result == null) {
			return;
		}
		connectionRegistry.disconnect(profileId); // the URL/credentials may have changed
		profileStore.update(result.profile());
		if (result.password() != null && result.rememberPassword()) {
			warnIfNotStored(credentialStore.savePassword(profileId, result.password().toCharArray()));
		} else if (!result.rememberPassword()) {
			credentialStore.deletePassword(profileId);
		}
		putRefresh(data);
	}

	private void handleDeleteConnection(NuclrResource focusedResource, List<NuclrResource> selectedResources,
			Map<String, Object> data) {
		var ids = new LinkedHashSet<String>();
		List<NuclrResource> targets = selectedResources != null && !selectedResources.isEmpty()
				? selectedResources
				: focusedResource != null ? List.of(focusedResource) : List.of();
		for (NuclrResource r : targets) {
			String id = connectionIdOf(r);
			if (id != null) {
				ids.add(id);
			}
		}
		if (ids.isEmpty()) {
			return;
		}
		int choice = JOptionPane.showConfirmDialog(activeWindow(),
				"Delete " + ids.size() + (ids.size() == 1 ? " connection" : " connections")
						+ "? This only removes the saved profile — the database itself is untouched.",
				"Delete Connection", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (choice != JOptionPane.YES_OPTION) {
			return;
		}
		for (String id : ids) {
			connectionRegistry.disconnect(id);
			profileStore.delete(id);
			credentialStore.deletePassword(id);
		}
		putRefresh(data);
	}

	/**
	 * Clone a saved connection, so a second database on the same server does not have to
	 * be typed out again. The copy gets its own id, and the stored password is copied
	 * across with it — a duplicate that cannot connect would not save anyone anything.
	 */
	private void handleDuplicateConnection(NuclrResource focusedResource, Map<String, Object> data) {
		String profileId = connectionIdOf(focusedResource);
		if (profileId == null) {
			return;
		}
		ConnectionProfile original = profileStore.findById(profileId);
		if (original == null) {
			return;
		}
		ConnectionProfile copy = ConnectionProfile.builder()
				.name(uniqueCopyName(original.getName()))
				.driverKey(original.getDriverKey())
				.customDriverClassName(original.getCustomDriverClassName())
				.customDriverJarPath(original.getCustomDriverJarPath())
				.jdbcUrl(original.getJdbcUrl())
				.host(original.getHost())
				.port(original.getPort())
				.database(original.getDatabase())
				.username(original.getUsername())
				.useSsl(original.isUseSsl())
				.build();
		ConnectionProfile saved = profileStore.add(copy);

		String password = credentialStore.loadPassword(profileId);
		if (password != null) {
			credentialStore.savePassword(saved.getId(), password.toCharArray());
		}
		putRefresh(data);
	}

	/** "Shop" becomes "Shop (copy)", then "Shop (copy 2)" — never a second identical name. */
	private String uniqueCopyName(String original) {
		var taken = profileStore.loadAll().stream().map(ConnectionProfile::getName).collect(Collectors.toSet());
		String candidate = original + " (copy)";
		for (int n = 2; taken.contains(candidate); n++) {
			candidate = original + " (copy " + n + ")";
		}
		return candidate;
	}

	/**
	 * The Size column is blank wherever the dialect offers no cheap estimate, so this is
	 * the way to actually find out. {@code COUNT(*)} can take a while on a large table,
	 * so it runs off the event thread and reports when it lands.
	 */
	private void handleCountRows(NuclrResource focusedResource) {
		if (!(focusedResource instanceof SqlNuclrResource sql)
				|| (sql.getKind() != ResourceKind.TABLE && sql.getKind() != ResourceKind.VIEW)) {
			return;
		}
		Thread.ofVirtual().name("sql-count-rows").start(() -> {
			try (Statement statement = sql.openConnection().createStatement();
					ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + sql.getQuotedTableRef())) {
				long count = rs.next() ? rs.getLong(1) : 0;
				showMessageDialog("Count Rows",
						sql.getSchemaName() + "." + sql.getTableName() + " holds "
								+ NumberFormat.getIntegerInstance().format(count)
								+ (count == 1 ? " row." : " rows."));
			} catch (Exception e) {
				log.debug("Could not count rows in {}: {}", sql.getFullPath(), e.getMessage());
				showErrorDialog("Count Rows", "Could not count the rows in \"" + sql.getName() + "\": " + e.getMessage());
			}
		});
	}

	// ------------------------------------------------------------------
	// CSV out: to a file, or to the clipboard
	// ------------------------------------------------------------------

	/**
	 * What a CSV action should act on. Selected rows win over the focused entry, so
	 * picking three rows and asking for CSV gives those three; with no rows picked, a
	 * focused table means the whole table.
	 */
	private record CsvTarget(SqlNuclrResource table, List<SqlNuclrResource> rows) {

		boolean isWholeTable() {
			return rows.isEmpty();
		}

		String suggestedFileName() {
			String base = table.getTableName() != null ? table.getTableName() : table.getName();
			return base.replaceAll("[^A-Za-z0-9._-]", "_") + ".csv";
		}
	}

	private CsvTarget csvTarget(List<NuclrResource> selectedResources, NuclrResource focusedResource) {
		var rows = new ArrayList<SqlNuclrResource>();
		for (NuclrResource r : selectedResources == null ? List.<NuclrResource>of() : selectedResources) {
			if (r instanceof SqlNuclrResource sql && sql.getKind() == ResourceKind.ROW) {
				rows.add(sql);
			}
		}
		if (focusedResource instanceof SqlNuclrResource focused) {
			if (focused.getKind() == ResourceKind.TABLE || focused.getKind() == ResourceKind.VIEW) {
				return new CsvTarget(focused, rows);
			}
			if (focused.getKind() == ResourceKind.ROW) {
				if (rows.isEmpty()) {
					rows.add(focused);
				}
				// A row knows its table by name but carries no live handle, so a row-only
				// selection is served entirely from the values already in hand.
				return new CsvTarget(focused, rows);
			}
		}
		return rows.isEmpty() ? null : new CsvTarget(rows.get(0), rows);
	}

	/** Header plus one line per selected row, from the values the listing already holds. */
	private static String selectedRowsCsv(CsvTarget target) {
		List<String> columns = target.rows().get(0).getColumnOrder();
		return CsvExport.rowsCsv(columns, target.rows().stream().map(NuclrResource::getMetadata).toList());
	}

	/**
	 * The CSV for this selection, or {@code null} when there is nothing to export. Kept
	 * apart from the dialogs and the clipboard so what gets written can be exercised
	 * without either.
	 *
	 * @param rowCap  stop after this many rows, or {@link CsvExport#NoRowCap} for all
	 * @param outcome receives how much was written and whether the cap cut it short
	 */
	String csvFor(List<NuclrResource> selectedResources, NuclrResource focusedResource, int rowCap,
			CsvExport.Written[] outcome) {
		CsvTarget target = csvTarget(selectedResources, focusedResource);
		if (target == null) {
			return null;
		}
		if (!target.isWholeTable()) {
			outcome[0] = new CsvExport.Written(target.rows().size(), false);
			return selectedRowsCsv(target);
		}
		try {
			return CsvExport.tableCsv(target.table().openConnection(), target.table().getQuotedTableRef(),
					rowCap, outcome);
		} catch (Exception e) {
			log.error("Could not render {} as CSV: {}", target.table().getFullPath(), e.getMessage(), e);
			return null;
		}
	}

	/** Every row, for a caller that does not care about a cap. */
	String csvFor(List<NuclrResource> selectedResources, NuclrResource focusedResource) {
		return csvFor(selectedResources, focusedResource, CsvExport.NoRowCap, new CsvExport.Written[1]);
	}

	/** The file name to offer in the save dialog, or {@code null} when nothing is exportable. */
	String suggestedCsvName(List<NuclrResource> selectedResources, NuclrResource focusedResource) {
		CsvTarget target = csvTarget(selectedResources, focusedResource);
		return target == null ? null : target.suggestedFileName();
	}

	/**
	 * Write the selection to {@code destination}. A whole table streams straight through
	 * to the file, so its size is bounded by the disk rather than by heap.
	 *
	 * @return how many rows were written
	 */
	long exportCsvTo(List<NuclrResource> selectedResources, NuclrResource focusedResource, Path destination)
			throws Exception {
		CsvTarget target = csvTarget(selectedResources, focusedResource);
		if (target == null) {
			return 0;
		}
		if (target.isWholeTable()) {
			return CsvExport.writeTable(target.table().openConnection(), target.table().getQuotedTableRef(),
					destination).rows();
		}
		Files.writeString(destination, selectedRowsCsv(target), StandardCharsets.UTF_8);
		return target.rows().size();
	}

	private void handleExportCsv(List<NuclrResource> selectedResources, NuclrResource focusedResource) {
		String suggestedName = suggestedCsvName(selectedResources, focusedResource);
		if (suggestedName == null) {
			return;
		}
		Path destination = chooseCsvDestination(suggestedName);
		if (destination == null) {
			return;
		}
		Thread.ofVirtual().name("sql-csv-file-export").start(() -> {
			try {
				long rows = exportCsvTo(selectedResources, focusedResource, destination);
				showMessageDialog("Export to CSV", "Wrote " + NumberFormat.getIntegerInstance().format(rows)
						+ (rows == 1 ? " row to " : " rows to ") + destination + ".");
			} catch (Exception e) {
				log.error("CSV export to {} failed: {}", destination, e.getMessage(), e);
				showErrorDialog("Export to CSV", "Could not write " + destination + ": " + e.getMessage());
			}
		});
	}

	private void handleCopyCsv(List<NuclrResource> selectedResources, NuclrResource focusedResource) {
		Thread.ofVirtual().name("sql-csv-clipboard").start(() -> {
			var outcome = new CsvExport.Written[1];
			String csv = csvFor(selectedResources, focusedResource, ClipboardRowCap, outcome);
			if (csv == null) {
				showErrorDialog("Copy as CSV", "There is nothing here to copy as CSV.");
				return;
			}
			copyToClipboard(csv);
			if (outcome[0] != null && outcome[0].truncated()) {
				showMessageDialog("Copy as CSV", "Copied the first "
						+ NumberFormat.getIntegerInstance().format(outcome[0].rows())
						+ " rows. The table has more than the clipboard should hold — "
						+ "use Export to CSV to get all of it.");
			}
		});
	}

	private static void copyToClipboard(String text) {
		if (GraphicsEnvironment.isHeadless()) {
			return;
		}
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
	}

	/**
	 * Ask where the CSV should go, defaulting to {@code suggestedName} and confirming an
	 * overwrite. Runs on the event thread because that is where {@code act} is called
	 * from; the writing that follows does not.
	 */
	private Path chooseCsvDestination(String suggestedName) {
		if (GraphicsEnvironment.isHeadless()) {
			return null;
		}
		Supplier<Path> ask = () -> {
			var chooser = new JFileChooser();
			chooser.setDialogTitle("Export to CSV");
			chooser.setSelectedFile(new File(suggestedName));
			chooser.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
			if (chooser.showSaveDialog(activeWindow()) != JFileChooser.APPROVE_OPTION) {
				return null;
			}
			File chosen = chooser.getSelectedFile();
			if (!chosen.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) {
				chosen = new File(chosen.getParentFile(), chosen.getName() + ".csv");
			}
			if (chosen.exists() && JOptionPane.showConfirmDialog(activeWindow(),
					chosen.getName() + " already exists. Replace it?", "Export to CSV",
					JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
				return null;
			}
			return chosen.toPath();
		};
		if (SwingUtilities.isEventDispatchThread()) {
			return ask.get();
		}
		var chosen = new AtomicReference<Path>();
		try {
			SwingUtilities.invokeAndWait(() -> chosen.set(ask.get()));
		} catch (Exception e) {
			return null;
		}
		return chosen.get();
	}

	private void handleDisconnect(NuclrResource focusedResource) {
		String id = connectionIdOf(focusedResource);
		if (id != null) {
			connectionRegistry.disconnect(id);
		}
	}

	private void handleFilterSort(NuclrResource focusedResource, Map<String, Object> data) {
		if (!(focusedResource instanceof SqlNuclrResource sql)
				|| (sql.getKind() != ResourceKind.TABLE && sql.getKind() != ResourceKind.VIEW)) {
			return;
		}
		QueryState current = queryState(sql.getUuid());
		var whereField = new JTextField(current.where() == null ? "" : current.where(), 30);
		var orderField = new JTextField(current.orderBy() == null ? "" : current.orderBy(), 30);
		var panel = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
		panel.add(new JLabel("WHERE (SQL fragment, without the WHERE keyword):"));
		panel.add(whereField);
		panel.add(new JLabel("ORDER BY (SQL fragment, without ORDER BY):"));
		panel.add(orderField);
		int choice = JOptionPane.showConfirmDialog(activeWindow(), panel, "Filter / Sort — " + sql.getTableName(),
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (choice != JOptionPane.OK_OPTION) {
			return;
		}
		applyFilterSort(sql, whereField.getText(), orderField.getText());
		putRefresh(data);
	}

	/**
	 * Record the Filter / Sort dialog's outcome for {@code tableResource}. Blank clears
	 * that side of it, so emptying both boxes takes the listing back to the whole table.
	 * Separate from the dialog so the state machine can be exercised without one.
	 */
	void applyFilterSort(SqlNuclrResource tableResource, String where, String orderBy) {
		queryStateByTableUuid.put(tableResource.getUuid(), new QueryState(blankToNull(where), blankToNull(orderBy)));
	}

	private static String connectionIdOf(NuclrResource resource) {
		return resource instanceof SqlNuclrResource sql ? sql.getProfileId() : null;
	}

	private static String blankToNull(String text) {
		return text == null || text.isBlank() ? null : text.trim();
	}

	private static void putRefresh(Map<String, Object> data) {
		if (data == null) {
			return;
		}
		try {
			data.put("result.refresh", Boolean.TRUE);
		} catch (UnsupportedOperationException ignored) {
			// an immutable payload map; nothing more we can do to request a refresh
		}
	}

	// ------------------------------------------------------------------
	// Small Swing helpers
	// ------------------------------------------------------------------

	/** @return the entered password (blank becomes {@code null}), or {@code null} if cancelled. */
	private PasswordPrompt promptForPassword(ConnectionProfile profile) {
		if (GraphicsEnvironment.isHeadless()) {
			// Nothing can answer a prompt without a display, so treat it as declined rather
			// than blocking the listing behind a dialog nobody will ever see.
			log.warn("Cannot ask for the password to \"{}\" without a display.", profile.getName());
			return null;
		}
		Supplier<PasswordPrompt> ask = () -> {
			var passwordField = new JPasswordField(20);
			var rememberBox = new JCheckBox("Remember password (OS keychain)", true);
			var panel = new JPanel(new BorderLayout(6, 6));
			panel.add(new JLabel("Password for \"" + profile.getName() + "\" (" + profile.getUsername() + "):"),
					BorderLayout.NORTH);
			panel.add(passwordField, BorderLayout.CENTER);
			panel.add(rememberBox, BorderLayout.SOUTH);
			int choice = JOptionPane.showConfirmDialog(activeWindow(), panel, "Connect",
					JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
			if (choice != JOptionPane.OK_OPTION) {
				return null;
			}
			String typed = new String(passwordField.getPassword());
			return new PasswordPrompt(typed.isBlank() ? null : typed, rememberBox.isSelected());
		};
		if (SwingUtilities.isEventDispatchThread()) {
			return ask.get();
		}
		var result = new AtomicReference<PasswordPrompt>();
		try {
			SwingUtilities.invokeAndWait(() -> result.set(ask.get()));
		} catch (Exception e) {
			return null;
		}
		return result.get();
	}

	/**
	 * The user asked for the password to be remembered and the OS store would not take it.
	 * Not fatal — the connection profile itself is saved either way — so this says what
	 * happened instead of failing the action.
	 */
	private void warnIfNotStored(String failureReason) {
		if (failureReason == null) {
			return;
		}
		showErrorDialog("Remember Password", "The connection was saved, but its password could not be stored: "
				+ failureReason);
	}

	private void showMessageDialog(String title, String message) {
		if (GraphicsEnvironment.isHeadless()) {
			log.info("{}: {}", title, message);
			return;
		}
		SwingUtilities.invokeLater(
				() -> JOptionPane.showMessageDialog(activeWindow(), message, title, JOptionPane.INFORMATION_MESSAGE));
	}

	private void showErrorDialog(String title, String message) {
		if (GraphicsEnvironment.isHeadless()) {
			// No display to put a dialog on (a headless test run, or a stripped JRE); the
			// failure is already logged, and throwing HeadlessException out of a listing
			// would turn a reportable error into a broken panel.
			log.warn("{}: {}", title, message);
			return;
		}
		Runnable show = () -> JOptionPane.showMessageDialog(activeWindow(), message, title, JOptionPane.ERROR_MESSAGE);
		if (SwingUtilities.isEventDispatchThread()) {
			show.run();
		} else {
			SwingUtilities.invokeLater(show);
		}
	}

	/**
	 * The window a dialog should hang off. An owner-less {@link JOptionPane} dialog is
	 * application-modal but owned by a hidden frame, so it can end up behind the main
	 * window while still blocking it (mirrors {@code ZipFilePanelPlugin}'s own helper).
	 */
	private static Window activeWindow() {
		return KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
	}
}
