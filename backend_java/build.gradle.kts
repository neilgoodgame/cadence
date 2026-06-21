plugins {
	java
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.cadence"
version = "0.1.0"
description = "Cadence training platform REST API (Java/Spring Boot implementation)"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(24)
	}
}

configurations {
	compileOnly {
		extendsFrom(configurations.annotationProcessor.get())
	}
}

repositories {
	mavenCentral()
}

val mapstructVersion = "1.6.3"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-authorization-server")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
	implementation("com.nimbusds:nimbus-jose-jwt")
	implementation("org.springframework.boot:spring-boot-starter-batch")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.retry:spring-retry")

	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")

	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
	implementation("com.garmin:fit:21.176.0")

	implementation("org.mapstruct:mapstruct:$mapstructVersion")
	annotationProcessor("org.mapstruct:mapstruct-processor:$mapstructVersion")

	developmentOnly("org.springframework.boot:spring-boot-devtools")

	testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.3"))
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.batch:spring-batch-test")
	testImplementation("org.springframework.security:spring-security-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// Mirrors the Python backend's pytest -m unit / pytest -m integration split: `test` (the
// default, unfiltered) runs everything, same as plain `pytest -q`; these two give the same
// explicit opt-in subsets. See IntegrationTest.java for how the "integration" tag is applied.
tasks.register<Test>("unitTest") {
	group = "verification"
	description = "Runs tests with no @Tag(\"integration\") - no external services required."
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform {
		excludeTags("integration")
	}
}

tasks.register<Test>("integrationTest") {
	group = "verification"
	description = "Runs tests tagged @Tag(\"integration\") - needs Docker (Testcontainers starts a real Postgres/TimescaleDB)."
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform {
		includeTags("integration")
	}
	shouldRunAfter("test", "unitTest")
}

tasks.withType<JavaCompile> {
	options.compilerArgs.add("-parameters")
}
