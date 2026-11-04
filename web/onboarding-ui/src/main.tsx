import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./shared/styles/global.css";
import { App } from "./app/App";
import { createAppRegistry } from "./app/features";

const root = document.getElementById("root");
if (!root) {
  throw new Error("Missing #root element");
}
createRoot(root).render(
  <StrictMode>
    <App registry={createAppRegistry()} />
  </StrictMode>,
);
