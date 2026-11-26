package dev.nuclr.plugin.core.panel.sql.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.panel.sql.PluginIds;
import dev.nuclr.plugin.core.panel.sql.SqlFilePanelPlugin;
import dev.nuclr.plugin.core.panel.sql.connection.ConnectionProfile;
import dev.nuclr.plugin.core.panel.sql.connection.ConnectionProfileStore;
import dev.nuclr.plugin.core.panel.sql.support.FakeContext;

class SqlTransferTest {

	private final FakeContext context = new FakeContext();
	private final ConnectionProfileStore store = new ConnectionProfileStore(context.settings);
	private final SqlFilePanelPlugin plugin = new SqlFilePanelPlugin();

	SqlTransferTest() {
		plugin.preinit(context);
	}

	/** One action through the plugin's act, as Commander sends it. */
	private Map<String, Object> act(String action, Map<String, Object> input) {
		var data = new HashMap<String, Object>(input);
		data.put("transfer.protocol", 1);
		if (!action.equals(SqlTransfer.Describe)) {
			data.putIfAbsent("type", "connections");
		}
		plugin.act(null, action, List.of(), null, data, null);
		return data;
	}

	@Test
	void describeDeclaresTheConnectionsCategoryWithEveryFieldTagged() {
		var data = act(SqlTransfer.Describe, Map.of());

		@SuppressWarnings("unchecked")
		var provider = ((List<Map<String, Object>>) data.get("transfer.providers")).getFirst();
		assertEquals("connections", provider.get("type"));
		assertEquals(1, provider.get("version"));
		var kinds = new HashMap<String, Object>();
		var suffixes = new HashMap<String, Object>();
		for (Object field : (List<?>) provider.get("fields")) {
			var map = (Map<?, ?>) field;
			kinds.put((String) map.get("name"), map.get("kind"));
			suffixes.put((String) map.get("name"), map.get("credentialSuffix"));
		}
		assertEquals("ROUTING", kinds.get("jdbcUrl"));
		assertEquals("ROUTING", kinds.get("sshHost"));
		assertEquals("PRIVATE_KEY_PATH", kinds.get("sshKeyPath"));
		assertEquals("RUNS_CODE", kinds.get("passwordCommand"));
		assertEquals("RUNS_CODE", kinds.get("customDriverJarPath"));
		assertEquals("", suffixes.get("password"));
		assertEquals(":ssh", suffixes.get("sshPassword"));
		assertEquals(":url", suffixes.get("urlSecrets"));
	}

	@Test
	void aSnapshotNeverCarriesASecretEvenOneLeftInAnOldUrl() {
		store.add(ConnectionProfile.builder().id("a").name("shop")
				.jdbcUrl("jdbc:postgresql://app:hunter2@db/shop?password=again").username("app").build());
		context.credentials.secrets.put("a", "db-password");

		@SuppressWarnings("unchecked")
		var row = ((List<Map<String, Object>>) act(SqlTransfer.Snapshot, Map.of()).get("rows")).getFirst();

		assertEquals("a", row.get("rowId"));
		assertEquals("shop", row.get("label"));
		assertEquals(true, row.get("hasSecrets"));
		var fields = (Map<?, ?>) row.get("fields");
		assertEquals("jdbc:postgresql://app@db/shop", fields.get("jdbcUrl"));
		assertFalse(fields.toString().contains("hunter2"));
		assertFalse(fields.toString().contains("db-password"));
	}

	@Test
	void readSecretsGivesThePasswordsAndTheUrlsSecretsFromWhereverTheyAre() {
		store.add(ConnectionProfile.builder().id("a").name("shop").jdbcUrl("jdbc:postgresql://db/shop?pwd=old-style")
				.build());
		context.credentials.secrets.put("a", "db-password");
		context.credentials.secrets.put("a:ssh", "ssh-password");
		context.credentials.secrets.put("a:url", "Q\ttoken=from-keychain");
		act(SqlTransfer.Snapshot, Map.of());

		var secrets = (Map<?, ?>) act(SqlTransfer.ReadSecrets, Map.of("rowId", "a")).get("secrets");

		assertEquals("db-password", secrets.get("password"));
		assertEquals("ssh-password", secrets.get("sshPassword"));
		assertEquals("Q\tpwd=old-style\nQ\ttoken=from-keychain", secrets.get("urlSecrets"));
	}

	@Test
	void anEntryWithoutAnIdIsStillExportable() {
		context.settings.putRaw(PluginIds.PLUGIN_ID, "profiles.json", "[{\"name\":\"legacy\"}]");

		@SuppressWarnings("unchecked")
		var row = ((List<Map<String, Object>>) act(SqlTransfer.Snapshot, Map.of()).get("rows")).getFirst();

		assertEquals("#1", row.get("rowId"));
		assertEquals(false, row.get("hasSecrets"));
	}

