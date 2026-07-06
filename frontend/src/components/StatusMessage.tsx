import type { ReactNode } from "react";

export function StatusMessage({ kind, children }: { kind: "loading" | "error" | "info"; children: ReactNode }) {
  return <p className={`status status-${kind}`}>{children}</p>;
}
