package io.github.mfuerstenau.leia.server.terms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import io.github.mfuerstenau.leia.protocol.terms.GetPositionsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPositionsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.Posting;
import io.github.mfuerstenau.leia.protocol.terms.PostingsFilter;
import io.github.mfuerstenau.leia.protocol.terms.TermEntry;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.TestIndexes;
import io.github.mfuerstenau.leia.server.filter.DocFilter;
import io.github.mfuerstenau.leia.server.filter.Filters;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import java.nio.file.Path;
import java.time.Duration;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code getTerms}, {@code getPostings} and {@code getPositions}. */
class TermHandlersTest {

    @TempDir
    Path tmp;

    private final Filters filters = new Filters();
    private final CurrentIndex currentIndex = new CurrentIndex(new Jobs(), filters);
    private final GetTermsHandler getTerms = new GetTermsHandler(currentIndex);
    private final GetPostingsHandler getPostings = new GetPostingsHandler(currentIndex, filters);
    private final GetPositionsHandler getPositions = new GetPositionsHandler(currentIndex);

    @Test
    void pagesThroughTerms() throws Exception {
        openIndex();

        GetTermsResult first = getTerms.handle(new GetTermsParams("body", Optional.empty(), 2));
        assertThat(first)
                .isEqualTo(new GetTermsResult(
                        List.of(new TermEntry("blue", false, 2, 2), new TermEntry("green", false, 1, 1)),
                        Optional.of(text("red"))));

        GetTermsResult second = getTerms.handle(new GetTermsParams("body", first.next(), 10));
        assertThat(second.terms()).extracting(TermEntry::term).containsExactly("red", "yellow");
        assertThat(second.next()).isEmpty();
    }

    @Test
    void seeksAndSendsBinaryTermsAsHex() throws Exception {
        openIndex();

        assertThat(getTerms.handle(new GetTermsParams("body", Optional.of(text("h")), 1)))
                .isEqualTo(new GetTermsResult(List.of(new TermEntry("red", false, 1, 2)), Optional.of(text("yellow"))));
        assertThat(getTerms.handle(new GetTermsParams("key", Optional.of(new BytesValue("FE", true)), 10)))
                .isEqualTo(new GetTermsResult(List.of(new TermEntry("ff", true, 1, 1)), Optional.empty()));
    }

    @Test
    void pagesThroughPostings() throws Exception {
        openIndex();

        GetPostingsResult first =
                getPostings.handle(new GetPostingsParams("body", text("blue"), 0, 1, Optional.empty()));
        assertThat(first)
                .isEqualTo(new GetPostingsResult(
                        2, 2, List.of(new Posting(0, false, OptionalInt.of(1), Optional.empty())), OptionalInt.of(2)));
        assertThat(getPostings.handle(new GetPostingsParams("body", text("blue"), 2, 1, Optional.empty())))
                .isEqualTo(new GetPostingsResult(
                        2,
                        2,
                        List.of(new Posting(2, false, OptionalInt.of(1), Optional.empty())),
                        OptionalInt.empty()));
        // A deleted document, and a field without frequencies.
        assertThat(getPostings
                        .handle(new GetPostingsParams("body", text("green"), 0, 10, Optional.empty()))
                        .postings())
                .containsExactly(new Posting(1, true, OptionalInt.of(1), Optional.empty()));
        assertThat(getPostings
                        .handle(new GetPostingsParams("key", new BytesValue("ff", true), 0, 10, Optional.empty()))
                        .postings())
                .containsExactly(new Posting(2, false, OptionalInt.empty(), Optional.empty()));
    }

    @Test
    void marksThePostingsInTheFilterOrReadsOnlyThose() throws Exception {
        openIndex();
        // The document with id 3.
        BitSet docs = new BitSet();
        docs.set(2);
        DocFilter filter = DocFilter.of(docs, currentIndex.runtime().index().maxDoc(), "id:3", "id:3", Duration.ZERO);
        filters.put(7, filter);

        assertThat(getPostings
                        .handle(new GetPostingsParams(
                                "body", text("blue"), 0, 10, Optional.of(new PostingsFilter(7, false))))
                        .postings())
                .containsExactly(
                        new Posting(0, false, OptionalInt.of(1), Optional.of(false)),
                        new Posting(2, false, OptionalInt.of(1), Optional.of(true)));
        GetPostingsResult only = getPostings.handle(
                new GetPostingsParams("body", text("blue"), 0, 10, Optional.of(new PostingsFilter(7, true))));
        assertThat(only.docFreq()).isEqualTo(2);
        assertThat(only.postings()).extracting(Posting::docId).containsExactly(2);
        assertThat(only.nextDoc()).isEmpty();

        assertFails(
                () -> getPostings.handle(
                        new GetPostingsParams("body", text("blue"), 0, 10, Optional.of(new PostingsFilter(8, false)))),
                ErrorCode.FILTER_NOT_FOUND);
    }

