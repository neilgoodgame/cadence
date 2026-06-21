package com.cadence.api.tokens;

import com.cadence.api.common.error.NotFoundException;
import com.cadence.api.common.error.ValidationException;
import com.cadence.api.security.pat.PersonalAccessToken;
import com.cadence.api.security.pat.PersonalAccessTokenRepository;
import com.cadence.api.security.pat.PersonalAccessTokenService;
import com.cadence.api.tokens.dto.CreateAccessTokenRequest;
import com.cadence.api.users.User;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The CRUD orchestration for personal access tokens (listing, persisting, rotating, revoking).
 * Kept separate from {@link PersonalAccessTokenService}, which only handles the
 * generate/hash/verify mechanics shared with the authentication path.
 */
@Service
public class AccessTokenService {

	private final PersonalAccessTokenRepository repository;
	private final PersonalAccessTokenService cryptoService;

	public AccessTokenService(PersonalAccessTokenRepository repository, PersonalAccessTokenService cryptoService) {
		this.repository = repository;
		this.cryptoService = cryptoService;
	}

	public record CreatedToken(PersonalAccessToken token, String secret) {
	}

	public List<PersonalAccessToken> list(String userId) {
		return repository.findByUserIdOrderByCreatedDesc(userId);
	}

	@Transactional
	public CreatedToken create(User user, CreateAccessTokenRequest request, Set<String> callerScopes) {
		if (!callerScopes.isEmpty() && !callerScopes.containsAll(request.scopes())) {
			throw new ValidationException("scopes must be a subset of your own scopes.", "scopes");
		}
		String secret = cryptoService.generateSecret();
		PersonalAccessToken token = new PersonalAccessToken();
		token.setUser(user);
		token.setName(request.name());
		token.setPrefix(cryptoService.visiblePrefix(secret));
		token.setHashedSecret(cryptoService.hash(secret));
		token.setScopes(request.scopes());
		token.setExpiresAt(request.expiresAt());
		repository.save(token);
		return new CreatedToken(token, secret);
	}

	public PersonalAccessToken get(String id) {
		return repository.findById(id).orElseThrow(() -> new NotFoundException("No such token."));
	}

	@Transactional
	public CreatedToken rotate(PersonalAccessToken token) {
		String secret = cryptoService.generateSecret();
		token.setPrefix(cryptoService.visiblePrefix(secret));
		token.setHashedSecret(cryptoService.hash(secret));
		repository.save(token);
		return new CreatedToken(token, secret);
	}

	@Transactional
	public void revoke(String id) {
		repository.deleteById(id);
	}
}
