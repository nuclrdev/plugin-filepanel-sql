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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import dev.nuclr.platform.NuclrThemeScheme;

/** Selection colours come from the active palette, and follow it when it changes. */
class QuickViewThemeTest {

	private static final Color Dark = new Color(43, 43, 43);
	private static final Color Light = new Color(250, 250, 250);

	@Test
	void aPaletteEntryWinsOverTheFallback() {
		var scheme = new NuclrThemeScheme("test", Map.of("file-mark", "#FF0000"));
		var theme = new QuickViewTheme(() -> scheme);

		assertEquals(Color.RED, theme.markColor(Dark));
	}

	@Test
	void theBandCanBeThemedIndependentlyOfTheAccent() {
		var scheme = new NuclrThemeScheme("test", Map.of("file-mark", "#FF0000", "file-mark-background", "#000080"));
		var theme = new QuickViewTheme(() -> scheme);

		assertEquals(Color.RED, theme.markColor(Dark));
		assertEquals(new Color(0, 0, 128), theme.markBackground(Dark));
	}

	@Test
	void withNoPaletteEntryTheFallbackAdaptsToTheBackdrop() {
		var theme = new QuickViewTheme(() -> new NuclrThemeScheme("bare", Map.of()));

		assertEquals(new Color(255, 213, 79), theme.markColor(Dark), "bright amber on a dark backdrop");
		assertEquals(new Color(176, 124, 0), theme.markColor(Light), "a deeper amber on a light one");
	}

	@Test
	void noThemeAtAllStillYieldsUsableColours() {
		var theme = new QuickViewTheme(null);

		assertEquals(new Color(255, 213, 79), theme.markColor(Dark));
		assertNotEquals(theme.markColor(Dark), theme.markBackground(Dark));
	}

	@Test
	void theBandSitsBetweenTheAccentAndTheBackdrop() {
		var theme = new QuickViewTheme(() -> new NuclrThemeScheme("bare", Map.of()));

		Color band = theme.markBackground(Dark);
		Color accent = theme.markColor(Dark);

		// A faint wash, so a run of rows reads as a block without drowning the text.
		assertTrue(band.getRed() > Dark.getRed() && band.getRed() < accent.getRed(), band.toString());
	}

	@Test
	void theColourIsReadPerCallSoAThemeSwitchIsPickedUp() {
		var active = new AtomicReference<>(new NuclrThemeScheme("one", Map.of("file-mark", "#FF0000")));
		var theme = new QuickViewTheme(active::get);
		assertEquals(Color.RED, theme.markColor(Dark));

		active.set(new NuclrThemeScheme("two", Map.of("file-mark", "#00FF00")));

		assertEquals(Color.GREEN, theme.markColor(Dark));
	}

	@Test
	void aPaletteEntryThatIsNotAColourFallsBackInsteadOfFailing() {
		var scheme = new NuclrThemeScheme("broken", Map.of("file-mark", "not-a-colour"));
		var theme = new QuickViewTheme(() -> scheme);

		assertEquals(new Color(255, 213, 79), theme.markColor(Dark));
	}

	@Test
	void aNullBackdropIsTreatedAsDarkRatherThanFailing() {
		var theme = new QuickViewTheme(() -> null);

		assertEquals(new Color(255, 213, 79), theme.markColor(null));
		assertTrue(QuickViewTheme.isDark(null));
	}
}
