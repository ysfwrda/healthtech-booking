import { Navigate, Outlet } from "react-router-dom";
import { useAuth } from "./AuthContext";
import { useDoctorAuth } from "./DoctorAuthContext";

// Pages for a logged-in patient. A logged-in doctor is sent to the doctor's own pages instead of the login form.
export function RequireAuth() {
  const { isAuthenticated } = useAuth();
  const { isDoctorAuthenticated } = useDoctorAuth();

  if (!isAuthenticated) {
    return <Navigate to={isDoctorAuthenticated ? "/doctor/appointments" : "/login"} replace />;
  }

  return <Outlet />;
}
