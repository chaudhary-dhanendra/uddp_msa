import { describe, expect, it } from "vitest";
import type { DepartmentWithSources } from "../../api/types";
import { departmentLabel, departmentsByPack } from "./index";

const departments: DepartmentWithSources[] = [
  {
    departmentCode: "DEPT-A",
    name: "Department A",
    status: "ACTIVE",
    sourcePacks: [
      {
        departmentCode: "DEPT-A",
        packId: "source-a1",
        sourceCode: "SA1",
        sourceIds: ["file"],
        status: "ACTIVE",
        version: "1.0.0",
      },
      {
        departmentCode: "DEPT-A",
        packId: "source-a2",
        sourceCode: "SA2",
        sourceIds: ["table"],
        status: "ACTIVE",
        version: "1.0.0",
      },
    ],
  },
  {
    departmentCode: "DEPT-B",
    name: null,
    status: "ACTIVE",
    sourcePacks: [],
  },
];

describe("departmentsByPack", () => {
  it("indexes every Source Pack by its pack ID", () => {
    const byPack = departmentsByPack(departments);

    expect(byPack.get("source-a1")).toEqual({
      code: "DEPT-A",
      name: "Department A",
    });
    expect(byPack.get("source-a2")?.code).toBe("DEPT-A");
    expect(byPack.size).toBe(2);
  });
});

describe("departmentLabel", () => {
  it("shows the name and code, or the fallback for an unassigned pack", () => {
    const byPack = departmentsByPack(departments);

    expect(departmentLabel(byPack.get("source-a1"), "—")).toBe(
      "Department A (DEPT-A)",
    );
    expect(departmentLabel(byPack.get("unknown"), "No department")).toBe(
      "No department",
    );
  });
});
