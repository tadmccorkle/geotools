package org.geotools.data.sqlserver;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.StringWriter;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.filter.FilterFactory;
import org.geotools.api.filter.spatial.Crosses;
import org.geotools.api.filter.spatial.Intersects;
import org.geotools.api.filter.spatial.Touches;
import org.geotools.data.DataUtilities;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.jdbc.JDBCDataStore;
import org.junit.Before;
import org.junit.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKTReader;

public class SQLServerFilterToSQLTest {

    private FilterFactory ff;
    private GeometryFactory gf;
    private SimpleFeatureType geographyType;
    private SimpleFeatureType geometryType;

    @Before
    public void setUp() throws Exception {
        ff = CommonFactoryFinder.getFilterFactory();
        gf = new GeometryFactory();

        geographyType = DataUtilities.createType("test", "geom:Geometry:srid=4326,name:String");
        geographyType.getGeometryDescriptor().getUserData().put(JDBCDataStore.JDBC_NATIVE_TYPENAME, "geography");

        geometryType = DataUtilities.createType("test", "geom:Geometry:srid=4326,name:String");
        geometryType.getGeometryDescriptor().getUserData().put(JDBCDataStore.JDBC_NATIVE_TYPENAME, "geometry");
    }

    @Test
    public void testGeographyLiteralUsesCCWShell() throws Exception {
        // CW shell (should be reversed to CCW)
        Coordinate[] cwShell = new Coordinate[] {
            new Coordinate(0, 0),
            new Coordinate(10, 0),
            new Coordinate(10, 10),
            new Coordinate(0, 10),
            new Coordinate(0, 0)
        };
        Polygon cwPoly = gf.createPolygon(cwShell);

        SQLServerFilterToSQL filterToSQL = new SQLServerFilterToSQL();
        StringWriter writer = new StringWriter();
        filterToSQL.setWriter(writer);
        filterToSQL.setFeatureType(geographyType);

        Intersects filter = ff.intersects(ff.property("geom"), ff.literal(cwPoly));
        filter.accept(filterToSQL, null);

        String sql = writer.toString();
        assertTrue("Should use geography prefix", sql.contains("geography::STGeomFromText"));

        // Verify the polygon in the SQL is CCW by parsing the WKT out
        int wktStart = sql.indexOf("'") + 1;
        int wktEnd = sql.indexOf("'", wktStart);
        String wkt = sql.substring(wktStart, wktEnd);
        Geometry parsed = new WKTReader().read(wkt);
        Polygon resultPoly = (Polygon) parsed;
        assertTrue(
                "Shell should be CCW",
                org.locationtech.jts.algorithm.Orientation.isCCW(
                        resultPoly.getExteriorRing().getCoordinates()));
    }

    @Test
    public void testGeographyLiteralEnforcesCWHoles() throws Exception {
        // CCW shell (correct)
        Coordinate[] shell = new Coordinate[] {
            new Coordinate(0, 0),
            new Coordinate(0, 10),
            new Coordinate(10, 10),
            new Coordinate(10, 0),
            new Coordinate(0, 0)
        };
        // CCW hole (wrong for geography — should be reversed to CW)
        Coordinate[] ccwHole = new Coordinate[] {
            new Coordinate(2, 2), new Coordinate(2, 8), new Coordinate(8, 8), new Coordinate(8, 2), new Coordinate(2, 2)
        };
        LinearRing shellRing = gf.createLinearRing(shell);
        LinearRing holeRing = gf.createLinearRing(ccwHole);
        Polygon poly = gf.createPolygon(shellRing, new LinearRing[] {holeRing});

        SQLServerFilterToSQL filterToSQL = new SQLServerFilterToSQL();
        StringWriter writer = new StringWriter();
        filterToSQL.setWriter(writer);
        filterToSQL.setFeatureType(geographyType);

        Intersects filter = ff.intersects(ff.property("geom"), ff.literal(poly));
        filter.accept(filterToSQL, null);

        String sql = writer.toString();
        int wktStart = sql.indexOf("'") + 1;
        int wktEnd = sql.indexOf("'", wktStart);
        String wkt = sql.substring(wktStart, wktEnd);
        Geometry parsed = new WKTReader().read(wkt);
        Polygon resultPoly = (Polygon) parsed;

        assertTrue(
                "Shell should be CCW",
                org.locationtech.jts.algorithm.Orientation.isCCW(
                        resultPoly.getExteriorRing().getCoordinates()));
        assertTrue(
                "Hole should be CW",
                !org.locationtech.jts.algorithm.Orientation.isCCW(
                        resultPoly.getInteriorRingN(0).getCoordinates()));
    }

