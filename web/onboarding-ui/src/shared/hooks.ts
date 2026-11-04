import { useCallback } from "react";
import { useSearchParams } from "react-router-dom";

/**
 * List state (filters and page) kept in the URL query string, so a filtered page can be bookmarked, shared and
 * restored with the back button. Values are strings; an empty value removes the parameter. Changing a filter resets
 * the page to 0.
 */
export function useListParams() {
  const [params, setParams] = useSearchParams();
  const get = useCallback((name: string) => params.get(name) ?? "", [params]);
  const page = Number.parseInt(params.get("page") ?? "0", 10) || 0;
  const set = useCallback(
    (values: Record<string, string | number | undefined>, keepPage = false) => {
      setParams((current) => {
        const next = new URLSearchParams(current);
        for (const [name, value] of Object.entries(values)) {
          if (value === undefined || value === "") {
            next.delete(name);
          } else {
            next.set(name, String(value));
          }
        }
        if (!keepPage && !("page" in values)) {
          next.delete("page");
        }
        return next;
      });
    },
    [setParams],
  );
  const setPage = useCallback(
    (value: number) => set({ page: value > 0 ? value : undefined }, true),
    [set],
  );
  return { get, set, page, setPage };
}

/** An optional query parameter: undefined when blank, so it is left out of the request. */
export function optional(value: string): string | undefined {
  const trimmed = value.trim();
  return trimmed === "" ? undefined : trimmed;
}
