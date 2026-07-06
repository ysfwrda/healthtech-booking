import { useEffect, useState } from "react";
import { getMyAppointments, cancelAppointment } from "../api/appointments";
import { getDoctor } from "../api/doctors";
import { ApiError } from "../api/client";
import { StatusMessage } from "../components/StatusMessage";
import type { AppointmentResponse } from "../api/types";

export function MyAppointmentsPage() {
  const [appointments, setAppointments] = useState<AppointmentResponse[]>([]);
  const [doctorNames, setDoctorNames] = useState<Record<string, string>>({});
  const [status, setStatus] = useState<"loading" | "success" | "error">("loading");
  const [error, setError] = useState("");
  const [cancellingId, setCancellingId] = useState<string | null>(null);

  function load() {
    setStatus("loading");
    getMyAppointments()
      .then(async (results) => {
        setAppointments(results);
        setStatus("success");

        const uniqueDoctorIds = [...new Set(results.map((a) => a.doctorId))];
        const entries = await Promise.all(
          uniqueDoctorIds.map(async (doctorId) => {
            try {
              const doctor = await getDoctor(doctorId);
              return [doctorId, `Dr. ${doctor.firstName} ${doctor.lastName}`] as const;
            } catch {
              return [doctorId, doctorId] as const;
            }
          }),
        );
        setDoctorNames(Object.fromEntries(entries));
      })
      .catch((err) => {
        setError(err instanceof ApiError ? err.message : "Could not load your appointments.");
        setStatus("error");
      });
  }

  useEffect(load, []);

  async function handleCancel(id: string) {
    setCancellingId(id);
    try {
      await cancelAppointment(id);
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not cancel this appointment.");
    } finally {
      setCancellingId(null);
    }
  }

  if (status === "loading") {
    return <StatusMessage kind="loading">Loading your appointments...</StatusMessage>;
  }
  if (status === "error") {
    return <StatusMessage kind="error">{error}</StatusMessage>;
  }

  return (
    <div>
      <h1>My appointments</h1>
      {appointments.length === 0 && <StatusMessage kind="info">You have no appointments yet.</StatusMessage>}
      <ul className="appointment-list">
        {appointments.map((appointment) => (
          <li key={appointment.id} className={appointment.status === "CANCELLED" ? "cancelled" : undefined}>
            <span>{doctorNames[appointment.doctorId] ?? appointment.doctorId}</span>
            <span>{appointment.dateTime.replace("T", " ")}</span>
            <span>{appointment.type}</span>
            <span>{appointment.status}</span>
            {appointment.status !== "CANCELLED" && (
              <button
                type="button"
                onClick={() => handleCancel(appointment.id)}
                disabled={cancellingId === appointment.id}
              >
                {cancellingId === appointment.id ? "Cancelling..." : "Cancel"}
              </button>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