    @Test
    void readsPositions() throws Exception {
        openIndex();

        assertThat(getPositions.handle(new GetPositionsParams(0, "body", text("red"))))
                .isEqualTo(new GetPositionsResult(List.of(position(0, 0, 3), position(2, 9, 12))));
        assertThat(getPositions
                        .handle(new GetPositionsParams(2, "key", new BytesValue("ff", true)))
                        .positions())
                .isEmpty();
    }

    @Test
    void failsWithoutOpenIndex() {
        assertFails(() -> getTerms.handle(new GetTermsParams("body", Optional.empty(), 10)), ErrorCode.NO_INDEX_OPEN);
        assertFails(
                () -> getPostings.handle(new GetPostingsParams("body", text("red"), 0, 10, Optional.empty())),
                ErrorCode.NO_INDEX_OPEN);
        assertFails(() -> getPositions.handle(new GetPositionsParams(0, "body", text("red"))), ErrorCode.NO_INDEX_OPEN);
    }

    @Test
    void failsForUnknownFieldTermOrDocument() throws Exception {
        openIndex();

        assertFails(
                () -> getTerms.handle(new GetTermsParams("bdy", Optional.empty(), 10)),
                ErrorCode.FIELD_NOT_FOUND,
                "No field 'bdy' in the index");
        assertFails(
                () -> getPostings.handle(new GetPostingsParams("body", text("quik"), 0, 10, Optional.empty())),
                ErrorCode.TERM_NOT_FOUND,
                "No term 'quik' in field 'body'");
        assertFails(
                () -> getPositions.handle(new GetPositionsParams(2, "body", text("red"))),
                ErrorCode.TERM_NOT_FOUND,
                "Document 2 does not contain 'red' in field 'body'");
        assertFails(
                () -> getPositions.handle(new GetPositionsParams(9, "body", text("red"))),
                ErrorCode.DOC_NOT_FOUND,
                "No document 9 in the index: maxDoc is 3");
    }

    @Test
    void rejectsInvalidParams() throws Exception {
        openIndex();

        assertFails(
                () -> getTerms.handle(new GetTermsParams("body", Optional.empty(), 0)),
                ErrorCode.INVALID_PARAMS,
                "Invalid params: limit must be between 1 and 1000, not 0");
        assertFails(
                () -> getTerms.handle(new GetTermsParams("body", Optional.empty(), 1001)), ErrorCode.INVALID_PARAMS);
        assertFails(
                () -> getTerms.handle(new GetTermsParams("body", Optional.of(new BytesValue("xyz", true)), 10)),
                ErrorCode.INVALID_PARAMS,
                "Invalid params: the binary term 'xyz' is not hex");
        assertFails(
                () -> getPostings.handle(new GetPostingsParams("body", text("red"), -1, 10, Optional.empty())),
                ErrorCode.INVALID_PARAMS,
                "Invalid params: fromDoc must not be negative, not -1");
        assertFails(
                () -> getPostings.handle(new GetPostingsParams("body", text("red"), 0, 1001, Optional.empty())),
                ErrorCode.INVALID_PARAMS);
    }

    private static BytesValue text(String text) {
        return new BytesValue(text, false);
    }

    private static TermPosition position(int position, int start, int end) {
        return new TermPosition(OptionalInt.of(position), OptionalInt.of(start), OptionalInt.of(end), Optional.empty());
    }

    /**
     * One segment, three documents: "red blue red" (id 1), "green yellow" (id 2, deleted), "blue" (id
     * 3, which also has the binary term ff in "key"). The body has positions and offsets.
     */
    private void openIndex() throws Exception {
        Path dir = tmp.resolve("index");
        FieldType body = new FieldType(TextField.TYPE_NOT_STORED);
        body.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer =
                        new IndexWriter(directory, new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
            int id = 1;
            for (String text : List.of("red blue red", "green yellow", "blue")) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(id++), Field.Store.YES));
                doc.add(new Field("body", text, body));
                if (id == 4) {
                    doc.add(new StringField("key", new BytesRef(new byte[] {(byte) 0xff}), Field.Store.NO));
                }
                writer.addDocument(doc);
            }
            writer.commit();
            writer.deleteDocuments(new Term("id", "2"));
        }
        currentIndex.replace(TestIndexes.open(dir));
    }

    private static void assertFails(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private static void assertFails(ThrowingCallable call, ErrorCode code, String message) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(code))
                .hasMessage(message);
    }
}
