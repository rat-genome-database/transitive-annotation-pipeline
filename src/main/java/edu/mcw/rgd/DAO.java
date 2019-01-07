package edu.mcw.rgd;

import edu.mcw.rgd.dao.impl.AnnotationDAO;
import edu.mcw.rgd.dao.impl.OrthologDAO;
import edu.mcw.rgd.dao.impl.XdbIdDAO;
import edu.mcw.rgd.datamodel.Ortholog;
import edu.mcw.rgd.datamodel.XdbId;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

/**
 * @author mtutaj
 * @since Dec 12, 2017
 * <p>
 * wrapper to handle all DAO code
 */
public class DAO {

    XdbIdDAO xdao = new XdbIdDAO();
    AnnotationDAO annotationDAO = new AnnotationDAO();
    OrthologDAO orthologDAO = new OrthologDAO();

    Logger logInserted = Logger.getLogger("inserted");
    Logger logDeleted = Logger.getLogger("deleted");

    public String getConnectionInfo() {
        return xdao.getConnectionInfo();
    }

    /**
     * get all active gene orthologs for given pair of species
     * @param speciesTypeKey1 species type key for first species
     * @param speciesTypeKey2 species type key for second species
     * @return List of Ortholog objects
     * @throws Exception when unexpected error in spring framework occurs
     */
    public List<Ortholog> getAllOrthologs(int speciesTypeKey1, int speciesTypeKey2) throws Exception {
        return orthologDAO.getAllOrthologs(speciesTypeKey1, speciesTypeKey2);
    }


    public List<XdbId> getXdbIdsByRgdId(int xdbKey, int rgdId) throws Exception {

        return xdao.getXdbIdsByRgdId(xdbKey, rgdId);
    }

    /**
     * get annotations by annotated object rgd id; skip CHEBI, HP and MP annotations
     * @param rgdId annotated object rgd id
     * @return list of Annotation objects
     * @throws Exception on spring framework dao failure
     */
    public List<Annotation> getAnnotations(int rgdId) throws Exception {
        List<Annotation> annots = annotationDAO.getAnnotations(rgdId);
        Iterator<Annotation> it = annots.iterator();
        while( it.hasNext() ) {
            Annotation a = it.next();
            if( a.getAspect().equals("E") || // CHEBI
                    a.getAspect().equals("H") || // HPO
                    a.getAspect().equals("N")    // MP
                    ) {
                it.remove();
            }
        }
        return annots;
    }

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

    /**
     * update last modified date to SYSDATE for annotation given full annot key
     * @param fullAnnotKey FULL_ANNOT_KEY
     * @return count of rows affected
     * @throws Exception on spring framework dao failure
     */
    public int updateLastModified(int fullAnnotKey) throws Exception{
        return annotationDAO.updateLastModified(fullAnnotKey);
    }

    /**
     * delete annotations older than passed in date
     *
     * @return number of rows affected
     * @throws Exception on spring framework dao failure
     */
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

}
