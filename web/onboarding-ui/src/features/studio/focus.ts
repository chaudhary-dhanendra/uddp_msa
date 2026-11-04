import { useEffect } from "react";
import { useInRouterContext, useLocation } from "react-router-dom";

/**
 * The pack part a "Fix in pack" link points at (`?focus=` on a wizard step): a rule id, a source column or a crosswalk
 * name. Rows mark themselves with `data-focused`; the step scrolls the matching row into view once it is rendered.
 */
export function useFocusKey(): string | null {
  // A step can be rendered outside a router (component tests); it then has nothing to focus. The router context never
  // changes for a mounted component, so the hook order is stable.
  if (!useInRouterContext()) {
    return null;
  }
  // eslint-disable-next-line react-hooks/rules-of-hooks
  return new URLSearchParams(useLocation().search).get("focus");
}

/** Whether a row with one of these keys is the focused one. */
export function isFocused(focus: string | null, ...keys: (string | null | undefined)[]): boolean {
  return focus != null && focus !== "" && keys.some((k) => k != null && k === focus);
}

/** Scrolls the first element marked `data-focused="true"` into view and moves keyboard focus to it. */
export function useScrollToFocused(focus: string | null, ready: boolean) {
  useEffect(() => {
    if (!focus || !ready) {
      return;
    }
    const element = document.querySelector<HTMLElement>('[data-focused="true"]');
    if (element) {
      element.scrollIntoView?.({ block: "center" });
      element.focus({ preventScroll: true });
    }
  }, [focus, ready]);
}
