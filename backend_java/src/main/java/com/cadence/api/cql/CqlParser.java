package com.cadence.api.cql;

import java.util.List;

/** Entry point: tokenizes, extracts a trailing {@code orderby [asc|desc]} clause, then parses the remainder. */
public final class CqlParser {

	public record Order(String field, String direction) {
	}

	public record ParseResult(CqlNode ast, Order order, boolean empty) {

		public static ParseResult emptyResult() {
			return new ParseResult(null, null, true);
		}
	}

	public static ParseResult parse(String raw) {
		List<String> tokens = CqlTokenizer.tokenize(raw);
		if (tokens.isEmpty()) {
			return ParseResult.emptyResult();
		}

		List<String> filterTokens = tokens;
		Order order = null;
		int orderByIndex = tokens.indexOf("orderby");
		if (orderByIndex >= 0) {
			List<String> rest = tokens.subList(orderByIndex + 1, tokens.size());
			filterTokens = tokens.subList(0, orderByIndex);
			String fieldWord = rest.isEmpty() ? null : rest.get(0);
			String f = fieldWord != null ? CqlFieldRegistry.fieldKey(fieldWord) : null;
			String direction = "desc";
			for (String w : rest) {
				if (w.startsWith("asc")) {
					direction = "asc";
				}
			}
			for (String w : rest) {
				if (w.startsWith("desc")) {
					direction = "desc";
				}
			}
			if (f != null) {
				order = new Order(f, direction);
			}
			else if (fieldWord != null) {
				throw new CqlException("Unknown sort field \"" + fieldWord + "\"");
			}
		}

		CqlGrammarParser parser = new CqlGrammarParser(filterTokens);
		CqlNode ast = parser.parseOr();
		if (ast == null && order == null) {
			throw new CqlException("Could not understand the query");
		}
		return new ParseResult(ast, order, false);
	}

	private CqlParser() {
	}
}
