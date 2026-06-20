package com.cadence.api.cql;

import java.util.ArrayList;
import java.util.List;

/**
 * Normalizes a natural-language-ish query into tokens: an ordered list of phrase-level
 * regex passes ("greater than" -> {@code >}, "tagged as" -> {@code tag}, ...) - order
 * matters, since more specific phrases must be consumed before the shorter phrases they
 * contain (e.g. "greater than or equal to" before "greater than") - followed by
 * whitespace splitting and stop-word filtering.
 */
public final class CqlTokenizer {

	private static final String[][] PHRASE_REPLACEMENTS = {
			{"average heart rate|avg heart rate|average hr|avg hr|heart rate|heartrate", " hr "},
			{"max(imum)? heart rate|max hr", " maxhr "},
			{"training load", " tss "},
			{"order(ed)? by|sort(ed)? by", " orderby "},
			{"tagged as|tagged|\\btags\\b|\\btag\\b|\\blabelled\\b|\\blabeled\\b", " tag "},
			{"greater than or equal to|at least", " >= "},
			{"less than or equal to|at most", " <= "},
			{"not equal to|is not|isn't", " != "},
			{"longer than|greater than|more than|bigger than|\\blonger\\b|\\bover\\b|\\babove\\b|\\bgreater\\b|\\bmore\\b", " > "},
			{"shorter than|less than|fewer than|smaller than|\\bshorter\\b|\\bunder\\b|\\bbelow\\b|\\bless\\b|\\bfewer\\b", " < "},
			{"equal to|equals|\\bequal\\b|\\bis\\b", " = "},
	};

	public static String normalize(String raw) {
		String s = " " + raw.toLowerCase().replace(",", " ").replace(";", " ") + " ";
		for (String[] rule : PHRASE_REPLACEMENTS) {
			s = s.replaceAll(rule[0], rule[1]);
		}
		// Protect multi-char operators behind placeholders before the single-char
		// passes below would otherwise tear them apart (">=" -> "> =").
		s = s.replaceAll(">=|=>", " ≥ ");
		s = s.replaceAll("<=|=<", " ≤ ");
		s = s.replaceAll("!=|<>", " ≠ ");
		s = s.replaceAll("=", " = ");
		s = s.replaceAll(">", " > ");
		s = s.replaceAll("<", " < ");
		s = s.replaceAll("≥", " >= ");
		s = s.replaceAll("≤", " <= ");
		s = s.replaceAll("≠", " != ");
		return s.replaceAll("\\s+", " ").trim();
	}

	public static List<String> tokenize(String raw) {
		String norm = normalize(raw);
		if (norm.isEmpty()) {
			return List.of();
		}
		List<String> tokens = new ArrayList<>();
		for (String t : norm.split(" ")) {
			if (!t.isEmpty() && !CqlFieldRegistry.STOP_WORDS.contains(t)) {
				tokens.add(t);
			}
		}
		return tokens;
	}

	private CqlTokenizer() {
	}
}
