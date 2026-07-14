import type { AppointmentType, DayOfWeek, Language } from "./types";

export const LANGUAGES: Language[] = [
  "GERMAN",
  "ENGLISH",
  "SPANISH",
  "FRENCH",
  "TURKISH",
  "ITALIAN",
  "ARABIC",
  "RUSSIAN",
  "PERSIAN",
];

export const DAYS_OF_WEEK: DayOfWeek[] = [
  "MONDAY",
  "TUESDAY",
  "WEDNESDAY",
  "THURSDAY",
  "FRIDAY",
  "SATURDAY",
  "SUNDAY",
];

// Appointment slots are fixed at 30 minutes, so opening hours only ever need to land on the hour or half-hour.
export const HALF_HOUR_TIMES: string[] = Array.from({ length: 48 }, (_, i) => {
  const hours = String(Math.floor(i / 2)).padStart(2, "0");
  const minutes = i % 2 === 0 ? "00" : "30";
  return `${hours}:${minutes}`;
});

export const APPOINTMENT_TYPES: { value: AppointmentType; label: string }[] = [
  { value: "INITIAL_CONSULTATION", label: "Initial consultation" },
  { value: "FOLLOW_UP", label: "Follow up" },
  { value: "VACCINATION", label: "Vaccination" },
];
