from django.contrib.auth.views import LoginView


class MCPLoginView(LoginView):
    """Session-based login page for oauth2-toolkit's ``AuthorizationView`` - it's a
    ``LoginRequiredMixin`` view that needs ``request.user.is_authenticated`` via a real Django
    session, which nothing in this app previously produced. Used only by the browser-redirect
    authorize/consent dance for the ``cadence-mcp`` client; unrelated to ``/v1/auth/login``
    (``accounts/views.py``), which stays a pure JSON API for the first-party frontend and never
    touches Django's session.

    Uses Django's stock ``AuthenticationForm``, which works out of the box against ``accounts.User``
    (``AUTH_USER_MODEL``) since it already extends ``AbstractBaseUser``/``PermissionsMixin`` with
    ``USERNAME_FIELD = "email"`` and ``set_password()`` hashing - no custom auth backend needed.
    """

    template_name = "authn/login.html"
    redirect_authenticated_user = True
