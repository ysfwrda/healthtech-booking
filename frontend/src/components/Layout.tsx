import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { useDoctorAuth } from "../auth/DoctorAuthContext";

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
        <NavLink to="/doctors" className="brand">
          HealthTech Booking
        </NavLink>
        <nav className="nav-links">
          <NavLink to="/doctors">Find a doctor</NavLink>
          {isAuthenticated && <NavLink to="/appointments">My appointments</NavLink>}
          {isAuthenticated ? (
            <>
              <span className="nav-username">{username}</span>
              <button type="button" onClick={handleLogout}>
                Log out
              </button>
            </>
          ) : (
            <>
              <NavLink to="/login">Log in</NavLink>
              <NavLink to="/register">Register</NavLink>
            </>
          )}
          {isDoctorAuthenticated ? (
            <>
              <span className="nav-username">{doctorEmail}</span>
              <button type="button" onClick={handleDoctorLogout}>
                Log out (doctor)
              </button>
            </>
          ) : (
            <>
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
