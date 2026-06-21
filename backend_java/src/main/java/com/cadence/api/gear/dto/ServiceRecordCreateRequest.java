package com.cadence.api.gear.dto;

import com.cadence.api.gear.ServiceAction;
import java.time.LocalDate;

public record ServiceRecordCreateRequest(ServiceAction action, Boolean reset, String note, LocalDate date) {
}
