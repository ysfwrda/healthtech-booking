import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useDoctorAuth } from "../auth/DoctorAuthContext";
import { ApiError } from "../api/client";
import { StatusMessage } from "../components/StatusMessage";

export function DoctorLoginPage() {
  const { loginDoctor } = useDoctorAuth();
  const navigate = useNavigate();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [status, setStatus] = useState<"idle" | "loading" | "error">("idle");
  const [error, setError] = useState("");

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setStatus("loading");
    setError("");
    try {
      await loginDoctor({ email, password });
      navigate("/doctors");
    } catch (err) {
      setStatus("error");
      setError(err instanceof ApiError ? err.message : "Login failed. Please try again.");
      return;
    }
    setStatus("idle");
  }

  return (
    <div className="form-page">
      <h1>Doctor log in</h1>
      <form onSubmit={handleSubmit}>
        <label>
          Email
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>
          Password
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </label>
        <button type="submit" disabled={status === "loading"}>
          {status === "loading" ? "Logging in..." : "Log in"}
        </button>
      </form>
      {status === "error" && <StatusMessage kind="error">{error}</StatusMessage>}
      <p>
        No account yet? <Link to="/doctors/register">Register as a doctor</Link>
      </p>
    </div>
  );
}
