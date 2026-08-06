package com.cadence.api.cql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Mirrors the Python backend's core/cql/tests.py::TokenizeTests quoted-phrase coverage. */
class CqlTokenizerTest {

	@Test
	void quotedPhraseStaysOneToken() {
		assertThat(CqlTokenizer.tokenize("tag \"Heat Training\"")).isEqualTo(List.of("tag", "heat training"));
	}

	@Test
	void quotedPhraseIsProtectedFromPhraseReplacement() {
		// Unquoted, "order by" collapses to the "orderby" keyword - quoting it as a
		// literal tag value must not.
		assertThat(CqlTokenizer.tokenize("tag \"Order By Distance\""))
				.isEqualTo(List.of("tag", "order by distance"));
	}

	@Test
	void quotedPhraseIsProtectedFromStopwordFiltering() {
		// "the" alone would be dropped as a stop word.
		assertThat(CqlTokenizer.tokenize("tag \"The Big Race\"")).isEqualTo(List.of("tag", "the big race"));
	}

	@Test
	void multipleQuotedPhrasesInOneQuery() {
		assertThat(CqlTokenizer.tokenize("tag \"Heat Training\" or tag \"Cold Run\""))
				.isEqualTo(List.of("tag", "heat training", "or", "tag", "cold run"));
	}
}
