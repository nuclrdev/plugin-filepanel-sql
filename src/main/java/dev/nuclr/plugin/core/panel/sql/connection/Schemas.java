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
package dev.nuclr.plugin.core.panel.sql.connection;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import lombok.extern.slf4j.Slf4j;

/**
 * The schemas a connection shows, as the explorer lists them under a connection and
 * as actions accept them.
 */
@Slf4j
public final class Schemas {

	private Schemas() {
	}

	/**
	 * The connection's schema names. Drivers with no real schema concept, or where the
	 * database already is the schema (MySQL), list none; the connection's own catalog
	 * stands in for them, or {@code "main"} when there is not even that.
	 *
	 * @param cancelled polled between rows; a cancelled listing returns what it has
	 * @return the names, never empty unless cancelled
	 */
	public static List<String> names(Connection connection, BooleanSupplier cancelled) {
		List<String> names = new ArrayList<>();
		try (ResultSet rs = connection.getMetaData().getSchemas()) {
			while (rs.next()) {
				if (cancelled.getAsBoolean()) {
					return names;
				}
				names.add(rs.getString("TABLE_SCHEM"));
			}
		} catch (SQLException e) {
			log.debug("getSchemas() unavailable: {}", e.getMessage());
		}
		if (names.isEmpty()) {
			String catalog = null;
			try {
				catalog = connection.getCatalog();
			} catch (SQLException ignored) {
				// fall through to the "main" default below
			}
			names.add(catalog != null && !catalog.isBlank() ? catalog : "main");
		}
		return names;
	}

	/**
	 * The catalog to pass to metadata calls alongside a schema name, or {@code null};
	 * most drivers accept a null catalog filter fine.
	 */
	public static String catalog(Connection connection) {
		try {
			return connection.getCatalog();
		} catch (SQLException e) {
			return null;
		}
	}
}
