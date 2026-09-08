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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.panel.sql.support.SqliteFixture;

/**
 * The "Size" column is a best-effort estimate: a dialect with no cheap row count, or a
 * count query that fails, has to leave the column blank rather than break the listing.
 */
class TableStatsTest {

	private Connection connection;

	@BeforeEach
	void setUp(@TempDir Path dir) throws Exception {
		connection = DriverManager.getConnection(SqliteFixture.create(dir.resolve("stats.db")));
	}

	@AfterEach
	void tearDown() throws Exception {
		connection.close();
	}

	@Test
	void anUnrecognisedDialectReportsNothing() {
		assertTrue(TableStats.estimate(connection, "jdbc:oracle:thin:@//host:1521/orcl", "APP").isEmpty());
		assertTrue(TableStats.estimate(connection, "jdbc:h2:mem:test", "PUBLIC").isEmpty());
	}

	@Test
	void aProfileWithNoUrlReportsNothing() {
		assertTrue(TableStats.estimate(connection, null, "main").isEmpty());
	}

	@Test
	void anEstimateQueryThatFailsDegradesToNothingRatherThanThrowing() {
		// A Postgres URL over a connection that knows nothing of pg_stat_user_tables: the
		// vendor query fails exactly the way an unprivileged or unusual server would.
		assertTrue(TableStats.estimate(connection, "jdbc:postgresql://host/db", "public").isEmpty());
		assertTrue(TableStats.estimate(connection, "jdbc:mysql://host/db", "shop").isEmpty());
		assertTrue(TableStats.estimate(connection, "jdbc:sqlserver://host;databaseName=db", "dbo").isEmpty());
	}
}
