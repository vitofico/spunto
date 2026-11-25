import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { QueryClientProvider } from "@tanstack/react-query";
import { queryClient } from "./lib";
import App from "./App";
import { ToastProvider } from "./components/core/Toast";
import { followBackgroundStyle } from "./lib/background-style";
import { followNotesLayout } from "./lib/notes-layout";
import { followThemeColor } from "./lib/theme-color";
import "./styles/global.css";
followBackgroundStyle();
followNotesLayout();
followThemeColor();
createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <App />
      </ToastProvider>
    </QueryClientProvider>
  </StrictMode>,
);
