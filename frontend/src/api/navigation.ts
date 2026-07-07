type NavigateFn = (path: string) => void;
let navigate: NavigateFn = (path) => { window.location.assign(path); };

export function setNavigate(fn: NavigateFn): void {
  navigate = fn;
}

export function goTo(path: string): void {
  navigate(path);
}