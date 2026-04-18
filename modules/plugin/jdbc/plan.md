# SQL Server GEOGRAPHY Support — Implementation Plan

Based on `review.md` findings and user feedback. Tasks are ordered by dependency and priority.

---

## Task 1: Remove all SRID=4326 geography heuristics (P0 — Issues 9, 10, 11)

**Problem**: Three places infer geography from `srid == 4326`, which misclassifies `geometry(4326)` columns and misses non-4326 geography columns.

### 1a. `SQLServerFilterToSQL.isCurrentGeography()` — Remove SRID fallback

**File**: `SQLServerFilterToSQL.java`

Remove the SRID-based fallback. Keep only `JDBC_NATIVE_TYPENAME` check. Default to `false` when unknown.

```java
private boolean isCurrentGeography() {
    if (currentGeometry == null) return false;
    Object nativeType = currentGeometry.getUserData().get(JDBCDataStore.JDBC_NATIVE_TYPENAME);
    return "geography".equals(nativeType);
}
```

This is safe because `JDBC_NATIVE_TYPENAME` is automatically populated by the framework (`JDBCFeatureSource` line 286) from JDBC metadata for all real table columns. SQL Views accessed via `ResultSetMetaData` will also have this populated. The fallback was only needed for a hypothesized scenario where the framework fails to populate it — defaulting to geometry is the safe choice.

### 1b. `SQLServerDialect.isGeography(descriptor, rs, column)` — Remove SRID fallback

**File**: `SQLServerDialect.java`

Keep the `JDBC_NATIVE_TYPENAME` check and `ResultSetMetaData.getColumnTypeName()` fallback (which covers SQL Views). Remove the final SRID-based heuristic.

```java
private boolean isGeography(GeometryDescriptor descriptor, ResultSet rs, String column) throws SQLException {
    Object nativeType =
            descriptor != null ? descriptor.getUserData().get(JDBCDataStore.JDBC_NATIVE_TYPENAME) : null;
    if ("geography".equals(nativeType)) return true;
    if ("geometry".equals(nativeType)) return false;

    // Fallback for SQL Views: check driver reported type name
    try {
        String typeName = rs.getMetaData().getColumnTypeName(rs.findColumn(column));
        if ("geography".equalsIgnoreCase(typeName)) return true;
        if ("geometry".equalsIgnoreCase(typeName)) return false;
    } catch (Exception e) {
        // ignore
    }
    return false;  // default to geometry when unknown
}
```

### 1c. `SQLServerDialect.encodeGeometryValue()` — Revert SRID heuristic, default to `geometry::`

**File**: `SQLServerDialect.java`

Revert the SRID-based heuristic back to the original behavior (always use `geometry::` prefix). This method is only called for INSERT/UPDATE operations, which are out of scope (read-only use case). Filter literals are handled correctly by `SQLServerFilterToSQL.visitLiteralGeometry()` which has proper descriptor context.

Simply restore the original code that always uses `geometry::STGeomFromText(...)`.

**Known limitation (document only)**: INSERT/UPDATE to geography tables will fail because SQL Server does not implicitly convert between geometry and geography types. Supporting this would require a `ThreadLocal` on the dialect plus a `JDBCDataStore` subclass to pass native type context into `encodeGeometryValue`.

---

## Task 2: Fix polygon orientation enforcement — `forceCCW` (P0 — Issue 12)

**File**: `SQLServerFilterToSQL.java`

**Problem**: Current `forceCCW` only checks the first polygon of a MultiPolygon and reverses the whole geometry. Holes are not independently validated.

**Fix**: Normalize each polygon individually. Shell must be CCW, each hole must be CW. Handle `Polygon`, `MultiPolygon`, and `GeometryCollection` containing polygons.

```java
private Geometry forceCCW(Geometry g) {
    if (g == null) return null;
    if (g instanceof Polygon) {
        return normalizePolygon((Polygon) g);
    } else if (g instanceof MultiPolygon mp) {
        Polygon[] polys = new Polygon[mp.getNumGeometries()];
        for (int i = 0; i < mp.getNumGeometries(); i++) {
            polys[i] = normalizePolygon((Polygon) mp.getGeometryN(i));
        }
        return g.getFactory().createMultiPolygon(polys);
    } else if (g instanceof GeometryCollection gc) {
        Geometry[] geoms = new Geometry[gc.getNumGeometries()];
        for (int i = 0; i < gc.getNumGeometries(); i++) {
            geoms[i] = forceCCW(gc.getGeometryN(i));
        }
        return g.getFactory().createGeometryCollection(geoms);
    }
    return g;
}

private Polygon normalizePolygon(Polygon poly) {
    GeometryFactory gf = poly.getFactory();
    LinearRing shell = orientRing(poly.getExteriorRing(), true, gf);   // shell = CCW
    LinearRing[] holes = new LinearRing[poly.getNumInteriorRing()];
    for (int i = 0; i < poly.getNumInteriorRing(); i++) {
        holes[i] = orientRing(poly.getInteriorRingN(i), false, gf);    // holes = CW
    }
    return gf.createPolygon(shell, holes);
}

private LinearRing orientRing(LinearRing ring, boolean ccw, GeometryFactory gf) {
    if (Orientation.isCCW(ring.getCoordinates()) != ccw) {
        return ring.reverse();
    }
    return ring;
}
```

