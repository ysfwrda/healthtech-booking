import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { getDoctor } from "../api/doctors";
import { getAvailability } from "../api/availability";
import { bookAppointment } from "../api/appointments";
import { ApiError } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { StatusMessage } from "../components/StatusMessage";
import { APPOINTMENT_TYPES } from "../api/constants";
import type { AppointmentType, DoctorResponse } from "../api/types";

function todayIsoDate(): string {
  return new Date().toISOString().slice(0, 10);
}

export function DoctorDetailPage() {
  const { id } = useParams<{ id: string }>();
  const doctorId = id ?? "";
  const { isAuthenticated } = useAuth();

  const [doctor, setDoctor] = useState<DoctorResponse | null>(null);
  const [doctorStatus, setDoctorStatus] = useState<"loading" | "success" | "error">("loading");
  const [doctorError, setDoctorError] = useState("");

  const [date, setDate] = useState(todayIsoDate());
  const [slots, setSlots] = useState<string[]>([]);
  const [slotsStatus, setSlotsStatus] = useState<"loading" | "success" | "error">("loading");
  const [slotsError, setSlotsError] = useState("");

  const [selectedSlot, setSelectedSlot] = useState<string | null>(null);
  const [type, setType] = useState<AppointmentType>("INITIAL_CONSULTATION");
  const [notes, setNotes] = useState("");
  const [bookingStatus, setBookingStatus] = useState<"idle" | "loading" | "error" | "success">("idle");
  const [bookingMessage, setBookingMessage] = useState("");

  useEffect(() => {
    getDoctor(doctorId)
      .then((response) => {
        setDoctor(response);
        setDoctorStatus("success");
      })
      .catch((err) => {
        setDoctorError(err instanceof ApiError ? err.message : "Could not load this doctor.");
        setDoctorStatus("error");
      });
  }, [doctorId]);

  function refreshSlots() {
    setSlotsStatus("loading");
    getAvailability(doctorId, date)
      .then((response) => {
        setSlots(response.availableSlots);
        setSlotsStatus("success");
      })
      .catch((err) => {
        setSlotsError(err instanceof ApiError ? err.message : "Could not load availability.");
        setSlotsStatus("error");
      });
  }

  useEffect(() => {
    setSelectedSlot(null);
    setBookingStatus("idle");
    refreshSlots();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [doctorId, date]);

  function selectSlot(slot: string) {
    setSelectedSlot(slot);
    setBookingStatus("idle");
    setBookingMessage("");
  }

  async function handleBook() {
    if (!selectedSlot) {
      return;
    }
    setBookingStatus("loading");
    setBookingMessage("");
    try {
      await bookAppointment({ doctorId, dateTime: selectedSlot, type, notes: notes || undefined });
      setBookingStatus("success");
      setBookingMessage("Appointment booked.");
      setNotes("");
      setSelectedSlot(null);
      refreshSlots();
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setBookingMessage("That slot was just taken. Here are the current openings for this day.");
        setSelectedSlot(null);
        refreshSlots();
      } else {
        setBookingMessage(err instanceof ApiError ? err.message : "Booking failed. Please try again.");
      }
      setBookingStatus("error");
    }
  }

  if (doctorStatus === "loading") {
    return <StatusMessage kind="loading">Loading doctor...</StatusMessage>;
  }
  if (doctorStatus === "error" || !doctor) {
    return <StatusMessage kind="error">{doctorError}</StatusMessage>;
  }

  return (
    <div>
      <h1>
        Dr. {doctor.firstName} {doctor.lastName}
      </h1>
      <p>{doctor.specialties.map((s) => s.name).join(", ")}</p>
      <p>
        {doctor.address.street} {doctor.address.houseNumber}, {doctor.address.postalCode} {doctor.address.city}
      </p>
      <p>Languages: {doctor.languages.join(", ")}</p>

      <h2>Availability</h2>
      <label>
        Date
        <input type="date" value={date} min={todayIsoDate()} onChange={(e) => setDate(e.target.value)} />
      </label>

      {slotsStatus === "loading" && <StatusMessage kind="loading">Loading slots...</StatusMessage>}
      {slotsStatus === "error" && <StatusMessage kind="error">{slotsError}</StatusMessage>}
      {slotsStatus === "success" && slots.length === 0 && (
        <StatusMessage kind="info">No open slots for this day.</StatusMessage>
      )}

      {slotsStatus === "success" && slots.length > 0 && (
        <ul className="slot-grid">
          {slots.map((slot) => (
            <li key={slot}>
              <button
                type="button"
                className={selectedSlot === slot ? "slot selected" : "slot"}
                onClick={() => selectSlot(slot)}
              >
                {slot.slice(11, 16)}
              </button>
            </li>
          ))}
        </ul>
      )}

      {isAuthenticated ? (
        <>
          {selectedSlot && (
            <div className="booking-form">
              <h2>Book {selectedSlot.replace("T", " ")}</h2>
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
              <button type="button" onClick={handleBook} disabled={bookingStatus === "loading"}>
                {bookingStatus === "loading" ? "Booking..." : "Confirm booking"}
              </button>
            </div>
          )}
          {bookingStatus === "success" && <StatusMessage kind="info">{bookingMessage}</StatusMessage>}
          {bookingStatus === "error" && <StatusMessage kind="error">{bookingMessage}</StatusMessage>}
        </>
      ) : (
        <StatusMessage kind="info">
          <Link to="/login">Log in</Link> to book an appointment.
        </StatusMessage>
      )}
    </div>
  );
}
