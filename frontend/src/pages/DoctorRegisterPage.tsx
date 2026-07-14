import { useEffect, useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useDoctorAuth } from "../auth/DoctorAuthContext";
import { getSpecialties } from "../api/doctors";
import { ApiError } from "../api/client";
import { StatusMessage } from "../components/StatusMessage";
import { DAYS_OF_WEEK, HALF_HOUR_TIMES, LANGUAGES } from "../api/constants";
import type { DayOfWeek, Language, OpeningHoursDto, SpecialtyDto } from "../api/types";

interface OpeningHoursRow {
  dayOfWeek: DayOfWeek;
  startTime: string;
  endTime: string;
}

function emptyRow(): OpeningHoursRow {
  return { dayOfWeek: "MONDAY", startTime: "", endTime: "" };
}

function isDuplicateRow(rows: OpeningHoursRow[], candidate: OpeningHoursRow, excludeIndex: number): boolean {
  return rows.some(
    (row, i) =>
      i !== excludeIndex &&
      row.dayOfWeek === candidate.dayOfWeek &&
      row.startTime === candidate.startTime &&
      row.endTime === candidate.endTime,
  );
}

export function DoctorRegisterPage() {
  const { registerDoctor } = useDoctorAuth();
  const navigate = useNavigate();

  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [phoneNumber, setPhoneNumber] = useState("");

  const [street, setStreet] = useState("");
  const [houseNumber, setHouseNumber] = useState("");
  const [postalCode, setPostalCode] = useState("");
  const [city, setCity] = useState("");
  const [state, setState] = useState("");
  const [country, setCountry] = useState("");

  const [specialties, setSpecialties] = useState<SpecialtyDto[]>([]);
  const [selectedSpecialtyIds, setSelectedSpecialtyIds] = useState<Set<string>>(new Set());
  const [selectedLanguages, setSelectedLanguages] = useState<Set<Language>>(new Set());
  const [openingHours, setOpeningHours] = useState<OpeningHoursRow[]>([emptyRow()]);
  const [duplicateRowIndex, setDuplicateRowIndex] = useState<number | null>(null);

  const [status, setStatus] = useState<"idle" | "loading" | "error">("idle");
  const [error, setError] = useState("");
  const [fieldErrors, setFieldErrors] = useState<Record<string, string> | undefined>(undefined);

  useEffect(() => {
    getSpecialties()
      .then(setSpecialties)
      .catch(() => setSpecialties([]));
  }, []);

  function toggleSpecialty(id: string) {
    setSelectedSpecialtyIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function toggleLanguage(language: Language) {
    setSelectedLanguages((prev) => {
      const next = new Set(prev);
      if (next.has(language)) next.delete(language);
      else next.add(language);
      return next;
    });
  }

  function updateRow(index: number, patch: Partial<OpeningHoursRow>) {
    const candidate = { ...openingHours[index], ...patch };
    if (candidate.startTime && candidate.endTime && isDuplicateRow(openingHours, candidate, index)) {
      setDuplicateRowIndex(index);
      return;
    }
    setDuplicateRowIndex(null);
    setOpeningHours((prev) => prev.map((row, i) => (i === index ? candidate : row)));
  }

  function addRow() {
    setDuplicateRowIndex(null);
    setOpeningHours((prev) => [...prev, emptyRow()]);
  }

  function removeRow(index: number) {
    setDuplicateRowIndex(null);
    setOpeningHours((prev) => prev.filter((_, i) => i !== index));
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError("");
    setFieldErrors(undefined);

    if (selectedSpecialtyIds.size === 0) {
      setStatus("error");
      setError("Select at least one specialty.");
      return;
    }
    if (selectedLanguages.size === 0) {
      setStatus("error");
      setError("Select at least one language.");
      return;
    }
    if (openingHours.length === 0 || openingHours.some((row) => !row.startTime || !row.endTime)) {
      setStatus("error");
      setError("Add at least one opening hours row with a start and end time.");
      return;
    }

    setStatus("loading");
    try {
      const payload: OpeningHoursDto[] = openingHours.map((row) => ({
        dayOfWeek: row.dayOfWeek,
        startTime: row.startTime,
        endTime: row.endTime,
      }));
      await registerDoctor({
        firstName,
        lastName,
        email,
        password,
        phoneNumber,
        address: {
          street,
          houseNumber,
          postalCode,
          city,
          state: state || undefined,
          country,
        },
        specialtyIds: Array.from(selectedSpecialtyIds),
        openingHours: payload,
        languages: Array.from(selectedLanguages),
      });
      navigate("/doctors");
    } catch (err) {
      setStatus("error");
      if (err instanceof ApiError) {
        setError(err.message);
        setFieldErrors(err.errors);
      } else {
        setError("Registration failed. Please try again.");
      }
      return;
    }
    setStatus("idle");
  }

  return (
    <div className="form-page form-page-wide">
      <h1>Register as a doctor</h1>
      <form onSubmit={handleSubmit}>
        <label>
          First name
          <input value={firstName} onChange={(e) => setFirstName(e.target.value)} required />
        </label>
        <label>
          Last name
          <input value={lastName} onChange={(e) => setLastName(e.target.value)} required />
        </label>
        <label>
          Email
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            minLength={8}
            required
          />
        </label>
        <label>
          Phone number
          <input value={phoneNumber} onChange={(e) => setPhoneNumber(e.target.value)} required />
        </label>

        <h2>Practice address</h2>
        <label>
          Street
          <input value={street} onChange={(e) => setStreet(e.target.value)} required />
        </label>
        <label>
          House number
          <input value={houseNumber} onChange={(e) => setHouseNumber(e.target.value)} required />
        </label>
        <label>
          Postal code
          <input value={postalCode} onChange={(e) => setPostalCode(e.target.value)} required />
        </label>
        <label>
          City
          <input value={city} onChange={(e) => setCity(e.target.value)} required />
        </label>
        <label>
          State (optional)
          <input value={state} onChange={(e) => setState(e.target.value)} />
        </label>
        <label>
          Country
          <input value={country} onChange={(e) => setCountry(e.target.value)} required />
        </label>

        <h2>Specialties</h2>
        <div className="checkbox-group">
          {specialties.map((specialty) => (
            <label key={specialty.id} className="checkbox-option">
              <input
                type="checkbox"
                checked={selectedSpecialtyIds.has(specialty.id)}
                onChange={() => toggleSpecialty(specialty.id)}
              />
              {specialty.name}
            </label>
          ))}
        </div>

        <h2>Languages</h2>
        <div className="checkbox-group">
          {LANGUAGES.map((language) => (
            <label key={language} className="checkbox-option">
              <input
                type="checkbox"
                checked={selectedLanguages.has(language)}
                onChange={() => toggleLanguage(language)}
              />
              {language}
            </label>
          ))}
        </div>

        <h2>Opening hours</h2>
        {openingHours.map((row, index) => (
          <div key={index}>
            <div className="opening-hours-row">
              <select
                value={row.dayOfWeek}
                onChange={(e) => updateRow(index, { dayOfWeek: e.target.value as DayOfWeek })}
              >
                {DAYS_OF_WEEK.map((day) => (
                  <option key={day} value={day}>
                    {day}
                  </option>
                ))}
              </select>
              <select
                value={row.startTime}
                onChange={(e) => updateRow(index, { startTime: e.target.value })}
                required
              >
                <option value="" disabled>
                  Start time
                </option>
                {HALF_HOUR_TIMES.map((time) => (
                  <option key={time} value={time}>
                    {time}
                  </option>
                ))}
              </select>
              <select
                value={row.endTime}
                onChange={(e) => updateRow(index, { endTime: e.target.value })}
                required
              >
                <option value="" disabled>
                  End time
                </option>
                {HALF_HOUR_TIMES.map((time) => (
                  <option key={time} value={time}>
                    {time}
                  </option>
                ))}
              </select>
              <button type="button" onClick={() => removeRow(index)} disabled={openingHours.length === 1}>
                Remove
              </button>
            </div>
            {duplicateRowIndex === index && (
              <StatusMessage kind="error">This opening-hours block is already added.</StatusMessage>
            )}
          </div>
        ))}
        <button type="button" onClick={addRow}>
          Add opening hours row
        </button>

        <button type="submit" disabled={status === "loading"}>
          {status === "loading" ? "Registering..." : "Register"}
        </button>
      </form>
      {status === "error" && (
        <>
          <StatusMessage kind="error">{error}</StatusMessage>
          {fieldErrors && (
            <ul className="field-errors">
              {Object.entries(fieldErrors).map(([field, message]) => (
                <li key={field}>
                  {field}: {message}
                </li>
              ))}
            </ul>
          )}
        </>
      )}
      <p>
        Already registered as a doctor? <Link to="/doctors">Find a doctor</Link>
      </p>
    </div>
  );
}
