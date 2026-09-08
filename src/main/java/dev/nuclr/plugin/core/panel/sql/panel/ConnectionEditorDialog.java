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
package dev.nuclr.plugin.core.panel.sql.panel;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.KeyStroke;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;

import dev.nuclr.plugin.core.panel.sql.connection.ConnectionProfile;
import dev.nuclr.plugin.core.panel.sql.connection.SqlConnector;
import dev.nuclr.plugin.core.panel.sql.driver.DriverCatalog;

/** F7 "New Connection…" / F4 "Edit Connection…" form. */
public final class ConnectionEditorDialog extends JDialog {

	public record Result(ConnectionProfile profile, String password, boolean rememberPassword) {
	}

	private final JTextField nameField = new JTextField(24);
	private final JComboBox<DriverCatalog.Entry> driverCombo =
			new JComboBox<>(DriverCatalog.all().toArray(new DriverCatalog.Entry[0]));
	private final JTextField hostField = new JTextField(16);
	private final JTextField portField = new JTextField(6);
	private final JTextField databaseField = new JTextField(16);
	private final JTextField jdbcUrlField = new JTextField(30);
	private final JButton buildUrlButton = new JButton("Build from fields");
	private final JTextField customClassField = new JTextField(24);
	private final JTextField customJarField = new JTextField(24);
	private final JButton browseJarButton = new JButton("Browse…");
	private final JTextField usernameField = new JTextField(16);
	private final JPasswordField passwordField = new JPasswordField(16);
	private final JCheckBox showPasswordBox = new JCheckBox("Show");
	private final JCheckBox rememberPasswordBox = new JCheckBox("Remember password (OS keychain)", true);
	private final JCheckBox useSslBox = new JCheckBox("Use SSL");
	/**
	 * Status and errors. A {@link JLabel} cannot wrap, so a driver's failure message —
	 * which routinely runs to several lines and carries the whole JDBC URL — set the
	 * label's preferred width to the length of the string and pulled the form apart.
	 * A fixed-size scrolling text area takes any message without the dialog's geometry
	 * depending on it: long text wraps, and anything past a few lines scrolls.
	 */
	private final JTextArea statusArea = new JTextArea(3, 1);

	private final JScrollPane statusScroll = new JScrollPane(statusArea,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

	/** Captured before "Show" first blanks it, so hiding again restores the theme's own bullet. */
	private final char defaultEchoChar = passwordField.getEchoChar();

	private String idBeingEdited;
	private Result result;

	private ConnectionEditorDialog(Window owner, String title) {
		super(owner, title, ModalityType.APPLICATION_MODAL);
		buildUi();
	}

	public static Result showNew(Window owner) {
		var dialog = new ConnectionEditorDialog(owner, "New SQL Connection");
		// No driver is singled out as the default: the combo lands on the first catalogue
		// entry, and the user picks what they are actually connecting to.
		dialog.onDriverChanged();
		dialog.setVisible(true);
		return dialog.result;
	}

	public static Result showEdit(Window owner, ConnectionProfile existing, boolean hasStoredPassword) {
		var dialog = new ConnectionEditorDialog(owner, "Edit SQL Connection");
		dialog.populateFrom(existing, hasStoredPassword);
		dialog.setVisible(true);
		return dialog.result;
	}

	private void populateFrom(ConnectionProfile p, boolean hasStoredPassword) {
		idBeingEdited = p.getId();
		nameField.setText(p.getName());
		driverCombo.setSelectedItem(DriverCatalog.byKey(p.getDriverKey()));
		jdbcUrlField.setText(p.getJdbcUrl());
		// The host/port/database boxes only ever fed the URL builder, so they are restored
		// from what the profile last built its URL from. A URL typed by hand saved none of
		// them, and then they simply stay empty rather than being guessed back out of it.
		hostField.setText(p.getHost());
		portField.setText(p.getPort());
		databaseField.setText(p.getDatabase());
		usernameField.setText(p.getUsername());
		useSslBox.setSelected(p.isUseSsl());
		customClassField.setText(p.getCustomDriverClassName());
		customJarField.setText(p.getCustomDriverJarPath());
		rememberPasswordBox.setSelected(hasStoredPassword);
		passwordField.setText("");
		passwordField.setToolTipText(hasStoredPassword ? "Leave blank to keep the stored password" : null);
		onDriverChanged();
	}

	private void installTooltips() {
		nameField.setToolTipText("What this connection is called in the panel. Any name you like.");
		driverCombo.setToolTipText("Built-in drivers are downloaded from Maven Central on first use and cached. "
				+ "Choose \"Custom JDBC driver…\" to supply your own jar.");
		hostField.setToolTipText("Server host name or address, used to build the JDBC URL.");
		portField.setToolTipText("Server port. Left at the driver's conventional port unless you change it.");
		databaseField.setToolTipText("Database (or catalogue) name, used to build the JDBC URL.");
		buildUrlButton.setToolTipText("Fill the JDBC URL in from the host, port and database above, "
				+ "overwriting whatever is there.");
		jdbcUrlField.setToolTipText("The URL actually used to connect. Build it from the fields above, "
				+ "or type or paste one directly.");
		customClassField.setToolTipText("Fully qualified driver class, e.g. org.h2.Driver.");
		customJarField.setToolTipText("Local jar containing that driver class. It is loaded in its own "
				+ "classloader, so it cannot clash with anything else.");
		browseJarButton.setToolTipText("Choose the driver jar.");
		usernameField.setToolTipText("Leave blank when the URL carries its own credentials or the database "
				+ "wants none — you will then never be asked for a password.");
		passwordField.setToolTipText("Only used to connect. It is never written to the profile.");
		showPasswordBox.setToolTipText("Show what you have typed, to check it before connecting.");
		rememberPasswordBox.setToolTipText("Store the password in the OS credential store — Windows Credential "
				+ "Manager, macOS Keychain, or the Linux Secret Service. Never in a settings file.");
		useSslBox.setToolTipText("Ask the driver for an encrypted connection, using whichever property "
				+ "the selected driver understands.");
		statusArea.setToolTipText("Result of Test Connection, and any problem with the form.");
	}

	private void buildUi() {
		driverCombo.setRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(
					JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
				super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				if (value instanceof DriverCatalog.Entry entry) {
					setText(entry.label());
				}
				return this;
			}
		});

