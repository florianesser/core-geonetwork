//===	Copyright (C) 2001-2007 Food and Agriculture Organization of the
//===	United Nations (FAO-UN), United Nations World Food Programme (WFP)
//===	and United Nations Environment Programme (UNEP)
//===
//===	This program is free software; you can redistribute it and/or modify
//===	it under the terms of the GNU General Public License as published by
//===	the Free Software Foundation; either version 2 of the License, or (at
//===	your option) any later version.
//===
//===	This program is distributed in the hope that it will be useful, but
//===	WITHOUT ANY WARRANTY; without even the implied warranty of
//===	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
//===	General Public License for more details.
//===
//===	You should have received a copy of the GNU General Public License
//===	along with this program; if not, write to the Free Software
//===	Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
//===
//===	Contact: Jeroen Ticheler - FAO - Viale delle Terme di Caracalla 2,
//===	Rome - Italy. email: geonetwork@osgeo.org
//==============================================================================

package org.fao.geonet.kernel.search.spatial;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.TopologyException;
import com.vividsolutions.jts.index.SpatialIndex;
import jeeves.JeevesJCS;
import jeeves.utils.Log;
import org.apache.jcs.access.GroupCacheAccess;
import org.apache.jcs.access.exception.CacheException;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.FieldSelector;
import org.apache.lucene.document.FieldSelectorResult;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.util.OpenBitSet;
import org.fao.geonet.constants.Geonet;
import org.geotools.data.DefaultQuery;
import org.geotools.data.FeatureSource;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.factory.GeoTools;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.filter.FilterFactory2;
import org.opengis.filter.Id;
import org.opengis.filter.expression.Literal;
import org.opengis.filter.expression.PropertyName;
import org.opengis.filter.identity.FeatureId;
import org.opengis.filter.spatial.SpatialOperator;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.fao.geonet.kernel.search.spatial.SpatialIndexWriter.SPATIAL_FILTER_JCS;
import static org.fao.geonet.kernel.search.spatial.SpatialIndexWriter._SPATIAL_INDEX_TYPENAME;

public abstract class SpatialFilter extends Filter
{
    private static final long     serialVersionUID = -6221744013750827050L;
    private static final SimpleFeatureType FEATURE_TYPE;
    static {
        SimpleFeatureTypeBuilder builder = new SimpleFeatureTypeBuilder();
        builder.add("the_geom", Geometry.class, DefaultGeographicCRS.WGS84);
        builder.setDefaultGeometry("the_geom");
        builder.setName(_SPATIAL_INDEX_TYPENAME);
        FEATURE_TYPE = builder.buildFeatureType();
    }

	private static final Geometry WORLD_BOUNDS;
	static {
		GeometryFactory fac = new GeometryFactory();
		WORLD_BOUNDS = fac.toGeometry(new Envelope(-180,180,-90,90));
	}
    protected final Geometry      _geom;
    protected final FeatureSource<SimpleFeatureType, SimpleFeature> _featureSource;
    protected final SpatialIndex    _index;

    protected final FilterFactory2  _filterFactory;
    protected       Query                 _query;
    protected final FieldSelector _selector;
    private org.opengis.filter.Filter _spatialFilter;
    private Map<String, FeatureId> _unrefinedMatches;
    private boolean warned = false;


    protected SpatialFilter(Query query, Geometry geom,
            FeatureSource<SimpleFeatureType, SimpleFeature> featureSource, SpatialIndex index) throws IOException
    {
        _query = query;
        _geom = geom;
        _featureSource = featureSource;
        _index = index;
        _filterFactory = CommonFactoryFinder.getFilterFactory2(GeoTools
                .getDefaultHints());

				_selector = new FieldSelector() {
                    private static final long serialVersionUID = 1L;

						public final FieldSelectorResult accept(String name) {
							if (name.equals("_id")) return FieldSelectorResult.LOAD_AND_BREAK;
							else return FieldSelectorResult.NO_LOAD;
						}
				};
    }

    protected SpatialFilter(Query query, Envelope bounds,
            FeatureSource<SimpleFeatureType, SimpleFeature> featureSource, SpatialIndex index) throws IOException
    {
        this(query,JTS.toGeometry(bounds),featureSource,index);
    }

    public DocIdSet getDocIdSet(final IndexReader reader) throws IOException
    {
        final OpenBitSet bits = new OpenBitSet(reader.maxDoc());

        final Map<String, FeatureId> unrefinedSpatialMatches = unrefinedSpatialMatches();
        final Set<FeatureId> matches = new HashSet<FeatureId>();
        final Multimap<FeatureId,Integer> docIndexLookup = HashMultimap.create();
        
        if(unrefinedSpatialMatches.isEmpty()) return bits;

        new IndexSearcher(reader).search(_query, new Collector() {
						private int docBase;
            private Document document;

						// ignore scorer
						public void setScorer(Scorer scorer) {}

						// accept docs out of order (for a BitSet it doesn't matter)
						public boolean acceptsDocsOutOfOrder() {
							return true;
						}

						public void collect(int doc) {
							doc = doc + docBase;	
              try {
                 document = reader.document(doc, _selector);
                 String key = document.get("_id");
                 FeatureId featureId = unrefinedSpatialMatches.get(key); 
                 if (featureId!=null) {
                   matches.add(featureId);
                   docIndexLookup.put(featureId, doc + docBase);
                 }
              } catch (Exception e) {
                 throw new RuntimeException(e);
              }
            }

						public void setNextReader(IndexReader reader, int docBase) {
							this.docBase = docBase;
						}
        });
        
        if( matches.isEmpty() ){
            return bits;
        } else {
            return applySpatialFilter(matches,docIndexLookup,bits);
        }
    }

