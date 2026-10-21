import { expect, it } from "vitest";
import {
  browserName,
  choiceLabel,
  formatBytes,
  humanize,
  plural,
} from "../src/features/format";

it("writes counts and sizes the way people read them", () => {
  expect(plural(1, "record")).toBe("1 record");
  expect(plural(2, "record")).toBe("2 records");
  expect(plural(0, "attachment")).toBe("0 attachments");
  expect(formatBytes(1)).toBe("1 byte");
  expect(formatBytes(250)).toBe("250 bytes");
  expect(formatBytes(3_400)).toBe("3.4 KB");
  expect(formatBytes(12_000_000)).toBe("12 MB");
});

it("turns setting keys and enum values into labels", () => {
  expect(humanize("min_notes")).toBe("Minimum notes");
  expect(humanize("min_age_days")).toBe("Minimum age days");
  expect(humanize("urls")).toBe("URLs");
  expect(choiceLabel("sunday")).toBe("Sunday");
  expect(choiceLabel("it")).toBe(
    new Intl.DisplayNames(undefined, { type: "language" }).of("it"),
  );
  expect(choiceLabel(3)).toBe("3");
});

it("names a browser from its user agent and keeps unknown agents as they are", () => {
  expect(
    browserName(
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
    ),
  ).toBe("Chrome on macOS");
  expect(
    browserName(
      "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1",
    ),
  ).toBe("Safari on iOS");
  expect(
    browserName(
      "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0",
    ),
  ).toBe("Firefox on Linux");
  expect(browserName("curl/8.7.1")).toBe("curl/8.7.1");
});
