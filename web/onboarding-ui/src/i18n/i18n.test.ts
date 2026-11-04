import { describe, expect, it, vi } from "vitest";
import { createI18n, translate } from "./i18n";

describe("i18n", () => {
  it("renders catalog values and falls back to English", () => {
    expect(createI18n("en").t("layout.devBanner")).toBe(
      "Development build",
    );
    expect(translate({}, "common.retry")).toBe("Try again");
  });
  it("returns a visible key for a missing translation", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    expect(createI18n("en").t("missing.key")).toBe("missing.key");
    warn.mockRestore();
  });
});
