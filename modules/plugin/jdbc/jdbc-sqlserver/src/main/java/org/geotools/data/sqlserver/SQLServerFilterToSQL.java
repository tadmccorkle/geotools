/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2002-2009, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geotools.data.sqlserver;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import org.geotools.api.filter.expression.Expression;
import org.geotools.api.filter.expression.Literal;
import org.geotools.api.filter.expression.PropertyName;
import org.geotools.api.filter.spatial.BBOX;
import org.geotools.api.filter.spatial.Beyond;
import org.geotools.api.filter.spatial.BinarySpatialOperator;
import org.geotools.api.filter.spatial.Contains;
import org.geotools.api.filter.spatial.Crosses;
import org.geotools.api.filter.spatial.DWithin;
import org.geotools.api.filter.spatial.Disjoint;
import org.geotools.api.filter.spatial.DistanceBufferOperator;
import org.geotools.api.filter.spatial.Equals;
import org.geotools.api.filter.spatial.Intersects;
import org.geotools.api.filter.spatial.Overlaps;
import org.geotools.api.filter.spatial.Touches;
import org.geotools.api.filter.spatial.Within;
import org.geotools.data.jdbc.FilterToSQL;
import org.geotools.data.util.DistanceBufferUtil;
import org.geotools.filter.FilterCapabilities;
import org.geotools.geometry.jts.JTS;
import org.geotools.jdbc.JDBCDataStore;
import org.geotools.jdbc.SQLDialect;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

public class SQLServerFilterToSQL extends FilterToSQL {

    private static final Envelope WORLD = new Envelope(-180, 180, -90, 90);

    @Override
    protected FilterCapabilities createFilterCapabilities() {
        FilterCapabilities caps = super.createFilterCapabilities();
        caps.addAll(SQLDialect.BASE_DBMS_CAPABILITIES);
        caps.addType(BBOX.class);
        caps.addType(Contains.class);
        caps.addType(Crosses.class);
        caps.addType(Disjoint.class);
        caps.addType(Equals.class);
        caps.addType(Intersects.class);
        caps.addType(Overlaps.class);
        caps.addType(Touches.class);
        caps.addType(Within.class);
        caps.addType(DWithin.class);
        caps.addType(Beyond.class);
        return caps;
    }

    @Override
    protected void visitLiteralGeometry(Literal expression) throws IOException {
        Geometry g = (Geometry) evaluateLiteral(expression, Geometry.class);
        if (g instanceof LinearRing ring) {
            // WKT does not support linear rings
            g = g.getFactory().createLineString(ring.getCoordinateSequence());
        }

        boolean geography = isCurrentGeography();
        String prefix = geography ? "geography" : "geometry";
        if (geography) {
            g = clipToWorld(g);
            g = forceCCW(g);
        }

        int srid = currentSRID != null ? currentSRID : (geography ? 4326 : 0);
        out.write(prefix + "::STGeomFromText('" + g.toText() + "', " + srid + ")");
    }

