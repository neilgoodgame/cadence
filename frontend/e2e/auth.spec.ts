import { expect, test } from "@playwright/test";
import { signUpNewAthlete } from "./helpers";

test("a new athlete can sign up and land on an authenticated dashboard", async ({ page }) => {
  const { name } = await signUpNewAthlete(page);

  await expect(page.getByText(name, { exact: true })).toBeVisible();
});

test("signing out returns to the login screen", async ({ page }) => {
  await signUpNewAthlete(page);

  await page.goto("/preferences");
  await page.getByRole("button", { name: "Log out" }).click();
  await expect(page).toHaveURL(/\/login$/);
});
