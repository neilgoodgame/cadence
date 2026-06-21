package com.cadence.api.uploads.parsing;

import java.io.InputStream;

public final class FileParserDispatcher {

	public static ParsedActivity parse(InputStream inputStream, String filename) throws Exception {
		String lower = filename.toLowerCase();
		if (lower.endsWith(".fit")) {
			return FitFileParser.parse(inputStream);
		}
		if (lower.endsWith(".gpx")) {
			return GpxFileParser.parse(inputStream);
		}
		if (lower.endsWith(".tcx")) {
			return TcxFileParser.parse(inputStream);
		}
		throw new IllegalArgumentException("Unsupported file type: " + filename);
	}

	private FileParserDispatcher() {
	}
}
