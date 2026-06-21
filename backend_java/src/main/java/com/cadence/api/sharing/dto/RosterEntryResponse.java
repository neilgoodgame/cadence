package com.cadence.api.sharing.dto;

public record RosterEntryResponse(String athleteId, String name, double compliance, int tsb, int flags) {
}
