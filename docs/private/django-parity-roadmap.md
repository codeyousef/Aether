# Aether: The "KMP Django" Roadmap

This document outlines the sequential feature list required to elevate Aether from a micro-framework to a full-featured "Batteries-Included" framework comparable to Django.

## Phase 1: The Data Layer (ORM Upgrade)
*Objective: Make database interactions expressive and powerful.*

1.  **Fluent Query API (`QuerySet`)**
    *   [x] Implement a `QuerySet`-like builder pattern.
    *   [x] Support chaining: `.filter(...)`, `.exclude(...)`, `.orderBy(...)`, `.limit(...)`.
    *   [x] Support field lookups: `age.gt(18)`, `name.startsWith("A")`.
    *   [x] Support aggregations: `count()`, `sum()`, `avg()`.

2.  **Model Relationships**
    *   [x] **ForeignKey**: Define one-to-many relationships. Auto-fetch related objects.
    *   [x] **OneToOne**: Define one-to-one relationships.
    *   [x] **ManyToMany**: Define many-to-many relationships with automatic join table management.
    *   [x] **Reverse Lookups**: Access related objects from the other side (e.g., `user.posts`).

3.  **Auto-Migrations**
    *   [x] **Schema Introspection**: Ability to read the current database schema.
    *   [x] **State Detection**: Compare `Model` definitions in code vs. actual DB schema.
    *   [x] **Migration Generator**: Auto-generate `up()` and `down()` SQL/Kotlin migration files.

## Phase 2: The "Contrib" System (Standard Modules)
*Objective: Provide standard features out-of-the-box.*

4.  **Authentication (`aether-auth`)**
    *   [x] **User Model**: Standard extensible `User` model.
    *   [x] **Session Management**: Database-backed or Redis-backed sessions.
    *   [x] **Auth Middleware**: Attach `user` to `Exchange`.
    *   [x] **Views**: Login, Logout, Password Reset, Password Change. (Basic Login/Logout implemented)
    *   [x] **Permissions**: Group-based and Object-level permissions.

5.  **Forms & Validation (`aether-forms`)**
    *   [x] **Schema Definition**: Define expected data structure and validation rules.
    *   [x] **Data Binding**: Bind Request (JSON/Form) to Objects.
    *   [x] **Validation**: Field-level and Cross-field validation.
    *   [x] **Error Handling**: Standardized error reporting.

6.  **Admin Interface (`aether-admin`)**
    *   [x] **Model Registration**: `admin.register(User)`.
    *   [x] **Auto-CRUD**: Generate List, Create, Update, Delete views automatically using `Summon`.
    *   [x] **Filters & Search**: Auto-generate UI filters based on model fields.

## Phase 3: Security & Middleware
*Objective: Secure by default.*

7.  **Security Hardening**
    *   [x] **CSRF Protection**: Middleware to prevent Cross-Site Request Forgery.
    *   [x] **Security Headers**: HSTS, X-Frame-Options, X-Content-Type-Options.
    *   [x] **Password Hashing**: Integrate robust hashing (Argon2/BCrypt).

## Phase 4: Tooling & DX
*Objective: Make development fast and easy.*

8.  **CLI Enhancements (`aether-cli`)**
    *   [x] `startproject` / `startapp`: Scaffolding.
    *   [x] `makemigrations` / `migrate`: Database management.
    *   [ ] `shell`: Interactive REPL with context loaded.
    *   [x] `inspectdb`: Generate Models from existing DB.

9.  **Debug Toolbar**
    *   [ ] UI overlay showing SQL queries, headers, and timing.

---

## Implementation Log

*   [x] **Completed**: Phase 2 - Forms & Validation (aether-forms)
*   [x] **Completed**: Phase 2 - Admin Interface Auto-CRUD (aether-admin)
*   [x] **Completed**: Phase 4 - CLI Migrations (aether-cli)
*   [x] **Completed**: Phase 2 - Admin Interface Filters & Search (aether-admin)
*   [x] **Completed**: Phase 3 - CSRF Protection (aether-core/middleware)
*   [x] **Completed**: Phase 3 - Security Headers (aether-core/middleware)
*   [x] **Completed**: Phase 3 - Password Hashing (aether-auth)
*   [x] **Completed**: Phase 4 - CLI Scaffolding (aether-cli)
*   [x] **Completed**: Phase 4 - InspectDB (aether-cli)
*   [ ] **Current Task**: Phase 4 - Debug Toolbar (aether-core/ui)

