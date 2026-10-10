import { useEffect, useId, useRef } from "react";
import type { ReactNode } from "react";

interface NoticeDialogProps {
  title: string;
  children: ReactNode;
  onClose: () => void;
}

// A modal pop-up built on the native <dialog>: focus is trapped while it is open and Escape closes it.
export function NoticeDialog({ title, children, onClose }: NoticeDialogProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const titleId = useId();

  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog && !dialog.open) {
      dialog.showModal();
    }
  }, []);

  return (
    <dialog ref={dialogRef} className="notice-dialog" aria-labelledby={titleId} onClose={onClose}>
      <h2 id={titleId}>{title}</h2>
      <div>{children}</div>
      <button type="button" autoFocus onClick={() => dialogRef.current?.close()}>
        OK
      </button>
    </dialog>
  );
}
