import { useState } from "react";
import { useI18n } from "../../i18n/i18n";
import { Button, Field, Panel, Select, TextInput } from "../../shared/components";
import { useBuiltIns, usePlugins, useTestValue } from "./api";

/**
 * Every parser that can be tested now: the built-in field parsers (Tier 2), then what loaded Tier-3 plug-ins provide.
 * A plug-in that provides a layout operation, not a parser, is listed too; the server refuses it with its reason.
 */
export function useParserKeys(): string[] {
  const builtIns = useBuiltIns().data;
  const plugins = usePlugins().data;
  const builtIn = (Array.isArray(builtIns) ? builtIns : []).filter((b) => b.kind === "parser").map((b) => b.key);
  const plugin = (Array.isArray(plugins) ? plugins : []).filter((p) => p.status === "LOADED").flatMap((p) => p.provides);
  return [...new Set([...builtIn, ...plugin])];
}

/** A parser output value as text: nested values (lists, objects) as JSON. */
export function outputText(value: unknown): string {
  if (value === null || value === undefined) {
    return "";
  }
  return typeof value === "object" ? JSON.stringify(value) : String(value);
}

/**
 * Pick a parser, enter one value, see the structured result (output keys, confidence, warnings). Runs the parser
 * exactly as a source's parse: step would, with the word lists as they are now. Used on Shaping plug-ins and Word
 * lists. The parser can be chosen by the page (a Test button on a row) through {@code parser}/{@code onParserChange}.
 */
export function ParserTestPanel({
  id = "parser-test",
  parser,
  onParserChange,
}: {
  id?: string;
  parser?: string;
  onParserChange?: (key: string) => void;
}) {
  const { t } = useI18n();
  const parsers = useParserKeys();
  const [own, setOwn] = useState("");
  const [value, setValue] = useState("");
  const [options, setOptions] = useState("");
  const [optionsError, setOptionsError] = useState(false);
  const test = useTestValue();
  const chosen = parser ?? own;
  const selected = chosen && parsers.includes(chosen) ? chosen : chosen || parsers[0] || "";
  const choose = (key: string) => (onParserChange ? onParserChange(key) : setOwn(key));

  const run = () => {
    let config: Record<string, unknown> | undefined;
    if (options.trim()) {
      try {
        config = JSON.parse(options) as Record<string, unknown>;
      } catch {
        setOptionsError(true);
        return;
      }
    }
    setOptionsError(false);
    test.mutate(config ? { parser: selected, value, config } : { parser: selected, value });
  };

  return (
    <Panel title={t("shaping.words.testTitle")} id={id}>
      <p className="muted">{t("shaping.words.testIntro")}</p>
      <div className="row">
        <Field id={`${id}-parser`} label={t("shaping.words.parser")}>
          <Select
            id={`${id}-parser`}
            value={selected}
            onChange={(e) => choose(e.target.value)}
            options={parsers.map((key) => ({ value: key, label: key }))}
          />
        </Field>
        <Field id={`${id}-value`} label={t("shaping.words.testValue")}>
          <TextInput
            id={`${id}-value`}
            value={value}
            onChange={(e) => setValue(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && selected && value) {
                run();
              }
            }}
          />
        </Field>
        <Field id={`${id}-options`} label={t("shaping.words.testOptions")} hint={t("shaping.words.testOptionsHint")}>
          <TextInput id={`${id}-options`} value={options} onChange={(e) => setOptions(e.target.value)} />
        </Field>
        <Button onClick={run} disabled={!selected || !value || test.isPending}>
          {t("shaping.words.test")}
        </Button>
      </div>
      {optionsError && (
        <p role="alert" data-testid="test-options-error">
          {t("shaping.words.testOptionsInvalid")}
        </p>
      )}
      {test.error && (
        <p role="alert" data-testid="test-error">
          {test.error.message}
        </p>
      )}
      {test.data && (
        <div data-testid="test-result">
          <p>
            <strong>{test.data.parser}</strong> · {t("shaping.words.confidence", { value: test.data.confidence })}
          </p>
          {Object.keys(test.data.values).length === 0 ? (
            <p>{t("shaping.words.noOutput")}</p>
          ) : (
            <table>
              <caption>{t("shaping.words.outputs")}</caption>
              <tbody>
                {Object.entries(test.data.values).map(([key, v]) => (
                  <tr key={key}>
                    <th scope="row">
                      <code>{key}</code>
                    </th>
                    <td>{outputText(v)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {test.data.warnings.length > 0 && (
            <ul data-testid="test-warnings">
              {test.data.warnings.map((w) => (
                <li key={w}>{w}</li>
              ))}
            </ul>
          )}
        </div>
      )}
    </Panel>
  );
}
