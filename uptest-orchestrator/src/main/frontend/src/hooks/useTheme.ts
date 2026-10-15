import { useCallback, useEffect, useState } from "react";

export type Theme = "light" | "dark";

/*
  The key is the one app.js used. Keeping the name is the whole point: an
  analyst who picked dark yesterday must not be handed light by the rewrite.

  Every localStorage touch is wrapped -- a private window throws on access
  rather than returning null, and an exception here would take the shell down
  before anything rendered.
*/
const STORAGE_KEY = "uptest.theme";

function readStored(): Theme | null {
  try {
    const value = localStorage.getItem(STORAGE_KEY);
    return value === "dark" || value === "light" ? value : null;
  } catch {
    return null;
  }
}

export function useTheme(): { theme: Theme; toggle: () => void } {
  const [theme, setTheme] = useState<Theme>(() => readStored() ?? "light");

  useEffect(() => {
    document.documentElement.setAttribute("data-theme", theme);
    try {
      localStorage.setItem(STORAGE_KEY, theme);
    } catch {
      /* private mode: the choice simply does not survive the session */
    }
  }, [theme]);

  const toggle = useCallback(() => {
    setTheme((current) => (current === "dark" ? "light" : "dark"));
  }, []);

  return { theme, toggle };
}
