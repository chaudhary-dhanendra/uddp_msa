import { useQuery } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import type { DepartmentWithSources } from "../../api/types";

/** The department that owns one Source Pack. */
export interface PackDepartment {
  code: string;
  name: string;
}

/** Pack ID -> owning department, from the departments list (a pack is listed under at most one department). */
export function departmentsByPack(
  departments: DepartmentWithSources[],
): Map<string, PackDepartment> {
  const byPack = new Map<string, PackDepartment>();
  for (const department of departments) {
    const code = department.departmentCode ?? "";
    const owner: PackDepartment = {
      code,
      name: department.name ?? code,
    };
    for (const pack of department.sourcePacks) {
      if (pack.packId) {
        byPack.set(pack.packId, owner);
      }
    }
  }
  return byPack;
}

/** Departments with their Source Packs, indexed by pack ID; empty while loading or when not permitted. */
export function usePackDepartments(): Map<string, PackDepartment> {
  const query = useQuery({
    queryKey: ["departments"],
    queryFn: () => unwrap(api.GET("/api/v1/departments")),
  });
  return departmentsByPack(query.data ?? []);
}

/** "Name (CODE)" for a pack's department, or the fallback when it has none. */
export function departmentLabel(
  department: PackDepartment | undefined,
  fallback: string,
): string {
  if (!department) {
    return fallback;
  }
  return `${department.name} (${department.code})`;
}
