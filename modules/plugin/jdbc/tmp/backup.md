# SQL Server GEOGRAPHY Support — Implementation Plan

Based on `review.md` findings and user feedback. Scoped to **read-only access** via GeoServer SQL Views.

## Scope

This plan targets **read-only** geography support: reading data, decoding binary, and encoding spatial filters. INSERT/UPDATE/CREATE TABLE for geography columns is out of scope and documented as a known limitation under Future Work.

---

## Task 1: Remove SRID=4326 geography heuristics (P0 — Issues 9, 10, 11)

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

### 1c. `SQLServerDialect.encodeGeometryValue()` — Revert to `geometry::` default

**File**: `SQLServerDialect.java`

**Read-only note**: This method is called by the framework for INSERT/UPDATE SQL generation only. It is **NOT** called during spatial filter encoding — `SQLServerFilterToSQL.visitLiteralGeometry()` handles filter literals independently with its own geography-aware prefix logic.

**Change**: Remove the SRID heuristic and restore the original `geometry::` prefix. This is correct for all geometry columns and is a no-op for the read-only use case.

**Known limitation (write path)**: INSERT/UPDATE of feature data to geography tables will use `geometry::STGeomFromText(...)`, which SQL Server will reject since it does not implicitly convert between geometry and geography types. This is consistent with PostGIS behavior, which also always uses `ST_GeomFromText` regardless of column type.

```java
@Override
public void encodeGeometryValue(Geometry value, int dimension, int srid, StringBuffer sql) throws IOException {
    if (value == null) {
        sql.append("NULL");
        return;
    }

    GeometryDimensionFinder finder = new GeometryDimensionFinder();
    value.apply(finder);
    WKTWriter writer = new WKTWriter2(finder.hasZ() ? 3 : 2);
    String wkt = writer.write(value);
    sql.append("geometry::STGeomFromText('")
            .append(wkt)
            .append("',")
            .append(srid)
            .append(")");
}
```

---

## Task 2: Fix polygon orientation enforcement — `forceCCW` (P0 — Issue 12)

**File**: `SQLServerFilterToSQL.java`

**Read-only relevance**: This is called by `visitLiteralGeometry()` when encoding geography filter polygons (e.g., BBOX and Intersects filters from GeoServer's tile renderer).

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

## Task 3: Handle unsupported geography spatial operators (P1 — Issue 14)

**File**: `SQLServerFilterToSQL.java`, method `visitBinarySpatialOperator`

**Read-only relevance**: Directly affects spatial filter encoding for geography columns.

**Problem**: `STCrosses` and `STTouches` are not supported on SQL Server geography columns. Currently emitted unconditionally.

**Fix**: Check `isCurrentGeography()` and throw `UnsupportedOperationException` for these operators.

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

## Task 4: Cleanup — rename `readCoordinate` variables (P3 — Readability)

**File**: `SqlServerBinaryReader.java`

**Read-only relevance**: This is core binary decode logic for reading geography values.

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

## Task 5: Minimal tests (P2 — Issue 20)

**File**: `SQLServerBinaryReaderTest.java` and/or new test class

Add offline unit tests for the read-path fixes only:

1. **`forceCCW` regression**: CW shell + CCW hole → corrected to CCW shell + CW hole. Mixed MultiPolygon → each polygon independently corrected.
2. **Geography binary decode for complex types**: geography polygon and linestring binary decode (if test data available).

---

## Execution Order

| Order | Task | Priority | Scope | Dependencies |
|---|---|---|---|---|
| 1 | Task 1a-c: Remove SRID heuristics | P0 | Read + Filter | None |
| 2 | Task 2: Fix forceCCW | P0 | Filter | None |
| 3 | Task 3: Unsupported operators | P1 | Filter | Task 1a (uses isCurrentGeography) |
| 4 | Task 4: readCoordinate rename | P3 | Read | None |
| 5 | Task 5: Unit tests | P2 | Tests | Tasks 1-3 |

---

## Deferred — Write/DDL Only

These tasks from `review.md` are **only relevant for write/DDL operations** and are out of scope for this read-only use case:

| Review Issue | Task | Reason deferred |
|---|---|---|
| 13 | Fix `postCreateTable` spatial index creation | DDL — only used when creating tables via GeoTools |
| 16 | DDL `getGeometryTypeName()` | DDL — only used when creating tables via GeoTools |
| 9 (write) | `encodeGeometryValue` geography prefix | Write — only used for INSERT/UPDATE SQL generation |

## Deferred — Optional / Low Priority

These tasks are relevant to read-only but are non-critical or already acceptable as-is:

| Review Issue | Task | Reason deferred |
|---|---|---|
| 15 | Envelope encoding type-awareness | `CAST(col AS GEOMETRY).STEnvelope()` works for both types; the CAST is a no-op for geometry |
| 17 | Metadata table parity for geography subtype | Schema fidelity only — does not affect read/filter correctness |
| 18 | clipToWorld sophistication | Current clipping handles GeoServer tile BBOX filters; antimeridian/pole edge cases deferred |
| 19 | Skip geography in optimized bounds | `getOptimizedBounds()` already returns null for SQL Views (virtual tables) |

---

## Future Work

- **Geography INSERT/UPDATE support**: Add `ThreadLocal<String>` to dialect + `SQLServerJDBCDataStore` subclass + factory rewiring to pass native type context into `encodeGeometryValue`.
- **Geography DDL support**: Fix `getGeometryTypeName`, `postCreateTable` spatial index flow. Allow `createSchema` to create geography columns.
- **`encodeGeometryEnvelope` optimization**: Conditionally skip `CAST(... AS GEOMETRY)` for geometry columns when descriptor context is available.
- **PostGIS-style filter subdivision**: Slice large geography filter polygons into 90×90° quadrants for antimeridian/pole safety.
- **Metadata table geography parity**: Look up geometry metadata table for geography columns to resolve subtypes (Point, Polygon, etc.).
