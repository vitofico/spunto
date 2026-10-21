import {
  Component,
  lazy,
  Suspense,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
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
  useSyncState,
  useHosted,
  useWorkspaceIssue,
} from "./lib";
import { useInstanceAppearance } from "./features/Appearance";
import { PluginRailViews, type CommandOrigin } from "./features/Slots";
import Timeline from "./pages/Timeline";
import { Icon } from "./components/core/Icon";
import { NotFound } from "./components/core/NotFound";
import { formatShortcut } from "./lib/platform";
import { useModalFocus } from "./components/core/useModalFocus";
import { ActiveSharedSpace } from "./features/Collaboration";
import { Palette } from "./components/core/Palette";
import {
  FirstRunGuide,
  hasCompletedGuide,
  markGuideCompleted,
} from "./components/core/FirstRunGuide";
const loadNote = () => import("./pages/Note");
// React.lazy keeps a failed import for good, so "Try again" builds fresh ones.
function lazyPages() {
  return {
    SharedNotesPage: lazy(() => import("./pages/SharedNotes")),
    NotePage: lazy(loadNote),
    SearchPage: lazy(() => import("./pages/Search")),
    AuthPage: lazy(() => import("./pages/Auth")),
    SettingsPage: lazy(() =>
      import("./features/Settings").then((m) => ({ default: m.SettingsPage })),
    ),
    ReviewPage: lazy(() =>
      import("./features/Review").then((m) => ({ default: m.ReviewPage })),
    ),
    StatusPage: lazy(() =>
      import("./features/Settings").then((m) => ({ default: m.StatusPage })),
    ),
  };
}
let pages = lazyPages();
const chunkFailure = (error: unknown) =>
  error instanceof Error &&
  /dynamically imported module|module script failed|unable to preload/i.test(
    error.message,
  );
/** One automatic reload per 10 s: a deploy renamed the chunks, a fresh page has the new names. */
function reloadOnce() {
  try {
    const last = Number(sessionStorage.getItem("spunto:chunk-reload") ?? 0);
    if (Date.now() - last < 10000) return false;
    sessionStorage.setItem("spunto:chunk-reload", String(Date.now()));
  } catch {
    return false;
  }
  location.reload();
  return true;
}
export class LoadBoundary extends Component<
  { children: ReactNode; location: string; onRetry: () => void },
  { failed: boolean }
> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  componentDidCatch(error: unknown) {
    if (chunkFailure(error) && navigator.onLine) reloadOnce();
  }
  componentDidMount() {
    window.addEventListener("online", this.retry);
  }
  componentWillUnmount() {
    window.removeEventListener("online", this.retry);
  }
  componentDidUpdate(previous: { location: string }) {
    if (this.state.failed && previous.location !== this.props.location)
      this.setState({ failed: false });
  }
  retry = () => {
    if (!this.state.failed) return;
    this.props.onRetry();
    this.setState({ failed: false });
  };
  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <div className="empty" role="alert">
        <h1>Something didn’t load.</h1>
        <p>Anything you typed is kept on this device.</p>
        <button className="button primary" onClick={this.retry}>
          Try again
        </button>
      </div>
    );
  }
}
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
  onPalette: (trigger: HTMLElement) => void;
  blocked: boolean;
  returnFocus: RefObject<HTMLElement | null>;
}) {
  const [location] = useLocation();
  const hosted = useHosted();
  const labels = useLabels();
  const sync = useSync();
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
          onClick={(event) => {
            close();
            onPalette(event.currentTarget);
          }}
        >
          <span>
            <Icon name="search" /> <span className="rail-label">Search</span>
          </span>
          <kbd>{formatShortcut("mod+k")}</kbd>
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
                    12 + Math.min(label.path.split("/").length - 1, 4) * 12,
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
          <p role="status">{sync.line}</p>
        </div>
      </aside>
      {open && <div className="rail-backdrop" onClick={close} />}
    </>
  );
}
/** What the keys below actually do; `?` and the palette open this list. */
const shortcuts: [string[], string][] = [
  [["n"], "New note"],
  [[formatShortcut("mod+enter")], "Save the note"],
  [[formatShortcut("mod+s")], "Save while editing"],
  [[formatShortcut("mod+k"), "/"], "Search and commands"],
  [["j", "k"], "Next or previous note"],
  [["↵", "o"], "Open the note"],
  [["e"], "Edit the note"],
  [["p"], "Pin or unpin"],
  [["#"], "Add a label (opens the note to edit)"],
  [["y"], "Archive"],
  [[formatShortcut("shift+y")], "Move to trash"],
  [["Esc"], "Close, or go back from a note"],
  [["["], "Collapse the navigation"],
  [["g a"], "Go to All notes"],
  [["g l"], "Go to the labels"],
  [["g t"], "Go to Trash"],
  [["g s"], "Go to Settings"],
  [["?"], "Show these keys"],
];
function decoded(path: string) {
  try {
    return decodeURIComponent(path);
  } catch {
    return "";
  }
}
/** D33 wording on wide screens; one short line on phones. */
function UnencryptedBand() {
  return (
    <div className="banner">
      <span className="banner-long">
        Not encrypted · This server uses plain HTTP. Fine on your own network;
        set up HTTPS before using it elsewhere.
      </span>
      <span className="banner-short">
        Not encrypted · This server uses plain HTTP.
      </span>
    </div>
  );
}
/** j/k land on the card's link to its note, or on the card itself. */
function focusEntry(entry: HTMLElement) {
  const link = entry.querySelector<HTMLElement>('a[href^="/n/"]');
  if (link) return link.focus();
  entry.tabIndex = -1;
  entry.focus();
}
let entryDepth = -1;
let lastLocation = "";
const scrollPositions = new Map<string, number>();
if (typeof history !== "undefined") history.scrollRestoration = "manual";
/** The number this history entry was given, when Back or a reload returns to it. */
function savedDepth() {
  const state: unknown = history.state;
  return state && typeof state === "object" && "spuntoDepth" in state
    ? Number(state.spuntoDepth)
    : NaN;
}
/** Back returns to a list left scrolled down: nothing there takes focus, so it keeps its place. */
const returningScrolled = (location: string) =>
  (scrollPositions.get(savedDepth() + location) ?? 0) > 0;
