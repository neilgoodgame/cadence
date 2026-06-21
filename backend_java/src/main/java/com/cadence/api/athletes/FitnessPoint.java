package com.cadence.api.athletes;

import java.time.LocalDate;

public record FitnessPoint(LocalDate date, double ctl, double atl, double tsb) {
}
