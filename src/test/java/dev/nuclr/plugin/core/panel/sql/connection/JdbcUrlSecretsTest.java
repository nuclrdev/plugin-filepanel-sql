package dev.nuclr.plugin.core.panel.sql.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class JdbcUrlSecretsTest {

	@Test
	void aPasswordInTheUserInfoIsTakenOutAndPutBack() {
		var split = JdbcUrlSecrets.split("jdbc:postgresql://app:hunter2@db1:5432/shop");
		assertEquals("jdbc:postgresql://app@db1:5432/shop", split.url());
		assertEquals(List.of('U'), JdbcUrlSecrets.kinds(split.secrets()));
		assertEquals("jdbc:postgresql://app:hunter2@db1:5432/shop", JdbcUrlSecrets.merge(split.url(), split.secrets()));
	}

	@Test
	void passwordParametersAreTakenOutWhereverTheySit() {
		assertRoundTrip("jdbc:postgresql://db/shop?password=hunter2", "jdbc:postgresql://db/shop");
		assertRoundTrip("jdbc:postgresql://db/shop?user=app&password=hunter2", "jdbc:postgresql://db/shop?user=app");
		assertRoundTrip("jdbc:mysql://db/shop?pwd=x&useSSL=true", "jdbc:mysql://db/shop?useSSL=true");
		assertRoundTrip("jdbc:sqlserver://db:1433;databaseName=shop;password=hunter2;encrypt=true",
				"jdbc:sqlserver://db:1433;databaseName=shop;encrypt=true");
		assertRoundTrip("jdbc:databricks://h:443;AuthMech=3;Token=dapi123", "jdbc:databricks://h:443;AuthMech=3");
		assertRoundTrip("jdbc:x://h/db?access_token=a&secret=b&passwd=c", "jdbc:x://h/db");
	}

	@Test
	void oraclesThinUserAndPasswordAreTakenOut() {
		var split = JdbcUrlSecrets.split("jdbc:oracle:thin:scott/tiger@db:1521/orcl");
		assertEquals("jdbc:oracle:thin:@db:1521/orcl", split.url());
		assertEquals("jdbc:oracle:thin:scott/tiger@db:1521/orcl", JdbcUrlSecrets.merge(split.url(), split.secrets()));
	}

	@Test
	void aUrlWithoutSecretsIsLeftAlone() {
		for (String url : List.of("jdbc:postgresql://db1:5432/shop", "jdbc:postgresql://app@db1/shop",
				"jdbc:postgresql://app:@db1/shop", "jdbc:oracle:thin:@db:1521/orcl", "jdbc:sqlite:C:/data/app.db")) {
			var split = JdbcUrlSecrets.split(url);
			assertEquals(url, split.url());
			assertFalse(split.hasSecrets(), url);
		}
		assertNull(JdbcUrlSecrets.split(null).url());
	}

	@Test
	void everyShapeRedactUrlHidesIsTakenOut() {
		String url = "jdbc:x://u:p1@h/db?password=p2&a=1";
		var split = JdbcUrlSecrets.split(url);
		assertFalse(split.url().contains("p1"));
		assertFalse(split.url().contains("p2"));
		assertEquals(url.replace(":p1", ":p1"), JdbcUrlSecrets.merge(split.url(), split.secrets()).replace("?a=1&password=p2", "?password=p2&a=1"));
	}

	@Test
	void joinKeepsEachLineOnce() {
		assertEquals("U\ta\nQ\tpassword=b", JdbcUrlSecrets.join("U\ta", "U\ta\nQ\tpassword=b"));
		assertNull(JdbcUrlSecrets.join(null, " "));
		assertTrue(JdbcUrlSecrets.join("U\ta", null).startsWith("U"));
	}

	private static void assertRoundTrip(String url, String stripped) {
		var split = JdbcUrlSecrets.split(url);
		assertEquals(stripped, split.url());
		assertTrue(split.hasSecrets());
		// Parameters come back at the end; the driver reads them the same.
		var merged = JdbcUrlSecrets.merge(split.url(), split.secrets());
		assertEquals(JdbcUrlSecrets.split(merged).url(), stripped);
		for (String line : split.secrets().split("\n")) {
			assertTrue(merged.contains(line.substring(2)), merged);
		}
	}
}
