package io.github.mfuerstenau.leia.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The runtime catalog and its reader (ADR 0078). */
class RuntimeCatalogTest {

    @Test
    void readsTheCatalogNewestFirst() {
        assertThat(RuntimeCatalog.load(RuntimeCatalogTest.class.getClassLoader()))
                .isEqualTo(RuntimeFolders.KNOWN)
                .containsExactly(
                        new RuntimeFolders.Known("lucene10", "Lucene 10", List.of(9, 10)),
                        new RuntimeFolders.Known("lucene9", "Lucene 9", List.of(8)),
                        new RuntimeFolders.Known("lucene7", "Lucene 7", List.of(6, 7)),
                        new RuntimeFolders.Known("lucene5", "Lucene 5", List.of(4, 5)),
                        new RuntimeFolders.Known("lucene3", "Lucene 3", List.of(2, 3)));
        assertThat(RuntimeFolders.LATEST.folder()).isEqualTo("lucene10");
    }

    @Test
    void readsEscapesAndWhitespace() {
        assertThat(RuntimeCatalog.parse("""
                        \t{"runtimes" : [ {"folder":"lucene\\u0039","name":"Lucene \\"9\\" \\\\ \\/ \\n",
                         "indexMajors":[ 8 ] } ] }\r
                        """))
                .containsExactly(new RuntimeFolders.Known("lucene9", "Lucene \"9\" \\ / \n", List.of(8)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            ''                                                                            | the document ends early
            '{"runtimes": ['                                                              | the document ends early
            '{"runtimes": []} x'                                                          | more after the document
            '{"runtimes": [{"folder": "a, "name": "A", "indexMajors": [1]}]}'           | expected '}'
            '{"runtimes": [{"folder": "a", "name": "A", "indexMajors": [1.5]}]}'          | only integers
            '{"runtimes": [{"folder": "a", "name": "A", "indexMajors": [1], "x": 1}]}'    | runtimes[0] has the unknown key "x"
            '{"runtimes": [{"folder": "a", "name": "A"}]}'                                | runtimes[0] lacks "indexMajors"
            '{"runtime": []}'                                                             | the catalog has the unknown key "runtime"
            '{"runtimes": []}'                                                            | runtimes is empty
            '[]'                                                                          | the catalog is not an object
            '{"runtimes": [{"folder": "Lucene9", "name": "A", "indexMajors": [1]}]}'      | runtimes[0].folder is not of a-z and 0-9
            '{"runtimes": [{"folder": "../x", "name": "A", "indexMajors": [1]}]}'         | runtimes[0].folder is not of a-z and 0-9
            '{"runtimes": [{"folder": "a", "name": " ", "indexMajors": [1]}]}'            | runtimes[0].name is blank
            '{"runtimes": [{"folder": "a", "name": "A", "indexMajors": []}]}'             | runtimes[0].indexMajors is empty
            '{"runtimes": [{"folder": "a", "name": "A", "indexMajors": [0]}]}'            | runtimes[0].indexMajors has 0, not a major
            '{"runtimes": [{"folder": "a", "name": "A", "indexMajors": ["1"]}]}'          | runtimes[0].indexMajors has 1, not a major
            '{"runtimes": [{"folder": "a", "name": 1, "indexMajors": [1]}]}'              | runtimes[0].name is not a string
            '{"runtimes": [{"folder": "a", "name": "A", "indexMajors": [1]}, {"folder": "a", "name": "B", "indexMajors": [2]}]}' | the folder a is there twice
            '{"runtimes": [{"folder": "a", "name": "A", "indexMajors": [1, 2]}, {"folder": "b", "name": "B", "indexMajors": [2]}]}' | the indexes of 2 are read by both a and b
            '{"runtimes": [{"folder": "a", "folder": "b"}]}'                              | the key "folder" is there twice
            """)
    void refusesWhatIsNotACatalog(String json, String problem) {
        assertThatThrownBy(() -> RuntimeCatalog.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(problem);
    }
}