		setLayout(new GridBagLayout());
		var gbc = new GridBagConstraints();
		gbc.insets = new Insets(4, 6, 4, 6);
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.anchor = GridBagConstraints.WEST;

		int row = 0;
		addRow(gbc, row++, "Name:", nameField);
		addRow(gbc, row++, "Driver:", driverCombo);

		var hostPortPanel = new JPanel(new BorderLayout(6, 0));
		hostPortPanel.add(hostField, BorderLayout.CENTER);
		var portWrap = new JPanel(new BorderLayout(4, 0));
		portWrap.add(new JLabel("Port:"), BorderLayout.WEST);
		portWrap.add(portField, BorderLayout.CENTER);
		hostPortPanel.add(portWrap, BorderLayout.EAST);
		addRow(gbc, row++, "Host:", hostPortPanel);

		addRow(gbc, row++, "Database:", databaseField);

		var urlPanel = new JPanel(new BorderLayout(6, 0));
		urlPanel.add(jdbcUrlField, BorderLayout.CENTER);
		urlPanel.add(buildUrlButton, BorderLayout.EAST);
		addRow(gbc, row++, "JDBC URL:", urlPanel);

		addRow(gbc, row++, "Driver class:", customClassField);
		var jarPanel = new JPanel(new BorderLayout(6, 0));
		jarPanel.add(customJarField, BorderLayout.CENTER);
		jarPanel.add(browseJarButton, BorderLayout.EAST);
		addRow(gbc, row++, "Driver jar:", jarPanel);

		addRow(gbc, row++, "Username:", usernameField);

		var passwordPanel = new JPanel(new BorderLayout(6, 0));
		passwordPanel.add(passwordField, BorderLayout.CENTER);
		passwordPanel.add(showPasswordBox, BorderLayout.EAST);
		addRow(gbc, row++, "Password:", passwordPanel);

		gbc.gridx = 1;
		gbc.gridy = row++;
		gbc.gridwidth = 1;
		gbc.weightx = 1;
		add(rememberPasswordBox, gbc);
		gbc.gridy = row++;
		add(useSslBox, gbc);

		// The status area is the one thing worth growing when the dialog is made taller:
		// that is where a long failure needs the room.
		gbc.gridx = 0;
		gbc.gridy = row++;
		gbc.gridwidth = 2;
		gbc.weighty = 1;
		gbc.fill = GridBagConstraints.BOTH;
		buildStatusArea();
		add(statusScroll, gbc);

