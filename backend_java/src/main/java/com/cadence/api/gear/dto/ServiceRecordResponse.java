package com.cadence.api.gear.dto;

import com.cadence.api.gear.ServiceAction;
import java.time.LocalDate;

public record ServiceRecordResponse(String id, String componentId, ServiceAction action, boolean reset, String note, LocalDate date) {
}
