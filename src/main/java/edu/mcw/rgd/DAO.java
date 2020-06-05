package edu.mcw.rgd;

import edu.mcw.rgd.dao.impl.AnnotationDAO;
import edu.mcw.rgd.dao.impl.OrthologDAO;
import edu.mcw.rgd.dao.impl.XdbIdDAO;
import edu.mcw.rgd.datamodel.Ortholog;
import edu.mcw.rgd.datamodel.XdbId;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.process.Utils;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author mtutaj
 * @since Dec 12, 2017
 * <p>
 * wrapper to handle all DAO code
 */
public class DAO {

    private Set<String> excludedOntologyAspects;

    XdbIdDAO xdao = new XdbIdDAO();
    AnnotationDAO annotationDAO = new AnnotationDAO();
    OrthologDAO orthologDAO = new OrthologDAO();

    Logger logInserted = Logger.getLogger("inserted");
    Logger logDeleted = Logger.getLogger("deleted");

    public String getConnectionInfo() {
        return xdao.getConnectionInfo();
    }

    synchronized public List<Ortholog> getOrthologsForSourceRgdId(int rgdId, Set<Integer> allowedSpeciesTypeKeys) throws Exception {
        List<Ortholog> orthos = _orthoCache.get(rgdId);
        if( orthos==null ) {
            orthos = orthologDAO.getOrthologsForSourceRgdId(rgdId);
            _orthoCache.put(rgdId, orthos);

            Iterator<Ortholog> it = orthos.iterator();
            while( it.hasNext() ) {
                Ortholog o = it.next();
                if( !allowedSpeciesTypeKeys.contains(o.getDestSpeciesTypeKey()) ) {
                    it.remove();
                }
            }
        }
        return orthos;
    }
    Map<Integer, List<Ortholog>> _orthoCache = new HashMap<>();

    public List<XdbId> getXdbIdsByRgdId(int xdbKey, int rgdId) throws Exception {

        return xdao.getXdbIdsByRgdId(xdbKey, rgdId);
    }

    public int getAnnotationCount(int refRgdId) throws Exception {
        return annotationDAO.getCountOfAnnotationsByReference(refRgdId);
    }

    /**
     * get annotations by annotated object rgd id; skip CHEBI, HP and MP annotations
     * @param rgdId annotated object rgd id
     * @return list of Annotation objects
     * @throws Exception on spring framework dao failure
    public List<Annotation> getAnnotations(int rgdId) throws Exception {

        List<Annotation> annots = _annotCache.get(rgdId);
        if( annots!=null ) {
            return annots;
        }

        annots = annotationDAO.getAnnotations(rgdId);
        Iterator<Annotation> it = annots.iterator();
        while( it.hasNext() ) {
            Annotation a = it.next();
            if( getExcludedOntologyAspects().contains(a.getAspect()) ) {
                it.remove();
            }
        }
        _annotCache.put(rgdId, annots);
        return annots;
    }
    static ConcurrentHashMap<Integer, List<Annotation>> _annotCache = new ConcurrentHashMap<>();
     */

    public List<Annotation> getIncomingAnnotations(int refRgdId, String forbiddenAspectClause, String evidenceClause, String speciesClause) throws Exception {

        String sql = ""+
        "SELECT a.*,i.species_type_key FROM full_annot a,rgd_ids i "+
        "WHERE rgd_id=annotated_object_rgd_id AND object_key=1 AND object_status='ACTIVE' AND with_info IS NULL "+
                " AND ref_rgd_id<>?" +
                " AND aspect NOT IN("+forbiddenAspectClause+")"+
                " AND evidence IN("+evidenceClause+")"+
                " AND species_type_key IN("+speciesClause+")";
        return annotationDAO.executeAnnotationQuery(sql, refRgdId);
    }

