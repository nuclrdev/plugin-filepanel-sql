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
package dev.nuclr.plugin.core.panel.sql.transfer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.nuclr.plugin.core.panel.sql.connection.ConnectionProfile;
import dev.nuclr.plugin.core.panel.sql.connection.ConnectionProfileStore;
import dev.nuclr.plugin.core.panel.sql.connection.CredentialStore;
import dev.nuclr.plugin.core.panel.sql.connection.JdbcUrlSecrets;
import lombok.extern.slf4j.Slf4j;

/**
 * This plugin's side of Commander's Configs → Export / Import, spoken over {@code act}
 * with plain maps (Commander's {@code TransferProtocol}; see
 * {@code commander/docs/settings-transfer-plan.md}, section 4). Saved connections are the
 * {@code connections} category.
 *
 * <p>Secrets never travel inside an ordinary field: a snapshot strips them out of the JDBC
 * URL, a password, SSH password and the URL's secrets are only read on request, and apply
 * strips the URL again - a crafted file cannot put a password back into
 * {@code settings.json}. Apply never writes the keychain; what it strips goes back to
 * Commander as recovered secrets, which Commander stores under the new id.
 *
 * <p>Called off the EDT, on the instance Commander keeps for actions.
 */
@Slf4j
public final class SqlTransfer {

	public static final String Describe = "transfer.describe";
	public static final String Snapshot = "transfer.snapshot";
	public static final String ReadSecrets = "transfer.readSecrets";
	public static final String FindEmbeddedSecrets = "transfer.findEmbeddedSecrets";
	public static final String Apply = "transfer.apply";
	public static final String Remove = "transfer.remove";

	static final int ProtocolVersion = 1;
	static final String Type = "connections";
	static final int SectionVersion = 1;

	// Field names, as they appear in the export file.
	static final String Name = "name";
	static final String DriverKey = "driverKey";
	static final String CustomDriverClassName = "customDriverClassName";
	static final String CustomDriverJarPath = "customDriverJarPath";
	static final String JdbcUrl = "jdbcUrl";
	static final String Host = "host";
	static final String Port = "port";
	static final String Database = "database";
	static final String Username = "username";
	static final String PasswordCommand = "passwordCommand";
	static final String UseSsl = "useSsl";
	static final String SshEnabled = "sshEnabled";
	static final String SshHost = "sshHost";
	static final String SshPort = "sshPort";
	static final String SshUsername = "sshUsername";
	static final String SshKeyPath = "sshKeyPath";
	static final String Password = "password";
	static final String SshPassword = "sshPassword";
	static final String UrlSecrets = "urlSecrets";

	private final ConnectionProfileStore profiles;
	private final CredentialStore credentials;

	/** The rows of the last snapshot by row id, so an export reads secrets for exactly those. */
	private Map<String, ConnectionProfile> lastSnapshot = Map.of();

	public SqlTransfer(ConnectionProfileStore profiles, CredentialStore credentials) {
		this.profiles = profiles;
		this.credentials = credentials;
	}

	public static boolean handles(String action) {
		return switch (action) {
			case Describe, Snapshot, ReadSecrets, FindEmbeddedSecrets, Apply, Remove -> true;
			default -> false;
		};
	}

	/**
	 * Answer one transfer action into {@code data}. Throws on failure; Commander treats a
	 * throw as "the plugin failed" and, for apply, relies on the store being unchanged.
	 */
	public void handle(String action, Map<String, Object> data) {
		if (!(data.get("transfer.protocol") instanceof Integer version) || version != ProtocolVersion) {
			return; // a protocol this build does not speak: do not take part
		}
		if (!Describe.equals(action) && !Type.equals(data.get("type"))) {
			return;
		}
		switch (action) {
			case Describe -> data.put("transfer.providers", List.of(describe()));
			case Snapshot -> data.put("rows", snapshot());
			case ReadSecrets -> data.put("secrets", readSecrets(String.valueOf(data.get("rowId"))));
			case FindEmbeddedSecrets -> data.put("fieldNames", findEmbeddedSecrets(strings(data.get("fields"))));
			case Apply -> {
				Map<String, Object> recovered = apply(items(data.get("items")));
				if (!recovered.isEmpty()) {
					data.put("recoveredSecrets", recovered);
				}
			}
			case Remove -> remove(ids(data.get("ids")));
			default -> {
			}
		}
	}

