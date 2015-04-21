package org.oscii.concordance;

import edu.stanford.nlp.mt.tm.DynamicTranslationModel;
import edu.stanford.nlp.mt.tm.SampledRule;
import edu.stanford.nlp.mt.util.ParallelSuffixArray;
import gnu.trove.map.hash.THashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.oscii.lex.Expression;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

/**
 * Corpus backed by a suffix array.
 */
public class SuffixArrayCorpus extends AlignedCorpus {
    // source language -> target language -> suffix array
    Map<String, Map<String, ParallelSuffixArray>> suffixes;
    private int maxSamples = 1000;
    private int maxTargetPhrase = 5;

    private final static Logger log = LogManager.getLogger(SuffixArrayCorpus.class);

    @Override
    public void read(String path, String sourceLanguage, String targetLanguage, int max) throws IOException {
        log.info("Reading sentences: " + sourceLanguage + "-" + targetLanguage);
        ParallelFiles paths = paths(path, sourceLanguage, targetLanguage);
        ParallelSuffixArray suffixArray = new ParallelSuffixArray(
                paths.sourceSentences.toString(),
                paths.targetSentences.toString(),
                paths.alignments.toString(),
                0);
        Map<String, ParallelSuffixArray> bySource = suffixes.getOrDefault(sourceLanguage, new THashMap<>());
        if (bySource.containsKey(targetLanguage)) {
            throw new RuntimeException("Multiple corpora for a language pair: "
                    + sourceLanguage + ", " + targetLanguage);
        }
        bySource.put(targetLanguage, suffixArray);
    }

    @Override
    public Function<Expression, Double> translationFrequencies(Expression source) {
        Map<String, ParallelSuffixArray> arrays = suffixes.get(source.language);
        if (arrays == null) {
            return AlignedCorpus::zeroFrequency;
        }

        Map<String, Map<String, Long>> counts = new THashMap<>();
        arrays.entrySet().forEach(kv -> counts.put(kv.getKey(), countAll(source.text, kv.getValue())));
        return normalizeByLanguage(counts);
    }

    private Map<String, Long> countAll(String text, ParallelSuffixArray suffixArray) {
        // Generate samples
        String[] words = text.trim().split("\\s+");
        int[] phrase = new int[words.length];
        for (int i = 0; i < phrase.length; i++) {
            phrase[i] = suffixArray.getVocabulary().indexOf(words[i]);
        }
        List<ParallelSuffixArray.QueryResult> samples = suffixArray.sample(phrase, true, maxSamples).samples;

        // Count translations
        Map<String, Long> counts = new THashMap<>();
        return samples.stream().flatMap(
                r -> DynamicTranslationModel.extractRules(r, words.length, maxTargetPhrase).stream())
                .collect(groupingBy(rule -> targetOf(rule, suffixArray), counting()));
    }

    private String targetOf(SampledRule rule, ParallelSuffixArray suffixArray) {
        String[] words = new String[rule.tgt.length];
        for (int i = 0; i < rule.tgt.length; i++) {
            words[i] = suffixArray.getVocabulary().get(rule.tgt[i]);
        }
        return String.join(" ", words);
    }

    @Override
    public List<AlignedSentence> examples(String query, String source, String target, int max) {
        return emptyList();
    }
}
