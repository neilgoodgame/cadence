package com.cadence.api.mcp.dispatch;

import com.cadence.api.common.error.ForbiddenException;
import com.cadence.api.security.AuthContextHolder;
import org.springframework.stereotype.Component;

/**
 * The one new authorization layer MCP tools add on top of the existing REST stack - scope
 * checking. Ownership/coach-share checks still go through the same {@code AccessGuard} every
 * REST controller uses; this only gates on whether the calling token was granted the scope a
 * given tool requires, since scopes aren't enforced per-REST-endpoint anywhere else in the app.
 */
@Component
public class McpToolAuthorizer {

	public void requireScope(String scope) {
		if (!AuthContextHolder.get().scopes().contains(scope)) {
			throw new ForbiddenException("This action requires the '" + scope + "' scope.");
		}
	}
}
