package edu.mcw.rgd;

import edu.mcw.rgd.datamodel.ontology.Annotation;

import java.util.ArrayList;
import java.util.List;
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

        incomingAnnots.parallelStream().forEach( a -> {

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
}
