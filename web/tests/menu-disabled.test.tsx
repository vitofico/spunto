import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MoreMenu } from "../src/components/core/Menu";

afterEach(cleanup);

it("keeps pending label actions closed until the menu is enabled", async () => {
  const select = vi.fn();
  const items = [{ label: "Rename", onSelect: select }];
  const view = render(
    <MoreMenu label="Label actions" items={items} disabled />,
  );
  const trigger = screen.getByRole("button", { name: "Label actions" });
  expect(trigger.hasAttribute("disabled")).toBe(true);
  await userEvent.click(trigger);
  expect(screen.queryByRole("menu")).toBeNull();
  view.rerender(<MoreMenu label="Label actions" items={items} />);
  await userEvent.click(trigger);
  await userEvent.click(screen.getByRole("menuitem", { name: "Rename" }));
  expect(select).toHaveBeenCalledOnce();
  expect(document.activeElement).toBe(trigger);
});
