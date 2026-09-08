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
package dev.nuclr.plugin.core.panel.sql.resource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.OpenOption;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.sql.connection.ConnectionProfile;
import dev.nuclr.plugin.core.panel.sql.csv.CsvExport;

/**
 * A node in the virtual tree {@code Explorer root -> Connection -> Schema ->
 * Table/View -> Row}. Always backed by {@code path == null} (per the SDK contract
 * for virtual/remote resources, see {@link NuclrResource#NuclrResource(java.nio.file.Path)}).
 *
 * <p>Deliberately not a Lombok {@code @Data} class: {@link NuclrResource} hand-writes
 * {@code equals}/{@code hashCode} by {@code uuid} alone, and a Lombok-generated
 * {@code equals} on this subclass would silently shadow that contract (the same
 * reason {@code ZipFileNuclrResource} and {@code FileNuclrResource} avoid it too).
 */
public final class SqlNuclrResource extends NuclrResource {

	private ResourceKind kind;
	private String profileId;
	private String schemaName;
	private String tableName;
	private List<String> columnOrder = List.of();

	/** Set only on TABLE/VIEW resources: how to reach a live connection for CSV export. */
	private transient Supplier<Connection> connectionSupplier;

	/** Set only on TABLE/VIEW resources: the fully qualified, already-quoted "schema"."table". */
	private String quotedTableRef;

	private SqlNuclrResource(ResourceKind kind) {
		super(null);
		this.kind = kind;
	}

	public ResourceKind getKind() {
		return kind;
	}

	public String getProfileId() {
		return profileId;
	}

	public String getSchemaName() {
		return schemaName;
	}

	public String getTableName() {
		return tableName;
	}

	public List<String> getColumnOrder() {
		return columnOrder;
	}

	/** The fully qualified, already-quoted {@code "schema"."table"} reference (TABLE/VIEW only). */
	public String getQuotedTableRef() {
		return quotedTableRef;
	}

	/**
	 * The shared, long-lived connection this table/view was listed through (TABLE/VIEW
	 * only) — owned by the file panel's {@code ConnectionRegistry}; callers must never
	 * close it.
	 */
	public Connection openConnection() {
		if (connectionSupplier == null) {
			throw new IllegalStateException("No live connection available for " + fullPath);
		}
		return connectionSupplier.get();
	}

	@Override
	public InputStream openInputStream(OpenOption... options) throws Exception {
		return switch (kind) {
			case ROW -> new ByteArrayInputStream(
					CsvExport.rowCsv(columnOrder, getMetadata()).getBytes(StandardCharsets.UTF_8));
			case TABLE, VIEW -> {
				if (connectionSupplier == null || quotedTableRef == null) {
					yield super.openInputStream(options);
				}
				yield CsvExport.streamTable(connectionSupplier.get(), quotedTableRef);
			}
			default -> super.openInputStream(options);
		};
	}

	// ------------------------------------------------------------------
	// Factories
	// ------------------------------------------------------------------

	/** The "SQL Explorer" drive root: lists saved connection profiles. */
	public static SqlNuclrResource explorerRoot() {
		var r = new SqlNuclrResource(ResourceKind.EXPLORER_ROOT);
		r.setUuid("sql://");
		r.setFullPath("sql://");
		r.setName("SQL Explorer");
		r.setFolder(true);
		return r;
	}

	/** A saved connection, not yet connected. */
	public static SqlNuclrResource connection(ConnectionProfile profile) {
		var r = new SqlNuclrResource(ResourceKind.CONNECTION);
		r.profileId = profile.getId();
		r.setUuid("sql://" + profile.getId());
		r.setFullPath(profile.getName());
		r.setName(profile.getName());
		r.setFolder(true);
		r.getMetadata().put("Name", profile.getName());
		r.getMetadata().put("Driver", profile.getDriverLabel());
		r.getMetadata().put("Host", profile.getDisplayTarget());
		return r;
	}

