# Changelog

## [0.2.0.0] - 2025-12-23
### Added
- **Session Management**: Added `SessionMiddleware` and `Exchange.session()` / `Exchange.requireSession()` for managing user sessions.
- **JSON DSL**: Added reified inline `Exchange.respondJson(statusCode, data)` for simplified JSON responses.
- **Form Parsing**: Added `Exchange.receiveParameters()` (JVM) to handle `application/x-www-form-urlencoded` requests.
- **Path Parameters**: Added `Exchange.pathParamOrThrow()` for safer parameter extraction.

## [0.1.0] - 2025-12-22
### Added
- Initial release of Aether Framework.
