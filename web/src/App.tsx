import {
  lazy,
  Suspense,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type RefObject,
} from "react";
import { Link, Route, Switch, useLocation } from "wouter";
import { useLocationProperty } from "wouter/use-browser-location";
import {
  useAuth,
  useLabels,
  useApiQuery,
  api,
  useLiveChanges,
  useOwner,
  useHosted,
  useWorkspaceIssue,
} from "./lib";
import { useInstanceAppearance } from "./features/Appearance";
import { PluginRailViews, type CommandOrigin } from "./features/Slots";
import Timeline from "./pages/Timeline";
import { Icon } from "./components/core/Icon";
import { useModalFocus } from "./components/core/useModalFocus";
import { ActiveSharedSpace } from "./features/Collaboration";
import { Palette } from "./components/core/Palette";
import {
  FirstRunGuide,
  hasCompletedGuide,
  markGuideCompleted,
} from "./components/core/FirstRunGuide";
const SharedNotesPage = lazy(() => import("./pages/SharedNotes"));
const NotePage = lazy(() => import("./pages/Note"));
const SearchPage = lazy(() => import("./pages/Search"));
const AuthPage = lazy(() => import("./pages/Auth"));
const SettingsPage = lazy(() =>
  import("./features/Settings").then((m) => ({ default: m.SettingsPage })),
);
const ReviewPage = lazy(() =>
  import("./features/Review").then((m) => ({ default: m.ReviewPage })),
);
const StatusPage = lazy(() =>
  import("./features/Settings").then((m) => ({ default: m.StatusPage })),
);
function Rail({
  collapsed,
  open,
  close,
  onPalette,
  blocked,
  returnFocus,
}: {
  collapsed: boolean;
  open: boolean;
  close: () => void;
  onPalette: () => void;
  blocked: boolean;
  returnFocus: RefObject<HTMLElement | null>;
}) {
  const [location] = useLocation();
  const hosted = useHosted();
  const labels = useLabels();
  const meta = useApiQuery(["meta"], api.meta);
  const rail = useRef<HTMLElement>(null);
  useModalFocus(rail, open, close, returnFocus);
  return (
    <>
      <aside
        ref={rail}
        className={"rail" + (open ? " open" : "")}
        aria-label="Navigation"
        role={open ? "dialog" : undefined}
        aria-modal={open || undefined}
        inert={blocked}
      >
        <div className="rail-heading">
          <Link
            href="/"
            className="wordmark"
            aria-label="Spunto home"
            onClick={close}
          >
            <span className="wordmark-text">spunto</span>
            <span>.</span>
          </Link>
          <button
            className="button rail-close"
            aria-label="Close navigation"
            data-modal-initial
            onClick={close}
          >
            <Icon name="close" />
          </button>
        </div>
        <button
          className="row rail-search"
          aria-label="Search and commands"
          onClick={() => {
            close();
            onPalette();
          }}
        >
          <span>
            <Icon name="search" /> <span className="rail-label">Search</span>
          </span>
          <kbd>⌘ K</kbd>
        </button>
        {hosted && <ActiveSharedSpace />}
        <nav>
          <Link
            href="/"
            aria-label="All notes"
            onClick={close}
            aria-current={location === "/" ? "page" : undefined}
          >
            <Icon name="notes" />
            <span className="rail-label">All notes</span>
          </Link>
          <Link
            href="/archive"
            aria-label="Archive"
            onClick={close}
            aria-current={location === "/archive" ? "page" : undefined}
          >
            <Icon name="archive" />
            <span className="rail-label">Archive</span>
          </Link>
          <Link
            href="/trash"
            aria-label="Trash"
            onClick={close}
            aria-current={location === "/trash" ? "page" : undefined}
          >
            <Icon name="trash" />
            <span className="rail-label">Trash</span>
          </Link>
        </nav>
        {hosted && (
          <nav>
            <Link
              href="/shared"
              onClick={close}
              aria-current={location.startsWith("/shared") ? "page" : undefined}
            >
              Shared with me
            </Link>
          </nav>
        )}
        {!collapsed && <PluginRailViews />}
        <div className="label-tree">
          <div className="overline">Labels</div>
          <nav>
            {labels.data?.labels.map((label) => (
              <Link
                key={label.path}
                href={"/l/" + label.path}
                onClick={close}
                aria-current={
                  location === "/l/" + label.path ? "page" : undefined
                }
                style={{
                  paddingLeft:
                    10 + Math.min(label.path.split("/").length - 1, 4) * 12,
                }}
              >
                <span>#</span>
                <span>{label.path.split("/").at(-1)}</span>
                <span className="count">{label.count}</span>
              </Link>
            ))}
            {labels.data?.labels.length === 0 && (
              <p className="muted">Labels appear as you type them.</p>
            )}
            {labels.isPending && (
              <p className="muted" role="status">
                Loading labels…
              </p>
            )}
            {labels.error && (
              <p className="error" role="alert">
                Could not load labels.
              </p>
            )}
          </nav>
        </div>
        <div className="rail-foot">
          <nav>
            <Link
              href="/settings/appearance"
              aria-label="Settings"
              aria-current={
                location.startsWith("/settings") ? "page" : undefined
              }
              onClick={close}
            >
              <Icon name="tune" />
              <span className="rail-label">Settings</span>
            </Link>
          </nav>
          <p role="status">
            {meta.isError
              ? "Offline"
              : meta.isPending
                ? "Connecting…"
                : "Synced"}
          </p>
        </div>
      </aside>
      {open && <div className="rail-backdrop" onClick={close} />}
    </>
  );
}
export default function App() {
  const auth = useAuth();
  const owner = useOwner();
  const workspaceIssue = useWorkspaceIssue();
  useInstanceAppearance(auth.status === "authenticated");
  const meta = useApiQuery(["meta"], api.meta);
  const [location, navigate] = useLocation();
  const entrySuffix = useLocationProperty(
    () => window.location.search + window.location.hash,
  );
  const [guideCompleted, setGuideCompleted] = useState(hasCompletedGuide);
  const [guideReplay, setGuideReplay] = useState(false);
  const guideTrigger = useRef<HTMLElement>(null);
  const automaticGuide =
    !guideCompleted &&
    auth.status === "authenticated" &&
    !!owner &&
    !workspaceIssue &&
    location === "/" &&
    !entrySuffix;
  const guideOpen =
    auth.status === "authenticated" &&
    !!owner &&
    (automaticGuide || guideReplay);
  function closeGuide(startWriting: boolean) {
    markGuideCompleted();
    setGuideCompleted(true);
    setGuideReplay(false);
    if (startWriting) navigate("/");
    else if (guideReplay)
      requestAnimationFrame(() => guideTrigger.current?.focus());
  }
  function showGuide() {
    if (document.querySelector("dialog[open]")) return;
    guideTrigger.current = document.activeElement as HTMLElement;
    setPalette(false);
    setHelp(false);
    setRailOpen(false);
    setGuideReplay(true);
  }
  const [commandResult, setCommandResult] = useState<unknown>(null);
  const [commandOrigin, setCommandOrigin] = useState<CommandOrigin>();
  const [palette, setPalette] = useState(false),
    [help, setHelp] = useState(false),
    [collapsed, setCollapsed] = useState(false),
    [railOpen, setRailOpen] = useState(false);
  const overlayTrigger = useRef<HTMLElement>(null);
  const helpDialog = useRef<HTMLElement>(null);
  const modalOpen = palette || help || railOpen;
  useModalFocus(helpDialog, help, () => setHelp(false), overlayTrigger);
  function rememberFocus() {
    if (!modalOpen)
      overlayTrigger.current = document.activeElement as HTMLElement;
  }
  function openSearch() {
    rememberFocus();
    setRailOpen(false);
    setHelp(false);
    setPalette(true);
  }
  useEffect(() => {
    if (!modalOpen) return;
    const previous = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = previous;
    };
  }, [modalOpen]);
  useEffect(() => {
    const expanded = window.matchMedia("(min-width: 1024px)");
    const close = () => {
      if (expanded.matches) setRailOpen(false);
    };
    expanded.addEventListener("change", close);
    return () => expanded.removeEventListener("change", close);
  }, []);
  useLiveChanges(auth.status === "authenticated" && !guideOpen);
  useLayoutEffect(() => {
    setCommandResult(null);
    setCommandOrigin(undefined);
    setPalette(false);
    setHelp(false);
    setRailOpen(false);
    setGuideReplay(false);
  }, [owner]);
  useEffect(() => {
    const handler = (event: Event) => {
      if (guideOpen) return;
      const detail = (event as CustomEvent).detail;
      setCommandResult(detail?.origin ? detail.value : detail);
      setCommandOrigin(detail?.origin);
      overlayTrigger.current = document.activeElement as HTMLElement;
      setRailOpen(false);
      setHelp(false);
      setPalette(true);
    };
    window.addEventListener("spunto:command-result", handler);
    return () => window.removeEventListener("spunto:command-result", handler);
  }, [guideOpen]);
  useEffect(() => {
    if (
      auth.status === "authenticated" &&
      (location === "/login" || location === "/setup")
    )
      navigate("/");
  }, [auth.status, location, navigate]);
  useEffect(() => {
    let go = false;
    const key = (e: KeyboardEvent) => {
      if (e.defaultPrevented) return;
      if (guideOpen) {
        if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k")
          e.preventDefault();
        return;
      }
      if (document.querySelector("dialog[open]")) {
        if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k")
          e.preventDefault();
        return;
      }
      const target = e.target as HTMLElement;
      const typing = target.closest(
        "input,textarea,select,[contenteditable=true]",
      );
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        if (!palette && !help && !railOpen)
          overlayTrigger.current = document.activeElement as HTMLElement;
        setRailOpen(false);
        setHelp(false);
        setPalette((v) => !v);
        return;
      }
      if (target.closest('[role="dialog"]')) return;
      if (typing) return;
      if (e.key === "Escape") {
        setPalette(false);
        setHelp(false);
        setRailOpen(false);
        if (location.startsWith("/n/")) navigate("/");
      } else if (e.key === "n") {
        e.preventDefault();
        navigate("/");
        requestAnimationFrame(() =>
          document.getElementById("composer")?.focus(),
        );
      } else if (e.key === "/") {
        e.preventDefault();
        overlayTrigger.current = document.activeElement as HTMLElement;
        setRailOpen(false);
        setHelp(false);
        setPalette(true);
      } else if (e.key === "[") {
        setCollapsed((v) => !v);
      } else if (e.key === "?") {
        overlayTrigger.current = document.activeElement as HTMLElement;
        setRailOpen(false);
        setPalette(false);
        setHelp(true);
      } else if (go) {
        go = false;
        const paths: Record<string, string> = {
          a: "/",
          l: "/",
          t: "/trash",
          s: "/settings/appearance",
        };
        if (paths[e.key]) navigate(paths[e.key]);
      } else if (e.key === "g") go = true;
    };
    window.addEventListener("keydown", key);
    return () => window.removeEventListener("keydown", key);
  }, [location, navigate, palette, help, railOpen, guideOpen]);
  if (auth.status === "loading" || (auth.status === "authenticated" && !owner))
    return (
      <main className="auth">
        <span className="wordmark">
          spunto<span>.</span>
        </span>
      </main>
    );
  if (auth.status !== "authenticated")
    return (
      <Suspense fallback={<main className="auth">Opening your notes…</main>}>
        <AuthPage setup={auth.status === "setup" || location === "/setup"} />
      </Suspense>
    );
  const app = (
    <div
      key={owner}
      hidden={guideReplay}
      className={"app" + (collapsed ? " collapsed" : "")}
    >
      <a href="#main" className="skip-link" inert={modalOpen}>
        Skip to notes
      </a>
      <Rail
        collapsed={collapsed}
        open={railOpen}
        close={() => setRailOpen(false)}
        onPalette={openSearch}
        blocked={palette || help}
        returnFocus={overlayTrigger}
      />
      <header className="topbar" inert={modalOpen}>
        <button
          className="button"
          aria-label="Open navigation"
          aria-expanded={railOpen}
          onClick={() => {
            rememberFocus();
            setRailOpen(true);
          }}
        >
          <Icon name="menu" />
        </button>
        <Link href="/" className="wordmark">
          spunto<span>.</span>
        </Link>
        <button className="button" aria-label="Search" onClick={openSearch}>
          <Icon name="search" />
        </button>
      </header>
      <main
        id="main"
        tabIndex={-1}
        inert={modalOpen}
        className={
          "column" +
          (location.startsWith("/n/") ? " note-column" : "") +
          (location.startsWith("/settings") ? " wide-column" : "")
        }
      >
        {workspaceIssue && (
          <p className="banner" role="alert">
            {workspaceIssue}
          </p>
        )}
        {meta.isError && (
          <div className="banner" role="status">
            Can’t reach the server. What you type is kept on this device.
          </div>
        )}
        {meta.data?.unencrypted && (
          <div className="banner">
            Not encrypted · This server uses plain HTTP. Fine on your own
            network; set up HTTPS before using it elsewhere.
          </div>
        )}
        <Suspense
          fallback={
            <p className="page-status" role="status">
              Opening…
            </p>
          }
        >
          <Switch>
            <Route path="/">
              <Timeline />
            </Route>
            <Route path="/n/:id">
              {(params) => <NotePage id={params.id} />}
            </Route>
            <Route path="/shared/n/:id">
              {(params) => <SharedNotesPage id={params.id} />}
            </Route>
            <Route path="/shared">
              <SharedNotesPage />
            </Route>
            <Route path="/search">
              <SearchPage />
            </Route>
            <Route path="/archive">
              <Timeline view="archive" />
            </Route>
            <Route path="/trash">
              <Timeline view="trash" />
            </Route>
            <Route path="/l/*">
              {(params) => (
                <Timeline
                  key={params["*"]}
                  label={decodeURIComponent(params["*"] ?? "")}
                />
              )}
            </Route>
            <Route path="/v/:plugin/:view">
              {(params) => (
                <Timeline
                  key={params.plugin + "/" + params.view}
                  view={params.plugin + "/" + params.view}
                />
              )}
            </Route>
            <Route path="/review">
              <ReviewPage />
            </Route>
            <Route path="/settings/*">
              {(params) => (
                <SettingsPage section={params["*"]} onShowGuide={showGuide} />
              )}
            </Route>
            <Route path="/settings">
              <SettingsPage section="appearance" onShowGuide={showGuide} />
            </Route>
            <Route path="/status">
              <StatusPage />
            </Route>
            <Route>
              <p>
                This page isn’t here. <Link href="/">Go to your notes.</Link>
              </p>
            </Route>
          </Switch>
        </Suspense>
      </main>
      {palette && (
        <Palette
          initialResult={commandResult}
          initialOrigin={commandOrigin}
          restoreFocus={overlayTrigger.current}
          onClose={() => {
            setPalette(false);
            setCommandResult(null);
          }}
          noteId={location.startsWith("/n/") ? location.slice(3) : undefined}
        />
      )}{" "}
      {help && (
        <div
          className="overlay"
          onClick={(event) => {
            if (event.target === event.currentTarget) setHelp(false);
          }}
        >
          <section
            ref={helpDialog}
            className="palette card"
            role="dialog"
            aria-modal="true"
            aria-label="Keyboard shortcuts"
            onKeyDown={(event) => {
              if (event.key === "Escape") {
                event.preventDefault();
                setHelp(false);
              }
            }}
          >
            <h2 className="page-title">A few keys, a little faster.</h2>
            <p>
              n · New note
              <br />⌘ / Ctrl Enter · Save
              <br />⌘ / Ctrl S · Save open note
              <br />⌘ / Ctrl K or / · Search and commands
              <br />[ · Collapse rail
              <br />
              Esc · Close or go back
            </p>
            <button
              className="button primary"
              autoFocus
              onClick={() => setHelp(false)}
            >
              Close
            </button>
          </section>
        </div>
      )}
    </div>
  );
  return (
    <>
      {guideOpen && (
        <FirstRunGuide
          onSkip={() => closeGuide(false)}
          onFinish={() => closeGuide(true)}
        />
      )}
      {!automaticGuide && app}
    </>
  );
}
