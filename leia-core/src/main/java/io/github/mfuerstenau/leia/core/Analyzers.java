package io.github.mfuerstenau.leia.core;

import io.github.mfuerstenau.leia.core.AnalysisComponents.Available;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.Component;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.LimitTokenCount;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.Shingles;
import io.github.mfuerstenau.leia.core.TextAnalysis.CharFilterStep;
import io.github.mfuerstenau.leia.core.TextAnalysis.Token;
import io.github.mfuerstenau.leia.core.TextAnalysis.TokenAttribute;
import io.github.mfuerstenau.leia.core.TextAnalysis.TokenStage;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharFilterFactory;
import org.apache.lucene.analysis.TokenFilterFactory;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.TokenizerFactory;
import org.apache.lucene.analysis.custom.CustomAnalyzer;
import org.apache.lucene.analysis.miscellaneous.LimitTokenCountAnalyzer;
import org.apache.lucene.analysis.shingle.ShingleAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.apache.lucene.analysis.util.FilesystemResourceLoader;
import org.apache.lucene.util.Attribute;
import org.apache.lucene.util.AttributeImpl;
import org.apache.lucene.util.ClasspathResourceLoader;
import org.apache.lucene.util.ResourceLoader;

/** Builds analyzers and shows what they make of a text (ADR 0014). */
public final class Analyzers {

    /** The attributes that {@link Token} has fields for, so they are not in its attribute list. */
    private static final Set<Class<? extends Attribute>> TYPED = Set.of(
            CharTermAttribute.class,
            OffsetAttribute.class,
            PositionIncrementAttribute.class,
            PositionLengthAttribute.class,
            TypeAttribute.class);

    /** The name of the stage of the stop words of a field (ADR 0039). */
    public static final String AUTO_STOP_WORDS = "AutoStopWords";

    private static final Pattern MODULE_ADVICE = Pattern.compile(" \\(if you use Java Module System[^)]*\\)");

    private Analyzers() {}

    /**
     * The preset analyzers, char filters, tokenizers and token filters on the classpath and in the
     * extension jars (ADR 0016). The first call reads the jars for the presets.
     */
    public static AnalysisComponents components() {
        return new AnalysisComponents(
                PresetAnalyzers.list(),
                available(CharFilterFactory.availableCharFilters(), CharFilterFactory::lookupClass),
                available(TokenizerFactory.availableTokenizers(), TokenizerFactory::lookupClass),
                available(TokenFilterFactory.availableTokenFilters(), TokenFilterFactory::lookupClass));
    }

    /**
     * A new analyzer for {@code spec}, which the caller closes. A custom analyzer reads its resource
     * files now.
     *
     * @throws InvalidAnalyzerException for a preset that is not listed, doesn't take or misses a word
     *     set, has a word list file that can't be read, or fails to construct, an
     *     unknown component, an unknown or bad parameter, a config directory that is not a
     *     directory, or a resource file that can't be read or parsed
     */
    public static Analyzer build(AnalyzerSpec spec) throws InvalidAnalyzerException {
        return switch (spec) {
            case AnalyzerSpec.Standard _ -> new StandardAnalyzer();
            case AnalyzerSpec.Preset preset -> PresetAnalyzers.create(preset);
            case AnalyzerSpec.Custom custom -> custom(custom);
            case AnalyzerSpec.Wrapped wrapped -> wrapped(wrapped);
            case AnalyzerSpec.PerField perField -> perField(perField);
            case AnalyzerSpec.AutoStopWords autoStopWords ->
                new AutoStopWordsAnalyzer(build(autoStopWords.analyzer()), autoStopWords.words());
        };
    }

    /** The default analyzer and each field's own (ADR 0039); one that fails closes those built before. */
    private static Analyzer perField(AnalyzerSpec.PerField spec) throws InvalidAnalyzerException {
        Analyzer defaultAnalyzer = build(spec.defaultAnalyzer());
        Map<String, Analyzer> fields = new TreeMap<>();
        try {
            for (Map.Entry<String, AnalyzerSpec> field : new TreeMap<>(spec.fields()).entrySet()) {
                fields.put(field.getKey(), fieldAnalyzer(field.getKey(), field.getValue()));
            }
        } catch (InvalidAnalyzerException | RuntimeException e) {
            fields.values().forEach(Analyzer::close);
            defaultAnalyzer.close();
            throw e;
        }
        return new PerFieldAnalyzer(defaultAnalyzer, fields);
    }

