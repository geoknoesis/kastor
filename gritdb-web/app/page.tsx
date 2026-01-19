import Link from "next/link";

export default function HomePage() {
  return (
    <main className="page">
      <div className="background-glow" aria-hidden="true" />

      <header className="nav">
        <div className="logo">Grit DB</div>
        <nav className="nav-links">
          <a href="#platform">Platform</a>
          <a href="#use-cases">Use cases</a>
          <a href="#plans">Pricing</a>
          <a href="#security">Security</a>
          <Link href="/login">Login</Link>
          <Link className="button" href="/register">
            Start free
          </Link>
        </nav>
      </header>

      <section className="hero">
        <div className="hero-content">
          <p className="eyebrow">Grit DB for SaaS teams</p>
          <h1>The versioned RDF graph database</h1>
          <p className="lead">
            Grit DB fuses Git-style versioning with RDF graphs so every change
            is diffable, auditable, and reversible. Ship knowledge systems with
            branching, provenance, and semantic integrity built in.
          </p>
          <div className="hero-actions">
            <Link className="button" href="/register">
              Start free
            </Link>
            <Link className="button secondary" href="/login">
              Book a demo
            </Link>
          </div>
          <div className="hero-metrics">
            <div>
              <strong>Versioned</strong>
              <span>Commits, branches, merges</span>
            </div>
            <div>
              <strong>Traceable</strong>
              <span>Diffs and provenance</span>
            </div>
            <div>
              <strong>Semantic</strong>
              <span>RDF + SHACL validation</span>
            </div>
          </div>
        </div>
        <div className="hero-panel">
          <div className="panel-card">
            <p className="label">Git + RDF Graph</p>
            <h3>Every change is a commit</h3>
            <p className="muted">
              Track named graphs over time with commit history, diffs, and
              reproducible snapshots.
            </p>
          </div>
          <div className="panel-card subtle">
            <p className="label">Branch-aware workflows</p>
            <h3>Promote changes safely</h3>
            <p className="muted">
              Isolate experiments, review diffs, and merge RDF updates with
              confidence.
            </p>
          </div>
        </div>
      </section>

      <section id="platform" className="section">
        <div className="section-heading">
          <h2>Platform capabilities</h2>
          <p className="muted">
            Versioned graphs, provenance, and semantic constraints for
            production-grade knowledge systems.
          </p>
        </div>
        <div className="grid">
          <div className="card">
            <h3>Git-style graph history</h3>
            <p>Commits, branches, merges, and tags for RDF datasets.</p>
          </div>
          <div className="card">
            <h3>Diffs and snapshots</h3>
            <p>Compare versions and reproduce any graph state instantly.</p>
          </div>
          <div className="card">
            <h3>Provenance tracking</h3>
            <p>Keep lineage for every triple and change set.</p>
          </div>
          <div className="card">
            <h3>Constraint enforcement</h3>
            <p>Validate commits with SHACL rules before merge.</p>
          </div>
        </div>
      </section>

      <section id="use-cases" className="section band">
        <div className="section-heading">
          <h2>Designed for modern SaaS use cases</h2>
          <p className="muted">
            Ideal when your product needs versioned knowledge and auditability.
          </p>
        </div>
        <div className="grid">
          <div className="card">
            <h3>Knowledge publishing</h3>
            <p>Release curated graph versions to customers and partners.</p>
          </div>
          <div className="card">
            <h3>Regulated workflows</h3>
            <p>Show who changed what, when, and why for every triple.</p>
          </div>
          <div className="card">
            <h3>Semantic CI/CD</h3>
            <p>Branch, validate, and merge graph updates like code.</p>
          </div>
          <div className="card">
            <h3>Model evolution</h3>
            <p>Version ontologies and schema changes without downtime.</p>
          </div>
        </div>
      </section>

      <section id="security" className="section">
        <div className="section-heading">
          <h2>Governance you can audit</h2>
          <p className="muted">
            Provenance and access controls designed for regulated data.
          </p>
        </div>
        <div className="security-grid">
          <div className="security-card">
            <h3>Immutable history</h3>
            <p>Every change recorded with diffs and lineage metadata.</p>
          </div>
          <div className="security-card">
            <h3>Policy gates</h3>
            <p>Block merges that violate SHACL or governance rules.</p>
          </div>
          <div className="security-card">
            <h3>Access control</h3>
            <p>Role-based permissions at dataset, graph, and branch level.</p>
          </div>
          <div className="security-card">
            <h3>Compliance reporting</h3>
            <p>Generate evidence reports from commit history and snapshots.</p>
          </div>
        </div>
      </section>

      <section id="plans" className="section plans">
        <div className="section-heading">
          <h2>Pricing built for versioned graphs</h2>
          <p className="muted">Start free, then scale governance and history.</p>
        </div>
        <div className="grid">
          <div className="card plan">
            <h3>Starter</h3>
            <p className="price">$0</p>
            <ul>
              <li>Community support</li>
              <li>1 workspace</li>
              <li>Basic versioning</li>
              <li>Shared infrastructure</li>
            </ul>
            <Link className="button" href="/register">
              Create free account
            </Link>
          </div>
          <div className="card plan featured">
            <h3>Growth</h3>
            <p className="price">$79 / mo</p>
            <ul>
              <li>Unlimited workspaces</li>
              <li>Branch workflows</li>
              <li>Priority support</li>
              <li>Private environments</li>
            </ul>
            <Link className="button" href="/register">
              Start Growth
            </Link>
          </div>
          <div className="card plan">
            <h3>Enterprise</h3>
            <p className="price">Let&apos;s talk</p>
            <ul>
              <li>Dedicated cluster</li>
              <li>Custom SLAs</li>
              <li>Compliance controls</li>
              <li>Dedicated success team</li>
            </ul>
            <Link className="button" href="/register">
              Contact sales
            </Link>
          </div>
        </div>
      </section>

      <section className="cta">
        <div>
          <h2>Ship knowledge changes with confidence</h2>
          <p className="muted">
            Versioned RDF graphs keep your product consistent and auditable.
          </p>
        </div>
        <div className="hero-actions">
          <Link className="button" href="/register">
            Register
          </Link>
          <Link className="button secondary" href="/login">
            Talk to sales
          </Link>
        </div>
      </section>

      <footer className="footer">
        <div>
          <strong>Grit DB</strong>
          <p className="muted small">The semantic core for modern SaaS.</p>
        </div>
        <div className="footer-links">
          <span>Docs</span>
          <span>Security</span>
          <span>Status</span>
          <span>Careers</span>
        </div>
      </footer>
    </main>
  );
}

