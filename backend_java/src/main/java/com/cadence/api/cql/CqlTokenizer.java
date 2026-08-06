package com.cadence.api.cql;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

	private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");
	// Alphabetic prefix (never produced by normalize()'s digit/operator handling, and not
	// a substring any phrase-replacement rule matches) so a placeholder token like
	// "cqlquotedphrase3" can never collide with a real token - a bare number like "140"
	// is all-digits, so prefixing with letters is enough to disambiguate without needing
	// a control character that has to survive being typed into a browser input.
	private static final String PLACEHOLDER_PREFIX = "cqlquotedphrase";
	private static final Pattern PLACEHOLDER = Pattern.compile(Pattern.quote(PLACEHOLDER_PREFIX) + "(\\d+)");

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
		// Quoted phrases (e.g. `tag "Heat Training"`) are pulled out before normalize()
		// runs, so the phrase-replacement passes and stop-word filter below - built for
		// loose natural-language input - can't reach inside and mangle a literal
		// multi-word value (a tag named "Order By Distance" would otherwise get torn
		// apart by the "order by" phrase rule). Each quoted phrase collapses to exactly
		// one token, spaces intact.
		List<String> quoted = new ArrayList<>();
		Matcher quoteMatcher = QUOTED.matcher(raw);
		StringBuilder stashed = new StringBuilder();
		int last = 0;
		while (quoteMatcher.find()) {
			stashed.append(raw, last, quoteMatcher.start());
			quoted.add(quoteMatcher.group(1).toLowerCase().trim());
			stashed.append(' ').append(PLACEHOLDER_PREFIX).append(quoted.size() - 1).append(' ');
			last = quoteMatcher.end();
		}
		stashed.append(raw.substring(last));

		String norm = normalize(stashed.toString());
		if (norm.isEmpty()) {
			return List.of();
		}
		List<String> tokens = new ArrayList<>();
		for (String t : norm.split(" ")) {
			if (t.isEmpty() || CqlFieldRegistry.STOP_WORDS.contains(t)) {
				continue;
			}
			Matcher placeholder = PLACEHOLDER.matcher(t);
			tokens.add(placeholder.matches() ? quoted.get(Integer.parseInt(placeholder.group(1))) : t);
		}
		return tokens;
	}

	private CqlTokenizer() {
	}
}
