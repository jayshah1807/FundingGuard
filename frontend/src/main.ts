import { bootstrapApplication } from "@angular/platform-browser";
import { Component, HostListener, OnInit } from "@angular/core";
import { CommonModule } from "@angular/common";
import { FormsModule } from "@angular/forms";
import {
  LucideAngularModule,
  ShieldCheck,
  LayoutDashboard,
  ArrowUpRight,
  ArrowDownLeft,
  ListChecks,
  Search,
  Bell,
  ChevronDown,
  ChevronRight,
  ArrowLeft,
  Plus,
  Download,
  SlidersHorizontal,
  CircleCheck,
  CircleAlert,
  Clock3,
  LockKeyhole,
  Activity,
  FileClock,
  Landmark,
  Users,
  Settings2,
  X,
  MoreHorizontal,
  ArrowRight,
  Check,
  LogOut,
  RefreshCw,
  ExternalLink,
  FileText,
  Fingerprint,
  ShieldAlert,
  CheckCheck,
  CircleHelp,
  PanelLeftClose,
  Menu,
  Copy,
  Eye,
  Send,
  CircleDot,
} from "lucide-angular";

@Component({
  selector: "app-root",
  standalone: true,
  imports: [CommonModule, FormsModule, LucideAngularModule],
  templateUrl: "./app.html",
})
export class AppComponent implements OnInit {
  icons = {
    ShieldCheck,
    LayoutDashboard,
    ArrowUpRight,
    ArrowDownLeft,
    ListChecks,
    Search,
    Bell,
    ChevronDown,
    ChevronRight,
    ArrowLeft,
    Plus,
    Download,
    SlidersHorizontal,
    CircleCheck,
    CircleAlert,
    Clock3,
    LockKeyhole,
    Activity,
    FileClock,
    Landmark,
    Users,
    Settings2,
    X,
    MoreHorizontal,
    ArrowRight,
    Check,
    LogOut,
    RefreshCw,
    ExternalLink,
    FileText,
    Fingerprint,
    ShieldAlert,
    CheckCheck,
    CircleHelp,
    PanelLeftClose,
    Menu,
    Copy,
    Eye,
    Send,
    CircleDot,
  };
  nav = [
    { id: "overview", label: "Overview", icon: LayoutDashboard },
    { id: "payouts", label: "Payout queue", icon: ListChecks },
    { id: "investigations", label: "Investigations", icon: ShieldAlert },
    { id: "automation", label: "Security automation", icon: Activity },
    { id: "ledger", label: "Release ledger", icon: Landmark },
    { id: "audit", label: "Audit trail", icon: FileClock },
    { id: "contacts", label: "Trusted contacts", icon: Users },
  ];
  accounts = [
    {
      email: "operations@demo.fundingguard.local",
      name: "Jay Shah",
      role: "Operations",
      initials: "JS",
    },
    {
      email: "verifier@demo.fundingguard.local",
      name: "Noah Singh",
      role: "Verification officer",
      initials: "NS",
    },
    {
      email: "approver@demo.fundingguard.local",
      name: "Maya Chen",
      role: "Payment approver",
      initials: "MC",
    },
    {
      email: "security@demo.fundingguard.local",
      name: "Elena Brooks",
      role: "Security analyst",
      initials: "EB",
    },
    {
      email: "auditor@demo.fundingguard.local",
      name: "Owen Reed",
      role: "Auditor",
      initials: "OR",
    },
  ];
  user: any = null;
  data: any = {
    payouts: [],
    cases: [],
    ledger: [],
    audit: [],
    contacts: [],
    notifications: [],
  };
  detail: any = null;
  view = "overview";
  selectedId = "";
  query = "";
  filter = "All payouts";
  loading = true;
  busy = false;
  error = "";
  toast = "";
  modal = "";
  notices = false;
  accountMenu = false;
  mobileNav = false;
  detailTab = "instructions";
  email = "operations@demo.fundingguard.local";
  password = "";
  csrf = "";
  loginError = "";
  actionReason = "";
  actionAmount = "";
  actionAccount = "";
  confirmation = false;
  releaseKey = "";
  confirmationType = "receipt";
  selectedCase = "";
  automation: any = { events: [], runs: [], scenarios: [] };
  automationLoading = false;
  runDetail: any = null;
  automationTab = "runs";
  signal: any = { payoutId: "", kind: "LOGIN_ANOMALY", detail: "" };
  signalKey = "";
  async loadAutomation() {
    this.automationLoading = true;
    try { this.automation = await this.api("/automation"); }
    catch (e: any) { this.error = e.message; }
    finally { this.automationLoading = false; }
  }
  get signalPayouts() { return this.data.payouts.filter((p: any) => !["RELEASED_SIMULATED", "CLOSED", "CANCELLED"].includes(p.state)); }
  get automationRows() {
    return this.automation[this.automationTab].filter((r: any) =>
      JSON.stringify(r).toLowerCase().includes(this.query.toLowerCase()));
  }
  async openRun(id: string) {
    try { this.runDetail = await this.api("/automation/runs/" + id); }
    catch (e: any) { this.error = e.message; }
  }
  async replayScenario(scenario: string) {
    this.busy = true; this.error = "";
    try {
      const r = await this.api("/automation/replay", "POST", { scenario });
      await this.refresh();
      this.automationTab = "scenarios";
      this.notify(r.passed ? "Scenario passed. Evidence and outcome recorded." : "Scenario result differed from expectation.");
    } catch (e: any) { this.error = e.message; }
    finally { this.busy = false; }
  }
  async ingestSignal() {
    const payout = this.data.payouts.find((p: any) => p.id === this.signal.payoutId);
    if (!payout) { this.error = "Select a payout."; return; }
    this.busy = true; this.error = "";
    this.signalKey ||= crypto.randomUUID();
    try {
      await this.api("/automation/events", "POST", { ...this.signal, version: payout.version, eventKey: this.signalKey });
      this.signalKey = ""; this.signal.detail = "";
      await this.refresh(); this.automationTab = "events";
      this.notify("Signal recorded and correlation evaluated.");
    } catch (e: any) { this.error = e.message; }
    finally { this.busy = false; }
  }
  form: any = {
    borrower: "",
    property: "",
    loanReference: "",
    contactId: "ct-1",
    amount: "",
    account: "",
    deadline: new Date().toISOString().slice(0, 10),
  };
  async ngOnInit() {
    await this.token();
    try {
      this.user = await this.api("/me");
      await this.refresh();
    } catch {}
    this.loading = false;
    this.readHash();
  }
  @HostListener("window:hashchange") readHash() {
    const h = location.hash.slice(1);
    if (h.startsWith("payout/")) {
      this.selectedId = h.slice(7);
      if (this.user) this.loadDetail(this.selectedId);
    } else if ([...this.nav.map((n) => n.id), "controls"].includes(h)) {
      this.view = h;
      this.detail = null;
      this.selectedId = "";
      if (h === "automation" && this.user) this.loadAutomation();
    }
  }
  @HostListener("document:keydown.escape") escape() {
    if (!this.busy) this.modal = "";
    this.notices = false;
    this.accountMenu = false;
    this.mobileNav = false;
  }
  @HostListener("document:keydown.tab", ["$event"]) trapFocus(
    event: KeyboardEvent,
  ) {
    if (!this.modal) return;
    const nodes = Array.from(
      document.querySelectorAll<HTMLElement>(
        ".modal button:not(:disabled), .modal input:not(:disabled), .modal select:not(:disabled), .modal textarea:not(:disabled)",
      ),
    );
    const first = nodes[0],
      last = nodes[nodes.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last?.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first?.focus();
    }
  }
  async token() {
    const r = await fetch("/api/csrf", { credentials: "same-origin" });
    const j = await r.json();
    this.csrf = j.token;
  }
  async api(
    path: string,
    method = "GET",
    body?: any,
    key?: string,
  ): Promise<any> {
    const headers: any = { "X-CSRF-TOKEN": this.csrf };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (key) headers["Idempotency-Key"] = key;
    const r = await fetch("/api" + path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: "same-origin",
    });
    if (!r.ok) {
      let j: any = {};
      try {
        j = await r.json();
      } catch {}
      if (r.status === 401) this.user = null;
      throw new Error(
        j.message || `Request could not be completed (${r.status}).`,
      );
    }
    return r.status === 204 ? null : r.json();
  }
  async login() {
    this.busy = true;
    this.loginError = "";
    try {
      await this.token();
      const r = await fetch("/api/login", {
        method: "POST",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded",
          "X-CSRF-TOKEN": this.csrf,
        },
        body: new URLSearchParams({
          username: this.email,
          password: this.password,
        }),
      });
      if (!r.ok)
        throw new Error("Check your email and password, then try again.");
      await this.token();
      this.user = await this.api("/me");
      this.modal = "";
      await this.refresh();
      this.readHash();
    } catch (e: any) {
      this.loginError = e.message;
    } finally {
      this.busy = false;
    }
  }
  async logout() {
    try {
      await this.api("/logout", "POST");
    } finally {
      this.user = null;
      this.data = {
        payouts: [],
        cases: [],
        ledger: [],
        audit: [],
        contacts: [],
        notifications: [],
      };
      this.detail = null;
      this.automation = { events: [], runs: [], scenarios: [] };
      this.runDetail = null;
      this.accountMenu = false;
      await this.token();
    }
  }
  async switchAccount(email: string) {
    if (this.isPrototype) {
      if (this.busy) return;
      this.busy = true;
      try {
        this.user = await this.api("/prototype/role", "POST", {role: email.split("@")[0].toUpperCase()});
        await this.token();
        this.accountMenu = false;
        await this.refresh();
      } catch (e: any) { this.error = e.message; }
      finally { this.busy = false; }
      return;
    }
    await this.logout();
    this.email = email;
    this.loginError = "";
  }
  get isPrototype() { return this.user?.tenant?.startsWith("prototype-") === true; }
  async explorePrototype() {
    this.busy = true;
    this.loginError = "";
    try {
      await this.token();
      this.user = await this.api("/prototype/start", "POST");
      await this.token();
      this.selectedId = "";
      this.detail = null;
      this.modal = "";
      this.navigate("overview");
      await this.refresh();
    } catch (e: any) { this.loginError = e.message; }
    finally { this.busy = false; }
  }
  async refresh() {
    this.error = "";
    try {
      this.data = await this.api("/workspace");
      this.user = this.data.user;
      if (this.isPrototype && !this.data.contacts.some((c: any) => c.id === this.form.contactId)) this.form.contactId = this.data.contacts[0]?.id || "";
      if (this.selectedId) await this.loadDetail(this.selectedId);
      if (this.view === "automation") await this.loadAutomation();
    } catch (e: any) {
      this.error = e.message;
    }
  }
  async loadDetail(id: string) {
    try {
      this.detail = await this.api("/payouts/" + id);
      this.detail.payout.blockers = this.detail.blockers;
      this.selectedId = id;
      this.error = "";
    } catch (e: any) {
      this.error = e.message;
    }
  }
  navigate(view: string) {
    this.view = view;
    this.detail = null;
    this.selectedId = "";
    this.query = "";
    this.filter = "All payouts";
    this.mobileNav = false;
    location.hash = view;
    if (view === "automation") this.loadAutomation();
    window.scrollTo(0, 0);
  }
  openPayout(id: string) {
    this.selectedId = id;
    this.detailTab = "instructions";
    location.hash = "payout/" + id;
    this.loadDetail(id);
    window.scrollTo(0, 0);
  }
  get activeCount() {
    return this.data.payouts.filter(
      (p: any) => !["RELEASED_SIMULATED", "CLOSED"].includes(p.state),
    ).length;
  }
  get total() {
    return this.data.payouts
      .filter((p: any) => p.state !== "RELEASED_SIMULATED")
      .reduce((s: number, p: any) => s + p.amount_minor, 0);
  }
  get ready() {
    return this.data.payouts.filter((p: any) => p.blockers.length === 0).length;
  }
  get holds() {
    return this.data.payouts.filter((p: any) => p.hold_active).length;
  }
  get openCases() {
    return this.data.cases.filter((c: any) => c.state === "OPEN").length;
  }
  get filtered() {
    return this.data.payouts.filter(
      (p: any) =>
        (this.filter === "All payouts" || this.status(p) === this.filter) &&
        [p.id, p.borrower, p.lender, p.property]
          .join(" ")
          .toLowerCase()
          .includes(this.query.toLowerCase()),
    );
  }
  get auditRows() {
    return this.data.audit.filter((a: any) =>
      [a.action, a.actor_name, a.payout_id, a.detail]
        .join(" ")
        .toLowerCase()
        .includes(this.query.toLowerCase()),
    );
  }
  get caseRows() {
    return this.data.cases.filter((a: any) =>
      [a.id, a.title, a.borrower, a.state]
        .join(" ")
        .toLowerCase()
        .includes(this.query.toLowerCase()),
    );
  }
  get ledgerRows() {
    return this.data.ledger.filter((a: any) =>
      [a.id, a.borrower, a.payout_id]
        .join(" ")
        .toLowerCase()
        .includes(this.query.toLowerCase()),
    );
  }
  status(p: any) {
    if (p.state === "RELEASED_SIMULATED") return "Released";
    if (p.hold_active) return "On hold";
    if (p.blockers?.length === 0) return "Ready to release";
    if (
      p.verified_version === p.version &&
      p.verified_at &&
      Date.now() - new Date(p.verified_at).getTime() < 86400000
    )
      return "Awaiting approval";
    return "Needs verification";
  }
  tone(p: any) {
    return (
      (
        {
          Released: "neutral",
          "On hold": "danger",
          "Ready to release": "success",
          "Awaiting approval": "info",
          "Needs verification": "warning",
        } as any
      )[this.status(p)] || "neutral"
    );
  }
  money(cents: number) {
    return new Intl.NumberFormat("en-CA", {
      style: "currency",
      currency: "CAD",
      maximumFractionDigits: 2,
      minimumFractionDigits: 2,
    }).format(cents / 100);
  }
  compactMoney(cents: number) {
    return new Intl.NumberFormat("en-CA", {
      style: "currency",
      currency: "CAD",
      notation: "compact",
      maximumFractionDigits: 2,
    }).format(cents / 100);
  }
  initials(s: string) {
    return (s || "")
      .split(" ")
      .filter(Boolean)
      .slice(0, 2)
      .map((x) => x[0])
      .join("");
  }
  nice(s: string) {
    return (s || "")
      .toLowerCase()
      .replaceAll("_", " ")
      .replace(/^./, (c) => c.toUpperCase());
  }
  date(s: string) {
    return new Date(s.length === 10 ? s + "T12:00:00" : s).toLocaleDateString(
      "en-CA",
      { month: "short", day: "numeric" },
    );
  }
  time(s: string) {
    return new Date(s).toLocaleTimeString("en-CA", {
      hour: "2-digit",
      minute: "2-digit",
    });
  }
  ago(s: string) {
    const n = Math.max(
      0,
      Math.floor((Date.now() - new Date(s).getTime()) / 60000),
    );
    return n < 1
      ? "Just now"
      : n < 60
        ? `${n}m ago`
        : n < 1440
          ? `${Math.floor(n / 60)}h ago`
          : this.date(s);
  }
  get today() {
    return new Date().toLocaleDateString("en-CA", {
      weekday: "long",
      month: "long",
      day: "numeric",
      year: "numeric",
    });
  }
  notify(message: string) {
    this.toast = message;
    setTimeout(() => (this.toast = ""), 6000);
  }
  openModal(kind: string) {
    this.modal = kind;
    this.actionReason = "";
    this.actionAmount = this.detail
      ? (this.detail.payout.amount_minor / 100).toFixed(2)
      : "";
    this.actionAccount = "";
    this.confirmation = false;
    this.error = "";
    if (kind === "release") this.releaseKey = crypto.randomUUID();
    setTimeout(
      () =>
        document
          .querySelector<HTMLInputElement>(
            ".modal input:not([type=checkbox]), .modal textarea",
          )
          ?.focus(),
      30,
    );
  }
  get modalTitle() {
    return (
      (
        {
          create: "New payout obligation",
          revise: "Revise payout instructions",
          verify: "Record independent verification",
          approve: "Approve current instructions",
          release: "Release simulated payout",
          hold: "Place a security hold",
          clear:
            this.user?.role === "SECURITY"
              ? "Propose hold clearance"
              : "Countersign hold clearance",
          confirm: "Record " + this.confirmationType + " evidence",
          resolve: "Resolve investigation",
        } as any
      )[this.modal] || "Review"
    );
  }
  cents(value: string) {
    if (!/^\d+(\.\d{1,2})?$/.test(value))
      throw new Error("Enter an amount with no more than two decimal places.");
    const [whole, frac = ""] = value.split(".");
    const n = Number(whole) * 100 + Number(frac.padEnd(2, "0"));
    if (!Number.isSafeInteger(n) || n < 1 || n > 100000000)
      throw new Error("Amount must be between CAD 0.01 and CAD 1,000,000.00.");
    return n;
  }
  async submit() {
    this.busy = true;
    this.error = "";
    try {
      if (this.modal === "create") {
        const result = await this.api("/payouts", "POST", {
          borrower: this.form.borrower,
          property: this.form.property,
          loanReference: this.form.loanReference,
          contactId: this.form.contactId,
          amountMinor: this.cents(this.form.amount),
          account: this.form.account,
          deadline: this.form.deadline,
        });
        this.modal = "";
        await this.refresh();
        this.openPayout(result.id);
        this.notify("Payout created. Independent verification is required.");
        return;
      }
      if (this.modal === "resolve") {
        await this.api("/cases/" + this.selectedCase + "/resolve", "POST", {
          reason: this.actionReason,
        });
        this.modal = "";
        await this.refresh();
        this.notify("Investigation resolved. Active holds remain in place.");
        return;
      }
      const action = this.modal;
      const body: any = {
        version: this.detail.payout.version,
        reason: this.actionReason,
      };
      if (action === "revise") {
        body.amountMinor = this.cents(this.actionAmount);
        body.account = this.actionAccount;
      }
      if (action === "confirm") body.type = this.confirmationType;
      if (["approve", "release"].includes(action) && !this.confirmation)
        throw new Error("Confirm you have reviewed the current instructions.");
      const result = await this.api(
        "/payouts/" + this.selectedId + "/" + action,
        "POST",
        body,
        action === "release" ? this.releaseKey : undefined,
      );
      this.modal = "";
      await this.refresh();
      this.notify(
        action === "release"
          ? `Simulated release recorded: ${result.ledgerId}`
          : "Saved. The audit trail has been updated.",
      );
    } catch (e: any) {
      this.error =
        e.message +
        (this.modal === "release"
          ? " If the connection was interrupted, retry here with the same request key."
          : "");
    } finally {
      this.busy = false;
    }
  }
  async exportAudit() {
    try {
      const r = await fetch("/api/audit/export");
      if (!r.ok) throw new Error("Export unavailable. Sign in and try again.");
      const url = URL.createObjectURL(await r.blob());
      const a = document.createElement("a");
      a.href = url;
      a.download = "fundingguard-audit.csv";
      a.click();
      URL.revokeObjectURL(url);
      this.notify("Masked audit export downloaded.");
    } catch (e: any) {
      this.notify(e.message);
    }
  }
  queueFilter(value: string) {
    this.filter = value;
  }
  eventIcon(action: string) {
    return action.includes("HOLD")
      ? this.icons.ShieldAlert
      : action.includes("APPROV") || action.includes("VERIF")
        ? this.icons.CircleCheck
        : action.includes("RELEASE")
          ? this.icons.ArrowUpRight
          : this.icons.FileText;
  }
}
bootstrapApplication(AppComponent).catch(console.error);
