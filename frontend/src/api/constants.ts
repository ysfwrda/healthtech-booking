import type { AppointmentType, Language } from "./types";

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

export const APPOINTMENT_TYPES: { value: AppointmentType; label: string }[] = [
  { value: "INITIAL_CONSULTATION", label: "Initial consultation" },
  { value: "FOLLOW_UP", label: "Follow up" },
  { value: "VACCINATION", label: "Vaccination" },
];