/**
 * Numbers each history entry (in history.state) so Back can tell whether the
 * previous entry is still this app, and puts each list back where it was left.
 */
function useHistoryEntries(location: string) {
  useLayoutEffect(() => {
    const opened = /^\/n\/([^/]+)$/.exec(lastLocation)?.[1];
    lastLocation = location;
    const state: unknown = history.state;
    const saved = savedDepth();
    const returning = Number.isInteger(saved);
    entryDepth = returning ? saved : entryDepth + 1;
    if (!returning)
      history.replaceState(
        {
          ...(state && typeof state === "object" ? state : {}),
          spuntoDepth: entryDepth,
        },
        "",
      );
    try {
      sessionStorage.setItem("spunto:in-app-nav", entryDepth > 0 ? "1" : "0");
    } catch {}
    const key = entryDepth + location;
    const target = returning ? (scrollPositions.get(key) ?? 0) : 0;
    let frame = 0,
      tries = 0,
      height = 0,
      steady = 0,
      restoring = true;
    // A cached list renders at once, then still grows ("Show more" appears after it is measured)
    // and a list still loading grows later: hold the position until the page height settles.
    const restore = () => {
      window.scrollTo(0, target);
      const now = document.documentElement.scrollHeight;
      steady =
        now === height && Math.abs(window.scrollY - target) <= 1
          ? steady + 1
          : 0;
      height = now;
      if (restoring && target && steady < 3 && tries++ < 60)
        frame = requestAnimationFrame(restore);
      else {
        restoring = false;
        // Back from a note gives focus to its card, without moving the list.
        if (target && opened)
          document
            .querySelector<HTMLElement>(
              `article.entry[data-note-id="${CSS.escape(opened)}"] a.time`,
            )
            ?.focus({ preventScroll: true });
      }
    };
    const stop = () => {
      restoring = false;
      cancelAnimationFrame(frame);
    };
    const remember = () => {
      if (!restoring) scrollPositions.set(key, window.scrollY);
    };
    restore();
    const intents = ["wheel", "touchstart", "keydown", "pointerdown"];
    for (const intent of intents)
      window.addEventListener(intent, stop, { passive: true });
    window.addEventListener("scroll", remember, { passive: true });
    return () => {
      stop();
      for (const intent of intents) window.removeEventListener(intent, stop);
      window.removeEventListener("scroll", remember);
    };
  }, [location]);
}
/** Back to the previous view when it is part of this app, else home. */
function goBack(navigate: (path: string) => void) {
  if (entryDepth > 0) history.back();
  else navigate("/");
}
const notes = (count: number) => `${count} ${count === 1 ? "note" : "notes"}`;
function useSync() {
  const meta = useApiQuery(["meta"], api.meta);
  const { online, waiting } = useSyncState();
  const offline = !online || meta.isError;
  return {
    offline,
    line: meta.isPending
      ? "Connecting…"
      : offline
        ? waiting
          ? `Offline · ${notes(waiting)} waiting`
          : "Offline"
        : waiting
          ? "Saving…"
          : "Synced",
  };
}
export default function App() {
  const auth = useAuth();
  const owner = useOwner();
  const workspaceIssue = useWorkspaceIssue();
  useInstanceAppearance(auth.status === "authenticated");
  const meta = useApiQuery(["meta"], api.meta);
  const sync = useSync();
  const [location, navigate] = useLocation();
  const [, setAttempt] = useState(0);
  function retryPages() {
    pages = lazyPages();
    setAttempt((attempt) => attempt + 1);
  }
  const {
    AuthPage,
    NotePage,
    ReviewPage,
    SearchPage,
    SettingsPage,
    SharedNotesPage,
    StatusPage,
  } = pages;
  const signedIn = auth.status === "authenticated";
  const returning = returningScrolled(location);
  useEffect(() => {
    if (!signedIn) return;
    // Fetch the note screen while idle, so opening a note works offline later.
    const timer = setTimeout(() => void loadNote().catch(() => {}), 2000);
    return () => clearTimeout(timer);
  }, [signedIn]);
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
  function showGuide(trigger: HTMLElement) {
    if (document.querySelector("dialog[open]")) return;
    guideTrigger.current = trigger;
    setPalette(false);
    setHelp(false);
    setRailOpen(false);
    setGuideReplay(true);
  }
  const [commandResult, setCommandResult] = useState<unknown>(null);
  const [commandOrigin, setCommandOrigin] = useState<CommandOrigin>();
  const [instant, setInstant] = useState(false);
  const [palette, setPalette] = useState(false),
    [help, setHelp] = useState(false),
    [collapsed, setCollapsed] = useState(false),
    [railOpen, setRailOpen] = useState(false);
  const overlayTrigger = useRef<HTMLElement>(null);
  const helpDialog = useRef<HTMLElement>(null);
  const modalOpen = palette || help || railOpen;
  useModalFocus(helpDialog, help, () => setHelp(false), overlayTrigger);
  /** A click names its button: WebKit doesn't focus a clicked button. */
  function rememberFocus(trigger = document.activeElement) {
    if (!modalOpen) overlayTrigger.current = trigger as HTMLElement;
  }
  function openSearch(trigger?: HTMLElement) {
    rememberFocus(trigger);
    setInstant(false);
    setRailOpen(false);
    setHelp(false);
    setPalette(true);
  }
  function openHelp() {
    rememberFocus();
    setRailOpen(false);
    setPalette(false);
    setHelp(true);
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
      setInstant(false);
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
  useHistoryEntries(location);
  useEffect(() => {
    let go = false;
    const key = (e: KeyboardEvent) => {
      if (e.defaultPrevented) return;
      const palettePressed =
        (e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k";
      if (guideOpen || document.querySelector("dialog[open]")) {
        if (palettePressed) e.preventDefault();
        return;
      }
      const target = e.target as HTMLElement;
      if (palettePressed) {
        e.preventDefault();
        setInstant(true);
        if (!palette && !help && !railOpen)
          overlayTrigger.current = document.activeElement as HTMLElement;
        setRailOpen(false);
        setHelp(false);
        setPalette((v) => !v);
        return;
      }
      // Single keys never fire while typing, inside a dialog, or with a modifier held.
      if (
        target.closest(
          'input,textarea,select,[contenteditable=true],[role="dialog"]',
        ) ||
        e.metaKey ||
        e.ctrlKey ||
        e.altKey
      )
        return;
      const entry = target.closest<HTMLElement>("#main .entry[data-note-id]");
      const entryAction = (action: string) => {
        if (!entry) return false;
        e.preventDefault();
        entry.dispatchEvent(
          new CustomEvent("spunto:entry-action", { detail: action }),
        );
        return true;
      };
      if (go) {
        go = false;
        if (e.key === "l") {
          e.preventDefault();
          if (matchMedia("(min-width: 1024px)").matches)
            document.querySelector<HTMLElement>(".label-tree a")?.focus();
          else {
            rememberFocus();
            setRailOpen(true);
          }
          return;
        }
        const paths: Record<string, string> = {
          a: "/",
          t: "/trash",
          s: "/settings/appearance",
        };
        if (paths[e.key]) {
          e.preventDefault();
          if (location !== paths[e.key]) navigate(paths[e.key]);
        }
        return;
      }
      if (e.key === "Escape") {
        if (location.startsWith("/n/")) goBack(navigate);
      } else if (e.key === "j" || e.key === "k") {
        const entries = [
          ...document.querySelectorAll<HTMLElement>(
            "#main .entry[data-note-id]",
          ),
        ];
        if (!entries.length) return;
        e.preventDefault();
        const at = entry ? entries.indexOf(entry) : -1;
        const next =
          entries[
            Math.min(
              Math.max(
                at + (e.key === "j" ? 1 : at < 0 ? entries.length : -1),
                0,
              ),
              entries.length - 1,
            )
          ];
        focusEntry(next);
      } else if (e.key === "o" || (e.key === "Enter" && target === entry)) {
        if (!entry) return;
        e.preventDefault();
        navigate("/n/" + entry.dataset.noteId);
      } else if (e.key === "e") {
        if (entryAction("edit")) return;
        const edit = document.querySelector<HTMLElement>(
          "#main button[data-edit-note]",
        );
        if (edit) {
          e.preventDefault();
          edit.click();
        }
      } else if (e.key === "p") entryAction("pin");
      else if (e.key === "#") entryAction("label");
      else if (e.key === "y") entryAction("archive");
      else if (e.key === "Y") entryAction("trash");
      else if (e.key === "n") {
        e.preventDefault();
        if (location !== "/") navigate("/");
        requestAnimationFrame(() =>
          document.getElementById("composer")?.focus(),
        );
      } else if (e.key === "/") {
        e.preventDefault();
        setInstant(true);
        overlayTrigger.current = document.activeElement as HTMLElement;
        setRailOpen(false);
        setHelp(false);
        setPalette(true);
      } else if (e.key === "[") {
        setCollapsed((v) => !v);
      } else if (e.key === "?") {
        e.preventDefault();
        openHelp();
      } else if (e.key === "g") go = true;
    };
    window.addEventListener("keydown", key);
    return () => window.removeEventListener("keydown", key);
  }, [location, navigate, palette, help, railOpen, guideOpen]);
  if (auth.status === "unreachable")
    return (
      <main className="auth">
        <div className="auth-state">
          <span className="wordmark">
            spunto<span>.</span>
          </span>
          <p role="alert">Can’t reach the server.</p>
          <button
            className="button"
            disabled={auth.reconnecting}
            onClick={auth.reconnect}
          >
            {auth.reconnecting ? "Trying…" : "Try again"}
          </button>
        </div>
      </main>
    );
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
      <LoadBoundary location={location} onRetry={retryPages}>
        <Suspense fallback={<main className="auth">Opening your notes…</main>}>
          <AuthPage
            setup={auth.status === "setup" || location === "/setup"}
            band={meta.data?.unencrypted ? <UnencryptedBand /> : null}
          />
        </Suspense>
      </LoadBoundary>
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
          onClick={(event) => {
            rememberFocus(event.currentTarget);
            setRailOpen(true);
          }}
        >
          <Icon name="menu" />
        </button>
        <Link href="/" className="wordmark">
          spunto<span>.</span>
        </Link>
        <button
          className="button"
          aria-label="Search"
          onClick={(event) => openSearch(event.currentTarget)}
        >
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
        {sync.offline && (
          <div className="banner" role="status">
            Can’t reach the server. What you type is kept on this device.
          </div>
        )}
        {meta.data?.unencrypted && <UnencryptedBand />}
        <LoadBoundary location={location} onRetry={retryPages}>
          <Suspense
            fallback={
              <p className="page-status" role="status">
                Opening…
              </p>
            }
          >
            <Switch>
              <Route path="/">
                <Timeline focusComposer={!returning} />
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
                {(params) => {
                  const label = decoded(params["*"] ?? "");
                  return label ? (
                    <Timeline
                      key={label}
                      label={label}
                      focusComposer={!returning}
                    />
                  ) : (
                    <NotFound />
                  );
                }}
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
                <NotFound />
              </Route>
            </Switch>
          </Suspense>
        </LoadBoundary>
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
          onHelp={openHelp}
          instant={instant}
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
            <dl className="keys">
              {shortcuts.map(([keys, action]) => (
                <div key={action}>
                  <dt>
                    {keys.map((key, index) => (
                      <span key={key}>
                        {index > 0 && " or "}
                        <kbd>{key}</kbd>
                      </span>
                    ))}
                  </dt>
                  <dd>{action}</dd>
                </div>
              ))}
            </dl>
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
