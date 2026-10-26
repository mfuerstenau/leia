package io.github.mfuerstenau.leia.tui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ThemeTest {

    @AfterEach
    void resetTheme() {
        Palette.use(Theme.DARK);
    }

    @Test
    void parsesTheIds() {
        assertThat(Theme.parse("dark")).hasValue(Theme.DARK);
        assertThat(Theme.parse("light")).hasValue(Theme.LIGHT);
        assertThat(Theme.parse("mono")).hasValue(Theme.MONO);
        assertThat(Theme.parse("nord")).hasValue(Theme.NORD);
        assertThat(Theme.parse("Nord")).isEmpty();
        assertThat(Theme.parse("")).isEmpty();
    }

    @Test
    void nextCyclesInOrder() {
        assertThat(Theme.DARK.next()).isEqualTo(Theme.LIGHT);
        assertThat(Theme.LIGHT.next()).isEqualTo(Theme.MONO);
        assertThat(Theme.MONO.next()).isEqualTo(Theme.NORD);
        assertThat(Theme.NORD.next()).isEqualTo(Theme.DARK);
    }

    @Test
    void darkIsTheDefaultAndTheLookBeforeThemes() {
        assertThat(Palette.theme()).isEqualTo(Theme.DARK);
        Palette dark = Palette.current();
        assertThat(dark.base()).isEqualTo(Style.EMPTY);
        assertThat(dark.error()).isEqualTo(Style.EMPTY.red());
        assertThat(dark.warning()).isEqualTo(Style.EMPTY.yellow());
        assertThat(dark.success()).isEqualTo(Style.EMPTY.green());
        assertThat(dark.accent()).isEqualTo(Style.EMPTY.cyan());
        assertThat(dark.match()).isEqualTo(Style.EMPTY.black().onYellow());
        assertThat(dark.muted()).isEqualTo(Style.EMPTY.fg(Color.DARK_GRAY));
    }

    @Test
    void useSwitchesThePalette() {
        Palette.use(Theme.NORD);

        assertThat(Palette.theme()).isEqualTo(Theme.NORD);
        assertThat(Palette.current()).isSameAs(Theme.NORD.palette());
        assertThat(Palette.current().base().bg()).hasValue(Color.hex("#2E3440"));
    }

    @Test
    void monoHasNoColours() {
        Palette mono = Theme.MONO.palette();

        assertThat(styles(mono)).allSatisfy(style -> {
            assertThat(style.fg()).isEmpty();
            assertThat(style.bg()).isEmpty();
        });
        assertThat(mono.colouredLogo()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    void everyRoleIsSet(Theme theme) {
        Palette palette = theme.palette();

        assertThat(styles(palette)).doesNotContainNull();
        // A highlighted match must stand out from the text around it under every theme.
        assertThat(palette.match()).isNotEqualTo(palette.base());
        assertThat(palette.error()).isNotEqualTo(Style.EMPTY);
        assertThat(palette.warning()).isNotEqualTo(Style.EMPTY);
    }

    private static Style[] styles(Palette palette) {
        return Arrays.stream(Palette.class.getRecordComponents())
                .filter(component -> component.getType() == Style.class)
                .map(component -> {
                    try {
                        return (Style) component.getAccessor().invoke(palette);
                    } catch (ReflectiveOperationException e) {
                        throw new AssertionError(e);
                    }
                })
                .toArray(Style[]::new);
    }
}