		var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		var testButton = new JButton("Test Connection");
		var okButton = new JButton("OK");
		var cancelButton = new JButton("Cancel");
		buttons.add(testButton);
		buttons.add(okButton);
		buttons.add(cancelButton);
		gbc.gridx = 0;
		gbc.gridy = row;
		gbc.gridwidth = 2;
		gbc.weighty = 0;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		add(buttons, gbc);

		driverCombo.addActionListener(e -> onDriverChanged());
		buildUrlButton.addActionListener(e -> jdbcUrlField.setText(buildUrl()));
		browseJarButton.addActionListener(e -> browseForJar());
		testButton.addActionListener(e -> testConnection());
		okButton.addActionListener(e -> onOk());
		Runnable cancel = () -> {
			result = null;
			dispose();
		};
		cancelButton.addActionListener(e -> cancel.run());
		showPasswordBox.addActionListener(e -> passwordField.setEchoChar(
				showPasswordBox.isSelected() ? (char) 0 : defaultEchoChar));

		testButton.setMnemonic(KeyEvent.VK_T);
		okButton.setMnemonic(KeyEvent.VK_O);
		cancelButton.setMnemonic(KeyEvent.VK_C);
		buildUrlButton.setMnemonic(KeyEvent.VK_B);
		rememberPasswordBox.setMnemonic(KeyEvent.VK_R);
		useSslBox.setMnemonic(KeyEvent.VK_S);

		// A dialog that will not close on Escape is the kind of thing that makes software
		// feel unfinished; JDialog gives no such binding on its own.
		getRootPane().registerKeyboardAction(e -> cancel.run(),
				KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
		installTooltips();

		getRootPane().setDefaultButton(okButton);
		pack();
		// Packed size is the smallest that shows the whole form; the user can widen the
		// dialog from there to read a long failure without having to scroll it.
		setMinimumSize(getSize());
		setResizable(true);
		setLocationRelativeTo(getOwner());
	}

	private void buildStatusArea() {
		statusArea.setEditable(false);
		statusArea.setLineWrap(true);
		statusArea.setWrapStyleWord(true);
		statusArea.setFocusable(false);
		// Blend into the form rather than reading as an input the user could type into.
		statusArea.setOpaque(false);
		statusArea.setBorder(null);
		statusArea.setFont(UIManager.getFont("Label.font"));
		statusScroll.setOpaque(false);
		statusScroll.getViewport().setOpaque(false);
		statusScroll.setBorder(null);
	}

	/** How a status message reads: neutral progress, success, or failure. */
	private enum Status {
		PROGRESS, SUCCESS, FAILURE
	}

	/**
	 * Show {@code message}. Colours come from the look and feel where one exists, because
	 * the commander runs a dark theme by default and a hardcoded black would have been
	 * all but invisible on it.
	 */
	private void setStatus(Status status, String message) {
		statusArea.setForeground(switch (status) {
			case PROGRESS -> UIManager.getColor("Label.foreground");
			case SUCCESS -> successColor();
			case FAILURE -> failureColor();
		});
		statusArea.setText(message == null ? "" : message);
		statusArea.setCaretPosition(0);
	}

	private static Color successColor() {
		Color themed = UIManager.getColor("Actions.Green");
		return themed != null ? themed : new Color(0, 128, 0);
	}

	private static Color failureColor() {
		Color themed = UIManager.getColor("Component.error.focusedBorderColor");
		return themed != null ? themed : Color.RED;
	}

	/**
	 * A driver reports a failure as a chain — "connection refused" wrapped in "could not
	 * connect" wrapped in a pool error — and only the innermost link usually says what
	 * actually went wrong. Read the chain out, skipping links that repeat what their
	 * cause already said, so the message stays worth reading at any length.
	 */
	static String describeFailure(Throwable failure) {
		var seen = new LinkedHashSet<String>();
		// Bound by depth rather than by how many distinct messages have been collected: a
		// chain that repeats one message would otherwise never grow the set, and a driver
		// that hands back a self-referential cause would never end the walk.
		Throwable t = failure;
		for (int depth = 0; t != null && depth < 5; depth++, t = t.getCause()) {
			String message = t.getMessage();
			if (message == null || message.isBlank()) {
				message = t.getClass().getSimpleName();
			}
			seen.add(message.trim());
		}
		var distinct = new ArrayList<String>();
		for (String message : seen) {
			// Drop a wrapper whose text is already contained in one we kept.
			if (distinct.stream().noneMatch(kept -> kept.contains(message))) {
				distinct.removeIf(message::contains);
				distinct.add(message);
			}
		}
		return String.join("\n\nCaused by: ", distinct);
	}

