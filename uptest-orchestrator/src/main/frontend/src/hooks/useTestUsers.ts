import { useCallback, useEffect, useState } from "react";
import { deleteTestUser, getTestUsers, saveTestUser } from "../api/endpoints";
import type { SaveUserRequest, TestUser } from "../api/types";

/*
  Kayıtlı test girişleri, dört panelin paylaştığı tek liste.

  One fetch for all of them, the same reasoning useCatalogs records: a second
  request for the second panel is the same answer twice and can disagree with
  itself. Reloaded after a save so a login added in Test Kullanıcıları shows
  up in the pickers without a page refresh -- and re-read from the server
  rather than appended locally, because the server derives the id and would
  derive it differently from a guess made here.
*/
export function useTestUsers() {
  const [users, setUsers] = useState<TestUser[]>([]);
  const [file, setFile] = useState("");
  const [loaded, setLoaded] = useState(false);

  const reload = useCallback(async () => {
    const result = await getTestUsers();
    if (!result.ok) {
      // An empty list is a legitimate state -- nobody has saved a login yet --
      // and so is an orchestrator that predates this endpoint. Neither is
      // worth a toast: the picker says what to do instead.
      setLoaded(true);
      return;
    }
    setUsers(result.body.users);
    setFile(result.body.file);
    setLoaded(true);
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  const save = useCallback(
    async (user: SaveUserRequest) => {
      const result = await saveTestUser(user);
      if (result.ok) await reload();
      return result;
    },
    [reload],
  );

  const remove = useCallback(
    async (id: string) => {
      const result = await deleteTestUser(id);
      if (result.ok) await reload();
      return result;
    },
    [reload],
  );

  const byId = useCallback(
    (id: string) => users.find((user) => user.id === id) ?? null,
    [users],
  );

  return { users, file, loaded, reload, save, remove, byId };
}
