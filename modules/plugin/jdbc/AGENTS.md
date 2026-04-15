# AGENTS.md - Geotools JDBC Plugins

This project contains several database-specific JDBC plugin modules for GeoTools.

## Project Overview
The JDBC modules extend the core `gt-jdbc` (found in `modules/library/jdbc`) library to provide support for various spatial databases.

This directory contains several database-specific JDBC plugin modules:
- `jdbc-db2`, `jdbc-hana`, `jdbc-informix`, `jdbc-mysql`, `jdbc-oracle`, `jdbc-postgis`, `jdbc-sqlserver`.

Each module typically follows this pattern:
- **`*DataStoreFactory`**: Entry point for the plugin, defines connection parameters.
- **`*Dialect`**: Extends `BasicSQLDialect` or `PreparedStatementSQLDialect`. Handles type mapping, SQL generation, and geometry encoding/decoding.
- **`*FilterToSQL`**: Extends `FilterToSQL`. Translates GeoTools `Filter` objects into database-specific SQL.

`jdbc-postgis` already has support for the PostGIS equivalent of SQL Server's `GEOGRAPHY`. While the data types may differ, the `jdbc-postgis` extension is an example of where logic may need to diverge between `GEOMETRY` and `GEOGRAPHY` data types.

### Active Module: `jdbc-sqlserver`
We are currently focusing on enhancing `jdbc-sqlserver` to support the `GEOGRAPHY` data type.
Refer to `./notes.md` for the detailed implementation plan and status.

## Development & Testing
- Maven is available in the path (e.g., executing `mvn [some command]` will work). Refer to the following for notes on building and testing this project with Maven:
  - Build: https://docs.geotools.org/latest/userguide/build/maven/build.html
  - Test: https://docs.geotools.org/latest/userguide/build/maven/testing.html
  - Build and Test Tips: https://docs.geotools.org/latest/userguide/build/maven/tips.html
- Common commands (reference links above for more):
  - **Build**: `mvn clean install -DskipTests` in the root or a specific module.
  - **Tests**:
    - **Unit Tests**: Run with `mvn test`.
    - **Online Tests**: Many modules have `OnlineTest` classes (e.g., `SQLServerGeographyOnlineTest`) that require a live database. These are usually skipped unless specific system properties are provided (see Geotools documentation on Online Testing).
- Be humble and honest. NEVER overstate what you got done or what actually works.

## Reference docs
- `./notes.md`: Current task context for SQL Server GEOGRAPHY support.