	static Map<String, Object> describe() {
		var fields = new ArrayList<Map<String, Object>>();
		fields.add(field(Name, "PLAIN"));
		fields.add(field(DriverKey, "PLAIN"));
		fields.add(field(CustomDriverClassName, "PLAIN"));
		fields.add(field(CustomDriverJarPath, "RUNS_CODE"));
		fields.add(field(JdbcUrl, "ROUTING"));
		fields.add(field(Host, "ROUTING"));
		fields.add(field(Port, "ROUTING"));
		fields.add(field(Database, "PLAIN"));
		fields.add(field(Username, "PLAIN"));
		fields.add(field(PasswordCommand, "RUNS_CODE"));
		fields.add(field(UseSsl, "PLAIN"));
		fields.add(field(SshEnabled, "ROUTING"));
		fields.add(field(SshHost, "ROUTING"));
		fields.add(field(SshPort, "ROUTING"));
		fields.add(field(SshUsername, "PLAIN"));
		fields.add(field(SshKeyPath, "PRIVATE_KEY_PATH"));
		fields.add(secret(Password, ""));
		fields.add(secret(SshPassword, ":ssh"));
		fields.add(secret(UrlSecrets, ":url"));
		return Map.of("type", Type, "version", SectionVersion, "title", "SQL", "fields", fields);
	}

	List<Map<String, Object>> snapshot() {
		var rows = new ArrayList<Map<String, Object>>();
		var byRow = new HashMap<String, ConnectionProfile>();
		int index = 0;
		for (ConnectionProfile profile : profiles.loadAll()) {
			index++;
			// An entry saved without an id keeps a row of its own; it just has no stored secrets.
			String rowId = profile.getId() != null && !profile.getId().isBlank() ? profile.getId() : "#" + index;
			byRow.put(rowId, profile);
			var row = new HashMap<String, Object>();
			row.put("rowId", rowId);
			row.put("label", profile.getName() == null || profile.getName().isBlank() ? rowId : profile.getName());
			row.put("fields", fields(profile));
			row.put("hasSecrets", !secrets(profile).isEmpty());
			rows.add(row);
		}
		lastSnapshot = byRow;
		return rows;
	}

	Map<String, String> readSecrets(String rowId) {
		ConnectionProfile profile = lastSnapshot.get(rowId);
		if (profile == null) {
			profile = profiles.findById(rowId);
		}
		return profile == null ? Map.of() : secrets(profile);
	}

	static List<String> findEmbeddedSecrets(Map<String, String> fields) {
		return JdbcUrlSecrets.split(fields.get(JdbcUrl)).hasSecrets() ? List.of(JdbcUrl) : List.of();
	}

	/** Add every item under its given id in one write; return the URL secrets stripped on the way. */
	Map<String, Object> apply(List<Map<String, Object>> items) {
		var added = new ArrayList<ConnectionProfile>();
		var recovered = new LinkedHashMap<String, Object>();
		for (Map<String, Object> item : items) {
			String id = (String) item.get("newId");
			if (id == null || id.isBlank()) {
				throw new IllegalArgumentException("An imported connection has no id");
			}
			Map<String, String> fields = strings(item.get("fields"));
			Map<String, String> keyFiles = strings(item.get("keyFiles"));

			JdbcUrlSecrets.Split url = JdbcUrlSecrets.split(fields.get(JdbcUrl));
			if (url.hasSecrets()) {
				recovered.put(id, Map.of(UrlSecrets, url.secrets()));
			}
			ConnectionProfile profile = ConnectionProfile.builder()
					.id(id)
					.name(blankTo(fields.get(Name), "Imported connection"))
					.driverKey(fields.get(DriverKey))
					.customDriverClassName(fields.get(CustomDriverClassName))
					.customDriverJarPath(fields.get(CustomDriverJarPath))
					.jdbcUrl(url.url())
					.host(fields.get(Host))
					.port(fields.get(Port))
					.database(fields.get(Database))
					.username(fields.get(Username))
					.passwordCommand(fields.get(PasswordCommand))
					.useSsl(Boolean.parseBoolean(fields.get(UseSsl)))
					.sshEnabled(Boolean.parseBoolean(fields.get(SshEnabled)))
					.sshHost(fields.get(SshHost))
					.sshPort(fields.get(SshPort))
					.sshUsername(fields.get(SshUsername))
					.sshKeyPath(keyFiles.getOrDefault(SshKeyPath, fields.get(SshKeyPath)))
					.build();
			added.add(profile);
		}
		profiles.addAll(added);
		return recovered;
	}

