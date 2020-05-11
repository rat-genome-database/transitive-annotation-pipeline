package edu.mcw.rgd;

import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.process.Utils;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class AnnotCache {

    public AnnotCache(int speciesTypeKey) {
        this.speciesTypeKey = speciesTypeKey;
    }

    public AtomicInteger insertedAnnots = new AtomicInteger(0);
    public AtomicInteger droppedGOAnnots = new AtomicInteger(0);
    public int speciesTypeKey; // species type key for ortholog species
    // we store them in a map to avoid multiple updates
    public ConcurrentHashMap<Integer, Object> upToDateFullAnnotKeys = new ConcurrentHashMap<Integer, Object>();

    private List<Annotation> incomingAnnots = new ArrayList<>();

    synchronized public void addIncomingAnnot(Annotation a) {
        incomingAnnots.add(a);
    }

    public void qcAndLoadAnnots(DAO dao) {

        List<Annotation> mergedAnnots = mergeIncomingAnnots();

        mergedAnnots.parallelStream().forEach( a -> {

            try {
                int fullAnnotKey = dao.getAnnotationKey(a);
                if (fullAnnotKey == 0) {
                    dao.insertAnnotation(a);
                    insertedAnnots.incrementAndGet();
                } else {
                    upToDateFullAnnotKeys.put(fullAnnotKey, 0);
                }
            } catch(Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Incoming annots built on the base of human ClinVar annots are often quite similar, differing only in XREF_SOURCE field.
     * Per RGD strategy, we can safely merge these annots into a single one, with its WITH_INFO field being an aggregate of WITH_INFO field
     * from source annotations.
     */
    List<Annotation> mergeIncomingAnnots() {

        Logger log = Logger.getLogger("core");
        log.info("   incoming annot count = "+incomingAnnots.size());

        Map<String, Annotation> mergedAnnots = new HashMap<>();
        for( Annotation a: incomingAnnots ) {
            String key = getMergeKey(a);
            Annotation mergedA = mergedAnnots.get(key);
            if( mergedA==null ) {
                mergedAnnots.put(key, a);
            } else {
                // merge WITH_INFO field
                Set<String> xrefs;
                if( a.getXrefSource()!=null ) {
                    xrefs = new TreeSet<>(Arrays.asList(a.getXrefSource().split("[\\|,;]")));
                } else {
                    xrefs = new TreeSet<>();
                }
                if( mergedA.getXrefSource()!=null ) {
                    xrefs.addAll(Arrays.asList(a.getXrefSource().split("[\\|,;]")));
                }
                mergedA.setXrefSource(Utils.concatenate(xrefs,"|"));
            }
        }

        List<Annotation> mergedAnnotList = new ArrayList<>(mergedAnnots.values());
        log.info("   merged annot count = "+mergedAnnotList.size());
        return mergedAnnotList;
    }

    String getMergeKey(Annotation a) {
        return a.getAnnotatedObjectRgdId()+"|"+a.getTermAcc()+"|"+a.getDataSrc()+"|"+a.getEvidence()
                +a.getRefRgdId()+"|"+a.getCreatedBy()+"|"+Utils.defaultString(a.getQualifier())
                +"|"+a.getNotes();
    }
}
