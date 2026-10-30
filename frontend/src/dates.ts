import { CHANGE_MIN_NOTICE_HOURS, CLINIC_TIME_ZONE } from "./api/constants";

export function todayIsoDate(): string {
  const formatter = new Intl.DateTimeFormat("en-CA", {
    timeZone: CLINIC_TIME_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  });
  return formatter.format(new Date());
}

/**
 * Parses an ISO-8601 date-time string without timezone offset (e.g. "2026-10-23T14:00:00")
 * in the configured clinic time zone and returns epoch milliseconds.
 */
export function parseClinicDateTime(dateTimeStr: string, timeZone: string = CLINIC_TIME_ZONE): number {
  const [datePart, timePart = "00:00:00"] = dateTimeStr.split("T");
  const [year, month, day] = datePart.split("-").map(Number);
  const [hours, minutes, seconds = 0] = timePart.split(":").map(Number);
  const asUtc = new Date(Date.UTC(year, month - 1, day, hours, minutes, seconds));

  const formatter = new Intl.DateTimeFormat("en-US", {
    timeZone,
    year: "numeric",
    month: "numeric",
    day: "numeric",
    hour: "numeric",
    minute: "numeric",
    second: "numeric",
    hour12: false,
  });
  const parts = Object.fromEntries(formatter.formatToParts(asUtc).map((p) => [p.type, p.value]));
  const hourVal = parts.hour === "24" ? 0 : Number(parts.hour);
  const zonedDateAsUtc = new Date(
    Date.UTC(
      Number(parts.year),
      Number(parts.month) - 1,
      Number(parts.day),
      hourVal,
      Number(parts.minute),
      Number(parts.second),
    ),
  );
  const offsetMs = zonedDateAsUtc.getTime() - asUtc.getTime();
  return asUtc.getTime() - offsetMs;
}

export function isWithinChangeNotice(dateTime: string): boolean {
  const startsAt = parseClinicDateTime(dateTime);
  return startsAt - Date.now() < CHANGE_MIN_NOTICE_HOURS * 60 * 60 * 1000;
}

