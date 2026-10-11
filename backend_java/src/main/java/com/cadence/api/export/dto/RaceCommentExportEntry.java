package com.cadence.api.export.dto;

import java.time.Instant;

/** authorId/authorName are deliberately omitted - an imported comment's original author may not
 * exist on the importing account at all (a coach account on a different system, say), so every
 * imported comment is attributed to the importing athlete instead (see ImportReader's
 * importRaceComments). */
public record RaceCommentExportEntry(
		String id, String raceId, String role, String text, Integer week, String parentId, Instant createdAt) {
}
