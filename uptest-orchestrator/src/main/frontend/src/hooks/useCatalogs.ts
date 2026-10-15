import { useCallback, useEffect, useState } from "react";
import { getCatalogs } from "../api/endpoints";

/*
  Shared by SuggestPanel and MultiPanel -- both generator dropdowns are
  filled from this ONE response (app.js:559-567: "A second fetch for the
  second panel would be the same answer twice and could disagree with
  itself"). `reload` is called after a successful catalog save, so a newly
  saved screen appears without a page refresh -- and re-read from the
  server rather than appended locally, because the server may normalise the
  name differently (lowercased app, slugged flow).
*/
export function useCatalogs() {
  const [catalogs, setCatalogs] = useState<string[]>([]);
  const [root, setRoot] = useState("");
  const [loaded, setLoaded] = useState(false);

  const reload = useCallback(async () => {
    const result = await getCatalogs();
    if (!result.ok) {
      setLoaded(true);
      return;
    }
    setCatalogs(result.body.catalogs);
    setRoot(result.body.root);
    setLoaded(true);
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  return { catalogs, root, loaded, reload };
}