---

## Task 3: Geography spatial index creation — Document only (P1 — Issue 13)

**File**: `SQLServerDialect.java`, method `postCreateTable`

**Problem**: When `bbox == null` AND column is geography, the `continue` statement skips index creation. Geography indexes don't need a bounding box.

**No code change**: `postCreateTable` is DDL (table/index creation), which is out of scope for read-only use. The current behavior is acceptable — geography indexes on existing tables are managed by the DBA.

**Known limitation (document only)**: If GeoTools is used to create tables with geography columns, spatial indexes may not be created when CRS/bbox info is unavailable. Fix would be to restructure the conditional so only geometry columns skip on missing bbox, and geography always creates the index without `BOUNDING_BOX`.

---

## Task 4: Handle unsupported geography spatial operators (P1 — Issue 14)

**File**: `SQLServerFilterToSQL.java`, method `visitBinarySpatialOperator`

**Problem**: `STCrosses` and `STTouches` are not supported on SQL Server geography columns. Currently emitted unconditionally.

**Fix**: Check `isCurrentGeography()` and throw `UnsupportedOperationException` for these operators. No synthetic fallback — the emulations are complex and risky.

```java
if (filter instanceof Crosses) {
    if (isCurrentGeography()) {
        throw new UnsupportedOperationException(
                "SQL Server GEOGRAPHY does not support STCrosses. Use Intersects instead.");
    }
    out.write(".STCrosses(");
} else if (filter instanceof Touches) {
    if (isCurrentGeography()) {
        throw new UnsupportedOperationException(
                "SQL Server GEOGRAPHY does not support STTouches. Use STDistance or Intersects instead.");
    }
    out.write(".STTouches(");
}
```

---

## Task 5: Make envelope encoding reliable via WKB round-trip (P2 — Issue 15)

**File**: `SQLServerDialect.java`, method `encodeGeometryEnvelope`

**Problem**: Currently uses `CAST(col AS GEOMETRY).STEnvelope().ToString()` unconditionally. `CAST(geography AS GEOMETRY)` is not documented by Microsoft and may be unreliable across SQL Server versions.

**Fix**: Replace the undocumented `CAST` with a documented conversion path: convert the column to WKB via `.STAsBinary()` (supported by both geometry and geography), then reconstruct as geometry via `geometry::STGeomFromWKB()`. This works uniformly for both types.

Current SQL:
```sql
CAST(col.STSrid as VARCHAR) + ':' + CAST(col AS GEOMETRY).STEnvelope().ToString()
```

New SQL:
```sql
CAST(col.STSrid as VARCHAR) + ':' + geometry::STGeomFromWKB(col.STAsBinary(), col.STSrid).STEnvelope().ToString()
```

```java
@Override
public void encodeGeometryEnvelope(String tableName, String geometryColumn, StringBuffer sql) {
    sql.append("CAST(");
    encodeColumnName(null, geometryColumn, sql);
    sql.append(".STSrid as VARCHAR)");

    sql.append(" + ':' + ");

    sql.append("geometry::STGeomFromWKB(");
    encodeColumnName(null, geometryColumn, sql);
    sql.append(".STAsBinary(), ");
    encodeColumnName(null, geometryColumn, sql);
    sql.append(".STSrid).STEnvelope().ToString()");
}
```

---

## Task 6: Give geography columns metadata-table parity in `getMapping()` (P3 — Issue 17)

**File**: `SQLServerDialect.java`, method `getMapping`

**Problem**: Geography columns always return `Geometry.class` without checking the geometry metadata table for subtype info.

**Fix**: Look up the metadata table for both `geometry` and `geography` type names.

```java
@Override
public Class<?> getMapping(ResultSet columnMetaData, Connection cx) throws SQLException {
    String typeName = columnMetaData.getString("TYPE_NAME");

    String gType = null;
    if (("geometry".equalsIgnoreCase(typeName) || "geography".equalsIgnoreCase(typeName))
            && geometryMetadataTable != null) {
        gType = lookupGeometryType(columnMetaData, cx, geometryMetadataTable, "f_geometry_column");
    } else if ("geometry".equalsIgnoreCase(typeName) || "geography".equalsIgnoreCase(typeName)) {
        return Geometry.class;
    } else {
        return null;
    }

    if (gType == null) {
        return Geometry.class;
    } else {
        Class geometryClass = TYPE_TO_CLASS_MAP.get(gType.toUpperCase());
        if (geometryClass == null) {
            geometryClass = Geometry.class;
        }
        return geometryClass;
    }
}
```

---

## Task 7: Skip geography in optimized-bounds extraction (P3 — Issue 19)

