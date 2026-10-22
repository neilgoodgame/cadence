package com.cadence.api.garmin;

import com.cadence.api.common.error.ValidationException;
import com.cadence.api.users.User;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GarminConnectionService {

	private final GarminConnectionRepository repository;
	private final GarminKeyCipher cipher;

	public GarminConnectionService(GarminConnectionRepository repository, GarminKeyCipher cipher) {
		this.repository = repository;
		this.cipher = cipher;
	}

	public Optional<GarminConnection> find(String ownerId) {
		return repository.findByOwnerId(ownerId);
	}

	@Transactional
	public GarminConnection save(User owner, String apiKey, String email, LocalDate keyExpiresOn,
			Integer syncLimit) {
		String key = apiKey == null || apiKey.isBlank() ? null : apiKey.trim();
		GarminConnection connection = repository.findByOwnerId(owner.getId()).orElse(null);
		if (connection == null && key == null) {
			throw new ValidationException("A Garmin API key is required.", "api_key");
		}
		if (keyExpiresOn != null && keyExpiresOn.isBefore(LocalDate.now(ZoneOffset.UTC))) {
			throw new ValidationException("The key's expiry date is in the past.", "key_expires_on");
		}
		String garminEmail = email == null || email.isBlank() ? owner.getEmail() : email.trim();
		if (garminEmail == null) {
			throw new ValidationException("An email address is required.", "email");
		}
		if (connection == null) {
			connection = new GarminConnection();
			connection.setOwner(owner);
		}
		if (key != null) {
			connection.setApiKeyEncrypted(cipher.encrypt(key, owner.getId()));
		}
		connection.setGarminEmail(garminEmail);
		connection.setKeyExpiresOn(keyExpiresOn);
		if (syncLimit != null) {
			if (syncLimit < 1 || syncLimit > GarminConnection.MAX_SYNC_LIMIT) {
				throw new ValidationException("sync_limit must be between 1 and " + GarminConnection.MAX_SYNC_LIMIT + ".",
						"sync_limit");
			}
			connection.setSyncLimit(syncLimit);
		}
		return repository.save(connection);
	}

	@Transactional
	public void delete(String ownerId) {
		repository.deleteByOwnerId(ownerId);
	}
}
