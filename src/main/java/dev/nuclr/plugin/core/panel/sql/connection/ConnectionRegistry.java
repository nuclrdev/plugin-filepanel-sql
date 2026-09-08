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
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.nuclr.plugin.core.panel.sql.driver.DriverClassLoader;
import lombok.extern.slf4j.Slf4j;

/**
 * Holds the live {@link Connection} (and the {@link DriverClassLoader} that loaded its
 * driver) for every currently-open connection profile, keyed by profile id.
 */
@Slf4j
public final class ConnectionRegistry {

	/** @param quoteString the connection's SQL identifier quote character, e.g. {@code "\""}. */
	public record Session(Connection connection, DriverClassLoader driverLoader, String quoteString) {
	}

	private final Map<String, Session> sessions = new ConcurrentHashMap<>();

	public boolean isConnected(String profileId) {
		return sessions.containsKey(profileId);
	}

	public Session get(String profileId) {
		return sessions.get(profileId);
	}

	public Connection getOpenConnection(String profileId) {
		Session session = sessions.get(profileId);
		if (session == null) {
			throw new IllegalStateException("Not connected: " + profileId);
		}
		return session.connection();
	}

	/** Connects if not already connected; otherwise returns the existing session. */
	public synchronized Session connect(ConnectionProfile profile, String password) throws Exception {
		Session existing = sessions.get(profile.getId());
		if (existing != null) {
			return existing;
		}

		SqlConnector.Opened opened = SqlConnector.connect(profile, password);
		String quoteString = identifierQuoteString(opened.connection());
		Session session = new Session(opened.connection(), opened.driverLoader(), quoteString);
		sessions.put(profile.getId(), session);
		return session;
	}

	private static String identifierQuoteString(Connection connection) {
		try {
			String quote = connection.getMetaData().getIdentifierQuoteString();
			return quote == null || quote.isBlank() ? "\"" : quote;
		} catch (SQLException e) {
			return "\"";
		}
	}

	public synchronized void disconnect(String profileId) {
		Session session = sessions.remove(profileId);
		if (session != null) {
			closeQuietly(session);
		}
	}

	public synchronized void disconnectAll() {
		for (String id : List.copyOf(sessions.keySet())) {
			disconnect(id);
		}
	}

	private void closeQuietly(Session session) {
		try {
			session.connection().close();
		} catch (SQLException e) {
			log.debug("Error closing SQL connection: {}", e.getMessage());
		}
		session.driverLoader().close();
	}
}
