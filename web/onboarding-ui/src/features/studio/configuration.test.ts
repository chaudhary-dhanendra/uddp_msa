import { describe, expect, it } from "vitest";
import type {
  MappingTarget,
  SourceProfile,
  StudioDraft,
  StudioSuggestions,
} from "../../api/types";
import {
  crosswalksFor,
  currentRecordKey,
  initialConfiguration,
  keyOf,
  missingValues,
  ruleFor,
  stateFieldKey,
  stateFieldRule,
  withKey,
} from "./configuration";

const gender: MappingTarget = {
  entity: "Resident",
  target: "gender",
  label: "Gender",
  type: "code",
  codeSet: "gender",
  crosswalkId: "gender",
};
const dob: MappingTarget = {
  entity: "Resident",
  target: "dateOfBirth",
  label: "Date of birth",
  type: "date",
  codeSet: null,
  crosswalkId: null,
};

const profile: SourceProfile = {
  rowCount: 2,
  rowsSampled: 2,
  rowsTotal: 2,
  sheets: null,
  sampleRows: [],
  columns: [
    {
      name: "sex",
      inferredType: "text",
      dateFormat: null,
      blanks: 0,
      distinct: 2,
      unique: true,
      topValues: [
        { value: "1", count: 1 },
        { value: "2", count: 1 },
      ],
    },
    {
      name: "born",
      inferredType: "date",
      dateFormat: "YYYY/MM/DD",
      blanks: 0,
      distinct: 2,
      unique: true,
      topValues: [],
    },
  ],
};

const suggestions: StudioSuggestions = {
  columns: [
    {
      column: "sex",
      targets: [
        {
          entity: "Resident",
          target: "gender",
          conversion: null,
          crosswalk: "gender",
          confidence: 0.7,
          basis: "values",
          accepted: true,
        },
        {
          entity: "Resident",
          target: "extensions.sex",
          conversion: null,
          crosswalk: null,
          confidence: 0.5,
          basis: "weak signal",
          accepted: false,
        },
      ],
    },
  ],
  crosswalks: [
    {
      id: "gender",
      codeSet: "gender",
      entries: [
        {
          sourceValue: "1",
          targetValue: "MALE",
          basis: "published",
          confidence: 0.9,
          accepted: true,
        },
        {
          sourceValue: "2",
          targetValue: null,
          basis: "no match",
          confidence: 0,
          accepted: false,
        },
      ],
    },
  ],
  recordIdField: "sex",
  recordKeyFields: [],
  layoutProposals: [],
};

const draft = {
  mappings: [],
  crosswalks: [],
  rules: null,
} as unknown as StudioDraft;

describe("Studio configuration", () => {
  it("starts from the pre-accepted suggestions only", () => {
    const configuration = initialConfiguration(draft, suggestions);
    expect(configuration.mappings).toEqual([
      {
        sourceField: "sex",
        entity: "Resident",
        target: "gender",
        conversion: null,
        crosswalk: "gender",
        required: null,
        stateField: null,
      },
    ]);
    expect(configuration.crosswalks).toBe(suggestions.crosswalks);
    expect(missingValues(configuration.crosswalks)).toBe(1);
  });

  it("keeps a column as a typed state field carried to Golden", () => {
    expect(stateFieldKey("disability_pct")).toBe("disabilityPct");
    expect(stateFieldKey("2nd Phone")).toBe("f2ndPhone");
    expect(stateFieldKey("uddp_flag")).toBe("fuddpFlag");
    const rule = stateFieldRule({
      name: "disability_pct",
      topValues: [{ value: "55" }, { value: "70.5" }],
    } as unknown as Parameters<typeof stateFieldRule>[0]);
    expect(rule).toEqual({
      sourceField: "disability_pct",
      entity: "Resident",
      target: "extensions.disabilityPct",
      conversion: null,
      crosswalk: null,
      required: null,
      stateField: { label: "disability_pct", type: "number", golden: true },
    });
    expect(keyOf(withKey(rule, "pct"))).toBe("pct");
  });

  it("derives a date conversion and a crosswalk from the chosen target", () => {
    expect(ruleFor(profile.columns[1], dob).conversion).toBe("date:YYYY/MM/DD");
    expect(ruleFor(profile.columns[0], gender).crosswalk).toBe("gender");
  });

  it("keeps suggested crosswalks and creates blank rows for a newly coded column", () => {
    const rules = [ruleFor(profile.columns[0], gender)];
    expect(crosswalksFor(rules, [], suggestions, profile, [gender])[0]).toBe(
      suggestions.crosswalks[0],
    );
    const blank = crosswalksFor(rules, [], undefined, profile, [gender]);
    expect(
      blank[0]?.entries.map((e) => [e.sourceValue, e.targetValue]),
    ).toEqual([
      ["1", null],
      ["2", null],
    ]);
  });
});

describe("currentRecordKey", () => {
  const columns = ["OWNER_NAME", "DOB", "VILLAGE_LGD"];

  it("keeps a record ID and keys that are current columns", () => {
    expect(currentRecordKey("OWNER_NAME", ["OWNER_NAME", "DOB"], columns)).toEqual({
      recordIdField: "OWNER_NAME",
      recordKeyFields: ["OWNER_NAME", "DOB"],
      dropped: [],
    });
  });

  it("drops a saved record ID and key whose header was renamed", () => {
    expect(
      currentRecordKey(
        "Name of Owner",
        ["Name of Owner", "OWNER_NAME", "DOB", null, "VILLAGE_LGD"],
        columns,
      ),
    ).toEqual({
      recordIdField: "",
      recordKeyFields: ["OWNER_NAME", "DOB", "VILLAGE_LGD"],
      dropped: ["Name of Owner"],
    });
  });

  it("treats a missing choice as none", () => {
    expect(currentRecordKey(null, null, columns)).toEqual({
      recordIdField: "",
      recordKeyFields: [],
      dropped: [],
    });
  });
});
