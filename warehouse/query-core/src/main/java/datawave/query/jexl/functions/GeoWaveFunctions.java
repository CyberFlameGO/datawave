package datawave.query.jexl.functions;

import datawave.data.normalizer.AbstractGeometryNormalizer;
import datawave.data.normalizer.GeoNormalizer;
import datawave.data.type.AbstractGeometryType;
import datawave.data.type.GeoType;
import datawave.data.type.util.AbstractGeometry;
import datawave.query.attributes.ValueTuple;
import datawave.query.collections.FunctionalSet;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;

/**
 * Provides functions for doing spatial queries, such as bounding boxes and circles of interest, as well as spatial relationships.
 *
 * Function names are all lower case and separated by underscores to play nice with case insensitive queries.
 *
 * NOTE: The JexlFunctionArgumentDescriptorFactory is implemented by GeoWaveFunctionsDescripter. This is kept as a separate class to reduce accumulo
 * dependencies on other jars.
 *
 */
@JexlFunctions(descriptorFactory = "datawave.query.jexl.functions.GeoWaveFunctionsDescriptor")
public class GeoWaveFunctions {
    public static final String GEOWAVE_FUNCTION_NAMESPACE = "geowave";
    
    // used to handle legacy geo data type
    private static final GeoNormalizer geoNormalizer = new GeoNormalizer();
    private static final GeometryFactory geometryFactory = new GeometryFactory();
    
    /**
     * Test intersection of a set of geometry with a field value
     */
    private static boolean intersectsGeometries(Object fieldValue, Geometry[] geometries) {
        Geometry thisGeom = getGeometryFromFieldValue(fieldValue);
        for (Geometry g : geometries) {
            if (thisGeom.intersects(g)) {
                return true;
            }
        }
        return false;
    }
    
    private static boolean intersectsGeometries(Iterable<?> values, Geometry[] geometries) {
        boolean successfullyParsedAValue = false;
        Exception parseException = null;
        for (Object fieldValue : values) {
            try {
                if (intersectsGeometries(fieldValue, geometries)) {
                    return true;
                }
                successfullyParsedAValue = true;
            } catch (Exception e) {
                // this is most likely a field value from the index (i.e. an encoded string @see GeometryNormalizer.getEncodedStringsFromGeometry)
                // ignore and continue down the list of values. This will be thrown if every value in the list threw an exception
                parseException = e;
            }
        }
        if (!successfullyParsedAValue) {
            throw new RuntimeException("Did not find any properly encoded values to match against", parseException);
        }
        return false;
    }
    
    private static Geometry getGeometryFromFieldValue(Object fieldValue) {
        if (fieldValue instanceof Geometry) {
            return (Geometry) fieldValue;
        } else if (fieldValue instanceof GeoNormalizer.GeoPoint) {
            return geoPointToGeometry((GeoNormalizer.GeoPoint) fieldValue);
        } else if (fieldValue instanceof String) {
            return parseGeometry((String) fieldValue);
        } else if (fieldValue instanceof ValueTuple) {
            ValueTuple t = (ValueTuple) fieldValue;
            Object o = t.second();
            if (o instanceof AbstractGeometryType) {
                AbstractGeometryType<?> gt = (AbstractGeometryType<?>) o;
                return ((AbstractGeometry<?>) gt.getDelegate()).getJTSGeometry();
            } else if (o instanceof GeoType) {
                return parseGeometryFromGeoType(ValueTuple.getNormalizedStringValue(fieldValue));
            }
        } else if (fieldValue instanceof FunctionalSet) {
            FunctionalSet<?> funcSet = (FunctionalSet<?>) fieldValue;
            Geometry[] geometries = funcSet.stream().map(GeoWaveFunctions::getGeometryFromFieldValue).toArray(Geometry[]::new);
            return new GeometryCollection(geometries, geometryFactory);
        }
        throw new IllegalArgumentException("Field Value:" + fieldValue + " cannot be recognized as a geometry");
    }
    
