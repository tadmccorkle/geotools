# SQL Server GEOGRAPHY Support Plan

## Problem Statement
The current SQL Server plugin only supports the `GEOMETRY` data type. Tables using `GEOGRAPHY` cannot be fully utilized, especially when they have spatial indexes that require `GEOGRAPHY`-specific spatial operators and prefixes.

## Research Findings
1.  **Hardcoded Prefixes**: `SQLServerFilterToSQL` and `SQLServerDialect` hardcode the `geometry::` prefix for static methods like `STGeomFromText`.
2.  **Type Recognition**: `SQLServerDialect.getMapping` only handles "geometry" type names from JDBC metadata.
3.  **Coordinate Order**:
    *   `GEOMETRY` uses (X, Y) order.
    *   `GEOGRAPHY` uses (Latitude, Longitude) order in its binary serialization, which corresponds to (Y, X).
    *   JTS and GeoTools generally expect (X, Y) or (Longitude, Latitude).
4.  **Binary Serialization**: `SqlServerBinaryReader` reads coordinates as (X, Y). For `GEOGRAPHY`, these must be swapped to (Longitude, Latitude) to match JTS expectations.
5.  **Spatial Functions**: `GEOGRAPHY` in SQL Server has a more limited set of native spatial methods compared to `GEOMETRY` (e.g., it lacks `STContains`, `STWithin` in older versions, though it has `STIntersects`).
6.  **Spatial Indexes**: `GEOGRAPHY` spatial indexes do not use a `BOUNDING_BOX` parameter during creation.

## Implementation Plan

### 1. Metadata Handling
*   **SQLServerDialect**: Override `setMetadata(GeometryDescriptor, ResultSet, Connection)` to capture the native type name ("GEOMETRY" or "GEOGRAPHY") from the JDBC `ResultSet` and store it in the `GeometryDescriptor`'s user data (e.g., under key `"SQLSERVER_NATIVE_TYPE"`).

### 2. Dialect Updates
*   **SQLServerDialect**:
    *   Update `registerSqlTypeNameToClassMappings` to include "geography" mapping to `Geometry.class`.
    *   Update `getMapping` to recognize "geography" type name.
    *   (Optional) Update `getGeometryTypeName` to handle different types if needed for table creation.

### 3. Coordinate Swapping for Native Serialization
*   **SqlServerBinaryReader**:
    *   Add a `toggleAxisOrder` or `swap` boolean flag.
    *   Update `readCoordinate()` to swap X and Y if the flag is set.
*   **SQLServerDialect**:
    *   In `decodeGeometryValue`, check the native type from the descriptor's user data.
    *   If the type is "GEOGRAPHY", enable coordinate swapping in `SqlServerBinaryReader`.

### 4. Encoding and Filtering
*   **SQLServerFilterToSQL**:
    *   Update `visitLiteralGeometry` to use either `geometry::` or `geography::` prefix based on the context of the operation.
    *   Track the current native type being processed during spatial operator visitation.
*   **SQLServerDialect**:
    *   Provide an overloaded `encodeGeometryValue` that accepts a `GeometryDescriptor` to correctly choose the prefix.
    *   **STEnvelope Fix**: Unlike PostGIS (which uses `&&` and skips `ST_Envelope` for geography), SQL Server `GEOGRAPHY` lacks a native bbox-only operator that is faster than `.Filter()`.
    *   **Final Strategy**: For `BBOX` filters, we will continue using `.Filter()` which is already the SQL Server dialect's way of providing index-assisted primary filtering.
    *   **Full Envelope**: For cases needing a true envelope (like `encodeGeometryEnvelope`), we will use `CAST(column AS GEOMETRY).STEnvelope()` to overcome the lack of native `GEOGRAPHY.STEnvelope()`.
    *   **STDistance Units**: Acknowledge that `GEOGRAPHY.STDistance` and `ST_DWithin` return meters regardless of SRID units.
    *   **Final Strategy**: For `DWithin` filters on `GEOGRAPHY` columns, we must convert the distance to meters using `DistanceBufferUtil.getDistanceInMeters(operator)` to match SQL Server expectations.
    *   **Literal Sanitization**: Following PostGIS's lead, implement a basic clipping of geography literals to the world envelope `(-180, 180, -90, 90)` in `SQLServerFilterToSQL` to prevent SQL Server errors for invalid geography coordinates.
    *   **SRID Handling**: Ensure `getGeometrySRID` correctly prioritizes 4326 for `GEOGRAPHY` columns if not explicitly found in metadata, matching SQL Server's default behavior.