    @Test
    public void testGeometryLiteralUsesGeometryPrefix() throws Exception {
        Coordinate[] shell = new Coordinate[] {
            new Coordinate(0, 0),
            new Coordinate(0, 10),
            new Coordinate(10, 10),
            new Coordinate(10, 0),
            new Coordinate(0, 0)
        };
        Polygon poly = gf.createPolygon(shell);

        SQLServerFilterToSQL filterToSQL = new SQLServerFilterToSQL();
        StringWriter writer = new StringWriter();
        filterToSQL.setWriter(writer);
        filterToSQL.setFeatureType(geometryType);

        Intersects filter = ff.intersects(ff.property("geom"), ff.literal(poly));
        filter.accept(filterToSQL, null);

        String sql = writer.toString();
        assertTrue("Should use geometry prefix for geometry columns", sql.contains("geometry::STGeomFromText"));
    }

    @Test
    public void testGeometrySrid4326StaysGeometry() throws Exception {
        // Verify that a geometry column with SRID 4326 does NOT get treated as geography
        Coordinate[] shell = new Coordinate[] {
            new Coordinate(0, 0),
            new Coordinate(0, 10),
            new Coordinate(10, 10),
            new Coordinate(10, 0),
            new Coordinate(0, 0)
        };
        Polygon poly = gf.createPolygon(shell);

        SQLServerFilterToSQL filterToSQL = new SQLServerFilterToSQL();
        StringWriter writer = new StringWriter();
        filterToSQL.setWriter(writer);
        filterToSQL.setFeatureType(geometryType);

        Intersects filter = ff.intersects(ff.property("geom"), ff.literal(poly));
        filter.accept(filterToSQL, null);

        String sql = writer.toString();
        assertTrue("geometry(4326) must use geometry:: prefix", sql.contains("geometry::STGeomFromText"));
        assertTrue("geometry(4326) must NOT use geography:: prefix", !sql.contains("geography::STGeomFromText"));
    }

    @Test
    public void testGeographyCrossesThrows() throws Exception {
        Geometry point = gf.createPoint(new Coordinate(10, 5));

        SQLServerFilterToSQL filterToSQL = new SQLServerFilterToSQL();
        StringWriter writer = new StringWriter();
        filterToSQL.setWriter(writer);
        filterToSQL.setFeatureType(geographyType);

        Crosses filter = ff.crosses(ff.property("geom"), ff.literal(point));
        try {
            filter.accept(filterToSQL, null);
            fail("Expected UnsupportedOperationException for Crosses on geography");
        } catch (UnsupportedOperationException e) {
            assertTrue(e.getMessage().contains("STCrosses"));
        }
    }

    @Test
    public void testGeographyTouchesThrows() throws Exception {
        Geometry point = gf.createPoint(new Coordinate(10, 5));

        SQLServerFilterToSQL filterToSQL = new SQLServerFilterToSQL();
        StringWriter writer = new StringWriter();
        filterToSQL.setWriter(writer);
        filterToSQL.setFeatureType(geographyType);

        Touches filter = ff.touches(ff.property("geom"), ff.literal(point));
        try {
            filter.accept(filterToSQL, null);
            fail("Expected UnsupportedOperationException for Touches on geography");
        } catch (UnsupportedOperationException e) {
            assertTrue(e.getMessage().contains("STTouches"));
        }
    }

    @Test
    public void testGeometryCrossesAllowed() throws Exception {
        Coordinate[] line = new Coordinate[] {new Coordinate(0, 0), new Coordinate(10, 10)};
        Geometry lineString = gf.createLineString(line);

        SQLServerFilterToSQL filterToSQL = new SQLServerFilterToSQL();
        StringWriter writer = new StringWriter();
        filterToSQL.setWriter(writer);
        filterToSQL.setFeatureType(geometryType);

        Crosses filter = ff.crosses(ff.property("geom"), ff.literal(lineString));
        filter.accept(filterToSQL, null);

        String sql = writer.toString();
        assertTrue("Crosses should be allowed on geometry", sql.contains(".STCrosses("));
    }
}
