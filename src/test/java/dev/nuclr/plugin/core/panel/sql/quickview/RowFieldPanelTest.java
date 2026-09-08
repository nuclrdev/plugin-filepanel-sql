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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.panel.sql.resource.SqlNuclrResource;

/** The two renderings behind the quick-view JSON toggle. */
class RowFieldPanelTest {

	private static SqlNuclrResource row(Map<String, Object> values) {
		return SqlNuclrResource.row("p1", "main", "users", "1", List.copyOf(values.keySet()), values);
	}

	private static Map<String, Object> ada() {
		var values = new LinkedHashMap<String, Object>();
		values.put("id", 1);
		values.put("name", "Ada");
		values.put("email", null);
		return values;
	}

	@Test
	void theFieldListIsOneColumnPerLineWithNullsSpelledOut() {
		assertEquals("id: 1\nname: Ada\nemail: NULL\n", RowFieldPanel.renderFields(row(ada())));
	}

	@Test
	void theFieldListFollowsTheColumnOrder() {
		var values = new LinkedHashMap<String, Object>();
		values.put("z", 1);
		values.put("a", 2);

		assertEquals("z: 1\na: 2\n", RowFieldPanel.renderFields(row(values)));
	}

	@Test
	void jsonKeepsNumbersAndBooleansUnquotedAndNullBare() {
		var values = new LinkedHashMap<String, Object>();
		values.put("id", 1);
		values.put("active", true);
		values.put("email", null);

		assertEquals("{\n  \"id\": 1,\n  \"active\": true,\n  \"email\": null\n}\n",
				RowFieldPanel.renderJson(row(values)));
	}

	@Test
	void jsonEscapesQuotesBackslashesAndNewlinesInsideValues() {
		var values = new LinkedHashMap<String, Object>();
		values.put("note", "say \"hi\"\r\nC:\\temp");

		assertEquals("{\n  \"note\": \"say \\\"hi\\\"\\nC:\\\\temp\"\n}\n",
				RowFieldPanel.renderJson(row(values)));
	}

	@Test
	void jsonEscapesColumnNamesToo() {
		var values = new LinkedHashMap<String, Object>();
		values.put("odd\"name", "x");

		assertEquals("{\n  \"odd\\\"name\": \"x\"\n}\n", RowFieldPanel.renderJson(row(values)));
	}

	@Test
	void aRowWithNoColumnsRendersAsEmptyRatherThanBroken() {
		var empty = row(new LinkedHashMap<>());

		assertEquals("", RowFieldPanel.renderFields(empty));
		assertEquals("{\n}\n", RowFieldPanel.renderJson(empty));
	}
}
