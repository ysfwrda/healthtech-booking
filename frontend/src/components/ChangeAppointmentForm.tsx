import { useState } from "react";
import { updateAppointment } from "../api/appointments";
import { ApiError } from "../api/client";
import { APPOINTMENT_TYPES } from "../api/constants";
import { todayIsoDate } from "../dates";
import { SlotPicker } from "./SlotPicker";
import { StatusMessage } from "./StatusMessage";
import type { AppointmentResponse, AppointmentType, AppointmentUpdateRequest } from "../api/types";

interface ChangeAppointmentFormProps {
  appointment: AppointmentResponse;
  onSaved: () => void;
  onClose: () => void;
}

export function ChangeAppointmentForm({ appointment, onSaved, onClose }: ChangeAppointmentFormProps) {
  const [type, setType] = useState<AppointmentType>(appointment.type);
  const [notes, setNotes] = useState(appointment.notes ?? "");
  const [date, setDate] = useState(appointment.dateTime.slice(0, 10));
  const [selectedSlot, setSelectedSlot] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  // Only the fields that differ from the appointment are sent.
  const update: AppointmentUpdateRequest = {};
  if (type !== appointment.type) {
    update.type = type;
  }
  if (notes !== (appointment.notes ?? "")) {
    update.notes = notes;
  }
  if (selectedSlot) {
    update.dateTime = selectedSlot;
  }
  const hasChanges = Object.keys(update).length > 0;

  function changeDate(newDate: string) {
    setDate(newDate);
    setSelectedSlot(null);
  }

  async function handleSave() {
    setSaving(true);
    setError("");
    try {
      await updateAppointment(appointment.id, update);
      onSaved();
    } catch (err) {
      if (err instanceof ApiError && err.title === "Slot Already Booked") {
        setError("That slot was just taken. Pick another one.");
        setSelectedSlot(null);
        setReloadToken((token) => token + 1);
      } else {
        setError(err instanceof ApiError ? err.message : "Could not change this appointment.");
      }
      setSaving(false);
    }
  }

  return (
    <div className="change-form">
      <h2>Change appointment</h2>
      <label>
        Appointment type
        <select value={type} onChange={(e) => setType(e.target.value as AppointmentType)}>
          {APPOINTMENT_TYPES.map((t) => (
            <option key={t.value} value={t.value}>
              {t.label}
            </option>
          ))}
        </select>
      </label>
      <label>
        Notes (optional)
        <textarea value={notes} maxLength={500} onChange={(e) => setNotes(e.target.value)} />
      </label>
      <label>
        New date (leave the time unselected to keep the current one)
        <input type="date" value={date} min={todayIsoDate()} onChange={(e) => changeDate(e.target.value)} />
      </label>
      <SlotPicker
        doctorId={appointment.doctorId}
        date={date}
        selectedSlot={selectedSlot}
        onSelect={setSelectedSlot}
        reloadToken={reloadToken}
      />
      {error && <StatusMessage kind="error">{error}</StatusMessage>}
      <div className="change-form-actions">
        <button type="button" onClick={handleSave} disabled={!hasChanges || saving}>
          {saving ? "Saving..." : "Save changes"}
        </button>
        <button type="button" onClick={onClose} disabled={saving}>
          Close
        </button>
      </div>
    </div>
  );
}