	public static SqlNuclrResource schema(String profileId, String schemaName) {
		var r = new SqlNuclrResource(ResourceKind.SCHEMA);
		r.profileId = profileId;
		r.schemaName = schemaName;
		r.setUuid("sql://" + profileId + "/" + schemaName);
		r.setFullPath(schemaName);
		r.setName(schemaName);
		r.setFolder(true);
		r.getMetadata().put("Name", schemaName);
		return r;
	}

	/**
	 * A table or view. {@code quotedTableRef} and {@code connectionSupplier} are only
	 * needed for CSV export ({@link #openInputStream}); pass {@code null} for either
	 * when the caller only needs the resource for display (e.g. the row-limit marker's
	 * sibling entries never export).
	 */
	public static SqlNuclrResource table(
			String profileId,
			String schemaName,
			String tableName,
			boolean isView,
			String quotedTableRef,
			Supplier<Connection> connectionSupplier) {
		var r = new SqlNuclrResource(isView ? ResourceKind.VIEW : ResourceKind.TABLE);
		r.profileId = profileId;
		r.schemaName = schemaName;
		r.tableName = tableName;
		r.quotedTableRef = quotedTableRef;
		r.connectionSupplier = connectionSupplier;
		r.setUuid("sql://" + profileId + "/" + schemaName + "/" + tableName);
		r.setFullPath(schemaName + "." + tableName);
		r.setName(tableName);
		r.setFolder(true);
		r.getMetadata().put("Name", tableName);
		return r;
	}

	/** {@code rowKey} is the row's identity for navigation (PK value(s) or an offset). */
	public static SqlNuclrResource row(
			String profileId,
			String schemaName,
			String tableName,
			String rowKey,
			List<String> columnOrder,
			Map<String, Object> values) {
		var r = new SqlNuclrResource(ResourceKind.ROW);
		r.profileId = profileId;
		r.schemaName = schemaName;
		r.tableName = tableName;
		r.columnOrder = columnOrder;
		String safeKey = rowKey == null ? "" : rowKey.replace('/', '_');
		r.setUuid("sql://" + profileId + "/" + schemaName + "/" + tableName + "/" + safeKey);
		r.setFullPath(schemaName + "." + tableName + "[" + rowKey + "]");
		r.setName(rowKey == null || rowKey.isEmpty() ? "(row)" : rowKey);
		r.setFolder(false);
		for (String column : columnOrder) {
			r.getMetadata().put(column, values.get(column));
		}
		return r;
	}

	/** Non-selectable marker appended when a table listing hits the row cap. */
	public static SqlNuclrResource rowLimitMarker(String profileId, String schemaName, String tableName, int rowLimit) {
		var r = new SqlNuclrResource(ResourceKind.ROW_LIMIT_MARKER);
		r.profileId = profileId;
		r.schemaName = schemaName;
		r.tableName = tableName;
		r.setUuid("sql://" + profileId + "/" + schemaName + "/" + tableName + "/#limit");
		r.setFullPath("");
		r.setName("⚠ row limit reached (" + rowLimit + ") — refine a filter to see more");
		r.setFolder(false);
		r.setReadable(false);
		return r;
	}

	/**
	 * Build the {@code ".."} entry shown at the top of a listing, reusing {@code parent}'s
	 * identity so selecting it re-opens exactly that resource (mirrors
	 * {@code ArchiveNuclrResource.buildParent} / the local-fs plugin's own {@code ".."} rows).
	 */
	public static SqlNuclrResource parentEntry(SqlNuclrResource parent) {
		var r = new SqlNuclrResource(parent.kind);
		r.profileId = parent.profileId;
		r.schemaName = parent.schemaName;
		r.tableName = parent.tableName;
		r.quotedTableRef = parent.quotedTableRef;
		r.connectionSupplier = parent.connectionSupplier;
		r.setUuid(parent.getUuid());
		r.setFullPath(parent.getFullPath());
		r.setName("..");
		r.setFolder(true);
		return r;
	}
}
