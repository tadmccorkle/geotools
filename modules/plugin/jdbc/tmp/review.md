# SQL Server GEOGRAPHY Support — Review

## Summary

The commit `1f0a299c` adds initial GEOGRAPHY data type support across `SQLServerDialect`, `SQLServerFilterToSQL`, `SqlServerBinaryReader`, and `SqlServerBinary`. Core reading, filtering, and some writing paths have been modified. Unit tests pass, and limited manual testing has validated basic SQL View scenarios.

The implementation has **correct coordinate handling in the binary reader and WKT encoding**, but has **several significant issues** that need to be resolved before this is production-ready.

---

## Key Differences: SQL Server GEOMETRY vs GEOGRAPHY

| Aspect | GEOMETRY | GEOGRAPHY |
|---|---|---|
| Coordinate semantics | Planar (X, Y) | Geodetic (Latitude, Longitude) |
| Binary serialization order | X first, Y second | Latitude (Y) first, Longitude (X) second |
| WKT coordinate order | (X Y) | (Lon Lat) — standard WKT order |
| Default SRID | 0 | 4326 (but supports others) |
| STEnvelope() | ✅ Supported | ❌ Not supported |
| STContains/STWithin | ✅ | ✅ (modern SQL Server) |
| STCrosses | ✅ | ❌ Not supported on geography |
| STTouches | ✅ | ❌ Not supported on geography |
| Spatial index BOUNDING_BOX | Required | Not allowed |
| Polygon orientation | No constraint | Must follow left-hand rule (CCW exterior) |
| STDistance units | CRS units | Always meters |
| CAST to other type | N/A | `CAST(geo AS GEOMETRY)` — works but undocumented |

## Key Differences: PostGIS GEOGRAPHY vs SQL Server GEOGRAPHY

| Aspect | PostGIS | SQL Server |
|---|---|---|
| Type detection | `JDBC_NATIVE_TYPENAME` only | `JDBC_NATIVE_TYPENAME` + result set metadata |
| Literal constructor | `ST_GeogFromText(wkt)` | `geography::STGeomFromText(wkt, srid)` |
| SRID in constructor | Omitted (implicit 4326) | Required |
| BBOX filter | Disables `&&` operator for geography | Uses `.Filter()` for both types |
| Large geometry handling | Slices into 90×90° quadrants | Clips to world envelope only |
| Orientation enforcement | Not explicitly needed (PostGIS handles it) | Required — must enforce CCW exterior in Java |
| Distance units | Meters for geography (via `DistanceBufferUtil`) | Meters for geography (via `DistanceBufferUtil`) |
| JDBC_NATIVE_TYPENAME | Populated by framework (`JDBCFeatureSource` L286) | Same — automatically populated from `column.typeName` |

---

## Findings

### ✅ Correct

1. **`readCoordinate()` coordinate swap** — Correct per MS-SSCLRT spec. Geometry binary stores (X, Y); geography stores (Lat, Lon). The swap produces proper JTS (X, Y) = (Lon, Lat) output for geography. However, variable names (`x`/`y`) are misleading — should be renamed to `first`/`second`.

2. **WKT encoding for geography literals** — JTS `toText()` outputs `(X Y)` = `(Lon Lat)`, which matches what `geography::STGeomFromText()` expects. No coordinate swap needed on the write path.

3. **`JDBC_NATIVE_TYPENAME` is automatically populated** — Confirmed via `JDBCFeatureSource.java` line 286: `ab.addUserData(JDBCDataStore.JDBC_NATIVE_TYPENAME, column.typeName)`. The framework populates this from JDBC metadata for all columns. This means the SRID-based fallback heuristics should rarely be needed.

4. **`getGeometrySRID()`** — `.STSrid` works for both types. No issue.

5. **`getGeometryDimension()`** — `.STPointN(1).Z` works for both types. No issue.

6. **`encodeGeometryColumn()`** — No special handling needed. Native serialization and `.STAsBinary()` work for both types.

7. **Distance conversion for DWithin/Beyond** — Correctly uses `DistanceBufferUtil.getDistanceInMeters()` for geography, matching the PostGIS approach.

8. **Geography spatial index without BOUNDING_BOX** — The `postCreateTable` method correctly omits `WITH (BOUNDING_BOX = ...)` for geography indexes.

### 🔴 Bugs

9. **`encodeGeometryValue()` uses SRID=4326 to infer geography** — This is the most severe bug. The method uses `if (srid == 4326) { prefix = "geography"; }`. This is fundamentally wrong:
   - SRID 4326 is commonly used with `geometry` columns
   - Geography columns can use SRIDs other than 4326
   - This will cause `geometry(4326)` columns to receive `geography::STGeomFromText(...)` calls, which will fail or produce wrong results
   - **Fix**: This method does not receive a `GeometryDescriptor`. Either plumb descriptor context through, or default to `"geometry"` and let the filter/prepared-statement path handle geography correctly.

