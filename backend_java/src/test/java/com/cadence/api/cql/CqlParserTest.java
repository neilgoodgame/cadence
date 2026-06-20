package com.cadence.api.cql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CqlParserTest {

	@Test
	void simpleComparisonWithUnit() {
		CqlParser.ParseResult result = CqlParser.parse("avg hr > 140bpm");
		assertThat(result.ast()).isEqualTo(new CqlNode.Cmp("hr", ">", 140.0));
	}

	@Test
	void naturalLanguagePhraseNormalization() {
		CqlParser.ParseResult result = CqlParser.parse("distance greater than 10km");
		assertThat(result.ast()).isEqualTo(new CqlNode.Cmp("distance", ">", 10.0));
	}

	@Test
	void sportKeywordRecognized() {
		CqlParser.ParseResult result = CqlParser.parse("sport = run");
		assertThat(result.ast()).isEqualTo(new CqlNode.Cmp("sport", "=", "run"));
	}

	@Test
	void bareSportWordWithoutFieldName() {
		CqlParser.ParseResult result = CqlParser.parse("rides");
		assertThat(result.ast()).isEqualTo(new CqlNode.Cmp("sport", "=", "bike"));
	}

	@Test
	void andCombinator() {
		CqlParser.ParseResult result = CqlParser.parse("sport = run and distance > 10km");
		assertThat(result.ast()).isEqualTo(new CqlNode.And(
				new CqlNode.Cmp("sport", "=", "run"),
				new CqlNode.Cmp("distance", ">", 10.0)));
	}

	@Test
	void implicitAndWithoutKeyword() {
		CqlParser.ParseResult result = CqlParser.parse("sport = run distance > 10km");
		assertThat(result.ast()).isEqualTo(new CqlNode.And(
				new CqlNode.Cmp("sport", "=", "run"),
				new CqlNode.Cmp("distance", ">", 10.0)));
	}

	@Test
	void orCombinator() {
		CqlParser.ParseResult result = CqlParser.parse("sport = run or sport = bike");
		assertThat(result.ast()).isEqualTo(new CqlNode.Or(
				new CqlNode.Cmp("sport", "=", "run"),
				new CqlNode.Cmp("sport", "=", "bike")));
	}

	@Test
	void tagFilter() {
		CqlParser.ParseResult result = CqlParser.parse("tagged race");
		assertThat(result.ast()).isEqualTo(new CqlNode.Cmp("tag", "=", "race"));
	}

	@Test
	void tagSingularization() {
		// "tag races" -> singularized to "race", mirroring the Python reference's _singular().
		CqlParser.ParseResult result = CqlParser.parse("tag races");
		assertThat(result.ast()).isEqualTo(new CqlNode.Cmp("tag", "=", "race"));
	}

	@Test
	void orderByWithDirection() {
		CqlParser.ParseResult result = CqlParser.parse("sport = run orderby tss asc");
		assertThat(result.order()).isEqualTo(new CqlParser.Order("tss", "asc"));
		assertThat(result.ast()).isEqualTo(new CqlNode.Cmp("sport", "=", "run"));
	}

	@Test
	void orderByDefaultsToDescending() {
		CqlParser.ParseResult result = CqlParser.parse("orderby distance");
		assertThat(result.order()).isEqualTo(new CqlParser.Order("distance", "desc"));
	}

	@Test
	void emptyQueryIsEmpty() {
		CqlParser.ParseResult result = CqlParser.parse("   ");
		assertThat(result.empty()).isTrue();
	}

	@Test
	void parenthesesAndPrecedence() {
		// Note: like the reference tokenizer, "(" / ")" only become their own tokens when
		// already whitespace-separated in the input - the normalizer never inserts spaces
		// around them itself.
		CqlParser.ParseResult result = CqlParser.parse("( sport = run or sport = bike ) and distance > 10km");
		assertThat(result.ast()).isEqualTo(new CqlNode.And(
				new CqlNode.Or(new CqlNode.Cmp("sport", "=", "run"), new CqlNode.Cmp("sport", "=", "bike")),
				new CqlNode.Cmp("distance", ">", 10.0)));
	}

	@Test
	void greaterThanOrEqualPhrase() {
		CqlParser.ParseResult result = CqlParser.parse("tss greater than or equal to 100");
		assertThat(result.ast()).isEqualTo(new CqlNode.Cmp("tss", ">=", 100.0));
	}

	@Test
	void missingUnitOnBareOperatorThrows() {
		assertThatThrownBy(() -> CqlParser.parse("> 140"))
				.isInstanceOf(CqlException.class)
				.hasMessageContaining("unit");
	}

	@Test
	void unknownSortFieldThrows() {
		assertThatThrownBy(() -> CqlParser.parse("orderby bananas"))
				.isInstanceOf(CqlException.class)
				.hasMessageContaining("Unknown sort field");
	}

	@Test
	void paceLiteralParsedAsSeconds() {
		CqlParser.ParseResult result = CqlParser.parse("pace = 4:30");
		assertThat(result.ast()).isEqualTo(new CqlNode.Cmp("pace", "=", 270.0));
	}
}
