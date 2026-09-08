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

import java.awt.CardLayout;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import javax.swing.JPanel;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.plugin.core.panel.sql.resource.SqlNuclrResource;

/** Swaps between {@link RowFieldPanel} and {@link TableGridPanel} depending on what's open. */
public final class QuickViewCardPanel extends JPanel {

	private static final String CardRow = "row";
	private static final String CardTable = "table";

	private final CardLayout cardLayout = new CardLayout();
	private final RowFieldPanel rowPanel;
	private final TableGridPanel tablePanel;

	/**
	 * @param theme supplies the active palette on demand rather than once, so a theme
	 *              switch reaches both cards on their next paint
	 */
	public QuickViewCardPanel(Supplier<NuclrThemeScheme> theme) {
		super();
		rowPanel = new RowFieldPanel(theme);
		tablePanel = new TableGridPanel(theme);
		setLayout(cardLayout);
		add(rowPanel, CardRow);
		add(tablePanel, CardTable);
	}

	public void showRow(SqlNuclrResource row) {
		rowPanel.show(row);
		cardLayout.show(this, CardRow);
	}

	public void showTable(SqlNuclrResource table, AtomicBoolean cancelled) {
		tablePanel.show(table, cancelled);
		cardLayout.show(this, CardTable);
	}

	public void clear() {
		rowPanel.clear();
		tablePanel.clear();
	}
}
