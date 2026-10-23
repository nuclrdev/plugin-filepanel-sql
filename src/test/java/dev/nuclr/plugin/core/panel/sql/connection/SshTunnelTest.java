package dev.nuclr.plugin.core.panel.sql.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SshTunnelTest {

	@Test
	void rewritesEveryBuiltInUrlShape() {
		assertEquals("jdbc:mysql://127.0.0.1:50000/shop",
				SshTunnel.rewriteUrl("jdbc:mysql://maindb-prod-dwh1.prod.gtau.net:3306/shop",
						"maindb-prod-dwh1.prod.gtau.net", 3306, 50000));
		assertEquals("jdbc:postgresql://127.0.0.1:50000/app?sslmode=require",
				SshTunnel.rewriteUrl("jdbc:postgresql://db:5432/app?sslmode=require", "db", 5432, 50000));
		assertEquals("jdbc:sqlserver://127.0.0.1:50000;databaseName=x",
				SshTunnel.rewriteUrl("jdbc:sqlserver://DB.local:1433;databaseName=x", "db.local", 1433, 50000));
	}

	@Test
	void rewritesHostWithoutPort() {
		assertEquals("jdbc:postgresql://127.0.0.1:50000/app",
				SshTunnel.rewriteUrl("jdbc:postgresql://db/app", "db", 5432, 50000));
	}

	@Test
	void leavesLookalikeHostsAlone() {
		// "proddb:5432" must not match inside "replica-proddb:5432".
		assertEquals("jdbc:postgresql://replica-proddb:5432,127.0.0.1:50000/app",
				SshTunnel.rewriteUrl("jdbc:postgresql://replica-proddb:5432,proddb:5432/app", "proddb", 5432, 50000));
	}

	@Test
	void refusesUrlForAnotherHost() {
		assertThrows(IllegalArgumentException.class,
				() -> SshTunnel.rewriteUrl("jdbc:mysql://other:3306/x", "db", 3306, 50000));
		assertThrows(IllegalArgumentException.class,
				() -> SshTunnel.rewriteUrl("jdbc:mysql://db:33060/x", "db", 3306, 50000));
	}

	/**
	 * A real round trip: an in-process SSH server, an echo socket standing in for the
	 * database, and bytes sent to the tunnel's local end coming back through it. The
	 * server's key is written to known_hosts first, since a headless run trusts nothing.
	 */
	@Test
	void carriesBytesThroughTheTunnel(@TempDir Path home) throws Exception {
		String realHome = System.getProperty("user.home");
		System.setProperty("user.home", home.toString());
		var sshd = SshServer.setUpDefaultServer();
		try (var echo = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			Thread.ofVirtual().start(() -> {
				try (Socket s = echo.accept()) {
					s.getInputStream().transferTo(s.getOutputStream());
				} catch (Exception ignored) {
					// test over
				}
			});

			var hostKeys = new SimpleGeneratorHostKeyProvider(home.resolve("hostkey.ser"));
			sshd.setKeyPairProvider(hostKeys);
			sshd.setHost("127.0.0.1");
			sshd.setPort(0);
			sshd.setPasswordAuthenticator((user, password, session) -> "dba".equals(user) && "s3cret".equals(password));
			sshd.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
			sshd.start();

			PublicKey serverKey = hostKeys.loadKeys(null).iterator().next().getPublic();
			Path knownHosts = home.resolve(".nuclr/net/known_hosts");
			Files.createDirectories(knownHosts.getParent());
			Files.writeString(knownHosts, "[127.0.0.1]:" + sshd.getPort() + " "
					+ PublicKeyEntry.toString(serverKey) + "\n");

			var profile = ConnectionProfile.builder().sshEnabled(true).sshHost("127.0.0.1")
					.sshPort(String.valueOf(sshd.getPort())).sshUsername("dba").build();
			try (var tunnel = SshTunnel.open(profile, "s3cret", "127.0.0.1", echo.getLocalPort());
					var client = new Socket(InetAddress.getLoopbackAddress(), tunnel.localPort())) {
				client.getOutputStream().write("SELECT 1".getBytes(StandardCharsets.US_ASCII));
				client.getOutputStream().flush();
				assertEquals("SELECT 1", new String(client.getInputStream().readNBytes(8), StandardCharsets.US_ASCII));
			}
		} finally {
			sshd.stop(true);
			System.setProperty("user.home", realHome);
		}
	}

	@Test
	void missingSshHostFails() {
		var profile = ConnectionProfile.builder().sshEnabled(true).sshHost("").build();
		assertThrows(java.io.IOException.class, () -> SshTunnel.open(profile, "x", "db", 3306));
	}

	@Test
	void expandsHome() {
		assertEquals(Path.of(System.getProperty("user.home"), ".ssh", "google_compute_engine"),
				SshTunnel.expandHome("~/.ssh/google_compute_engine"));
		assertEquals(Path.of("/etc/key"), SshTunnel.expandHome("/etc/key"));
	}

	@Test
	void sshPasswordOnlyAskedForWithoutKey() {
		var profile = ConnectionProfile.builder().sshEnabled(true).build();
		assertEquals(true, profile.needsSshPassword());
		profile.setSshKeyPath("~/.ssh/id_ed25519");
		assertEquals(false, profile.needsSshPassword());
		profile.setSshEnabled(false);
		profile.setSshKeyPath(null);
		assertEquals(false, profile.needsSshPassword());
	}
}
