import { useEffect, useState } from "react";
import { useI18n } from "../../i18n/i18n";
import { Button, Field, PageHeader, Panel, QueryState, Select, TextInput } from "../../shared/components";
import { useSession } from "../auth";
import {
  useProposeWordList,
  useWordLists,
  type WordGroup,
  type WordList,
} from "./api";
import { ChangeQueue } from "./ChangeQueue";
import { ParserTestPanel } from "./ParserTestPanel";

const lines = (values: string[]) => values.join("\n");
const fromLines = (text: string) =>
  text
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);

/** A group whose key is its own name (a flat list) cannot be added or removed; map and marker groups can. */
const isFlat = (group: WordGroup) => group.key === group.section;

function GroupEditor({
  list,
  disabled,
}: {
  list: WordList;
  disabled: boolean;
}) {
  const { t } = useI18n();
  // The text of each group is kept as typed (a new line must survive until something follows it).
  const rowsOf = (groups: WordGroup[]) => groups.map((group) => ({ group, text: lines(group.values) }));
  const [rows, setRows] = useState(() => rowsOf(list.groups));
  const [newKey, setNewKey] = useState("");
  const propose = useProposeWordList();
  useEffect(() => setRows(rowsOf(list.groups)), [list]);
  const groups: WordGroup[] = rows.map((row) => ({ ...row.group, values: fromLines(row.text) }));
  const changed = JSON.stringify(groups) !== JSON.stringify(list.groups);
  const update = (index: number, change: Partial<WordGroup>) =>
    setRows(rows.map((row, i) => (i === index ? { ...row, group: { ...row.group, ...change } } : row)));
  const setGroups = (next: WordGroup[]) => setRows(rowsOf(next));
  const sections = [...new Set(groups.map((g) => g.section))];
  const addable = sections.filter((section) => {
    const first = groups.find((g) => g.section === section);
    return first !== undefined && !isFlat(first);
  });

  const add = (section: string) => {
    const existing = groups.filter((g) => g.section === section);
    const marker = existing[0] !== undefined && "relation" in existing[0].attrs;
    const key = marker ? String(existing.length + 1) : newKey.trim();
    if (!key || (!marker && existing.some((g) => g.key === key))) {
      return;
    }
    setGroups([
      ...groups,
      { section, key, attrs: marker ? { relation: "", gender: "" } : {}, values: [] },
    ]);
    setNewKey("");
  };

  return (
    <>
      {list.description && <p className="muted">{list.description}</p>}
      {list.pendingChangeId && (
        <p role="status" data-testid="word-list-pending">
          {t("shaping.words.pending")}
        </p>
      )}
      {groups.map((group, index) => {
        const label = isFlat(group)
          ? group.section
          : group.attrs.relation !== undefined
            ? `${group.attrs.relation || t("shaping.words.newMarker")}${group.attrs.gender ? ` (${group.attrs.gender})` : ""}`
            : group.key;
        return (
          <div key={`${group.section}-${group.key}`} data-testid={`group-${group.section}-${group.key}`}>
            <Field id={`group-${index}`} label={label} hint={t("shaping.words.onePerLine")}>
              <textarea
                id={`group-${index}`}
                rows={Math.min(8, Math.max(2, group.values.length))}
                value={rows[index]?.text ?? ""}
                disabled={disabled}
                onChange={(e) =>
                  setRows(rows.map((row, i) => (i === index ? { ...row, text: e.target.value } : row)))
                }
                style={{ width: "100%" }}
              />
            </Field>
            {group.attrs.relation !== undefined && !disabled && (
              <div className="row">
                <TextInput
                  aria-label={t("shaping.words.relation")}
                  value={group.attrs.relation}
                  onChange={(e) => update(index, { attrs: { ...group.attrs, relation: e.target.value } })}
                />
                <TextInput
                  aria-label={t("shaping.words.gender")}
                  value={group.attrs.gender ?? ""}
                  onChange={(e) => update(index, { attrs: { ...group.attrs, gender: e.target.value } })}
                />
              </div>
            )}
            {!isFlat(group) && !disabled && (
              <Button
                variant="secondary"
                onClick={() => setGroups(groups.filter((_, i) => i !== index))}
                aria-label={t("shaping.words.removeGroup", { name: label })}
              >
                {t("shaping.words.removeGroupButton")}
              </Button>
            )}
          </div>
        );
      })}
      {!disabled &&
        addable.map((section) => (
          <div className="row" key={section}>
            {"relation" in (groups.find((g) => g.section === section)?.attrs ?? {}) ? null : (
              <TextInput
                aria-label={t("shaping.words.newGroupName")}
                value={newKey}
                onChange={(e) => setNewKey(e.target.value)}
              />
            )}
            <Button variant="secondary" onClick={() => add(section)}>
              {t("shaping.words.addGroup")}
            </Button>
          </div>
        ))}
      {propose.error && (
        <p role="alert" data-testid="propose-error">
          {propose.error.message}
        </p>
      )}
      {propose.isSuccess && <p role="status">{t("shaping.words.proposed")}</p>}
      {!disabled && (
        <Button
          onClick={() => propose.mutate({ id: list.id, groups })}
          disabled={!changed || propose.isPending}
        >
          {t("shaping.words.propose")}
        </Button>
      )}
    </>
  );
}

/** Reference data → Word lists: view and propose changes to the lists the parsers read; another user approves. */
export function WordListsPage() {
  const { t } = useI18n();
  const session = useSession();
  const lists = useWordLists();
  const [selected, setSelected] = useState("");
  return (
    <>
      <PageHeader title={t("shaping.words.title")} description={t("shaping.words.description")} />
      <QueryState query={lists} isEmpty={(rows) => rows.length === 0}>
        {(rows) => {
          const list = rows.find((l) => l.id === selected) ?? rows[0];
          if (!list) {
            return null;
          }
          return (
            <Panel title={t("shaping.words.lists")} id="word-lists">
              <Field id="word-list-select" label={t("shaping.words.list")}>
                <Select
                  id="word-list-select"
                  value={list.id}
                  onChange={(e) => setSelected(e.target.value)}
                  options={rows.map((l) => ({
                    value: l.id,
                    label: l.pendingChangeId ? `${l.id} (${t("shaping.words.waiting")})` : l.id,
                  }))}
                />
              </Field>
              <GroupEditor key={list.id} list={list} disabled={!session.can("SHAPING_MANAGE") || !!list.pendingChangeId} />
            </Panel>
          );
        }}
      </QueryState>
      <ChangeQueue kind="WORD_LIST" />
      <ParserTestPanel id="word-test" />
    </>
  );
}
