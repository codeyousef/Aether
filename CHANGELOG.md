# Changelog

## [0.3.5.0] - 2026-01-03
### Fixed
- **Vert.x body reading**: Simplified body reading to use `body().coAwait()` with error logging
  - Previous handler/endHandler approach didn't work correctly
  - Added error logging for debugging body read failures

## [0.3.4.0] - 2026-01-03
### Fixed
- **Vert.x body reading**: Fixed request body not being read properly in some environments (e.g., Cloud Run)
  - Changed from `vertxRequest.body().coAwait()` to using `handler`/`endHandler` pattern
  - Ensures body stream is properly resumed and collected before processing
  - Fixes forms returning empty data on POST requests

## [0.3.3.0] - 2026-01-03
### Added
- **ModelAdmin enhancements**: New configuration options for form customization
  - `multilineFields`: List of field names that should render as textarea instead of single-line input
  - `excludeFields`: List of field names to hide from forms (values still saved via defaults)
  - `defaultValues`: Map of field names to default values for new objects
- **ModelForm defaults**: Constructor now accepts `defaultValues` parameter for pre-filling forms

### Changed
- Form rendering now respects `multilineFields` and `excludeFields` from ModelAdmin
- Default values are automatically merged when submitting new objects

## [0.3.2.0] - 2026-01-03
### Added
- **Modern Admin UI**: Complete redesign of the admin interface with CMS-style look
  - `AdminTheme`: Design tokens (colors, spacing, typography, shadows) with Tailwind-inspired slate/blue palette
  - `AdminComponents`: Reusable UI building blocks (cards, tables, forms, filters, badges)
  - Dashboard with stats cards, quick actions, and content overview
  - Improved list views with search toolbar, filter sidebar, and action buttons
  - Modern form pages with proper layout and validation styling
  - Styled delete confirmation pages
  - Responsive sidebar navigation with SVG icons
  - Inter font and smooth transitions

### Changed
- `Form.allFields()`: New public method to access form fields (replaces protected access)
- `Form.getFieldError()`: New method to get single field error message

## [0.3.1.4] - 2025-12-28
### Fixed
- Admin site URLs no longer duplicate the admin prefix (e.g., `/admin/admin/services` → `/admin/services`)

## [0.3.1.3] - 2025-12-27
### Fixed
- Redirect now defers `response.end()` so SessionMiddleware can set cookies before headers are finalized
- Vert.x server finalizes responses after middleware completes to ensure Set-Cookie is emitted on redirects

## [0.3.1.2] - 2025-12-27
### Fixed
- Publish workflow now includes `aether-auth`, `aether-forms`, and `aether-admin` modules

## [0.3.1.1] - 2025-12-26
### Fixed
- AdminSite routing issue where dashboard handler was not properly registered for root path

## [0.3.1.0] - 2025-12-26
### Added
- **First Maven Central publish** for `aether-auth`, `aether-forms`, and `aether-admin` modules

## [0.3.0.0] - 2025-12-26
### Added
- **Admin Module** (`aether-admin`): Django-like admin interface for managing database models
  - `AdminSite`: Central registry for model admin configuration with auto-generated routes
  - `ModelAdmin`: Customizable list display, search fields, filters, and display links
  - `ModelForm`: Auto-generated forms from Model columns with CRUD operations
  - Bootstrap-styled responsive UI
- **Auth Module** (`aether-auth`): Authentication and authorization system
  - `User` model with password hashing and session management
  - `AuthMiddleware` for protected routes
  - `Permission` system for fine-grained access control
- **Forms Module** (`aether-forms`): Form handling and validation
  - Form field types with automatic HTML rendering
  - Server-side validation with error messages
  - CSRF protection integration
- **Security Pipeline**: Added `installCsrfProtection()` and `installSecurityHeaders()` middleware
- **Debug Toolbar**: Added `installDebugToolbar()` for development mode debugging

### Changed
- Upgraded to Kotlin 2.2.20

## [0.2.0.0] - 2025-12-23
### Added
- **Session Management**: Added `SessionMiddleware` and `Exchange.session()` / `Exchange.requireSession()` for managing user sessions.
- **JSON DSL**: Added reified inline `Exchange.respondJson(statusCode, data)` for simplified JSON responses.
- **Form Parsing**: Added `Exchange.receiveParameters()` (JVM) to handle `application/x-www-form-urlencoded` requests.
- **Path Parameters**: Added `Exchange.pathParamOrThrow()` for safer parameter extraction.

## [0.1.0] - 2025-12-22
### Added
- Initial release of Aether Framework.