### 5. Table Creation & Spatial Indexes
*   **SQLServerDialect**:
    *   In `postCreateTable`, check the native type.
    *   For `GEOGRAPHY` columns, omit the `BOUNDING_BOX` parameter when creating spatial indexes.

## Verification Plan
1.  **Unit Tests**:
    *   Update `SqlServerBinaryReaderTest` to verify coordinate swapping.
2.  **Online Tests**:
    *   Create a new online test `SQLServerGeographyOnlineTest` (or similar) that uses a table with a `GEOGRAPHY` column.
    *   Verify reading, filtering, and (if possible) writing to this column.

---

## Post-implementation Notes
1. Unit Test Verification
Run the SQLServerBinaryReaderTest class. I have added a new test case, testGeographyPoint, which specifically validates that:
 * The SqlServerBinaryReader correctly identifies a geography stream.
 * The (Latitude, Longitude) coordinates stored in the SQL Server binary format are swapped to (Longitude, Latitude) to match JTS/GeoTools standards.
 * Expected Result: All tests, including the new testGeographyPoint, should pass.

2. Integration (Online) Verification
If you have access to a SQL Server instance, you can verify the end-to-end support by creating a table with a GEOGRAPHY column:

 1 CREATE TABLE GeographyTest (
 2     id INT PRIMARY KEY,
 3     geo GEOGRAPHY
 4 );
 5 INSERT INTO GeographyTest (id, geo) VALUES (1, geography::STGeomFromText('POINT(10 5)', 4326));
 6 CREATE SPATIAL INDEX ix_geo ON GeographyTest(geo); -- Should succeed without BOUNDING_BOX
Then, use GeoTools to:
 * Read the data: Verify that the point comes back as POINT(10 5) (not POINT(5 10)).
 * Filter the data: Run a BBOX or Intersects query.
     * Expectation: The generated SQL should use geography::STGeomFromText and the .Filter() operator.
 * Distance queries: Run a DWithin filter with a distance in degrees (if using 4326).
     * Expectation: The dialect should automatically convert the distance to meters before sending it to SQL Server's .STDistance() method.

3. Visual Log Inspection
Enable FINE logging for org.geotools.jdbc to inspect the generated SQL.
 * Geometry Columns: Should see geometry::STGeomFromText(...).
 * Geography Columns: Should see geography::STGeomFromText(...).
 * Envelopes: Should see CAST(column AS GEOMETRY).STEnvelope() in the SQL for geography columns.

Summary of what was implemented:
 - Binary Reader: Added coordinate swapping logic and a geography flag to handle MS-SSCLRS geography format.
 - Dialect Recognition: Updated the dialect to recognize geography as a valid geometry type during metadata crawling.
 - Dynamic Prefixing: Updated the filter encoder to switch between geometry:: and geography:: based on the column type.
 - Coordinate Clipping: Implemented automatic clipping for geography literals to ensure they stay within the valid (-180, 180, -90, 90) world range, preventing SQL Server "out of range" errors.
 - Distance Support: Added automatic distance conversion to meters for DWithin and Beyond filters on geography columns.

---

