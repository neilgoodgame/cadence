import type { Page } from "@playwright/test";

/** Signs up a fresh, never-before-seen athlete through the real UI and waits for the
 * post-login redirect - the cheapest way to get an authenticated, isolated account per test
 * without coupling to a shared/manually-managed test user.
 *
 * Deliberately NOT cached/shared via Playwright's storageState pattern across tests: the
 * backend's refresh token is single-use and rotated on every redemption (see
 * AuthContext.tsx's doRefresh comment), and every full page load re-triggers a silent
 * refresh on mount. A storageState snapshot's baked-in refresh token can only ever be
 * redeemed successfully by the first test that loads it - every other test/context reusing
 * that same snapshot's token fails and gets logged out. One signup per test is correct here,
 * not just simpler - see e2e/README.md for the resulting rate-limit implication. */
export async function signUpNewAthlete(page: Page, name = "E2E Test User") {
  const email = `e2e-${Date.now()}-${Math.random().toString(36).slice(2)}@example.com`;
  const password = "correct-horse-battery";

  await page.goto("/login");
  await page.getByText("Create account", { exact: true }).click();
  await page.getByLabel("Full name").fill(name);
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(password);
  await page.locator("form").getByRole("button", { name: "Create account" }).click();
  await page.waitForURL("/");

  return { name, email, password };
}