	private void addRow(GridBagConstraints gbc, int row, String label, JComponent field) {
		gbc.gridx = 0;
		gbc.gridy = row;
		gbc.gridwidth = 1;
		gbc.weightx = 0;
		add(new JLabel(label), gbc);
		gbc.gridx = 1;
		// Only the field column grows, so a wider dialog means wider inputs and a wider
		// wrap for the status area rather than a form marooned in empty space.
		gbc.weightx = 1;
		add(field, gbc);
	}

	private void onDriverChanged() {
		var entry = (DriverCatalog.Entry) driverCombo.getSelectedItem();
		boolean isCustom = entry != null && "custom".equals(entry.key());
		// A custom driver has no URL template to build from, so its connection is described
		// by the JDBC URL alone; every built-in driver takes host, port and database.
		hostField.setEnabled(!isCustom);
		portField.setEnabled(!isCustom);
		databaseField.setEnabled(!isCustom);
		buildUrlButton.setEnabled(!isCustom);
		customClassField.setEnabled(isCustom);
		customJarField.setEnabled(isCustom);
		browseJarButton.setEnabled(isCustom);
		if (entry != null && entry.defaultPort() > 0 && portField.getText().isBlank()) {
			portField.setText(String.valueOf(entry.defaultPort()));
		}
	}

	private String buildUrl() {
		var entry = (DriverCatalog.Entry) driverCombo.getSelectedItem();
		if (entry == null || entry.urlTemplate() == null) {
			return jdbcUrlField.getText();
		}
		int port = parsePort(portField.getText(), entry.defaultPort());
		return String.format(entry.urlTemplate(), hostField.getText().trim(), port, databaseField.getText().trim());
	}

	private static int parsePort(String text, int fallback) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private void browseForJar() {
		var chooser = new JFileChooser();
		chooser.setDialogTitle("Choose JDBC driver jar");
		chooser.setFileFilter(new FileNameExtensionFilter("Jar files", "jar"));
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
			customJarField.setText(chooser.getSelectedFile().getAbsolutePath());
		}
	}

	private ConnectionProfile buildProfile() {
		var entry = (DriverCatalog.Entry) driverCombo.getSelectedItem();
		return ConnectionProfile.builder()
				.id(idBeingEdited)
				.name(nameField.getText().trim())
				.driverKey(entry == null ? "custom" : entry.key())
				.customDriverClassName(customClassField.getText().trim())
				.customDriverJarPath(customJarField.getText().trim())
				.jdbcUrl(jdbcUrlField.getText().trim())
				.host(hostField.getText().trim())
				.port(portField.getText().trim())
				.database(databaseField.getText().trim())
				.username(usernameField.getText().trim())
				.useSsl(useSslBox.isSelected())
				.build();
	}

	private void testConnection() {
		setStatus(Status.PROGRESS, "Testing…");
		ConnectionProfile profile = buildProfile();
		String password = new String(passwordField.getPassword());
		setInputEnabled(false);
		Thread.ofVirtual().name("sql-test-connection").start(() -> {
			Status status;
			String message;
			try {
				var opened = SqlConnector.connect(profile, password.isBlank() ? null : password);
				opened.close();
				status = Status.SUCCESS;
				message = "Connection succeeded.";
			} catch (Exception e) {
				status = Status.FAILURE;
				message = "Failed: " + describeFailure(e);
			}
			Status finalStatus = status;
			String finalMessage = message;
			SwingUtilities.invokeLater(() -> {
				setStatus(finalStatus, finalMessage);
				setInputEnabled(true);
			});
		});
	}

	private void setInputEnabled(boolean enabled) {
		for (var c : new JComponent[] {
				nameField, driverCombo, hostField, portField, databaseField, jdbcUrlField,
				customClassField, customJarField, usernameField, passwordField, showPasswordBox,
				rememberPasswordBox, useSslBox }) {
			c.setEnabled(enabled);
		}
		if (enabled) {
			onDriverChanged(); // restore the driver-specific disabled fields
		}
	}

	private void onOk() {
		if (nameField.getText().isBlank()) {
			setStatus(Status.FAILURE, "Name is required.");
			return;
		}
		if (jdbcUrlField.getText().isBlank()) {
			setStatus(Status.FAILURE, "JDBC URL is required.");
			return;
		}
		String password = new String(passwordField.getPassword());
		result = new Result(buildProfile(), password.isBlank() ? null : password, rememberPasswordBox.isSelected());
		dispose();
	}
}