    private Geometry forceCCW(Geometry g) {
        if (g == null) return null;
        if (g instanceof Polygon poly) {
            return normalizePolygon(poly);
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
        LinearRing shell = orientRing(poly.getExteriorRing(), true);
        LinearRing[] holes = new LinearRing[poly.getNumInteriorRing()];
        for (int i = 0; i < poly.getNumInteriorRing(); i++) {
            holes[i] = orientRing(poly.getInteriorRingN(i), false);
        }
        return gf.createPolygon(shell, holes);
    }

    private LinearRing orientRing(LinearRing ring, boolean ccw) {
        if (Orientation.isCCW(ring.getCoordinates()) != ccw) {
            return ring.reverse();
        }
        return ring;
    }

    // Clips geometry to the valid world envelope for geography literals.
    // Adequate for typical GeoServer BBOX tile filters (small rectangles within world bounds).
    // TODO: antimeridian-crossing or pole-adjacent filters may need PostGIS-style
    //  90x90 degree subdivision (see FilterToSqlHelper.clipToWorld in jdbc-postgis).
    private Geometry clipToWorld(Geometry g) {
        if (g == null) return null;
        Envelope env = g.getEnvelopeInternal();
        if (!WORLD.contains(env)) {
            return g.intersection(JTS.toGeometry(WORLD));
        }
        return g;
    }

    private boolean isCurrentGeography() {
        if (currentGeometry == null) return false;
        Object nativeType = currentGeometry.getUserData().get(JDBCDataStore.JDBC_NATIVE_TYPENAME);
        return "geography".equals(nativeType);
    }

    @Override
    protected Object visitBinarySpatialOperator(
            BinarySpatialOperator filter, PropertyName property, Literal geometry, boolean swapped, Object extraData) {
        return visitBinarySpatialOperator(filter, property, (Expression) geometry, swapped, extraData);
    }

    @Override
    protected Object visitBinarySpatialOperator(
            BinarySpatialOperator filter, Expression e1, Expression e2, Object extraData) {
        return visitBinarySpatialOperator(filter, e1, e2, false, extraData);
    }

    protected Object visitBinarySpatialOperator(
            BinarySpatialOperator filter, Expression e1, Expression e2, boolean swapped, Object extraData) {

        try {
            // if the filter is not disjoint, and it with a BBOX filter
            if (!(filter instanceof Disjoint) && !(filter instanceof DistanceBufferOperator)) {
                e1.accept(this, extraData);
                out.write(".Filter(");
                e2.accept(this, extraData);
                out.write(") = 1");

                if (!(filter instanceof BBOX)) {
                    out.write(" AND ");
                }
            }

            if (filter instanceof BBOX) {
                // nothing to do. already encoded above
                return extraData;
            }

            if (filter instanceof DistanceBufferOperator operator) {
                double distance = operator.getDistance();
                if (isCurrentGeography()) {
                    distance = DistanceBufferUtil.getDistanceInMeters(operator);
                }

                e1.accept(this, extraData);
                out.write(".STDistance(");
                e2.accept(this, extraData);
                out.write(")");

                if (filter instanceof DWithin) {
                    out.write("<");
                } else if (filter instanceof Beyond) {
                    out.write(">");
                } else {
                    throw new RuntimeException("Unknown distance operator.");
                }

                out.write(Double.toString(distance));
            } else {

                if (swapped) {
                    e2.accept(this, extraData);
                } else {
                    e1.accept(this, extraData);
                }

                if (filter instanceof Contains) {
                    out.write(".STContains(");
                } else if (filter instanceof Crosses) {
                    if (isCurrentGeography()) {
                        throw new UnsupportedOperationException(
                                "SQL Server GEOGRAPHY does not support STCrosses. Use Intersects instead.");
                    }
                    out.write(".STCrosses(");
                } else if (filter instanceof Disjoint) {
                    out.write(".STDisjoint(");
                } else if (filter instanceof Equals) {
                    out.write(".STEquals(");
                } else if (filter instanceof Intersects) {
                    out.write(".STIntersects(");
                } else if (filter instanceof Overlaps) {
                    out.write(".STOverlaps(");
                } else if (filter instanceof Touches) {
                    if (isCurrentGeography()) {
                        throw new UnsupportedOperationException(
                                "SQL Server GEOGRAPHY does not support STTouches. Use STDistance or Intersects instead.");
                    }
                    out.write(".STTouches(");
                } else if (filter instanceof Within) {
                    out.write(".STWithin(");
                } else {
                    throw new RuntimeException("Unknown operator: " + filter);
                }

                if (swapped) {
                    e1.accept(this, extraData);
                } else {
                    e2.accept(this, extraData);
                }

                out.write(") = 1");
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return extraData;
    }

    @Override
    protected void writeLiteral(Object literal) throws IOException {
        if (literal instanceof Date) {
            SimpleDateFormat DATETIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            out.write("'" + DATETIME_FORMAT.format(literal) + "'");
        } else if (literal instanceof Boolean boolean1) {
            out.write(String.valueOf(boolean1 ? 1 : 0));
        } else {
            super.writeLiteral(literal);
        }
    }
}
