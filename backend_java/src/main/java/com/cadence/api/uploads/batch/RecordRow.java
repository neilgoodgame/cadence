package com.cadence.api.uploads.batch;

import java.sql.Timestamp;

/**
 * One {@code record} table row, built once by {@link RecordItemProcessor} and never mutated
 * afterward - a record fits that write-once shape better than the mutable JavaBean this used to
 * be. {@code ts} is a {@code java.sql.Timestamp} rather than {@code Instant}: this goes through
 * plain JDBC (bypassing Hibernate's type system, which does know how to bind an {@code Instant}),
 * and pgjdbc's own {@code setObject} can't infer a SQL type for a bare {@code Instant}.
 *
 * <p>{@code UploadJobConfig}'s {@code recordItemWriter} maps this via an explicit
 * {@code ItemSqlParameterSourceProvider}, not {@code JdbcBatchItemWriterBuilder.beanMapped()} -
 * {@code beanMapped()} resolves named SQL parameters through {@code getXxx()}-style JavaBean
 * accessors, which a record's {@code xxx()}-named accessors don't match.
 */
public record RecordRow(
		String activityId,
		Timestamp ts,
		int t,
		Integer power,
		Integer heartrate,
		Integer cadence,
		Double altitude,
		Double lat,
		Double lng,
		Double speed,
		Double distanceKm,
		Double airTemp,
		Integer humidity,
		Double coreTemp,
		Double skinTemp,
		Double heatStrain) {
}