    /** The analyzer of {@code field}, with the field's name in the message of an invalid one. */
    private static Analyzer fieldAnalyzer(String field, AnalyzerSpec spec) throws InvalidAnalyzerException {
        try {
            return build(spec);
        } catch (InvalidAnalyzerException e) {
            throw new InvalidAnalyzerException("field '" + field + "': " + e.getMessage(), e);
        }
    }

    /** The analyzer in Lucene's shingle wrapper, then in its token limit (ADR 0023). */
    private static Analyzer wrapped(AnalyzerSpec.Wrapped spec) throws InvalidAnalyzerException {
        // Checked first, so that nothing is built for nothing.
        if (spec.shingles().isPresent()) {
            Shingles shingles = spec.shingles().orElseThrow();
            if (shingles.minShingleSize() < 2) {
                throw new InvalidAnalyzerException(
                        "shingles: the minimum size must be at least 2, not " + shingles.minShingleSize(), null);
            }
            if (shingles.maxShingleSize() < shingles.minShingleSize()) {
                throw new InvalidAnalyzerException(
                        "shingles: the maximum size must be at least the minimum size, " + shingles.minShingleSize()
                                + ", not " + shingles.maxShingleSize(),
                        null);
            }
        }
        if (spec.limitTokenCount().isPresent()
                && spec.limitTokenCount().orElseThrow().maxTokenCount() < 1) {
            throw new InvalidAnalyzerException(
                    "token limit: the maximum token count must be at least 1, not "
                            + spec.limitTokenCount().orElseThrow().maxTokenCount(),
                    null);
        }
        Analyzer base = build(spec.analyzer());
        List<Analyzer> wrappers = new ArrayList<>(2);
        Analyzer wrapped = base;
        if (spec.shingles().isPresent()) {
            Shingles shingles = spec.shingles().orElseThrow();
            wrapped = new ShingleAnalyzerWrapper(
                    wrapped,
                    shingles.minShingleSize(),
                    shingles.maxShingleSize(),
                    shingles.tokenSeparator(),
                    shingles.outputUnigrams(),
                    shingles.outputUnigramsIfNoShingles(),
                    shingles.fillerToken());
            wrappers.add(wrapped);
        }
        if (spec.limitTokenCount().isPresent()) {
            LimitTokenCount limit = spec.limitTokenCount().orElseThrow();
            wrapped = new LimitTokenCountAnalyzer(wrapped, limit.maxTokenCount(), limit.consumeAllTokens());
            wrappers.add(wrapped);
        }
        return wrappers.isEmpty() ? base : new WrappedAnalyzer(base, wrappers);
    }

    /**
     * What {@code analyzer} makes of {@code text}. A {@link CustomAnalyzer} is shown step by step:
     * each stage runs the chain again from the start, up to its component. A wrapped analyzer is
     * shown as the analyzer, then one stage per wrapper, named by its class (ADR 0023). Any other
     * analyzer has one stage, named by its class.
     *
     * @param maxTokens the most tokens a stage has; it stops there and is marked truncated
     */
    public static TextAnalysis analyze(Analyzer analyzer, String text, int maxTokens) throws IOException {
        return analyze(analyzer, "", text, maxTokens);
    }