    private static Geometry parseGeometry(String geomString) {
        Geometry geom;
        try {
            geom = AbstractGeometryNormalizer.parseGeometry(geomString);
        } catch (IllegalArgumentException e) {
            geom = parseGeometryFromGeoType(geomString);
        }
        return geom;
    }
    
    private static Geometry parseGeometryFromGeoType(String geoTypeString) {
        Geometry geom = null;
        try {
            geom = geoPointToGeometry(GeoNormalizer.isNormalized(geoTypeString) ? GeoNormalizer.GeoPoint.decodeZRef(geoTypeString) : GeoNormalizer.GeoPoint
                            .decodeZRef(geoNormalizer.normalize(geoTypeString)));
        } catch (Exception e) {
            // do nothing
        }
        return geom;
    }
    
    private static Geometry geoPointToGeometry(GeoNormalizer.GeoPoint geoPoint) {
        return geometryFactory.createPoint(new Coordinate(geoPoint.getLongitude(), geoPoint.getLatitude()));
    }
    
    public static boolean contains(Object fieldValue, String geoString) {
        Geometry otherGeom = AbstractGeometryNormalizer.parseGeometry(geoString);
        Geometry thisGeom = getGeometryFromFieldValue(fieldValue);
        return thisGeom.contains(otherGeom);
    }
    
    public static boolean contains(Iterable<?> values, String geoString) {
        boolean successfullyParsedAValue = false;
        Exception parseException = null;
        for (Object fieldValue : values) {
            try {
                if (contains(fieldValue, geoString)) {
                    return true;
                }
                successfullyParsedAValue = true;
            } catch (Exception e) {
                // this is most likely a field value from the index (i.e. an encoded string @see GeometryNormalizer.getEncodedStringsFromGeometry)
                // ignore and continue down the list of values. This will be thrown if every value in the list threw an exception
                parseException = e;
            }
        }
        if (!successfullyParsedAValue) {
            throw new RuntimeException("Did not find any properly encoded values to match against", parseException);
        }
        return false;
    }
    
    public static boolean covers(Object fieldValue, String geoString) {
        Geometry otherGeom = AbstractGeometryNormalizer.parseGeometry(geoString);
        Geometry thisGeom = getGeometryFromFieldValue(fieldValue);
        return thisGeom.covers(otherGeom);
    }
    
    public static boolean covers(Iterable<?> values, String geoString) {
        boolean successfullyParsedAValue = false;
        Exception parseException = null;
        for (Object fieldValue : values) {
            try {
                if (covers(fieldValue, geoString)) {
                    return true;
                }
                successfullyParsedAValue = true;
            } catch (Exception e) {
                // this is most likely a field value from the index (i.e. an encoded string @see GeometryNormalizer.getEncodedStringsFromGeometry)
                // ignore and continue down the list of values. This will be thrown if every value in the list threw an exception
                parseException = e;
            }
        }
        if (!successfullyParsedAValue) {
            throw new RuntimeException("Did not find any properly encoded values to match against", parseException);
        }
        return false;
    }
    
    public static boolean covered_by(Object fieldValue, String geoString) {
        Geometry otherGeom = AbstractGeometryNormalizer.parseGeometry(geoString);
        Geometry thisGeom = getGeometryFromFieldValue(fieldValue);
        return thisGeom.coveredBy(otherGeom);
    }
    
    public static boolean covered_by(Iterable<?> values, String geoString) {
        boolean successfullyParsedAValue = false;
        Exception parseException = null;
        for (Object fieldValue : values) {
            try {
                if (covered_by(fieldValue, geoString)) {
                    return true;
                }
                successfullyParsedAValue = true;
            } catch (Exception e) {
                // this is most likely a field value from the index (i.e. an encoded string @see GeometryNormalizer.getEncodedStringsFromGeometry)
                // ignore and continue down the list of values. This will be thrown if every value in the list threw an exception
                parseException = e;
            }
        }
        if (!successfullyParsedAValue) {
            throw new RuntimeException("Did not find any properly encoded values to match against", parseException);
        }
        return false;
    }
    
