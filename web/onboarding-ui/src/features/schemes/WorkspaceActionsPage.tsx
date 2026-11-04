import { SchemeWorkspacePage } from "./index";

/**
 * The scheme version route. The workspace carries its own maker-checker actions (edit, submit, approve or reject,
 * create a new version), so this only keeps the route's component name stable.
 */
export function SchemeWorkspaceActionsPage() {
  return <SchemeWorkspacePage />;
}
