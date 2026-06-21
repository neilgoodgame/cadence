package com.cadence.api.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Extend this for any test that needs a real Spring context and/or database - it boots the
 * actual app context against a Testcontainers Postgres/TimescaleDB instance (same image as
 * {@code docker-compose.yml}, so the {@code timescaledb} extension and {@code create_hypertable}
 * in the Flyway migrations actually apply, not just plain Postgres) and runs every real
 * migration on startup, exercising the schema for real rather than mocking JPA.
 *
 * <p>{@code @Tag("integration")} is {@code @Inherited}, so every subclass is tagged without
 * needing its own annotation - same property the Python backend's {@code conftest.py} gets from
 * auto-detecting {@code TestCase}/{@code TransactionTestCase} subclasses: a test needing the
 * database has no way to get one without going through this class, so it can't end up
 * un-tagged by mistake. See {@code build.gradle.kts}'s {@code unitTest}/{@code integrationTest}
 * tasks for how the tag is used to split test runs.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
public abstract class IntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
			DockerImageName.parse("timescale/timescaledb:latest-pg16").asCompatibleSubstituteFor("postgres"));
}