    public static boolean crosses(Object fieldValue, String geoString) {
        Geometry otherGeom = AbstractGeometryNormalizer.parseGeometry(geoString);
        Geometry thisGeom = getGeometryFromFieldValue(fieldValue);
        return thisGeom.crosses(otherGeom);
    }
    
    public static boolean crosses(Iterable<?> values, String geoString) {
        boolean successfullyParsedAValue = false;
        Exception parseException = null;
        for (Object fieldValue : values) {
            try {
                if (crosses(fieldValue, geoString)) {
                    return true;
                }
                successfullyParsedAValue = true;
            } catch (Exception e) {
                // this is most likely a field value from the index (i.e. an encoded string @see GeometryNormalizer.getEncodedStringsFromGeometry)
                // ignore and continue down the list of values. This will be thrown if every value in the list threw an exception
                parseException = e;
            }
        }
        if (!successfullyParsedAValue) {
            throw new RuntimeException("Did not find any properly encoded values to match against", parseException);
        }
        return false;
    }
    
    public static boolean intersects(Object fieldValue, String geoString) {
        Geometry otherGeom = AbstractGeometryNormalizer.parseGeometry(geoString);
        Geometry thisGeom = getGeometryFromFieldValue(fieldValue);
        return thisGeom.intersects(otherGeom);
    }
    
    public static boolean intersects(Iterable<?> values, String geoString) {
        boolean successfullyParsedAValue = false;
        Exception parseException = null;
        for (Object fieldValue : values) {
            try {
                if (intersects(fieldValue, geoString)) {
                    return true;
                }
                successfullyParsedAValue = true;
            } catch (Exception e) {
                // this is most likely a field value from the index (i.e. an encoded string @see GeometryNormalizer.getEncodedStringsFromGeometry)
                // ignore and continue down the list of values. This will be thrown if every value in the list threw an exception
                parseException = e;
            }
        }
        if (!successfullyParsedAValue) {
            throw new RuntimeException("Did not find any properly encoded values to match against", parseException);
        }
        return false;
    }
    
    public static boolean overlaps(Object fieldValue, String geoString) {
        Geometry otherGeom = AbstractGeometryNormalizer.parseGeometry(geoString);
        Geometry thisGeom = getGeometryFromFieldValue(fieldValue);
        return thisGeom.overlaps(otherGeom);
    }
    
    public static boolean overlaps(Iterable<?> values, String geoString) {
        boolean successfullyParsedAValue = false;
        Exception parseException = null;
        for (Object fieldValue : values) {
            try {
                if (overlaps(fieldValue, geoString)) {
                    return true;
                }
                successfullyParsedAValue = true;
            } catch (Exception e) {
                // this is most likely a field value from the index (i.e. an encoded string @see GeometryNormalizer.getEncodedStringsFromGeometry)
                // ignore and continue down the list of values. This will be thrown if every value in the list threw an exception
                parseException = e;
            }
        }
        if (!successfullyParsedAValue) {
            throw new RuntimeException("Did not find any properly encoded values to match against", parseException);
        }
        return false;
    }
    
    public static boolean within(Object fieldValue, String geoString) {
        Geometry otherGeom = AbstractGeometryNormalizer.parseGeometry(geoString);
        Geometry thisGeom = getGeometryFromFieldValue(fieldValue);
        return thisGeom.within(otherGeom);
    }
    
    public static boolean within(Iterable<?> values, String geoString) {
        boolean successfullyParsedAValue = false;
        Exception parseException = null;
        for (Object fieldValue : values) {
            try {
                if (within(fieldValue, geoString)) {
                    return true;
                }
                successfullyParsedAValue = true;
            } catch (Exception e) {
                // this is most likely a field value from the index (i.e. an encoded string @see GeometryNormalizer.getEncodedStringsFromGeometry)
                // ignore and continue down the list of values. This will be thrown if every value in the list threw an exception
                parseException = e;
            }
        }
        if (!successfullyParsedAValue) {
            throw new RuntimeException("Did not find any properly encoded values to match against", parseException);
        }
        return false;
    }
}
