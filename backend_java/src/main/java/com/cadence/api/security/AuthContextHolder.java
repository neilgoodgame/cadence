package com.cadence.api.security;

/** Request-scoped accessor for the resolved {@link AuthContext}, populated by {@link AuthContextFilter}. */
public final class AuthContextHolder {

	private static final ThreadLocal<AuthContext> CURRENT = new ThreadLocal<>();

	private AuthContextHolder() {
	}

	public static void set(AuthContext context) {
		CURRENT.set(context);
	}

	public static AuthContext get() {
		AuthContext context = CURRENT.get();
		if (context == null) {
			throw new IllegalStateException("No AuthContext bound to this request.");
		}
		return context;
	}

	public static void clear() {
		CURRENT.remove();
	}
}