    private OpenBitSet applySpatialFilter(Set<FeatureId> matches, Multimap<FeatureId, Integer> docIndexLookup, OpenBitSet bits) throws IOException
    {

        JeevesJCS jcs = getJCSCache();
        processCachedFeatures(jcs, matches, docIndexLookup, bits);

        Id fidFilter = _filterFactory.id(matches);
        String ftn = _featureSource.getSchema().getName().getLocalPart();
        String[] geomAtt = {_featureSource.getSchema().getGeometryDescriptor().getLocalName()};
        FeatureCollection<SimpleFeatureType, SimpleFeature> features = _featureSource
                .getFeatures(new DefaultQuery(ftn, fidFilter,geomAtt));
        FeatureIterator<SimpleFeature> iterator = features.features();

        
        try {
            while (iterator.hasNext()) {
                SimpleFeature feature = iterator.next();
                FeatureId featureId = feature.getIdentifier();
                jcs.put(featureId.getID(), feature.getDefaultGeometry());
                if( evaluateFeature(feature) ){
                  for(int doc:docIndexLookup.get(featureId)) {
                      bits.set(doc);
                  }
                }
            }
        } catch (CacheException e) {
            throw new Error(e);
        } finally {
            iterator.close();
        }
        return bits;
    }

	static JeevesJCS getJCSCache() throws Error {
	    JeevesJCS jcs;
        try {
            jcs = JeevesJCS.getInstance(SPATIAL_FILTER_JCS);
        } catch (CacheException e) {
            throw new Error(e);
        }
	    return jcs;
    }

    private boolean evaluateFeature(SimpleFeature feature)
    {
        try{
            return getFilter().evaluate(feature);
        }catch ( TopologyException e){
            if( !warned ){
                warned =true;
                Log.warning(Geonet.SPATIAL, e.getMessage()+" errors are occuring with filter: "+getFilter());
            }
            if(Log.isDebugEnabled(Geonet.SPATIAL))
                Log.debug(Geonet.SPATIAL, e.getMessage()+": occurred during a search: "+getFilter()+" on feature: "+feature.getDefaultGeometry());
            return false;
        }
    }

    private void processCachedFeatures(GroupCacheAccess jcs, Set<FeatureId> matches, Multimap<FeatureId, Integer> docIndexLookup, OpenBitSet bits)
    {
        for(java.util.Iterator<FeatureId> iter=matches.iterator();iter.hasNext();){
            FeatureId id = iter.next();
          Geometry geom = (Geometry) jcs.get(id.getID());
            if( geom!=null ){
                iter.remove();
                SimpleFeature feature = SimpleFeatureBuilder.build(FEATURE_TYPE, new Object[]{geom}, id.getID());
                if( evaluateFeature(feature) ){
                  for(int doc:docIndexLookup.get(id)) {
                      bits.set(doc);
                  }
                }
            }
        }
    }

    private synchronized org.opengis.filter.Filter getFilter()
    {
        if (_spatialFilter == null) {
            _spatialFilter = createFilter(_featureSource);
        }

        return _spatialFilter;
    }

    /**
     * Returns all the FeatureId and ID attributes based on the query against the spatial index
     * 
     * @return all the FeatureId and ID attributes based on the query against the spatial index
     */
    protected synchronized Map<String,FeatureId> unrefinedSpatialMatches(){
        if(_unrefinedMatches==null){
            Geometry geom = null;

            // _index.query returns geometries that intersect with provided envelope. To use later a spatial filter that
            // provides geometries that don't intersect with the query envelope (_geom) should be used a full extent
            // envelope in this method, instead of the query envelope (_geom)
            if (getFilter().getClass().getName().equals("org.geotools.filter.spatial.DisjointImpl")) {
                try {
                    geom = WORLD_BOUNDS;
                } catch (Exception ex) {
                    ex.printStackTrace();
                    return _unrefinedMatches;
                }

            } else {
                geom = _geom;
            }

            @SuppressWarnings("unchecked")
            List<Pair<FeatureId,String>> fids = _index.query(geom.getEnvelopeInternal());
            _unrefinedMatches = new HashMap<String,FeatureId>();
            for (Pair<FeatureId, String> match : fids) {
                _unrefinedMatches.put(match.two(), match.one());
            }
        }
        return _unrefinedMatches;
    }
    
    protected org.opengis.filter.Filter createFilter(FeatureSource<SimpleFeatureType, SimpleFeature> source)
    {
        String geomAttName = source.getSchema().getGeometryDescriptor()
                .getLocalName();
        PropertyName geomPropertyName = _filterFactory.property(geomAttName);

        Literal geomExpression = _filterFactory.literal(_geom);
        org.opengis.filter.Filter filter = createGeomFilter(_filterFactory,
                geomPropertyName, geomExpression);
        return filter;
    }

    protected SpatialOperator createGeomFilter(FilterFactory2 filterFactory,
            PropertyName geomPropertyName, Literal geomExpression)
    {
        throw new UnsupportedOperationException(
                "createGeomFilter must be overridden if createFilter is not overridden");
    }

    public Query getQuery() {
        return _query;
    }

    public void setQuery(Query query) {
        _query = query;
    }
}
