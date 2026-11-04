import { createContext, useContext, useMemo, type ReactNode } from "react";
import en from "./en.json";

/**
 * A deliberately small i18n layer: every UI string is a key in a JSON catalog (en.json today; hi.json can be added
 * without touching components). Keys are dotted paths; `{{name}}` placeholders are replaced from `vars`.
 */
export interface Messages {
  [key: string]: string | Messages;
}

export const catalogs: Record<string, Messages> = { en };

/** BCP 47 tags used for number and date formatting per catalog. */
const formatLocales: Record<string, string> = { en: "en-IN" };

export function lookup(messages: Messages, key: string): string | undefined {
  let node: string | Messages | undefined = messages;
  for (const part of key.split(".")) {
    if (node === undefined || typeof node === "string") {
      return undefined;
    }
    node = node[part];
  }
  return typeof node === "string" ? node : undefined;
}

export type Vars = Record<string, string | number | null | undefined>;

export function translate(
  messages: Messages,
  key: string,
  vars?: Vars,
): string {
  const template = lookup(messages, key) ?? lookup(catalogs.en ?? {}, key);
  if (template === undefined) {
    if (import.meta.env.DEV) {
      console.warn(`Missing i18n key: ${key}`);
    }
    return key;
  }
  return template.replace(/\{\{(\w+)\}\}/g, (_, name: string) =>
    String(vars?.[name] ?? ""),
  );
}

export interface I18n {
  locale: string;
  t: (key: string, vars?: Vars) => string;
  number: (value: number | null | undefined) => string;
  dateTime: (value: string | null | undefined) => string;
  duration: (ms: number | null | undefined) => string;
}

export function createI18n(locale: string): I18n {
  const messages = catalogs[locale] ?? catalogs.en ?? {};
  const tag = formatLocales[locale] ?? "en-IN";
  const numberFormat = new Intl.NumberFormat(tag);
  const dateFormat = new Intl.DateTimeFormat(tag, {
    dateStyle: "medium",
    timeStyle: "medium",
  });
  const t = (key: string, vars?: Vars) => translate(messages, key, vars);
  return {
    locale,
    t,
    number: (value) =>
      value === null || value === undefined ? "—" : numberFormat.format(value),
    dateTime: (value) => (value ? dateFormat.format(new Date(value)) : "—"),
    duration: (ms) => {
      if (ms === null || ms === undefined) {
        return "—";
      }
      if (ms < 1000) {
        return t("common.durationMs", { value: ms });
      }
      const seconds = ms / 1000;
      if (seconds < 60) {
        return t("common.durationSeconds", { value: seconds.toFixed(1) });
      }
      return t("common.durationMinutes", {
        minutes: Math.floor(seconds / 60),
        seconds: Math.round(seconds % 60),
      });
    },
  };
}

const I18nContext = createContext<I18n>(createI18n("en"));

export function I18nProvider({
  locale = "en",
  children,
}: {
  locale?: string;
  children: ReactNode;
}) {
  const value = useMemo(() => createI18n(locale), [locale]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18n {
  return useContext(I18nContext);
}
