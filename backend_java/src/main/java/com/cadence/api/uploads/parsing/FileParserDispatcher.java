package com.cadence.api.uploads.parsing;

import java.io.InputStream;
import java.util.List;

public final class FileParserDispatcher {

	/**
	 * Single-element for GPX/TCX and normal FIT files; a multisport FIT file yields the parent
	 * activity first, then one child per session - see {@link FitFileParser#parse}.
	 */
	public static List<ParsedActivity> parse(InputStream inputStream, String filename) throws Exception {
		String lower = filename.toLowerCase();
		if (lower.endsWith(".fit")) {
			return FitFileParser.parse(inputStream);
		}
		if (lower.endsWith(".gpx")) {
			return List.of(GpxFileParser.parse(inputStream));
		}
		if (lower.endsWith(".tcx")) {
			return List.of(TcxFileParser.parse(inputStream));
		}
		throw new IllegalArgumentException("Unsupported file type: " + filename);
	}

	private FileParserDispatcher() {
	}
}
