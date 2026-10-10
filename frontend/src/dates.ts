import { CHANGE_MIN_NOTICE_HOURS } from "./api/constants";

export function todayIsoDate(): string {
  return new Date().toISOString().slice(0, 10);
}

// dateTime is the server's local date-time without an offset, which the Date constructor reads as local time.
export function isWithinChangeNotice(dateTime: string): boolean {
  const startsAt = new Date(dateTime).getTime();
  return startsAt - Date.now() < CHANGE_MIN_NOTICE_HOURS * 60 * 60 * 1000;
}
