import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { searchDoctors, getSpecialties } from "../api/doctors";
import { LANGUAGES } from "../api/constants";
import { ApiError } from "../api/client";
import { StatusMessage } from "../components/StatusMessage";
import type { DoctorSummaryResponse, Language, SpecialtyDto } from "../api/types";

export function DoctorSearchPage() {
  const [specialties, setSpecialties] = useState<SpecialtyDto[]>([]);
  const [specialty, setSpecialty] = useState("");
  const [language, setLanguage] = useState<Language | "">("");
  const [doctors, setDoctors] = useState<DoctorSummaryResponse[]>([]);
  const [status, setStatus] = useState<"loading" | "success" | "error">("loading");
  const [error, setError] = useState("");

  useEffect(() => {
    getSpecialties()
      .then(setSpecialties)
      .catch(() => setSpecialties([]));
  }, []);

  useEffect(() => {
    setStatus("loading");
    searchDoctors({ specialty: specialty || undefined, language: language || undefined })
      .then((results) => {
        setDoctors(results);
        setStatus("success");
      })
      .catch((err) => {
        setError(err instanceof ApiError ? err.message : "Could not load doctors.");
        setStatus("error");
      });
  }, [specialty, language]);

  return (
    <div>
      <h1>Find a doctor</h1>
      <div className="filters">
        <label>
          Specialty
          <select value={specialty} onChange={(e) => setSpecialty(e.target.value)}>
            <option value="">All specialties</option>
            {specialties.map((s) => (
              <option key={s.id} value={s.name}>
                {s.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          Language
          <select value={language} onChange={(e) => setLanguage(e.target.value as Language | "")}>
            <option value="">Any language</option>
            {LANGUAGES.map((l) => (
              <option key={l} value={l}>
                {l}
              </option>
            ))}
          </select>
        </label>
      </div>

      {status === "loading" && <StatusMessage kind="loading">Loading doctors...</StatusMessage>}
      {status === "error" && <StatusMessage kind="error">{error}</StatusMessage>}
      {status === "success" && doctors.length === 0 && (
        <StatusMessage kind="info">No doctors match these filters.</StatusMessage>
      )}

      <ul className="doctor-list">
        {doctors.map((doctor) => (
          <li key={doctor.id} className="doctor-card">
            <Link to={`/doctors/${doctor.id}`}>
              <strong>
                Dr. {doctor.firstName} {doctor.lastName}
              </strong>
            </Link>
            <span>{doctor.specialties.map((s) => s.name).join(", ")}</span>
            <span>
              {doctor.postalCode} {doctor.city}
            </span>
            <span>{doctor.languages.join(", ")}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
