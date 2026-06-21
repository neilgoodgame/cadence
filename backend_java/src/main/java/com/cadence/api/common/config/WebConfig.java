package com.cadence.api.common.config;

import com.cadence.api.activities.BestEffortKind;
import com.cadence.api.activities.DurationCurveMetric;
import com.cadence.api.activities.Environment;
import com.cadence.api.athletes.ZoneType;
import com.cadence.api.common.domain.Sport;
import com.cadence.api.uploads.OnDuplicate;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Path/query parameters go through Spring's {@code Converter} mechanism, not Jackson - so
 * enums with a lowercase wire format (e.g. {@code heart_rate}) need an explicit converter
 * here even though {@code @JsonCreator} already handles them in request/response bodies.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

	@Override
	public void addFormatters(FormatterRegistry registry) {
		registry.addConverter(new Converter<String, ZoneType>() {
			@Override
			public ZoneType convert(String source) {
				return ZoneType.fromWireValue(source);
			}
		});
		registry.addConverter(new Converter<String, Sport>() {
			@Override
			public Sport convert(String source) {
				return Sport.fromWireValue(source);
			}
		});
		registry.addConverter(new Converter<String, Environment>() {
			@Override
			public Environment convert(String source) {
				return Environment.fromWireValue(source);
			}
		});
		registry.addConverter(new Converter<String, DurationCurveMetric>() {
			@Override
			public DurationCurveMetric convert(String source) {
				return DurationCurveMetric.fromWireValue(source);
			}
		});
		registry.addConverter(new Converter<String, BestEffortKind>() {
			@Override
			public BestEffortKind convert(String source) {
				return BestEffortKind.fromWireValue(source);
			}
		});
		registry.addConverter(new Converter<String, OnDuplicate>() {
			@Override
			public OnDuplicate convert(String source) {
				return OnDuplicate.fromWireValue(source);
			}
		});
	}
}
