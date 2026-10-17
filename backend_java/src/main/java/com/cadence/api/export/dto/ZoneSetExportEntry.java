package com.cadence.api.export.dto;

import com.cadence.api.athletes.Zone;
import com.cadence.api.athletes.ZoneType;
import java.util.List;

/** One row of the top-level "zones" export section - present only when the export was requested
 * with includeProfile=true (see ExportWriter.write), same opt-in reasoning as
 * ProfileExportEntry. Only whatever ZoneSet rows the athlete actually has are written - a type
 * never opened in the Zones editor simply isn't in the file. */
public record ZoneSetExportEntry(ZoneType type, List<Zone> zones) {
}