    public int getAnnotationCount(int rgdId, String termAcc, String qualifier, int refRgdId) throws Exception {

        String key = rgdId+"|"+termAcc+"|"+qualifier;
        Integer cnt = _annotCache2.get(key);
        if( cnt!=null ) {
            return cnt;
        }

        List<Annotation> annots = annotationDAO.getAnnotations(rgdId, termAcc);
        Iterator<Annotation> it = annots.iterator();
        while( it.hasNext() ) {
            Annotation a = it.next();
            if( refRgdId==a.getRefRgdId() ) {
                it.remove();
                continue;
            }
            if( !Utils.stringsAreEqual(qualifier, a.getQualifier()) ) {
                it.remove();
            }
        }
        _annotCache2.put(key, annots.size());
        return annots.size();
    }
    static ConcurrentHashMap<String, Integer> _annotCache2 = new ConcurrentHashMap<>();

    /**
     * get annotation key by a list of values that comprise unique key:
     * TERM_ACC+ANNOTATED_OBJECT_RGD_ID+REF_RGD_ID+EVIDENCE+WITH_INFO+QUALIFIER+XREF_SOURCE
     * @param annot Annotation object with the following fields set: TERM_ACC+ANNOTATED_OBJECT_RGD_ID+REF_RGD_ID+EVIDENCE+WITH_INFO+QUALIFIER+XREF_SOURCE
     * @return value of annotation key or 0 if there is no such annotation
     * @throws Exception on spring framework dao failure
     */
    public int getAnnotationKey(Annotation annot) throws Exception {
        return annotationDAO.getAnnotationKey(annot);
    }

    /**
     * Insert new annotation into FULL_ANNOT table; full_annot_key will be auto generated from sequence and returned
     * <p>
     * Note: this implementation uses only one roundtrip to database vs traditional approach resulting in double throughput
     * @param annot Annotation object representing column values
     * @throws Exception
     * @return value of new full annot key
     */
    public int insertAnnotation(Annotation annot) throws Exception{
        logInserted.debug(annot.dump("|"));
        return annotationDAO.insertAnnotation(annot);
    }

    public int updateLastModified(List<Integer> fullAnnotKeys) throws Exception{
        return annotationDAO.updateLastModified(fullAnnotKeys);
    }

    /**
     * delete annotations older than passed in date
     *
     * @return number of rows affected
     * @throws Exception on spring framework dao failure
    public int deleteAnnotationsCreatedBy(int createdBy, Date dt, int refRgdId, int speciesTypeKey, Logger log) throws Exception{

        List<Annotation> staleAnnots = annotationDAO.getAnnotationsModifiedBeforeTimestamp(createdBy, dt, refRgdId, speciesTypeKey);
        log.debug("  stale annots found = "+staleAnnots.size());
        if( staleAnnots.isEmpty() ) {
            return 0;
        }

        List<Integer> keys = new ArrayList<>(staleAnnots.size());
        for( Annotation a: staleAnnots ) {
            logDeleted.debug(a.dump("|"));
            keys.add(a.getKey());
        }

        int rws = annotationDAO.deleteAnnotations(keys);
        log.debug("  stale annots deleted = "+rws);
        return rws;
    }
     */

    public int deleteAnnotationsCreatedBy(int createdBy, Date dt, int refRgdId, Logger log) throws Exception{

        List<Annotation> staleAnnots = annotationDAO.getAnnotationsModifiedBeforeTimestamp(createdBy, dt, refRgdId);
        log.debug("  stale annots found = "+staleAnnots.size());
        if( staleAnnots.isEmpty() ) {
            return 0;
        }

        List<Integer> keys = new ArrayList<>(staleAnnots.size());
        for( Annotation a: staleAnnots ) {
            logDeleted.debug(a.dump("|"));
            keys.add(a.getKey());
        }

        int rws = annotationDAO.deleteAnnotations(keys);
        log.debug("  stale annots deleted = "+rws);
        return rws;
    }

    public Set<String> getExcludedOntologyAspects() {
        return excludedOntologyAspects;
    }

    public void setExcludedOntologyAspects(Set<String> excludedOntologyAspects) {
        this.excludedOntologyAspects = excludedOntologyAspects;
    }
}