    /**
     * What {@code analyzer} makes of {@code text} in {@code field}, as {@link #analyze(Analyzer,
     * String, int)} shows it (ADR 0039). An analyzer per field is shown as the field's analyzer, its
     * own or the default one. Stop words of the field add a stage, {@value #AUTO_STOP_WORDS}, after
     * the analyzer's own; a field without words has none.
     */
    public static TextAnalysis analyze(Analyzer analyzer, String field, String text, int maxTokens) throws IOException {
        if (analyzer instanceof AutoStopWordsAnalyzer autoStopWords) {
            TextAnalysis inner = analyze(autoStopWords.delegate(), field, text, maxTokens);
            if (autoStopWords.words(field).filter(words -> !words.isEmpty()).isEmpty()) {
                return inner;
            }
            List<TokenStage> stages = new ArrayList<>(inner.stages());
            stages.add(stage(AUTO_STOP_WORDS, analyzer.tokenStream(field, text), maxTokens));
            return new TextAnalysis(inner.charFilters(), stages);
        }
        if (analyzer instanceof PerFieldAnalyzer perField) {
            return analyze(perField.analyzer(field), field, text, maxTokens);
        }
        if (analyzer instanceof WrappedAnalyzer wrapped) {
            TextAnalysis base = analyze(wrapped.base(), field, text, maxTokens);
            List<TokenStage> stages = new ArrayList<>(base.stages());
            for (Analyzer wrapper : wrapped.wrappers()) {
                stages.add(stage(wrapper.getClass().getSimpleName(), wrapper.tokenStream(field, text), maxTokens));
            }
            return new TextAnalysis(base.charFilters(), stages);
        }
        if (!(analyzer instanceof CustomAnalyzer custom)) {
            TokenStage stage = stage(analyzer.getClass().getSimpleName(), analyzer.tokenStream(field, text), maxTokens);
            return new TextAnalysis(List.of(), List.of(stage));
        }
        List<CharFilterFactory> charFilters = custom.getCharFilterFactories();
        List<CharFilterStep> steps = new ArrayList<>(charFilters.size());
        for (int i = 0; i < charFilters.size(); i++) {
            try (Reader reader = charFiltered(charFilters.subList(0, i + 1), text)) {
                steps.add(new CharFilterStep(
                        CharFilterFactory.findSPIName(charFilters.get(i).getClass()), read(reader)));
            }
        }
        List<TokenFilterFactory> tokenFilters = custom.getTokenFilterFactories();
        List<TokenStage> stages = new ArrayList<>(tokenFilters.size() + 1);
        for (int i = 0; i <= tokenFilters.size(); i++) {
            Tokenizer tokenizer = custom.getTokenizerFactory().create();
            tokenizer.setReader(charFiltered(charFilters, text));
            TokenStream stream = tokenizer;
            for (TokenFilterFactory filter : tokenFilters.subList(0, i)) {
                stream = filter.create(stream);
            }
            String name = i == 0
                    ? TokenizerFactory.findSPIName(custom.getTokenizerFactory().getClass())
                    : TokenFilterFactory.findSPIName(tokenFilters.get(i - 1).getClass());
            stages.add(stage(name, stream, maxTokens));
        }
        return new TextAnalysis(steps, stages);
    }

    private static CustomAnalyzer custom(AnalyzerSpec.Custom spec) throws InvalidAnalyzerException {
        CustomAnalyzer.Builder builder = builder(spec);
        for (Component component : spec.charFilters()) {
            add(
                    "char filter",
                    component,
                    CharFilterFactory.availableCharFilters(),
                    () -> builder.addCharFilter(component.name(), new HashMap<>(component.params())));
        }
        Component tokenizer = spec.tokenizer();
        add(
                "tokenizer",
                tokenizer,
                TokenizerFactory.availableTokenizers(),
                () -> builder.withTokenizer(tokenizer.name(), new HashMap<>(tokenizer.params())));
        for (Component component : spec.tokenFilters()) {
            add(
                    "token filter",
                    component,
                    TokenFilterFactory.availableTokenFilters(),
                    () -> builder.addTokenFilter(component.name(), new HashMap<>(component.params())));
        }
        return builder.build();
    }

