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
package dev.nuclr.plugin.core.panel.sql.quickview;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;

import dev.nuclr.platform.NuclrThemeScheme;

import dev.nuclr.plugin.core.panel.sql.resource.SqlNuclrResource;
import lombok.extern.slf4j.Slf4j;

/**
 * QuickView (Ctrl+Q) preview for a table or view: a small, bounded grid. Deliberately a
 * much smaller cap than the file panel's own table listing (50k) — this is a quick
 * preview, not the primary browsing surface, so it stays cheap and fast to render.
 */
@Slf4j
public final class TableGridPanel extends JPanel {

	private static final int PREVIEW_ROW_CAP = 500;

	private final DefaultTableModel model = new DefaultTableModel() {
		@Override
		public boolean isCellEditable(int row, int column) {
			return false;
		}
	};
	private final QuickViewTheme theme;

	private final JTable table = new JTable(model) {

		/**
		 * Paint a selected row in the theme's mark colours rather than the look and feel's
		 * own, so a run of rows picked here matches a run of entries marked in the panel.
		 * Resolved per row, so a theme change lands on the next repaint.
		 */
		@Override
		public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
			Component cell = super.prepareRenderer(renderer, row, column);
			if (isRowSelected(row)) {
				cell.setBackground(theme.markBackground(getBackground()));
				cell.setForeground(theme.markColor(getBackground()));
			} else {
				cell.setBackground(getBackground());
				cell.setForeground(getForeground());
			}
			return cell;
		}

		/**
		 * Right-clicking a row the current selection does not cover moves the selection
		 * there first, so "Copy" acts on the row under the cursor rather than on whatever
		 * happened to be selected beforehand. Swing calls this to place the component
		 * popup and passes {@code null} when it came from the keyboard menu key — which
		 * must leave a selection the user built with the keyboard alone.
		 */
		@Override
		public Point getPopupLocation(MouseEvent event) {
			if (event != null) {
				int row = rowAtPoint(event.getPoint());
				if (row >= 0 && !isRowSelected(row)) {
					setRowSelectionInterval(row, row);
				}
			}
			return super.getPopupLocation(event);
		}
	};
	private final JLabel statusLabel = new JLabel(" ");

	/** Whether the last load stopped at {@link #PREVIEW_ROW_CAP}, for the status line. */
	private boolean truncatedPreview;

	public TableGridPanel(Supplier<NuclrThemeScheme> theme) {
		super(new BorderLayout());
		this.theme = new QuickViewTheme(theme);
		table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		// Ctrl-click to add a row, shift-click to extend a run — the same reach the panel's
		// own marking gives, so a selection can be copied or exported as one.
		table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		table.getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				updateStatus();
			}
		});
		// Let the grid own the whole viewport, so a right-click in the empty space under
		// the last row still lands on the table and brings the menu up.
		table.setFillsViewportHeight(true);
		var scrollPane = new JScrollPane(table);
		scrollPane.setComponentPopupMenu(ClipboardContextMenu.install(table));
		add(scrollPane, BorderLayout.CENTER);
		add(statusLabel, BorderLayout.SOUTH);
	}

	public void show(SqlNuclrResource tableResource, AtomicBoolean cancelled) {
		statusLabel.setText("Loading…");
		model.setRowCount(0);
		model.setColumnCount(0);

		Thread.ofVirtual().name("sql-quickview-grid").start(() -> {
			try {
				// Shared, long-lived connection owned by the file panel's ConnectionRegistry —
				// never close it here.
				Connection connection = tableResource.openConnection();
				String sql = "SELECT * FROM " + tableResource.getQuotedTableRef();
				try (Statement statement = connection.createStatement()) {
					statement.setMaxRows(PREVIEW_ROW_CAP + 1);
					try (ResultSet rs = statement.executeQuery(sql)) {
						ResultSetMetaData meta = rs.getMetaData();
						int columnCount = meta.getColumnCount();
						List<String> columns = new ArrayList<>(columnCount);
						for (int i = 1; i <= columnCount; i++) {
							columns.add(meta.getColumnLabel(i));
						}

						List<Object[]> rows = new ArrayList<>();
						int index = 0;
						boolean truncated = false;
						while (rs.next()) {
							if (cancelled != null && cancelled.get()) {
								break;
							}
							if (index >= PREVIEW_ROW_CAP) {
								truncated = true;
								break;
							}
							Object[] row = new Object[columnCount];
							for (int i = 1; i <= columnCount; i++) {
								row[i - 1] = rs.getObject(i);
							}
							rows.add(row);
							index++;
						}

						boolean finalTruncated = truncated;
						SwingUtilities.invokeLater(() -> {
							model.setColumnIdentifiers(columns.toArray());
							for (Object[] row : rows) {
								model.addRow(row);
							}
							truncatedPreview = finalTruncated;
							updateStatus();
						});
					}
				}
			} catch (Exception e) {
				log.debug("SQL quickview grid failed for {}: {}", tableResource.getFullPath(), e.getMessage());
				SwingUtilities.invokeLater(() -> statusLabel.setText("Failed to load: " + e.getMessage()));
			}
		});
	}

	/** Test seam: the grid itself, for exercising its right-click row targeting. */
	JTable grid() {
		return table;
	}

	public void clear() {
		model.setRowCount(0);
		model.setColumnCount(0);
		truncatedPreview = false;
		statusLabel.setText(" ");
	}

	/**
	 * How much is loaded, and how much of it is picked. Without the second half a
	 * multi-row selection is only visible as colour, which is no use for confirming that
	 * a Ctrl-click landed where it was meant to before copying.
	 */
	private void updateStatus() {
		int rows = model.getRowCount();
		int selected = table.getSelectedRowCount();
		var status = new StringBuilder()
				.append(rows)
				.append(rows == 1 ? " row" : " rows");
		if (truncatedPreview) {
			status.append(" (preview truncated at ").append(PREVIEW_ROW_CAP).append(')');
		}
		if (selected > 0) {
			status.append(" — ").append(selected).append(" selected");
		}
		statusLabel.setText(status.toString());
	}

	/** Test seam: the status line as the user reads it. */
	String statusText() {
		return statusLabel.getText();
	}
}
