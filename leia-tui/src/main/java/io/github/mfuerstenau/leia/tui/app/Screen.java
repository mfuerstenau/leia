package io.github.mfuerstenau.leia.tui.app;

/**
 * The screens; {@code 1} to {@code 7} switch between them. All but the analysis screen need an
 * open index. Without a reader, only the commits and check screens show the index (ADR 0021, ADR
 * 0022).
 */
enum Screen {
    OVERVIEW("Overview"),
    DOCUMENTS("Documents"),
    TERMS("Terms"),
    COMMITS("Commits"),
    SEARCH("Search"),
    ANALYSIS("Analysis"),
    CHECK("Check");

    final String name;

    Screen(String name) {
        this.name = name;
    }

    /** The tab's title: its key and name. */
    String title() {
        return (ordinal() + 1) + " " + name;
    }
}
