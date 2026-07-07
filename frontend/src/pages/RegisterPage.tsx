import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { ApiError } from "../api/client";
import { StatusMessage } from "../components/StatusMessage";
import type { InsuranceType } from "../api/types";

export function RegisterPage() {
  const { register } = useAuth();
  const navigate = useNavigate();

  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [dateOfBirth, setDateOfBirth] = useState("");
  const [email, setEmail] = useState("");
  const [insuranceType, setInsuranceType] = useState<InsuranceType>("STATUTORY");
  const [status, setStatus] = useState<"idle" | "loading" | "error">("idle");
  const [error, setError] = useState("");

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setStatus("loading");
    setError("");
    try {
      await register({ firstName, lastName, username, password, dateOfBirth, email, insuranceType });
      navigate("/doctors");
    } catch (err) {
      setStatus("error");
      setError(err instanceof ApiError ? err.message : "Registration failed. Please try again.");
      return;
    }
    setStatus("idle");
  }

  return (
    <div className="form-page">
      <h1>Register</h1>
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
          Username
          <input value={username} onChange={(e) => setUsername(e.target.value)} required />
        </label>
        <label>
          Email
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>
          Password
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </label>
        <label>
          Date of birth
          <input type="date" value={dateOfBirth} onChange={(e) => setDateOfBirth(e.target.value)} required />
        </label>
        <label>
          Insurance type
          <select value={insuranceType} onChange={(e) => setInsuranceType(e.target.value as InsuranceType)}>
            <option value="STATUTORY">Statutory</option>
            <option value="PRIVATE">Private</option>
          </select>
        </label>
        <button type="submit" disabled={status === "loading"}>
          {status === "loading" ? "Registering..." : "Register"}
        </button>
      </form>
      {status === "error" && <StatusMessage kind="error">{error}</StatusMessage>}
      <p>
        Already have an account? <Link to="/login">Log in</Link>
      </p>
    </div>
  );
}
