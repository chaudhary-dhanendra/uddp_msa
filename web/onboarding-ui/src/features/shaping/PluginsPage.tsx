import { useState } from "react";
import { useI18n } from "../../i18n/i18n";
import { Button, DataTable, PageHeader, Panel, QueryState, StatusBadge, type Column } from "../../shared/components";
import { useSession } from "../auth";
import { useBuiltIns, usePlugins, useProposePlugin, type BuiltIn, type PluginInfo } from "./api";
import { ChangeQueue } from "./ChangeQueue";
import { ParserTestPanel } from "./ParserTestPanel";

/**
 * Platform settings → Shaping plug-ins: a form to test any parser on a value, built-in operations and parsers, the
 * Tier-3 jars found at startup and their approval. A Test button on a parser row picks it in the form.
 */
export function ShapingPluginsPage() {
  const { t } = useI18n();
  const session = useSession();
  const builtIns = useBuiltIns();
  const plugins = usePlugins();
  const propose = useProposePlugin();
  const canManage = session.can("SHAPING_MANAGE");
  const [testParser, setTestParser] = useState<string | undefined>(undefined);
  const testIt = (key: string) => {
    setTestParser(key);
    document.getElementById("plugin-test")?.scrollIntoView?.({ behavior: "smooth", block: "start" });
  };
  const testButton = (key: string) => (
    <Button
      key={key}
      variant="secondary"
      onClick={() => testIt(key)}
      aria-label={t("shaping.plugins.testFor", { name: key })}
    >
      {t("shaping.plugins.test")}
    </Button>
  );

  const builtInColumns: Column<BuiltIn>[] = [
    { key: "kind", header: t("shaping.plugins.kind"), render: (b) => t(`shaping.plugins.kinds.${b.kind}`) },
    { key: "name", header: t("shaping.plugins.name"), render: (b) => b.name },
    { key: "version", header: t("shaping.plugins.version"), render: (b) => b.version },
    { key: "test", header: t("shaping.plugins.actions"), render: (b) => (b.kind === "parser" ? testButton(b.key) : null) },
  ];

  const pluginColumns: Column<PluginInfo>[] = [
    { key: "file", header: t("shaping.plugins.file"), render: (p) => p.fileName ?? "—" },
    {
      key: "plugin", header: t("shaping.plugins.plugin"),
      render: (p) => (p.name ? `${p.name}${p.version ? ` ${p.version}` : ""}` : "—"),
    },
    { key: "status", header: t("shaping.plugins.status"), render: (p) => <StatusBadge status={p.status} /> },
    { key: "reason", header: t("shaping.plugins.reason"), render: (p) => p.reason ?? "" },
    {
      key: "hash", header: t("shaping.plugins.hash"),
      render: (p) => (
        <code title={p.sha256}>{p.sha256.slice(0, 12)}</code>
      ),
    },
    {
      key: "provides", header: t("shaping.plugins.provides"),
      render: (p) =>
        p.provides.length === 0 ? (
          "—"
        ) : (
          <>
            {p.provides.join(", ")}
            {p.status === "LOADED" && <div>{p.provides.map(testButton)}</div>}
          </>
        ),
    },
    {
      key: "packs", header: t("shaping.plugins.packs"),
      render: (p) => (p.packs.length ? p.packs.join(", ") : "—"),
    },
    {
      key: "approved", header: t("shaping.plugins.approved"),
      render: (p) => (p.approved ? t("shaping.plugins.yes") : t("shaping.plugins.no")),
    },
    {
      key: "actions", header: t("shaping.plugins.actions"),
      render: (p) => {
        if (p.pendingChangeId) {
          return <span>{t("shaping.plugins.waiting", { action: p.pendingAction ?? "" })}</span>;
        }
        if (!canManage) {
          return null;
        }
        return p.approved ? (
          <Button
            variant="secondary"
            onClick={() => propose.mutate({ sha256: p.sha256, action: "REVOKE" })}
            aria-label={t("shaping.plugins.revokeFor", { name: p.fileName ?? p.sha256.slice(0, 12) })}
          >
            {t("shaping.plugins.revoke")}
          </Button>
        ) : p.status === "NOT_FOUND" ? null : (
          <Button
            onClick={() => propose.mutate({ sha256: p.sha256, action: "APPROVE" })}
            aria-label={t("shaping.plugins.approveFor", { name: p.fileName ?? p.sha256.slice(0, 12) })}
          >
            {t("shaping.plugins.approve")}
          </Button>
        );
      },
    },
  ];

  return (
    <>
      <PageHeader title={t("shaping.plugins.title")} description={t("shaping.plugins.description")} />
      <ParserTestPanel id="plugin-test" parser={testParser} onParserChange={setTestParser} />
      <Panel title={t("shaping.plugins.builtIn")} id="built-in">
        <QueryState query={builtIns} isEmpty={(rows) => rows.length === 0}>
          {(rows) => <DataTable caption={t("shaping.plugins.builtIn")} columns={builtInColumns} rows={rows} rowKey={(b) => b.key + b.kind} />}
        </QueryState>
      </Panel>
      <Panel title={t("shaping.plugins.tier3")} id="tier3">
        <p className="muted">{t("shaping.plugins.restart")}</p>
        {propose.error && (
          <p role="alert" data-testid="plugin-error">
            {propose.error.message}
          </p>
        )}
        <QueryState query={plugins} emptyText={t("shaping.plugins.none")} isEmpty={(rows) => rows.length === 0}>
          {(rows) => <DataTable caption={t("shaping.plugins.tier3")} columns={pluginColumns} rows={rows} rowKey={(p) => p.sha256} testId="plugin-table" />}
        </QueryState>
      </Panel>
      <ChangeQueue kind="PLUGIN" />
    </>
  );
}