**File**: `SQLServerDialect.java`, method `getOptimizedBounds`

**Problem**: Geography spatial indexes don't populate `bounding_box_*` columns in `sys.spatial_index_tessellations`, so the query returns nulls. The existing comment at line 1075-1076 acknowledges this.

**Fix**: Skip geography columns in the loop, adding `null` for them. The framework falls back to unoptimized bounds when all entries are null.

```java
.forEach(attributeDescriptor -> {
    // Geography indexes don't store bounding box metadata
    if (isGeography((GeometryDescriptor) attributeDescriptor)) {
        result.add(null);
        return;
    }
    try {
        result.add(getIndexBounds(...));
    } catch (SQLException e) { ... }
});
```

**Future note**: SQL Server geography bounds could theoretically be computed via `SELECT geography::EnvelopeAggregate(col).STAsText() FROM table`, but this requires a full table scan and is not an "optimized" path.

---

## Task 8: Cleanup — rename `readCoordinate` variables (P3 — Readability)

**File**: `SqlServerBinaryReader.java`

**Problem**: Variables named `x` and `y` are misleading — the first double read is X for geometry but Latitude (Y) for geography.

**Fix**:
```java
private Coordinate readCoordinate() throws IOException, ParseException {
    double first = dis.readDouble();
    double second = dis.readDouble();
    if (binary.isGeography()) {
        // Geography: first=Latitude(Y), second=Longitude(X) → JTS Coordinate(X, Y)
        return new Coordinate(second, first);
    } else {
        // Geometry: first=X, second=Y → JTS Coordinate(X, Y)
        return new Coordinate(first, second);
    }
}
```

---

## Task 9: Add clipToWorld TODO comment (P3 — Issue 18)

**File**: `SQLServerFilterToSQL.java`

No functional change. Add a comment noting that the current clipping is adequate for typical GeoServer BBOX filters (small tile rectangles within world bounds), but antimeridian-crossing or pole-adjacent filters may need PostGIS-style 90×90° subdivision in the future.

---

## Task 10: Minimal tests (P2 — Issue 20)

**File**: `SQLServerBinaryReaderTest.java` and/or new test class

Add offline unit tests for:

1. **`forceCCW` regression**: CW shell + CCW hole → corrected to CCW shell + CW hole. Mixed MultiPolygon → each polygon independently corrected.
2. **Geography binary decode for complex types**: geography polygon and linestring binary decode (if test data available).

Do not add:
- Online tests (require live database)
- Factory/datastore wiring tests
- Extensive operator tests

---

## Task 11: DDL — Document only (P3 — Issue 16)

`getGeometryTypeName()` stays returning `"geometry"`. PostGIS also does not support creating geography columns via GeoTools DDL. No code change — out of scope for read-only use.

**Known limitation (document only)**: `CREATE TABLE` via GeoTools will always create geometry columns, never geography. Supporting geography DDL would require `getGeometryTypeName` to be type-aware and `postCreateTable` index creation to handle geography (Task 3).

---

## Execution Order

| Order | Task | Priority | Risk | Dependencies |
|---|---|---|---|---|
| 1 | Task 1a-b: Remove SRID heuristics (FilterToSQL + Dialect decode) | P0 | Low (straightforward removal) | None |
| 2 | Task 1c: Revert encodeGeometryValue to always use `geometry::` | P0 | Low (revert to original) | None |
| 3 | Task 2: Fix forceCCW | P0 | Medium (geometry logic) | None |
| 4 | Task 4: Unsupported operators | P1 | Low | Task 1a (uses isCurrentGeography) |
| 5 | Task 8: readCoordinate rename | P3 | Trivial | None |
| 6 | Task 6: Metadata table parity | P3 | Low | None |
| 7 | Task 7: Skip geography in optimized bounds | P3 | Low | None |
| 8 | Task 5: Envelope encoding via WKB round-trip | P2 | Low | None |
| 9 | Task 9: clipToWorld TODO | P3 | Trivial | None |
| 10 | Task 10: Unit tests | P2 | Low | Tasks 1-4 (tests verify fixes) |
| — | Task 3: Index creation (document only) | P1 | None (no change) | N/A |
| — | Task 11: DDL (document only) | P3 | None (no change) | N/A |

## Out of Scope (Read-Only Use Case)

The following are **documented limitations only** — no code changes needed:
- **Geography INSERT/UPDATE** (Task 1c known limitation): `encodeGeometryValue` always uses `geometry::` prefix. Writing to geography tables would require a `ThreadLocal` + `JDBCDataStore` subclass.
- **Geography spatial index DDL** (Task 3): `postCreateTable` may skip geography index creation when CRS/bbox is unavailable. Indexes on existing tables are managed by DBAs.
- **Geography column DDL** (Task 11): `getGeometryTypeName()` always returns `"geometry"`. Tables created via GeoTools will always use geometry columns.

## Future Work

- **PostGIS-style filter subdivision**: Slice large geography filter polygons into 90×90° quadrants.
