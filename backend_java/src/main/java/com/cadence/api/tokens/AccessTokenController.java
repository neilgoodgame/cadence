package com.cadence.api.tokens;

import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.common.paging.DataListResponse;
import com.cadence.api.security.AuthContext;
import com.cadence.api.security.AuthContextHolder;
import com.cadence.api.security.pat.PersonalAccessToken;
import com.cadence.api.tokens.dto.AccessTokenCreatedResponse;
import com.cadence.api.tokens.dto.AccessTokenResponse;
import com.cadence.api.tokens.dto.CreateAccessTokenRequest;
import com.cadence.api.users.User;
import com.cadence.api.users.UserService;
import jakarta.validation.Valid;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccessTokenController {

	private final AccessTokenService accessTokenService;
	private final UserService userService;

	public AccessTokenController(AccessTokenService accessTokenService, UserService userService) {
		this.accessTokenService = accessTokenService;
		this.userService = userService;
	}

	@GetMapping("/v1/auth/tokens")
	public DataListResponse<AccessTokenResponse> list() {
		String userId = AuthContextHolder.get().sub();
		return new DataListResponse<>(accessTokenService.list(userId).stream().map(this::toResponse).toList());
	}

	@PostMapping("/v1/auth/tokens")
	@ResponseStatus(HttpStatus.CREATED)
	public AccessTokenCreatedResponse create(@Valid @RequestBody CreateAccessTokenRequest request) {
		AuthContext context = AuthContextHolder.get();
		User user = userService.getById(context.sub());
		AccessTokenService.CreatedToken created = accessTokenService.create(user, request, context.scopes());
		return new AccessTokenCreatedResponse(toResponse(created.token()), created.secret());
	}

	@DeleteMapping("/v1/auth/tokens/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void revoke(@PathVariable String id) {
		requireOwned(id);
		accessTokenService.revoke(id);
	}

	@PostMapping("/v1/auth/tokens/{id}/rotate")
	public AccessTokenCreatedResponse rotate(@PathVariable String id) {
		PersonalAccessToken token = requireOwned(id);
		AccessTokenService.CreatedToken rotated = accessTokenService.rotate(token);
		return new AccessTokenCreatedResponse(toResponse(rotated.token()), rotated.secret());
	}

	private PersonalAccessToken requireOwned(String id) {
		PersonalAccessToken token = accessTokenService.get(id);
		if (!token.getUser().getId().equals(AuthContextHolder.get().sub())) {
			throw new ForbiddenException("This token does not belong to you.");
		}
		return token;
	}

	private AccessTokenResponse toResponse(PersonalAccessToken token) {
		return new AccessTokenResponse(token.getId(), token.getName(), token.getPrefix(), token.getScopes(),
				token.getCreated().atZone(ZoneOffset.UTC).toLocalDate(), token.getExpiresAt(), token.getLastUsed());
	}
}
