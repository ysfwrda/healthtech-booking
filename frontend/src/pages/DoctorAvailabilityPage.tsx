import { useEffect, useState, type FormEvent } from "react";
import { addVacation, getMyAvailability, removeVacation, replaceOpeningHours } from "../api/doctorAvailability";
import { ApiError } from "../api/client";
import { DAYS_OF_WEEK, HALF_HOUR_TIMES } from "../api/constants";
import { StatusMessage } from "../components/StatusMessage";
import { todayIsoDate } from "../dates";
import type { DayOfWeek, OpeningHoursDto, VacationDto } from "../api/types";

const AFFECTED_NOTICE =
  "Saved. If booked appointments no longer fit, they are flagged on the Appointments page and their patients are asked to reschedule or cancel.";

// The first problem with the blocks, or null. The server checks the same rules; this saves the round trip.
function hoursProblem(hours: OpeningHoursDto[]): string | null {
  for (const row of hours) {
    if (row.startTime >= row.endTime) {
      return `${row.dayOfWeek}: a block must end after it starts.`;
    }
  }
  for (let i = 0; i < hours.length; i++) {
    for (let j = i + 1; j < hours.length; j++) {
      const a = hours[i];
      const b = hours[j];
      if (a.dayOfWeek === b.dayOfWeek && a.startTime < b.endTime && b.startTime < a.endTime) {
        return `${a.dayOfWeek}: two blocks overlap.`;
      }
    }
  }
  return null;
}

function sortedHours(hours: OpeningHoursDto[]): OpeningHoursDto[] {
  return [...hours]
    .map((h) => ({ dayOfWeek: h.dayOfWeek, startTime: h.startTime.slice(0, 5), endTime: h.endTime.slice(0, 5) }))
    .sort(
      (a, b) =>
        DAYS_OF_WEEK.indexOf(a.dayOfWeek) - DAYS_OF_WEEK.indexOf(b.dayOfWeek) || a.startTime.localeCompare(b.startTime),
    );
}

function describeError(err: unknown, fallback: string): string {
  if (!(err instanceof ApiError)) {
    return fallback;
  }
  const fieldMessages = err.errors ? Object.values(err.errors) : [];
  return fieldMessages.length > 0 ? `${err.message}: ${fieldMessages.join("; ")}` : err.message;
}