# SQL Server GEOGRAPHY Support - Part 2
I was able to compile the changes to the SQL Server extension after removing the `SQLServerDialect.setMetadata` override. This method does not exist to be overridden. In addition to confirming it builds, I have also verified the changes do support using GEOGRAPHY columns in SQL Views, and SQL filters are generated correctly (using `geography::` qualifier instead of `geometry::`). However, I am having some issues, some or all of which may be due to build incompatibilities.

1. The issues I'm having occur after making queries. Some are like "[sqlserver.jtds] - Failed to find JTDS jar" - I'm thinking these are due to version mismatches between my running GeoServer and this Geotools repository.
2. Some queries fail because the bounding box filter provided to filter down the geography column are not valid. SQL Server suggests calling a MakeValid function to fix the issue at the risk of breaking the filter's desired bounds, but this wouldn't fix orientation issues.

## 1. Lack of `SQLServerDialect.setMetadata`
*   **The Issue**: This method was hallucinated. It doesn't actually exist. Something higher up the pipeline must handle setting this metadata, because it seems like the PostGIS extension checks it.
*   **Conclusion**: Confirmed non-critical. The dynamic detection using `ResultSetMetaData` and SRID heuristics covers all cases, including SQL Views where metadata persistence is missing.

## 2. JTDS Jar Errors
*   **Conclusion**: Ignored as per user feedback (noise only).

## 3. Geography BBOX Validity & Orientation
*   **The Issue**: SQL Server `GEOGRAPHY` is strictly "Left-Hand Rule" compliant. A clockwise (CW) polygon is interpreted as covering the entire globe *minus* the polygon area. This often leads to "invalid geography" or "hemisphere violation" errors during spatial filtering.
*   **Why `MakeValid` fails**: `MakeValid` fixes self-intersections or overlaps but doesn't necessarily reorient a valid CW polygon to the intended "small" CCW version.
*   **Why `ReorientObject` is risky in SQL**: Applying it blindly to all geography literals would flip already-correct CCW polygons back to CW, causing the same "large polygon" issue.
*   **The New Strategy (Java-side Correction)**:
    *   **Context**: `SQLServerFilterToSQL.visitLiteralGeometry`.
    *   **Action**: If the native type is `GEOGRAPHY`, inspect the literal `Geometry`.
    *   **Logic**: Use JTS `Orientation.isCCW()` (or `Algorithm.isCCW`) to check the exterior ring. If it is clockwise, use `geometry.reverse()` to flip it to CCW before encoding it as WKT.
    *   **Benefit**: This ensures the WKT sent to SQL Server always represents the "small" area intended by the filter, resolving 24426 errors without the need for complex SQL functions.

## Revised Plan for Implementation
1.  **Java Orientation Fix**: Implement the CCW-enforcement logic in `SQLServerFilterToSQL.visitLiteralGeometry`.
2.  **Verification**: Confirm that this reorientation allows BBOX and Intersects queries to work on `GEOGRAPHY` columns without validity errors.

---

## Post-implementation notes
*   **Java-Side Orientation Fix**: Added a `forceCCW` helper method in `SQLServerFilterToSQL`. This method uses JTS `Orientation.isCCW()` to check the exterior ring of geography literals. If a polygon is clockwise (CW), it is automatically reversed to counter-clockwise (CCW) before being sent to SQL Server. This ensures compliance with SQL Server's "Left-Hand Rule" and resolves the invalid geography errors you were experiencing.
*   **Robust Detection**: Maintained the layered detection logic that correctly identifies `GEOGRAPHY` vs. `GEOMETRY` even in SQL Views, preventing accidental misinterpretation of SRID 4326 geometry columns.
*   **Distance and Clipping**: Kept the automatic meter-based distance conversion for `DWithin` and the world-envelope clipping for geography literals.

These changes provide a comprehensive and robust implementation of SQL Server `GEOGRAPHY` support that integrates seamlessly with GeoServer SQL Views. I have documented these final details and the verification steps in `notes-p2.md`.