    /** A builder that reads resource files from the config directory, then through the extension jars' loader. */
    private static CustomAnalyzer.Builder builder(AnalyzerSpec.Custom spec) throws InvalidAnalyzerException {
        ResourceLoader classpath = new ClasspathResourceLoader(ExtensionJars.classLoader());
        try {
            return CustomAnalyzer.builder(spec.configDir()
                    .<ResourceLoader>map(dir -> new FilesystemResourceLoader(dir, classpath))
                    .orElse(classpath));
        } catch (IllegalArgumentException e) {
            throw new InvalidAnalyzerException("config directory: " + e.getMessage(), e);
        }
    }

    private interface Step {
        void run() throws IOException;
    }

    /** Runs {@code step}, which adds {@code component}, and says which component failed and why. */
    private static void add(String kind, Component component, Set<String> available, Step step)
            throws InvalidAnalyzerException {
        String what = kind + " '" + component.name() + "'";
        // Lucene's own message lists every name there is.
        if (available.stream().noneMatch(component.name()::equalsIgnoreCase)) {
            throw new InvalidAnalyzerException("no " + what, null);
        }
        try {
            step.run();
        } catch (NoSuchFileException e) {
            throw new InvalidAnalyzerException(what + ": no file " + e.getMessage(), e);
        } catch (IOException | UncheckedIOException | IllegalArgumentException | IllegalStateException e) {
            throw new InvalidAnalyzerException(what + ": " + message(e), e);
        } catch (LinkageError e) {
            // A factory from an extension jar whose dependency is missing (ADR 0016).
            throw new InvalidAnalyzerException(what + ": " + e, e);
        }
    }

    private static String message(Exception e) {
        Throwable cause = e instanceof UncheckedIOException unchecked ? unchecked.getCause() : e;
        if (cause.getMessage() == null) {
            return cause.getClass().getSimpleName();
        }
        // The class path loader's advice on modules doesn't apply: the server has none of its own.
        return MODULE_ADVICE.matcher(cause.getMessage()).replaceFirst("");
    }

    private static List<Available> available(Set<String> names, Function<String, Class<?>> lookup) {
        return names.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .map(name -> new Available(name, lookup.apply(name).getName()))
                .toList();
    }

    private static Reader charFiltered(List<CharFilterFactory> charFilters, String text) {
        Reader reader = new StringReader(text);
        for (CharFilterFactory charFilter : charFilters) {
            reader = charFilter.create(reader);
        }
        return reader;
    }

    private static String read(Reader reader) throws IOException {
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[1024];
        for (int read = reader.read(buffer); read != -1; read = reader.read(buffer)) {
            text.append(buffer, 0, read);
        }
        return text.toString();
    }

    private static TokenStage stage(String name, TokenStream stream, int maxTokens) throws IOException {
        try (stream) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            OffsetAttribute offset = stream.addAttribute(OffsetAttribute.class);
            PositionIncrementAttribute increment = stream.addAttribute(PositionIncrementAttribute.class);
            PositionLengthAttribute length = stream.addAttribute(PositionLengthAttribute.class);
            TypeAttribute type = stream.addAttribute(TypeAttribute.class);
            List<Token> tokens = new ArrayList<>();
            stream.reset();
            int position = -1;
            while (stream.incrementToken()) {
                if (tokens.size() == maxTokens) {
                    // A stream may be closed before it is used up; end() is only for one that was.
                    return new TokenStage(name, tokens, true);
                }
                position += increment.getPositionIncrement();
                tokens.add(new Token(
                        term.toString(),
                        position,
                        increment.getPositionIncrement(),
                        length.getPositionLength(),
                        offset.startOffset(),
                        offset.endOffset(),
                        type.type(),
                        attributes(stream)));
            }
            stream.end();
            return new TokenStage(name, tokens, false);
        }
    }

    private static List<TokenAttribute> attributes(TokenStream stream) {
        List<TokenAttribute> attributes = new ArrayList<>();
        for (Iterator<AttributeImpl> impls = stream.getAttributeImplsIterator(); impls.hasNext(); ) {
            impls.next().reflectWith((attribute, key, value) -> {
                if (value != null && !TYPED.contains(attribute)) {
                    attributes.add(new TokenAttribute(attribute.getSimpleName(), key, value.toString()));
                }
            });
        }
        return attributes;
    }
}