export function DoctorAvailabilityPage() {
  const [hours, setHours] = useState<OpeningHoursDto[]>([]);
  const [vacations, setVacations] = useState<VacationDto[]>([]);
  const [status, setStatus] = useState<"loading" | "success" | "error">("loading");
  const [loadError, setLoadError] = useState("");

  const [savingHours, setSavingHours] = useState(false);
  const [hoursError, setHoursError] = useState("");
  const [hoursNotice, setHoursNotice] = useState("");

  const [startDate, setStartDate] = useState(todayIsoDate());
  const [endDate, setEndDate] = useState(todayIsoDate());
  const [vacationBusy, setVacationBusy] = useState(false);
  const [vacationError, setVacationError] = useState("");
  const [vacationNotice, setVacationNotice] = useState("");

  useEffect(() => {
    getMyAvailability()
      .then((availability) => {
        setHours(sortedHours(availability.openingHours));
        setVacations(availability.vacations);
        setStatus("success");
      })
      .catch((err) => {
        setLoadError(describeError(err, "Could not load your availability."));
        setStatus("error");
      });
  }, []);

  function updateRow(index: number, change: Partial<OpeningHoursDto>) {
    setHours((current) => current.map((row, i) => (i === index ? { ...row, ...change } : row)));
    setHoursNotice("");
  }

  function addRow() {
    setHours((current) => [...current, { dayOfWeek: "MONDAY", startTime: "09:00", endTime: "17:00" }]);
    setHoursNotice("");
  }

  function removeRow(index: number) {
    setHours((current) => current.filter((_, i) => i !== index));
    setHoursNotice("");
  }

  async function handleSaveHours(event: FormEvent) {
    event.preventDefault();
    setHoursError("");
    setHoursNotice("");
    const problem = hoursProblem(hours);
    if (problem) {
      setHoursError(problem);
      return;
    }
    setSavingHours(true);
    try {
      const saved = await replaceOpeningHours(hours);
      setHours(sortedHours(saved.openingHours));
      setHoursNotice(AFFECTED_NOTICE);
    } catch (err) {
      setHoursError(describeError(err, "Could not save your opening hours."));
    } finally {
      setSavingHours(false);
    }
  }

  async function handleAddVacation(event: FormEvent) {
    event.preventDefault();
    setVacationBusy(true);
    setVacationError("");
    setVacationNotice("");
    try {
      const created = await addVacation({ startDate, endDate });
      setVacations((current) => [...current, created].sort((a, b) => a.startDate.localeCompare(b.startDate)));
      setVacationNotice(AFFECTED_NOTICE);
    } catch (err) {
      setVacationError(describeError(err, "Could not save this vacation."));
    } finally {
      setVacationBusy(false);
    }
  }

  async function handleRemoveVacation(id: string) {
    setVacationBusy(true);
    setVacationError("");
    setVacationNotice("");
    try {
      await removeVacation(id);
      setVacations((current) => current.filter((v) => v.id !== id));
      setVacationNotice("Vacation removed. Appointments it had flagged are released.");
    } catch (err) {
      setVacationError(describeError(err, "Could not remove this vacation."));
    } finally {
      setVacationBusy(false);
    }
  }

  if (status === "loading") {
    return <StatusMessage kind="loading">Loading your availability...</StatusMessage>;
  }
  if (status === "error") {
    return <StatusMessage kind="error">{loadError}</StatusMessage>;
  }

  const vacationRangeInvalid = startDate === "" || endDate === "" || startDate > endDate;

  return (
    <div className="form-page-wide">
      <h1>My availability</h1>

      <section>
        <h2>Weekly opening hours</h2>
        <p>Patients can book 30-minute slots inside these blocks. Blocks start and end on the hour or half hour.</p>
        <form onSubmit={handleSaveHours}>
          {hours.length === 0 && <StatusMessage kind="info">No opening hours: patients cannot book you.</StatusMessage>}
          {hours.map((row, index) => (
            <div key={index} className="opening-hours-row">
              <select
                aria-label="Day"
                value={row.dayOfWeek}
                onChange={(e) => updateRow(index, { dayOfWeek: e.target.value as DayOfWeek })}
              >
                {DAYS_OF_WEEK.map((day) => (
                  <option key={day} value={day}>
                    {day}
                  </option>
                ))}
              </select>
              <select aria-label="From" value={row.startTime} onChange={(e) => updateRow(index, { startTime: e.target.value })}>
                {HALF_HOUR_TIMES.map((time) => (
                  <option key={time} value={time}>
                    {time}
                  </option>
                ))}
              </select>
              <select aria-label="To" value={row.endTime} onChange={(e) => updateRow(index, { endTime: e.target.value })}>
                {HALF_HOUR_TIMES.map((time) => (
                  <option key={time} value={time}>
                    {time}
                  </option>
                ))}
              </select>
              <button type="button" onClick={() => removeRow(index)}>
                Remove
              </button>
            </div>
          ))}
          <div className="change-form-actions">
            <button type="button" onClick={addRow}>
              Add a block
            </button>
            <button type="submit" disabled={savingHours}>
              {savingHours ? "Saving..." : "Save opening hours"}
            </button>
          </div>
        </form>
        {hoursNotice && <StatusMessage kind="info">{hoursNotice}</StatusMessage>}
        {hoursError && <StatusMessage kind="error">{hoursError}</StatusMessage>}
      </section>

      <section>
        <h2>Vacations</h2>
        <p>Whole days, first and last day included. Patients cannot book you on these days.</p>
        {vacations.length === 0 && <StatusMessage kind="info">No upcoming vacations.</StatusMessage>}
        <ul className="vacation-list">
          {vacations.map((vacation) => (
            <li key={vacation.id}>
              <span>
                {vacation.startDate === vacation.endDate
                  ? vacation.startDate
                  : `${vacation.startDate} to ${vacation.endDate}`}
              </span>
              <button type="button" onClick={() => handleRemoveVacation(vacation.id)} disabled={vacationBusy}>
                Remove
              </button>
            </li>
          ))}
        </ul>
        <form className="filters" onSubmit={handleAddVacation}>
          <label>
            First day
            <input
              type="date"
              value={startDate}
              min={todayIsoDate()}
              onChange={(e) => {
                setStartDate(e.target.value);
                if (endDate < e.target.value) {
                  setEndDate(e.target.value);
                }
              }}
              required
            />
          </label>
          <label>
            Last day
            <input type="date" value={endDate} min={startDate || todayIsoDate()} onChange={(e) => setEndDate(e.target.value)} required />
          </label>
          <button type="submit" disabled={vacationBusy || vacationRangeInvalid}>
            Add vacation
          </button>
        </form>
        {vacationNotice && <StatusMessage kind="info">{vacationNotice}</StatusMessage>}
        {vacationError && <StatusMessage kind="error">{vacationError}</StatusMessage>}
      </section>
    </div>
  );
}
