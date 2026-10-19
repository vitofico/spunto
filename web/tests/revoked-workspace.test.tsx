import { cleanup, renderHook, waitFor } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { expect, it, vi } from "vitest";
import { api } from "../src/lib/api";
import { ApiError } from "../src/lib/errors";
import { currentSession, queryClient, useAuth } from "../src/lib/query";

it.each([true, false])(
  "clears revoked space content when personal access is %s",
  async (personalAvailable) => {
    const session = {
      owner_id: "alice",
      user_id: "alice",
      credential_id: "browser",
      workspace_id: "team",
      workspace_role: "viewer" as const,
    };
    const personal = {
      ...session,
      workspace_id: "personal",
      workspace_role: "owner" as const,
    };
    vi.spyOn(api, "meta").mockResolvedValue({ setup_pending: false } as Awaited<
      ReturnType<typeof api.meta>
    >);
    const read = vi.spyOn(api, "session").mockResolvedValue(session);
    const fallback = vi.spyOn(api, "personalSession");
    if (personalAvailable) fallback.mockResolvedValue(personal);
    else fallback.mockRejectedValue(new ApiError(404, "workspace_unavailable"));
    function Wrapper({ children }: { children: ReactNode }) {
      return (
        <QueryClientProvider client={queryClient}>
          {children}
        </QueryClientProvider>
      );
    }
    try {
      const { result } = renderHook(useAuth, { wrapper: Wrapper });
      await waitFor(() => expect(result.current.status).toBe("authenticated"));
      await waitFor(() => expect(currentSession()?.workspace_id).toBe("team"));
      queryClient.setQueryData(["note", "same-id"], {
        body: "Team cached content",
      });
      read.mockRejectedValue(new ApiError(404, "workspace_unavailable"));
      await queryClient.invalidateQueries({ queryKey: ["session"] });
      await waitFor(() => expect(fallback).toHaveBeenCalledOnce());
      if (personalAvailable) {
        await waitFor(() =>
          expect(currentSession()?.workspace_id).toBe("personal"),
        );
      } else {
        await waitFor(() => expect(result.current.status).toBe("anonymous"));
        expect(currentSession()).toBeNull();
      }
      expect(queryClient.getQueryData(["note", "same-id"])).toBeUndefined();
    } finally {
      cleanup();
      queryClient.clear();
      vi.restoreAllMocks();
    }
  },
);
