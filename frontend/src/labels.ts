import { APPOINTMENT_TYPES } from "./api/constants";
import type { AppointmentStatus, AppointmentType, ConflictReason } from "./api/types";

export function statusLabel(status: AppointmentStatus): string {
  switch (status) {
    case "PENDING":
      return "Awaiting confirmation";
    case "CONFIRMED":
      return "Confirmed";
    case "CANCELLED":
      return "Cancelled";
  }
}

export function typeLabel(type: AppointmentType): string {
  return APPOINTMENT_TYPES.find((t) => t.value === type)?.label ?? type;
}

// What the doctor sees about a flagged appointment.
export function conflictLabelForDoctor(reason: ConflictReason): string {
  return reason === "DOCTOR_VACATION"
    ? "On one of your vacation days. The patient was asked to reschedule or cancel."
    : "Outside your opening hours. The patient was asked to reschedule or cancel.";
}