	void remove(List<String> ids) {
		profiles.removeAll(new HashSet<>(ids));
	}

	/** A profile's non-secret fields, with the JDBC URL stripped of anything secret. */
	static Map<String, Object> fields(ConnectionProfile profile) {
		var fields = new LinkedHashMap<String, Object>();
		put(fields, Name, profile.getName());
		put(fields, DriverKey, profile.getDriverKey());
		put(fields, CustomDriverClassName, profile.getCustomDriverClassName());
		put(fields, CustomDriverJarPath, profile.getCustomDriverJarPath());
		put(fields, JdbcUrl, JdbcUrlSecrets.split(profile.getJdbcUrl()).url());
		put(fields, Host, profile.getHost());
		put(fields, Port, profile.getPort());
		put(fields, Database, profile.getDatabase());
		put(fields, Username, profile.getUsername());
		put(fields, PasswordCommand, profile.getPasswordCommand());
		fields.put(UseSsl, String.valueOf(profile.isUseSsl()));
		fields.put(SshEnabled, String.valueOf(profile.isSshEnabled()));
		put(fields, SshHost, profile.getSshHost());
		put(fields, SshPort, profile.getSshPort());
		put(fields, SshUsername, profile.getSshUsername());
		put(fields, SshKeyPath, profile.getSshKeyPath());
		return fields;
	}

	/**
	 * Everything secret about a profile: the stored password and SSH password, and the URL's
	 * secrets - whatever the keychain holds under {@code id:url}, plus any still left in a
	 * URL saved before they moved there.
	 */
	private Map<String, String> secrets(ConnectionProfile profile) {
		var secrets = new LinkedHashMap<String, String>();
		String id = profile.getId();
		boolean keyed = id != null && !id.isBlank();
		putSecret(secrets, Password, keyed ? credentials.loadPassword(id) : null);
		putSecret(secrets, SshPassword, keyed ? credentials.loadPassword(CredentialStore.sshKey(id)) : null);
		putSecret(secrets, UrlSecrets, JdbcUrlSecrets.join(JdbcUrlSecrets.split(profile.getJdbcUrl()).secrets(),
				keyed ? credentials.loadPassword(CredentialStore.urlKey(id)) : null));
		return secrets;
	}

	private static Map<String, Object> field(String name, String kind) {
		return Map.of("name", name, "kind", kind);
	}

	private static Map<String, Object> secret(String name, String suffix) {
		return Map.of("name", name, "kind", "SECRET", "credentialSuffix", suffix);
	}

	private static void put(Map<String, Object> fields, String name, String value) {
		if (value != null && !value.isBlank()) {
			fields.put(name, value);
		}
	}

	private static void putSecret(Map<String, String> secrets, String name, String value) {
		if (value != null && !value.isBlank()) {
			secrets.put(name, value);
		}
	}

	private static String blankTo(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private static Map<String, String> strings(Object raw) {
		var map = new HashMap<String, String>();
		if (raw instanceof Map<?, ?> values) {
			values.forEach((key, value) -> {
				if (key instanceof String name && value instanceof String text) {
					map.put(name, text);
				}
			});
		}
		return map;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> items(Object raw) {
		var items = new ArrayList<Map<String, Object>>();
		if (raw instanceof List<?> list) {
			for (Object item : list) {
				if (item instanceof Map<?, ?> map) {
					items.add((Map<String, Object>) map);
				}
			}
		}
		return items;
	}

	private static List<String> ids(Object raw) {
		var ids = new ArrayList<String>();
		if (raw instanceof List<?> list) {
			for (Object id : list) {
				if (id instanceof String text) {
					ids.add(text);
				}
			}
		}
		return ids;
	}

	/** Field names, for tests. */
	static Set<String> fieldNames() {
		var names = new HashSet<String>();
		for (Object field : (List<?>) describe().get("fields")) {
			names.add((String) ((Map<?, ?>) field).get("name"));
		}
		return names;
	}
}
