# Changelog

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
