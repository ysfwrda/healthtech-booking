import { useEffect, useState } from "react";
import {
  cancelAppointmentAsDoctor,
  confirmAppointment,
  getDoctorAppointments,
  updateAppointmentAsDoctor,
} from "../api/doctorAppointments";
import { ApiError } from "../api/client";
import { ChangeAppointmentForm } from "../components/ChangeAppointmentForm";
import { StatusMessage } from "../components/StatusMessage";
import { todayIsoDate } from "../dates";
import { conflictLabelForDoctor, statusLabel, typeLabel } from "../labels";
import type { AppointmentStatus, DoctorAppointmentResponse } from "../api/types";

const STATUSES: AppointmentStatus[] = ["PENDING", "CONFIRMED", "CANCELLED"];

export function DoctorAppointmentsPage() {
  // Today onwards, pending and confirmed: what a doctor works through. Everything else is one click away.
  const [from, setFrom] = useState(todayIsoDate());
  const [to, setTo] = useState("");
  const [statuses, setStatuses] = useState<AppointmentStatus[]>(["PENDING", "CONFIRMED"]);

  const [appointments, setAppointments] = useState<DoctorAppointmentResponse[]>([]);
  const [status, setStatus] = useState<"loading" | "success" | "error">("loading");
  const [error, setError] = useState("");
  // Shown above the list: the outcome of the last action.
  const [actionError, setActionError] = useState("");
  const [notice, setNotice] = useState("");
  const [busyId, setBusyId] = useState<string | null>(null);
  const [changingId, setChangingId] = useState<string | null>(null);

  // Bumped after an action so the list is read again with the same filters.
  const [reloadToken, setReloadToken] = useState(0);

  const filtersInvalid = from !== "" && to !== "" && from > to;
  // No box ticked would otherwise be sent as "every status"; show nothing instead of what looks unselected.
  const noStatusSelected = statuses.length === 0;

  useEffect(() => {
    if ((from !== "" && to !== "" && from > to) || statuses.length === 0) {
      return;
    }
    let stale = false;
    setStatus("loading");
    getDoctorAppointments({ from, to, statuses })
      .then((results) => {
        if (!stale) {
          setAppointments(results);
          setStatus("success");
        }
      })
      .catch((err) => {
        if (!stale) {
          setError(err instanceof ApiError ? err.message : "Could not load your appointments.");
          setStatus("error");
        }
      });
    return () => {
      stale = true;
    };
  }, [from, to, statuses, reloadToken]);

  function load() {
    setReloadToken((token) => token + 1);
  }

  function toggleStatus(value: AppointmentStatus) {
    setStatuses((current) => (current.includes(value) ? current.filter((s) => s !== value) : [...current, value]));
  }

  async function runAction(id: string, action: () => Promise<unknown>, successNotice: string, failure: string) {
    setBusyId(id);
    setActionError("");
    setNotice("");
    try {
      await action();
      setChangingId(null);
      setNotice(successNotice);
      load();
    } catch (err) {
      setActionError(err instanceof ApiError ? err.message : failure);
    } finally {
      setBusyId(null);
    }
  }

  function handleConfirm(appointment: DoctorAppointmentResponse) {
    return runAction(
      appointment.id,
      () => confirmAppointment(appointment.id),
      "Appointment confirmed. The patient was notified.",
      "Could not confirm this appointment.",
    );
  }

  function handleCancel(appointment: DoctorAppointmentResponse) {
    if (!window.confirm("Cancel this appointment? The patient will be notified.")) {
      return;
    }
    return runAction(
      appointment.id,
      () => cancelAppointmentAsDoctor(appointment.id),
      "Appointment cancelled. The patient was notified.",
      "Could not cancel this appointment.",
    );
  }

  function handleChanged() {
    setChangingId(null);
    setActionError("");
    setNotice("Appointment changed. The patient was notified.");
    load();
  }

  return (
    <div>
      <h1>Appointments</h1>
      <form className="filters" onSubmit={(e) => e.preventDefault()}>
        <label>
          From
          <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label>
          To
          <input type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />
        </label>
        <fieldset className="checkbox-group">
          <legend>Status</legend>
          {STATUSES.map((value) => (
            <label key={value} className="checkbox-option">
              <input type="checkbox" checked={statuses.includes(value)} onChange={() => toggleStatus(value)} />
              {statusLabel(value)}
            </label>
          ))}
        </fieldset>
      </form>
      {filtersInvalid && <StatusMessage kind="error">The start date must not be after the end date.</StatusMessage>}
      {noStatusSelected && <StatusMessage kind="info">Select at least one status to see appointments.</StatusMessage>}
      {notice && <StatusMessage kind="info">{notice}</StatusMessage>}
      {actionError && <StatusMessage kind="error">{actionError}</StatusMessage>}
      {!filtersInvalid && !noStatusSelected && status === "loading" && <StatusMessage kind="loading">Loading appointments...</StatusMessage>}
      {!filtersInvalid && !noStatusSelected && status === "error" && <StatusMessage kind="error">{error}</StatusMessage>}
      {!filtersInvalid && !noStatusSelected && status === "success" && appointments.length === 0 && (
        <StatusMessage kind="info">No appointments match these filters.</StatusMessage>
      )}
      {!filtersInvalid && !noStatusSelected && status === "success" && (
        <ul className="appointment-list">
          {appointments.map((appointment) => {
            const cancelled = appointment.status === "CANCELLED";
            const flagged = appointment.conflictReason != null;
            return (
              <li key={appointment.id} className={cancelled ? "cancelled" : undefined}>
                <span>{appointment.dateTime.replace("T", " ").slice(0, 16)}</span>
                <span>
                  {appointment.patientName ?? "Unknown patient"}
                  {appointment.patientEmail ? ` (${appointment.patientEmail})` : ""}
                </span>
                <span>{typeLabel(appointment.type)}</span>
                <span className={`badge badge-${appointment.status.toLowerCase()}`}>{statusLabel(appointment.status)}</span>
                {appointment.notes && <span className="appointment-notes">Patient notes: {appointment.notes}</span>}
                {!cancelled && (
                  <>
                    {appointment.status === "PENDING" && !flagged && (
                      <button type="button" onClick={() => handleConfirm(appointment)} disabled={busyId === appointment.id}>
                        Confirm
                      </button>
                    )}
                    <button
                      type="button"
                      onClick={() => setChangingId(changingId === appointment.id ? null : appointment.id)}
                      disabled={busyId === appointment.id}
                    >
                      Change
                    </button>
                    <button type="button" onClick={() => handleCancel(appointment)} disabled={busyId === appointment.id}>
                      Cancel
                    </button>
                  </>
                )}
                {flagged && !cancelled && (
                  <p className="flag-banner" role="status">
                    {conflictLabelForDoctor(appointment.conflictReason!)} Change its time or cancel it to resolve this.
                  </p>
                )}
                {changingId === appointment.id && (
                  <ChangeAppointmentForm
                    appointment={appointment}
                    canEditNotes={false}
                    save={(id, update) => updateAppointmentAsDoctor(id, { type: update.type, dateTime: update.dateTime })}
                    onSaved={handleChanged}
                    onClose={() => setChangingId(null)}
                  />
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