10. **`isCurrentGeography()` SRID=4326 fallback in FilterToSQL** — Same problem. Falls back to `srid == 4326` when `JDBC_NATIVE_TYPENAME` is absent. This can misclassify geometry(4326) columns, causing wrong literal prefixes, unnecessary CCW enforcement, and incorrect meter conversion.
    - **Fix**: Remove the SRID fallback. Default to `false` (treat as geometry) when type is unknown.

11. **`isGeography(descriptor, rs, column)` SRID=4326 fallback in Dialect** — Same problem in the decode path. Misclassifying a geometry(4326) column as geography causes coordinate swap in the binary reader, corrupting every decoded geometry.
    - **Fix**: Remove the SRID fallback. The `ResultSetMetaData.getColumnTypeName()` fallback is correct and sufficient for SQL Views.

12. **`forceCCW()` for MultiPolygon is incomplete** — Only checks the first polygon's exterior ring, then reverses the *entire* MultiPolygon. Issues:
    - If polygon 0 is CCW but polygon 1 is CW, no fix applied
    - If polygon 0 is CW but polygon 1 is CCW, reversing both breaks polygon 1
    - Holes are not independently validated (geography expects CW holes)
    - **Fix**: Normalize each polygon individually — shell must be CCW, each hole must be CW.

13. **Geography spatial index skipped when `bbox == null`** — In `postCreateTable`, when `bbox == null` AND the column is geography, the `continue` statement is still reached. Geography indexes don't need a bounding box, so index creation should proceed regardless.
    - Current flow: `if (bbox == null || isGeography(gd)) { if (bbox == null) { continue; } }` — this means geography + no CRS → skip index, which is wrong.
    - **Fix**: Restructure: skip only if geometry AND bbox is null. Always allow geography index creation.

14. **Unsupported geography spatial operators emitted unconditionally** — `SQLServerFilterToSQL` emits `STCrosses()` and `STTouches()` for geography columns, but SQL Server GEOGRAPHY does not support these methods. This will produce SQL errors at runtime.
    - **Fix**: Check `isCurrentGeography()` before emitting these operators. Either throw a clear exception or fall back to a supported alternative.

### ⚠️ Issues / Gaps

15. **`encodeGeometryEnvelope()` uses `CAST(col AS GEOMETRY).STEnvelope()`** — Applied unconditionally for both types. For geometry columns, `CAST(geometry AS GEOMETRY)` is a no-op so it works, but it's unnecessary overhead. The `CAST(geography AS GEOMETRY)` conversion is functional but not officially documented by Microsoft, making it fragile.

16. **`getGeometryTypeName()` always returns `"geometry"`** — Schema creation (`CREATE TABLE`) cannot create geography columns. This means GEOGRAPHY support is read/query-only; DDL is incomplete.

17. **`getMapping()` returns `Geometry.class` for all geography columns** — Subtype metadata (Point, Polygon, etc.) is not resolved for geography, unlike geometry which uses the metadata table. This loses schema fidelity.

18. **`clipToWorld()` is simplistic** — Unlike PostGIS's helper which slices large geometries into 90×90° quadrants to avoid shortest-arc problems, the SQL Server version only clips to the world envelope. While SQL Server may be less susceptible to shortest-arc issues than PostGIS, large filter polygons near the antimeridian or poles could still produce unexpected results.

19. **`getOptimizedBounds()` explicitly notes geography is unsupported** — There's a comment at line 1075-1076 acknowledging this gap. The `getIndexBounds` query relies on `bounding_box_xmin` etc. from `sys.spatial_index_tessellations`, which geography indexes don't populate.

20. **Test coverage is minimal** — Only one geography-specific unit test (`testGeographyPoint`). Missing tests for: geometry(4326) not being treated as geography, geography decode of complex types (polygon, linestring, multipolygon), geography with non-4326 SRID, unsupported operator handling, spatial index creation, and multipolygon orientation normalization.

---

## Priority Ranking

| Priority | Issue # | Description |
|---|---|---|
| P0 — Blocker | 9, 10, 11 | SRID=4326 heuristic misclassifies geometry as geography (and vice versa) |
| P0 — Blocker | 12 | forceCCW only checks first polygon, reverses entire multi |
| P1 — High | 13 | Geography index creation skipped when no CRS/bbox |
| P1 — High | 14 | Unsupported geography operators (STCrosses, STTouches) emitted |
| P2 — Medium | 15 | CAST-based envelope encoding fragility |
| P2 — Medium | 20 | Insufficient test coverage |
| P3 — Low | 16, 17 | DDL and subtype mapping incomplete |
| P3 — Low | 18, 19 | clipToWorld simplistic; optimized bounds unsupported |
