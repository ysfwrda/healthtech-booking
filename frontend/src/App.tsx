import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { AuthProvider } from "./auth/AuthContext";
import { DoctorAuthProvider } from "./auth/DoctorAuthContext";
import { RequireAuth } from "./auth/RequireAuth";
import { RequireDoctorAuth } from "./auth/RequireDoctorAuth";
import { useDoctorAuth } from "./auth/DoctorAuthContext";
import { Layout } from "./components/Layout";
import { LoginPage } from "./pages/LoginPage";
import { RegisterPage } from "./pages/RegisterPage";
import { DoctorLoginPage } from "./pages/DoctorLoginPage";
import { DoctorRegisterPage } from "./pages/DoctorRegisterPage";
import { DoctorSearchPage } from "./pages/DoctorSearchPage";
import { DoctorDetailPage } from "./pages/DoctorDetailPage";
import { MyAppointmentsPage } from "./pages/MyAppointmentsPage";
import { DoctorAppointmentsPage } from "./pages/DoctorAppointmentsPage";
import { DoctorAvailabilityPage } from "./pages/DoctorAvailabilityPage";
import { useNavigate } from "react-router-dom";
import { useEffect } from "react";
import { setNavigate } from "./api/navigation";

function RouterBridge() {
  const navigate = useNavigate();
  useEffect(() => {
    setNavigate((path) => navigate(path));
  }, [navigate]);
  return null;
}

// Each kind of user lands on their own start page.
function HomeRedirect() {
  const { isDoctorAuthenticated } = useDoctorAuth();
  return <Navigate to={isDoctorAuthenticated ? "/doctor/appointments" : "/doctors"} replace />;
}

function App() {
  return (
    <BrowserRouter>
      <RouterBridge />
      <AuthProvider>
        <DoctorAuthProvider>
          <Routes>
            <Route element={<Layout />}>
              <Route index element={<HomeRedirect />} />
              <Route path="login" element={<LoginPage />} />
              <Route path="register" element={<RegisterPage />} />
              <Route path="doctors/login" element={<DoctorLoginPage />} />
              <Route path="doctors/register" element={<DoctorRegisterPage />} />
              <Route path="doctors" element={<DoctorSearchPage />} />
              <Route path="doctors/:id" element={<DoctorDetailPage />} />
              <Route element={<RequireAuth />}>
                <Route path="appointments" element={<MyAppointmentsPage />} />
              </Route>
              <Route element={<RequireDoctorAuth />}>
                <Route path="doctor/appointments" element={<DoctorAppointmentsPage />} />
                <Route path="doctor/availability" element={<DoctorAvailabilityPage />} />
              </Route>
              <Route path="*" element={<HomeRedirect />} />
            </Route>
          </Routes>
        </DoctorAuthProvider>
      </AuthProvider>
    </BrowserRouter>
  );
}

export default App;
