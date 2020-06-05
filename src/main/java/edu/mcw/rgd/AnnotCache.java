package edu.mcw.rgd;

import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.process.Utils;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class AnnotCache {

    Logger log = Logger.getLogger("core");

    public AtomicInteger insertedAnnots = new AtomicInteger(0);
    // we store them in a map to avoid multiple updates
    public ConcurrentHashMap<Integer, Object> upToDateFullAnnotKeys = new ConcurrentHashMap<Integer, Object>();

    private List<Annotation> incomingAnnots = new ArrayList<>();

    synchronized public void addIncomingAnnot(Annotation a) {
        incomingAnnots.add(a);
    }

    public void qcAndLoadAnnots(DAO dao) throws Exception {

        List<Annotation> mergedAnnots = mergeIncomingAnnots();

        List<Annotation> uniqueAnnots = getAnnotsWithoutDuplicates(dao, mergedAnnots);

        uniqueAnnots.parallelStream().forEach( a -> {

            try {
                int fullAnnotKey = dao.getAnnotationKey(a);
                if (fullAnnotKey == 0) {
                    dao.insertAnnotation(a);
                    insertedAnnots.incrementAndGet();
                } else {
                    upToDateFullAnnotKeys.put(fullAnnotKey, 0);
                }
            } catch(Exception e) {
                log.warn("PROBLEMATIC ANNOT=  "+a.dump("|"));
                throw new RuntimeException(e);
            }
        });

    }

    List<Annotation> getAnnotsWithoutDuplicates(DAO dao, List<Annotation> mergedAnnots) throws Exception {

        List<Annotation> uniqueAnnots = new ArrayList<>();

        for( Annotation a: mergedAnnots ) {

            int annotCountInRgd = dao.getAnnotationCount(a.getAnnotatedObjectRgdId(), a.getTermAcc(), a.getQualifier(), a.getRefRgdId());
            if( annotCountInRgd==0 ) {
                uniqueAnnots.add(a);
            }
        }

        mergedAnnots.clear();

        return uniqueAnnots;
    }

    /**
     * Incoming annots built on the base of human ClinVar annots are often quite similar, differing only in XREF_SOURCE field.
     * Per RGD strategy, we can safely merge these annots into a single one, with its WITH_INFO field being an aggregate of WITH_INFO field
     * from source annotations.
     */
    List<Annotation> mergeIncomingAnnots() throws CloneNotSupportedException {

        log.info("   incoming annot count = "+Utils.formatThousands(incomingAnnots.size()));

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
                    xrefs = new TreeSet<>(Arrays.asList(a.getXrefSource().split("[\\|\\,\\;]")));
                } else {
                    xrefs = new TreeSet<>();
                }
                if( mergedA.getXrefSource()!=null ) {
                    xrefs.addAll(Arrays.asList(mergedA.getXrefSource().split("[\\|\\,\\;]")));
                }
                mergedA.setXrefSource(Utils.concatenate(xrefs,"|"));
            }
        }

        List<Annotation> mergedAnnotList = new ArrayList<>(mergedAnnots.values());

        splitAnnots(mergedAnnotList);
        log.info("   merged annot count = "+Utils.formatThousands(mergedAnnotList.size()));
        return mergedAnnotList;
    }

    void splitAnnots(List<Annotation> annots) throws CloneNotSupportedException {

        // XREF_SOURCE field cannot be longer than 4000 chars; if it is longer, it must be split into multiple annotations
        List<Annotation> annotSplits = new ArrayList<>();

        for( Annotation a: annots ) {
            if( a.getXrefSource()==null ) {
                continue;
            }

            while( a.getXrefSource().length()>4000 ) {
                int splitPos = a.getXrefSource().lastIndexOf("|", 4000);
                String goodXrefSrc = a.getXrefSource().substring(0, splitPos);
                Annotation a2 = (Annotation) a.clone();
                a2.setXrefSource(goodXrefSrc);
                annotSplits.add(a2);
                a.setXrefSource(a.getXrefSource().substring(splitPos+1));

                if(false) { // dbg
                    log.warn("===");
                    log.warn("SPLIT1 " + a2.dump("|"));
                    log.warn("SPLIT2 " + a.dump("|"));
                    log.warn("===");
                }
            }
        }

        if( !annotSplits.isEmpty() ) {
            log.info("   merged annot splits = "+Utils.formatThousands(annotSplits.size()));
            annots.addAll(annotSplits);
        }
    }

    String getMergeKey(Annotation a) {
        return a.getAnnotatedObjectRgdId()+"|"+a.getTermAcc()+"|"+a.getDataSrc()+"|"+a.getEvidence()
                +"|"+a.getRefRgdId()+"|"+a.getCreatedBy()+"|"+Utils.defaultString(a.getQualifier())
                +"|"+a.getWithInfo()+"|"+a.getNotes();
    }

    public void clear() {
        insertedAnnots.set(0);
        upToDateFullAnnotKeys.clear();
        incomingAnnots.clear();
    }
}
