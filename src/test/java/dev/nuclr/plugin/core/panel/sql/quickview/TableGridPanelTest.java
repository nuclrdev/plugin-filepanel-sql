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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Point;
import java.awt.event.MouseEvent;

import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.nuclr.platform.NuclrThemeScheme;

/** Where a right-click leaves the selection before the copy menu opens over it. */
class TableGridPanelTest {

	private TableGridPanel panel;
	private JTable table;

	@BeforeEach
	void setUp() {
		panel = new TableGridPanel(() -> null);
		table = panel.grid();
		var model = (DefaultTableModel) table.getModel();
		model.setColumnIdentifiers(new Object[] { "id", "name" });
		model.addRow(new Object[] { 1, "Ada" });
		model.addRow(new Object[] { 2, "Grace" });
		model.addRow(new Object[] { 3, "Bob" });
		// Give the grid a geometry so rowAtPoint has something to answer with.
		table.setSize(300, table.getRowHeight() * 3);
		table.doLayout();
	}

	private Point rightClickOnRow(int row) {
		var event = new MouseEvent(table, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
				0, 10, table.getRowHeight() * row + 2, 1, true, MouseEvent.BUTTON3);
		return table.getPopupLocation(event);
	}

	@Test
	void theMenuIsInstalledOnTheGrid() {
		assertNotNull(table.getComponentPopupMenu());
	}

	@Test
	void theGridFillsItsViewportSoARightClickBelowTheRowsStillFindsIt() {
		assertTrue(table.getFillsViewportHeight());
	}

	@Test
	void theMarginBesideTheColumnsAnswersTheSameMenu() {
		// AUTO_RESIZE_OFF leaves the grid narrower than its viewport, and that margin is
		// the scroll pane rather than the table.
		var scrollPane = (javax.swing.JScrollPane) java.util.Arrays.stream(panel.getComponents())
				.filter(javax.swing.JScrollPane.class::isInstance)
				.findFirst()
				.orElseThrow();

		assertNotNull(scrollPane.getComponentPopupMenu());
		assertSame(table.getComponentPopupMenu(), scrollPane.getComponentPopupMenu());
	}

	@Test
	void rightClickingAnUnselectedRowSelectsItSoCopyActsOnWhatIsUnderTheCursor() {
		table.setRowSelectionInterval(0, 0);

		rightClickOnRow(2);

		assertEquals(1, table.getSelectedRowCount());
		assertEquals(2, table.getSelectedRow());
	}

	@Test
	void rightClickingInsideAMultiRowSelectionKeepsIt() {
		table.setRowSelectionInterval(0, 2);

		rightClickOnRow(1);

		assertEquals(3, table.getSelectedRowCount(), "a right-click inside the selection must not shrink it");
	}

	@Test
	void theKeyboardMenuKeyLeavesTheSelectionAlone() {
		table.setRowSelectionInterval(0, 1);

		// Swing passes null when the popup was asked for from the keyboard.
		table.getPopupLocation(null);

		assertEquals(2, table.getSelectedRowCount());
	}

	@Test
	void clearingTheGridEmptiesItWithoutLosingTheMenu() {
		table.setRowSelectionInterval(0, 0);

		panel.clear();

		assertEquals(0, table.getRowCount());
		assertNotNull(table.getComponentPopupMenu());
	}

	// ------------------------------------------------------------------
	// Multiple selection
	// ------------------------------------------------------------------

	@Test
	void severalRowsCanBeSelectedAtOnce() {
		assertEquals(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION, table.getSelectionModel().getSelectionMode());

		table.setRowSelectionInterval(0, 1);
		table.addRowSelectionInterval(2, 2);

		assertEquals(3, table.getSelectedRowCount());
	}

	@Test
	void aSelectedRowIsPaintedInTheThemeAccentRatherThanTheLookAndFeelDefault() {
		var themed = new TableGridPanel(() -> new NuclrThemeScheme("test",
				java.util.Map.of("file-mark", "#FF0000", "file-mark-background", "#000080")));
		var grid = themed.grid();
		var model = (DefaultTableModel) grid.getModel();
		model.setColumnIdentifiers(new Object[] { "id" });
		model.addRow(new Object[] { 1 });
		model.addRow(new Object[] { 2 });
		grid.setRowSelectionInterval(0, 0);

		// prepareRenderer hands back one shared component per column, so read its colours
		// before asking about the next row or the second call overwrites the first.
		var selected = grid.prepareRenderer(grid.getCellRenderer(0, 0), 0, 0);
		Color selectedForeground = selected.getForeground();
		Color selectedBackground = selected.getBackground();
		var unselected = grid.prepareRenderer(grid.getCellRenderer(1, 0), 1, 0);
		Color unselectedBackground = unselected.getBackground();

		assertEquals(Color.RED, selectedForeground);
		assertEquals(new Color(0, 0, 128), selectedBackground);
		assertEquals(grid.getBackground(), unselectedBackground);
	}

	@Test
	void everyRowOfAMultiRowSelectionIsPainted() {
		var themed = new TableGridPanel(() -> new NuclrThemeScheme("test", java.util.Map.of("file-mark", "#FF0000")));
		var grid = themed.grid();
		var model = (DefaultTableModel) grid.getModel();
		model.setColumnIdentifiers(new Object[] { "id" });
		for (int i = 0; i < 3; i++) {
			model.addRow(new Object[] { i });
		}
		grid.setRowSelectionInterval(0, 2);

		for (int row = 0; row < 3; row++) {
			assertEquals(Color.RED, grid.prepareRenderer(grid.getCellRenderer(row, 0), row, 0).getForeground(),
					"row " + row);
		}
	}

	@Test
	void theStatusLineSaysHowManyRowsArePicked() {
		table.setRowSelectionInterval(0, 1);

		assertEquals("3 rows — 2 selected", panel.statusText());
	}

	@Test
	void theStatusLineDropsTheCountWhenNothingIsPicked() {
		table.setRowSelectionInterval(0, 1);
		table.clearSelection();

		assertEquals("3 rows", panel.statusText());
	}

	@Test
	void rightClickingInsideAMultiRowSelectionKeepsTheStatusIntact() {
		table.setRowSelectionInterval(0, 2);

		rightClickOnRow(1);

		assertEquals("3 rows — 3 selected", panel.statusText());
	}
}
