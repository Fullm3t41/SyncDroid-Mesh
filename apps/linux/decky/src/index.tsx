import { ButtonItem, PanelSection, PanelSectionRow, ToggleField, staticClasses } from "@decky/ui";
import { callable, definePlugin } from "@decky/api";
import { useEffect, useState } from "react";
import { FaSync } from "react-icons/fa";

type Status = { enabled: boolean; mode: string; status: string; online: number; devices: number };
const status = callable<[], Status>("status");
const enable = callable<[enabled: boolean], Status>("set_enabled");
const sync = callable<[], Status>("sync_now");

function Content() {
  const [state, setState] = useState<Status>();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let mounted = true;
    const refresh = () => status().then(value => { if (mounted) setState(value); })
      .catch(reason => { if (mounted) setError(String(reason)); });
    void refresh();
    const timer = setInterval(refresh, 5000);
    return () => { mounted = false; clearInterval(timer); };
  }, []);
  async function act(action: () => Promise<Status>) {
    setBusy(true); setError("");
    try { setState(await action()); } catch (reason) { setError(String(reason)); }
    finally { setBusy(false); }
  }
  return <PanelSection title="SyncDeck">
    <PanelSectionRow><ToggleField label="Background sync" checked={state?.enabled ?? false}
      disabled={busy || !state} onChange={value => void act(() => enable(value))}
      description="Allow the lightweight helper in Gaming Mode. Turning this off finishes active transfers and stops the helper." /></PanelSectionRow>
    <PanelSectionRow><div>{error || state?.status || "Connecting…"}</div></PanelSectionRow>
    <PanelSectionRow><div>{state?.online ?? 0} / {state?.devices ?? 0} devices online</div></PanelSectionRow>
    <PanelSectionRow><ButtonItem disabled={busy || !state?.enabled || state.mode === "app" || state.mode === "stopping"}
      onClick={() => void act(sync)}>Sync now</ButtonItem></PanelSectionRow>
    <PanelSectionRow><div>Use the dedicated SyncDeck app for pairing, folders, chat, and managing files.
      {state?.mode === "app" ? " The app currently owns syncing." : ""}</div></PanelSectionRow>
  </PanelSection>;
}
export default definePlugin(() => ({ name: "SyncDeck", titleView: <div className={staticClasses.Title}>SyncDeck</div>,
  content: <Content />, icon: <FaSync /> }));
