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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

/**
 * Best-effort, per-schema approximate row counts, keyed by table name — the confirmed
 * "Size" behaviour for the schema listing: one lightweight vendor-specific estimate
 * query per schema (not per table), blank wherever the dialect isn't recognised or the
 * query fails.
 */
@Slf4j
final class TableStats {

	private TableStats() {
	}

	static Map<String, Long> estimate(Connection connection, String jdbcUrl, String schemaName) {
		if (jdbcUrl == null) {
			return Map.of();
		}
		try {
			if (jdbcUrl.startsWith("jdbc:postgresql")) {
				return runEstimateQuery(connection,
						"SELECT relname, n_live_tup FROM pg_stat_user_tables WHERE schemaname = ?", schemaName);
			}
			if (jdbcUrl.startsWith("jdbc:mysql") || jdbcUrl.startsWith("jdbc:mariadb")) {
				return runEstimateQuery(connection,
						"SELECT table_name, table_rows FROM information_schema.tables WHERE table_schema = ?",
						schemaName);
			}
			if (jdbcUrl.startsWith("jdbc:sqlserver")) {
				return runEstimateQuery(connection, """
						SELECT t.name, SUM(p.rows)
						FROM sys.tables t
						JOIN sys.schemas s ON t.schema_id = s.schema_id
						JOIN sys.partitions p ON t.object_id = p.object_id AND p.index_id IN (0, 1)
						WHERE s.name = ?
						GROUP BY t.name
						""", schemaName);
			}
		} catch (SQLException e) {
			log.debug("Row-count estimate unavailable for schema {}: {}", schemaName, e.getMessage());
		}
		// Anything else unrecognised: no cheap estimate available, leave blank.
		return Map.of();
	}

	private static Map<String, Long> runEstimateQuery(Connection connection, String sql, String schemaName) throws SQLException {
		var result = new HashMap<String, Long>();
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			ps.setString(1, schemaName);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String name = rs.getString(1);
					long count = rs.getLong(2);
					if (name != null) {
						result.put(name, count);
					}
				}
			}
		}
		return result;
	}
}