	@Test
	void findEmbeddedSecretsNamesAUrlThatStillCarriesOne() {
		assertEquals(List.of("jdbcUrl"),
				act(SqlTransfer.FindEmbeddedSecrets, Map.of("fields", Map.of("jdbcUrl", "jdbc:oracle:thin:a/b@h:1/x")))
						.get("fieldNames"));
		assertEquals(List.of(),
				act(SqlTransfer.FindEmbeddedSecrets, Map.of("fields", Map.of("jdbcUrl", "jdbc:postgresql://h/x")))
						.get("fieldNames"));
	}

	@Test
	void applyAppendsUnderTheGivenIdsKeepsTheRestAndHandsBackWhatItStripped() {
		store.add(ConnectionProfile.builder().id("existing").name("local").jdbcUrl("jdbc:sqlite:x.db").build());

		var data = act(SqlTransfer.Apply, Map.of("items", List.of(
				item("new-1", Map.of("name", "shop", "jdbcUrl", "jdbc:postgresql://db/shop?password=crafted",
						"sshEnabled", "true", "sshHost", "bastion", "useSsl", "true"), Map.of("sshKeyPath", "/keys/id")),
				item("new-2", Map.of("jdbcUrl", "jdbc:postgresql://db/other"), Map.of()))));

		var all = store.loadAll();
		assertEquals(List.of("existing", "new-1", "new-2"), all.stream().map(ConnectionProfile::getId).toList());
		var shop = store.findById("new-1");
		assertEquals("jdbc:postgresql://db/shop", shop.getJdbcUrl(), "a crafted file cannot put a password back");
		assertTrue(shop.isSshEnabled());
		assertTrue(shop.isUseSsl());
		assertEquals("/keys/id", shop.getSshKeyPath());
		assertEquals("Imported connection", store.findById("new-2").getName());
		assertEquals(Map.of("new-1", Map.of("urlSecrets", "Q\tpassword=crafted")), data.get("recoveredSecrets"));
		assertTrue(context.credentials.secrets.isEmpty(), "apply never writes the keychain");
	}

	@Test
	void applyOverAnUnreadableStoreThrowsAndChangesNothing() {
		context.settings.putRaw(PluginIds.PLUGIN_ID, "profiles.json", "{not json");

		assertThrows(IllegalStateException.class,
				() -> act(SqlTransfer.Apply, Map.of("items", List.of(item("n", Map.of("name", "x"), Map.of())))));
		assertEquals("{not json", context.settings.get(PluginIds.PLUGIN_ID, "profiles.json"));
	}

	@Test
	void aFailedWriteThrowsInsteadOfBeingSwallowed() {
		store.add(ConnectionProfile.builder().id("existing").name("local").build());
		context.settings.failWrites = true;

		assertThrows(IllegalStateException.class,
				() -> act(SqlTransfer.Apply, Map.of("items", List.of(item("n", Map.of("name", "x"), Map.of())))));
		context.settings.failWrites = false;
		assertEquals(List.of("existing"), store.loadAll().stream().map(ConnectionProfile::getId).toList());
	}

	@Test
	void removeDeletesOnlyTheGivenIds() {
		store.add(ConnectionProfile.builder().id("keep").name("a").build());
		store.add(ConnectionProfile.builder().id("drop").name("b").build());

		act(SqlTransfer.Remove, Map.of("ids", List.of("drop", "unknown")));

		assertEquals(List.of("keep"), store.loadAll().stream().map(ConnectionProfile::getId).toList());
	}

	@Test
	void anotherProtocolVersionOrCategoryIsNotAnswered() {
		var data = new HashMap<String, Object>();
		data.put("transfer.protocol", 2);
		plugin.act(null, SqlTransfer.Describe, List.of(), null, data, null);
		assertFalse(data.containsKey("transfer.providers"));

		var other = act(SqlTransfer.Snapshot, Map.of("type", "profiles"));
		assertFalse(other.containsKey("rows"));
	}

	@Test
	void everyProfileFieldIsDeclared() {
		var exported = new ArrayList<>(SqlTransfer.fields(ConnectionProfile.builder().name("n").driverKey("d")
				.customDriverClassName("c").customDriverJarPath("j").jdbcUrl("u").host("h").port("p").database("db")
				.username("us").passwordCommand("pc").sshHost("sh").sshPort("sp").sshUsername("su").sshKeyPath("k")
				.build()).keySet());
		assertTrue(SqlTransfer.fieldNames().containsAll(exported), exported.toString());
	}

	private static Map<String, Object> item(String id, Map<String, String> fields, Map<String, String> keyFiles) {
		return Map.of("newId", id, "fields", fields, "keyFiles", keyFiles);
	}
}
