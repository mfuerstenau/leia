package io.github.mfuerstenau.leia.core;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PayloadAttribute;
import org.apache.lucene.util.BytesRef;

/** Fixed tokens with offsets and a payload each; a {@code null} payload means none. */
final class PayloadTokens extends TokenStream {

    private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
    private final OffsetAttribute offset = addAttribute(OffsetAttribute.class);
    private final PayloadAttribute payload = addAttribute(PayloadAttribute.class);
    private final String[] tokens;
    private final int[] starts;
    private final String[] payloads;
    private int next;

    PayloadTokens(String[] tokens, int[] starts, String... payloads) {
        this.tokens = tokens;
        this.starts = starts;
        this.payloads = payloads;
    }

    @Override
    public boolean incrementToken() {
        if (next == tokens.length) {
            return false;
        }
        clearAttributes();
        term.append(tokens[next]);
        offset.setOffset(starts[next], starts[next] + tokens[next].length());
        payload.setPayload(payloads[next] == null ? null : new BytesRef(payloads[next]));
        next++;
        return true;
    }
}
