import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { useDoctorAuth } from "../auth/DoctorAuthContext";

// One shell per kind of visitor: a logged-in doctor sees only the doctor's links, a logged-in patient only the
// patient's, and a visitor the public ones. Logging in as one role logs the other out, so the two never mix.
export function Layout() {
  const { isAuthenticated, username, logout } = useAuth();
  const { isDoctorAuthenticated, doctorEmail, logoutDoctor } = useDoctorAuth();
  const navigate = useNavigate();

  function handleLogout() {
    logout();
    navigate("/login");
  }

  function handleDoctorLogout() {
    logoutDoctor();
    navigate("/doctors");
  }

  return (
    <div className="app-shell">
      <header className="app-header">
        <NavLink to={isDoctorAuthenticated ? "/doctor/appointments" : "/doctors"} className="brand">
          HealthTech Booking
        </NavLink>
        <nav className="nav-links">
          {isDoctorAuthenticated ? (
            <>
              <NavLink to="/doctor/appointments">Appointments</NavLink>
              <NavLink to="/doctor/availability">My availability</NavLink>
              <span className="nav-username">{doctorEmail}</span>
              <button type="button" onClick={handleDoctorLogout}>
                Log out
              </button>
            </>
          ) : isAuthenticated ? (
            <>
              <NavLink to="/doctors">Find a doctor</NavLink>
              <NavLink to="/appointments">My appointments</NavLink>
              <span className="nav-username">{username}</span>
              <button type="button" onClick={handleLogout}>
                Log out
              </button>
            </>
          ) : (
            <>
              <NavLink to="/doctors">Find a doctor</NavLink>
              <NavLink to="/login">Log in</NavLink>
              <NavLink to="/register">Register</NavLink>
              <NavLink to="/doctors/login">Doctor log in</NavLink>
              <NavLink to="/doctors/register">Register as a doctor</NavLink>
            </>
          )}
        </nav>
      </header>
      <main className="app-main">
        <Outlet />
      </main>
    </div>
  );
}
