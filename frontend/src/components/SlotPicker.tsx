import { useEffect, useState } from "react";
import { getAvailability } from "../api/availability";
import { ApiError } from "../api/client";
import { StatusMessage } from "./StatusMessage";

interface SlotPickerProps {
  doctorId: string;
  date: string;
  selectedSlot: string | null;
  onSelect: (slot: string) => void;
  // Changing this value loads the slots again, e.g. after a slot was taken in the meantime.
  reloadToken?: number;
}

export function SlotPicker({ doctorId, date, selectedSlot, onSelect, reloadToken = 0 }: SlotPickerProps) {
  const [slots, setSlots] = useState<string[]>([]);
  const [status, setStatus] = useState<"loading" | "success" | "error">("loading");
  const [error, setError] = useState("");

  useEffect(() => {
    let stale = false;
    setStatus("loading");
    getAvailability(doctorId, date)
      .then((response) => {
        if (!stale) {
          setSlots(response.availableSlots);
          setStatus("success");
        }
      })
      .catch((err) => {
        if (!stale) {
          setError(err instanceof ApiError ? err.message : "Could not load availability.");
          setStatus("error");
        }
      });
    return () => {
      stale = true;
    };
  }, [doctorId, date, reloadToken]);

  if (status === "loading") {
    return <StatusMessage kind="loading">Loading slots...</StatusMessage>;
  }
  if (status === "error") {
    return <StatusMessage kind="error">{error}</StatusMessage>;
  }
  if (slots.length === 0) {
    return <StatusMessage kind="info">No open slots for this day.</StatusMessage>;
  }
  return (
    <ul className="slot-grid">
      {slots.map((slot) => (
        <li key={slot}>
          <button
            type="button"
            className={selectedSlot === slot ? "slot selected" : "slot"}
            onClick={() => onSelect(slot)}
          >
            {slot.slice(11, 16)}
          </button>
        </li>
      ))}
    </ul>
  );
}
