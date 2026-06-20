package com.cadence.api.common.jpa;

import jakarta.persistence.AttributeConverter;

/**
 * Maps a Java enum to/from the lowercase string the database (and the wire format)
 * actually uses, e.g. {@code ShareRole.COACH <-> "coach"}. One concrete subclass per
 * enum type, registered with {@code @Converter(autoApply = true)}.
 */
public abstract class LowercaseEnumConverter<E extends Enum<E>> implements AttributeConverter<E, String> {

	private final Class<E> enumType;

	protected LowercaseEnumConverter(Class<E> enumType) {
		this.enumType = enumType;
	}

	@Override
	public String convertToDatabaseColumn(E attribute) {
		return attribute == null ? null : attribute.name().toLowerCase();
	}

	@Override
	public E convertToEntityAttribute(String dbData) {
		return dbData == null ? null : Enum.valueOf(enumType, dbData.toUpperCase());
	}
}
