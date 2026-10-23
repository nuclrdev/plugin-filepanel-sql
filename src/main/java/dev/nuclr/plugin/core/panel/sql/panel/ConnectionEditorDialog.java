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

import static dev.nuclr.plugin.core.panel.sql.Messages.tr;

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
import javax.swing.JTabbedPane;
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

	public record Result(ConnectionProfile profile, String password, boolean rememberPassword,
			String sshPassword, boolean rememberSshPassword) {
	}

	private final JTextField nameField = new JTextField(24);
	private final JComboBox<DriverCatalog.Entry> driverCombo =
			new JComboBox<>(DriverCatalog.all().toArray(new DriverCatalog.Entry[0]));
	private final JTextField hostField = new JTextField(16);
	private final JTextField portField = new JTextField(6);
	private final JTextField databaseField = new JTextField(16);
	private final JTextField jdbcUrlField = new JTextField(30);
	private final JButton buildUrlButton = new JButton(tr("editor.build.url"));
	private final JTextField customClassField = new JTextField(24);
	private final JTextField customJarField = new JTextField(24);
	private final JButton browseJarButton = new JButton(tr("editor.browse"));
	private final JTextField usernameField = new JTextField(16);
	private final JPasswordField passwordField = new JPasswordField(16);
	private final JCheckBox showPasswordBox = new JCheckBox(tr("editor.show.password"));
	private final JCheckBox rememberPasswordBox = new JCheckBox(tr("password.remember"), true);
	private final JCheckBox useSslBox = new JCheckBox(tr("editor.use.ssl"));
	private final JCheckBox sshEnabledBox = new JCheckBox(tr("editor.ssh.enabled"));
	private final JTextField sshHostField = new JTextField(16);
	private final JTextField sshPortField = new JTextField(6);
	private final JTextField sshUsernameField = new JTextField(16);
	private final JTextField sshKeyField = new JTextField(24);
	private final JButton browseKeyButton = new JButton(tr("editor.browse"));
	private final JPasswordField sshPasswordField = new JPasswordField(16);
	private final JCheckBox rememberSshPasswordBox = new JCheckBox(tr("password.remember"), true);
	private final JTabbedPane tabs = new JTabbedPane();
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
		var dialog = new ConnectionEditorDialog(owner, tr("editor.title.new"));
		// No driver is singled out as the default: the combo lands on the first catalogue
		// entry, and the user picks what they are actually connecting to.
		dialog.onDriverChanged();
		dialog.setVisible(true);
		return dialog.result;
	}

	public static Result showEdit(Window owner, ConnectionProfile existing, boolean hasStoredPassword,
			boolean hasStoredSshPassword) {
		var dialog = new ConnectionEditorDialog(owner, tr("editor.title.edit"));
		dialog.populateFrom(existing, hasStoredPassword, hasStoredSshPassword);
		dialog.setVisible(true);
		return dialog.result;
	}

	private void populateFrom(ConnectionProfile p, boolean hasStoredPassword, boolean hasStoredSshPassword) {
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
		passwordField.setToolTipText(hasStoredPassword ? tr("editor.password.keep.tooltip") : null);
		sshEnabledBox.setSelected(p.isSshEnabled());
		sshHostField.setText(p.getSshHost());
		if (p.getSshPort() != null && !p.getSshPort().isBlank()) {
			sshPortField.setText(p.getSshPort());
		}
		sshUsernameField.setText(p.getSshUsername());
		sshKeyField.setText(p.getSshKeyPath());
		rememberSshPasswordBox.setSelected(hasStoredSshPassword);
		sshPasswordField.setText("");
		sshPasswordField.setToolTipText(hasStoredSshPassword ? tr("editor.password.keep.tooltip") : null);
		onDriverChanged();
		onSshToggled();
	}

	private void installTooltips() {
		nameField.setToolTipText(tr("editor.name.tooltip"));
		driverCombo.setToolTipText(tr("editor.driver.tooltip"));
		hostField.setToolTipText(tr("editor.host.tooltip"));
		portField.setToolTipText(tr("editor.port.tooltip"));
		databaseField.setToolTipText(tr("editor.database.tooltip"));
		buildUrlButton.setToolTipText(tr("editor.build.url.tooltip"));
		jdbcUrlField.setToolTipText(tr("editor.url.tooltip"));
		customClassField.setToolTipText(tr("editor.driver.class.tooltip"));
		customJarField.setToolTipText(tr("editor.driver.jar.tooltip"));
		browseJarButton.setToolTipText(tr("editor.browse.tooltip"));
		usernameField.setToolTipText(tr("editor.username.tooltip"));
		passwordField.setToolTipText(tr("editor.password.tooltip"));
		showPasswordBox.setToolTipText(tr("editor.show.password.tooltip"));
		rememberPasswordBox.setToolTipText(tr("editor.remember.tooltip"));
		useSslBox.setToolTipText(tr("editor.use.ssl.tooltip"));
		statusArea.setToolTipText(tr("editor.status.tooltip"));
		sshEnabledBox.setToolTipText(tr("editor.ssh.enabled.tooltip"));
		sshHostField.setToolTipText(tr("editor.ssh.host.tooltip"));
		sshPortField.setToolTipText(tr("editor.ssh.port.tooltip"));
		sshUsernameField.setToolTipText(tr("editor.ssh.username.tooltip"));
		sshKeyField.setToolTipText(tr("editor.ssh.key.tooltip"));
		browseKeyButton.setToolTipText(tr("editor.ssh.browse.tooltip"));
		sshPasswordField.setToolTipText(tr("editor.ssh.password.tooltip"));
		rememberSshPasswordBox.setToolTipText(tr("editor.remember.tooltip"));
	}

	private void buildUi() {
		driverCombo.setRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(
					JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
				super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				if (value instanceof DriverCatalog.Entry entry) {
					setText(entry == DriverCatalog.CUSTOM ? tr("editor.driver.custom") : entry.label());
				}
				return this;
			}
		});

		var form = new JPanel(new GridBagLayout());
		var gbc = new GridBagConstraints();
		gbc.insets = new Insets(4, 6, 4, 6);
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.anchor = GridBagConstraints.WEST;

		int row = 0;
		addRow(form, gbc, row++, tr("editor.label.name"), nameField);
		addRow(form, gbc, row++, tr("editor.label.driver"), driverCombo);
		addRow(form, gbc, row++, tr("editor.label.host"), withPort(hostField, portField));
		addRow(form, gbc, row++, tr("editor.label.database"), databaseField);

		var urlPanel = new JPanel(new BorderLayout(6, 0));
		urlPanel.add(jdbcUrlField, BorderLayout.CENTER);
		urlPanel.add(buildUrlButton, BorderLayout.EAST);
		addRow(form, gbc, row++, "JDBC URL:", urlPanel);

		addRow(form, gbc, row++, tr("editor.label.driver.class"), customClassField);
		var jarPanel = new JPanel(new BorderLayout(6, 0));
		jarPanel.add(customJarField, BorderLayout.CENTER);
		jarPanel.add(browseJarButton, BorderLayout.EAST);
		addRow(form, gbc, row++, tr("editor.label.driver.jar"), jarPanel);

		addRow(form, gbc, row++, tr("editor.label.username"), usernameField);

		var passwordPanel = new JPanel(new BorderLayout(6, 0));
		passwordPanel.add(passwordField, BorderLayout.CENTER);
		passwordPanel.add(showPasswordBox, BorderLayout.EAST);
		addRow(form, gbc, row++, tr("editor.label.password"), passwordPanel);

		addRow(form, gbc, row++, null, rememberPasswordBox);
		addRow(form, gbc, row++, null, useSslBox);
		pushToTop(form, gbc, row);

		var ssh = new JPanel(new GridBagLayout());
		row = 0;
		addRow(ssh, gbc, row++, null, sshEnabledBox);
		addRow(ssh, gbc, row++, tr("editor.label.ssh.host"), withPort(sshHostField, sshPortField));
		addRow(ssh, gbc, row++, tr("editor.label.username"), sshUsernameField);
		var keyPanel = new JPanel(new BorderLayout(6, 0));
		keyPanel.add(sshKeyField, BorderLayout.CENTER);
		keyPanel.add(browseKeyButton, BorderLayout.EAST);
		addRow(ssh, gbc, row++, tr("editor.label.ssh.key"), keyPanel);
		addRow(ssh, gbc, row++, tr("editor.label.ssh.password"), sshPasswordField);
		addRow(ssh, gbc, row++, null, rememberSshPasswordBox);
		var sshHint = new JLabel(tr("editor.ssh.hint"));
		sshHint.setForeground(UIManager.getColor("Label.disabledForeground"));
		addRow(ssh, gbc, row++, null, sshHint);
		pushToTop(ssh, gbc, row);
		sshPortField.setText("22");

		tabs.addTab(tr("editor.tab.connection"), form);
		tabs.addTab(tr("editor.tab.ssh"), ssh);
		tabs.setMnemonicAt(0, KeyEvent.VK_N);
		tabs.setMnemonicAt(1, KeyEvent.VK_H);

		setLayout(new GridBagLayout());
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.gridwidth = 1;
		gbc.weightx = 1;
		gbc.weighty = 0;
		gbc.fill = GridBagConstraints.BOTH;
		add(tabs, gbc);
		row = 1;

		// The status area is the one thing worth growing when the dialog is made taller:
		// that is where a long failure needs the room.
		gbc.gridy = row++;
		gbc.weighty = 1;
		buildStatusArea();
		add(statusScroll, gbc);

		var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		var testButton = new JButton(tr("editor.test"));
		var okButton = new JButton(tr("common.ok"));
		var cancelButton = new JButton(tr("common.cancel"));
		buttons.add(testButton);
		buttons.add(okButton);
		buttons.add(cancelButton);
		gbc.gridy = row;
		gbc.weighty = 0;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		add(buttons, gbc);

		driverCombo.addActionListener(e -> onDriverChanged());
		sshEnabledBox.addActionListener(e -> onSshToggled());
		browseKeyButton.addActionListener(e -> browseForKey());
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
		sshEnabledBox.setMnemonic(KeyEvent.VK_U);
		onSshToggled();

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
		return String.join("\n\n" + tr("editor.caused.by") + " ", distinct);
	}

	/** @param label the row's label, or {@code null} for a field under the field column alone */
	private static void addRow(JPanel panel, GridBagConstraints gbc, int row, String label, JComponent field) {
		gbc.gridy = row;
		gbc.gridwidth = 1;
		gbc.weighty = 0;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		if (label != null) {
			gbc.gridx = 0;
			gbc.weightx = 0;
			panel.add(new JLabel(label), gbc);
		}
		gbc.gridx = 1;
		// Only the field column grows, so a wider dialog means wider inputs and a wider
		// wrap for the status area rather than a form marooned in empty space.
		gbc.weightx = 1;
		panel.add(field, gbc);
	}

	/** The two tabs differ in height; this keeps the shorter one's rows at the top. */
	private static void pushToTop(JPanel panel, GridBagConstraints gbc, int row) {
		gbc.gridx = 0;
		gbc.gridy = row;
		gbc.gridwidth = 2;
		gbc.weighty = 1;
		panel.add(new JPanel(), gbc);
		gbc.weighty = 0;
	}

	private static JPanel withPort(JTextField host, JTextField port) {
		var hostPortPanel = new JPanel(new BorderLayout(6, 0));
		hostPortPanel.add(host, BorderLayout.CENTER);
		var portWrap = new JPanel(new BorderLayout(4, 0));
		portWrap.add(new JLabel(tr("editor.label.port")), BorderLayout.WEST);
		portWrap.add(port, BorderLayout.CENTER);
		hostPortPanel.add(portWrap, BorderLayout.EAST);
		return hostPortPanel;
	}

	private void onSshToggled() {
		boolean on = sshEnabledBox.isSelected() && sshEnabledBox.isEnabled();
		for (var c : new JComponent[] {
				sshHostField, sshPortField, sshUsernameField, sshKeyField, browseKeyButton,
				sshPasswordField, rememberSshPasswordBox }) {
			c.setEnabled(on);
		}
	}

	private void browseForKey() {
		var chooser = new JFileChooser();
		chooser.setDialogTitle(tr("editor.ssh.key.chooser.title"));
		chooser.setFileHidingEnabled(false);
		var sshDir = new java.io.File(System.getProperty("user.home"), ".ssh");
		if (sshDir.isDirectory()) {
			chooser.setCurrentDirectory(sshDir);
		}
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
			sshKeyField.setText(chooser.getSelectedFile().getAbsolutePath());
		}
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
		chooser.setDialogTitle(tr("editor.jar.chooser.title"));
		chooser.setFileFilter(new FileNameExtensionFilter(tr("editor.jar.filter"), "jar"));
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
				.sshEnabled(sshEnabledBox.isSelected())
				.sshHost(sshHostField.getText().trim())
				.sshPort(sshPortField.getText().trim())
				.sshUsername(sshUsernameField.getText().trim())
				.sshKeyPath(sshKeyField.getText().trim())
				.build();
	}

	private void testConnection() {
		setStatus(Status.PROGRESS, tr("editor.testing"));
		ConnectionProfile profile = buildProfile();
		String password = new String(passwordField.getPassword());
		String sshPassword = new String(sshPasswordField.getPassword());
		setInputEnabled(false);
		Thread.ofVirtual().name("sql-test-connection").start(() -> {
			Status status;
			String message;
			try {
				var opened = SqlConnector.connect(profile, password.isBlank() ? null : password,
						sshPassword.isEmpty() ? null : sshPassword);
				opened.close();
				status = Status.SUCCESS;
				message = tr("editor.test.ok");
			} catch (Exception e) {
				status = Status.FAILURE;
				message = tr("editor.test.failed", describeFailure(e));
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
				rememberPasswordBox, useSslBox, sshEnabledBox, sshHostField, sshPortField, sshUsernameField,
				sshKeyField, browseKeyButton, sshPasswordField, rememberSshPasswordBox }) {
			c.setEnabled(enabled);
		}
		if (enabled) {
			onDriverChanged(); // restore the driver-specific disabled fields
			onSshToggled();
		}
	}

	private void onOk() {
		if (nameField.getText().isBlank()) {
			setStatus(Status.FAILURE, tr("editor.name.required"));
			return;
		}
		if (jdbcUrlField.getText().isBlank()) {
			setStatus(Status.FAILURE, tr("editor.url.required"));
			return;
		}
		if (sshEnabledBox.isSelected() && sshHostField.getText().isBlank()) {
			tabs.setSelectedIndex(1);
			setStatus(Status.FAILURE, tr("editor.ssh.host.required"));
			return;
		}
		if (sshEnabledBox.isSelected() && !hasTunnelTarget()) {
			tabs.setSelectedIndex(0);
			setStatus(Status.FAILURE, tr("ssh.error.no.target"));
			return;
		}
		String password = new String(passwordField.getPassword());
		String sshPassword = new String(sshPasswordField.getPassword());
		result = new Result(buildProfile(), password.isBlank() ? null : password, rememberPasswordBox.isSelected(),
				sshPassword.isEmpty() ? null : sshPassword, rememberSshPasswordBox.isSelected());
		dispose();
	}

	/**
	 * A tunnel goes to the database host and port as the SSH server sees them, which are
	 * the Host and Port fields; a custom driver has neither, so it cannot be tunnelled.
	 */
	private boolean hasTunnelTarget() {
		var entry = (DriverCatalog.Entry) driverCombo.getSelectedItem();
		return entry != null && !"custom".equals(entry.key()) && !hostField.getText().isBlank();
	}
}
