import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";

export function Layout() {
  const { isAuthenticated, username, logout } = useAuth();
  const navigate = useNavigate();

  function handleLogout() {
    logout();
    navigate("/login");
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
        </nav>
      </header>
      <main className="app-main">
        <Outlet />
      </main>
    </div>
  );
}
