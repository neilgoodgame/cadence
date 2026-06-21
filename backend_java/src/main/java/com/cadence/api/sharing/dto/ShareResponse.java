package com.cadence.api.sharing.dto;

import com.cadence.api.sharing.ShareRole;
import com.cadence.api.sharing.ShareStatus;
import java.time.LocalDate;

public record ShareResponse(String id, String name, String handle, ShareRole role, ShareStatus status, LocalDate since) {
}
