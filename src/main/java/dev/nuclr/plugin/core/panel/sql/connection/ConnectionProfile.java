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

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonIgnore;

import dev.nuclr.plugin.core.panel.sql.driver.DriverCatalog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A saved SQL connection's non-secret metadata. Plain POJO — persisted as JSON by
 * {@link ConnectionProfileStore}; the password is deliberately never a field here (see
 * {@link CredentialStore}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionProfile implements Serializable {

	private String id;

	private String name;

	/** {@link DriverCatalog.Entry#key()}, or {@code "custom"}. */
	private String driverKey;

	/** Only set when {@code driverKey == "custom"}. */
	private String customDriverClassName;

	/** Only set when {@code driverKey == "custom"}: local path to the driver jar. */
	private String customDriverJarPath;

	/** Full JDBC URL, either typed directly or built from host/port/database in the editor. */
	private String jdbcUrl;

	/**
	 * The host/port/database the URL was last built from, kept only so
	 * {@code ConnectionEditorDialog} can show the same fields again on a later edit.
	 * {@link #jdbcUrl} stays the single source of truth for connecting — a URL typed
	 * by hand leaves these blank, and that is fine.
	 */
	private String host;

	private String port;

	/** Database name, as the URL builder uses it. */
	private String database;

	private String username;

	private boolean useSsl;

	/**
	 * Reach the database through an SSH tunnel: {@link #host} and {@link #port} are then
	 * the database as the SSH server sees it, and the JDBC URL is pointed at the local
	 * end of the tunnel on connect (see {@link SshTunnel}).
	 */
	private boolean sshEnabled;

	private String sshHost;

	/** Blank means 22. */
	private String sshPort;

	/** Blank means the local user name, as {@code ssh} itself does. */
	private String sshUsername;

	/** Private key file; blank to log in with the SSH password alone. */
	private String sshKeyPath;

	@JsonIgnore
	public String getDriverLabel() {
		if ("custom".equals(driverKey)) {
			return customDriverClassName != null && !customDriverClassName.isBlank()
					? customDriverClassName
					: "Custom driver";
		}
		DriverCatalog.Entry entry = DriverCatalog.byKey(driverKey);
		if (entry == DriverCatalog.CUSTOM) {
			// A key this build no longer offers — a profile saved against a driver that has
			// since been retired. Naming it says what the connection actually wants, where
			// the catalogue's own fallback label ("Custom JDBC driver…") would just look
			// like an unfinished menu entry sitting in the Driver column.
			return (driverKey == null || driverKey.isBlank() ? "Unknown" : driverKey) + " (unavailable)";
		}
		return entry.label();
	}

	/** Shown as the "Host" column for the connection entry in the explorer root listing. */
	@JsonIgnore
	public String getDisplayTarget() {
		String url = jdbcUrl == null ? "" : jdbcUrl;
		return sshEnabled && sshHost != null && !sshHost.isBlank() ? url + " via " + sshHost : url;
	}

	/** Whether connecting should ask for a password when none is stored. */
	public boolean needsPassword() {
		return username != null && !username.isBlank();
	}

	/**
	 * Whether connecting should ask for an SSH password when none is stored. A key file
	 * logs in on its own, so only a password-only tunnel prompts; an encrypted key's
	 * passphrase is entered in the editor instead.
	 */
	public boolean needsSshPassword() {
		return sshEnabled && (sshKeyPath == null || sshKeyPath.isBlank());
	}
}
