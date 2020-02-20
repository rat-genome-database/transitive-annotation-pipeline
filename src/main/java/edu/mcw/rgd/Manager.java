package edu.mcw.rgd;

import edu.mcw.rgd.datamodel.*;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.process.Utils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author mtutaj
 * @since Dec 12, 2017
 * based on human orthologs and human gene annotations,
 *    create ISS annotations for chinchilla, squirrel, bonobo, dog and pig
 *    for all ontologies except CHEBI, MP and HP
 */
public class Manager {

    private DAO dao;
    private String version;
    private String pipelineName;
    private int createdBy;
    private int refRgdId;
    private List<String> speciesProcessed;
    private String evidenceCode;

    Logger log = Logger.getLogger("core");

    NumberFormat plusMinusNF = new DecimalFormat(" +###,###,###; -###,###,###");

    public static void main(String[] args) throws Exception {

        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(bf).loadBeanDefinitions(new FileSystemResource("properties/AppConfigure.xml"));
        Manager manager = (Manager) (bf.getBean("manager"));

        try {
            manager.run();
        } catch (Exception e) {
            manager.log.error(e);
            throw e;
        }
    }

    public void run() throws Exception {
        Date dateStart = new Date();
        log.info(getVersion());

        SimpleDateFormat sdt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        log.info("  started at: "+sdt.format(dateStart));
        log.info("  "+dao.getConnectionInfo());
        log.info("===");

        for( String speciesName: getSpeciesProcessed() ) {
            run(speciesName);
        }

        log.info("=== DONE ===  elapsed: " + Utils.formatElapsedTime(dateStart.getTime(), System.currentTimeMillis()));
        log.info("");
    }

    void run(String speciesName) throws Exception {

        long startTime = System.currentTimeMillis();

        int speciesTypeKey = SpeciesType.parse(speciesName);
        if( SpeciesType.getTaxonomicId(speciesTypeKey)==0 ) {
            throw new Exception("ERROR: invalid species: "+speciesName);
        }

        String species = SpeciesType.getCommonName(speciesTypeKey);
        log.info("START: species = " + species);

        handle(species, speciesTypeKey, new Date(startTime));

        log.info("END:  time elapsed: " + Utils.formatElapsedTime(startTime, System.currentTimeMillis()));
        log.info("===");
    }

    void handle(String species, int speciesTypeKey, Date dateStart) throws Exception {

        log.debug("processing annotations for Human-"+species+" orthologs");

        AnnotInfo info = new AnnotInfo(speciesTypeKey);
        List<Ortholog> orthologs = dao.getAllOrthologs(SpeciesType.HUMAN, speciesTypeKey);
        Collections.shuffle(orthologs); // randomize orthologs for better parallelism of annotations retrieval
        log.info("Human-"+species+" orthologs loaded: "+Utils.formatThousands(orthologs.size()));

        int origAnnotCount = dao.getAnnotationCount(getRefRgdId(), speciesTypeKey);

        orthologs.parallelStream().forEach( o -> {
            try {
                handleAnnotations(o.getSrcRgdId(), o.getDestRgdId(), o.getDestSpeciesTypeKey(), info);
            } catch(Exception e) {
                throw new RuntimeException(e);
            }
        });

        int count = info.droppedGOAnnots.get();
        if( count!=0 ) {
            log.info("  skipped incoming human GO annotations: "+ Utils.formatThousands(count));
        }

        count = info.insertedAnnots.get();
        if( count!=0 ) {
            log.info("annotations inserted: " + Utils.formatThousands(count));
        }

        // update last modified date for matching annots in batches
        updateLastModified(info);

        log.debug("deleting stale annotations...");
        int annotDeleted = dao.deleteAnnotationsCreatedBy(getCreatedBy(), dateStart, getRefRgdId(), speciesTypeKey, log);
        if( annotDeleted!=0 ) {
            log.info("annotations deleted : " + Utils.formatThousands(annotDeleted));
        }

        int newAnnotCount = dao.getAnnotationCount(getRefRgdId(), speciesTypeKey);

        log.info("annotations matching: "+Utils.formatThousands(origAnnotCount-annotDeleted)+"    (last-modified-date updated)");

        int diffAnnotCount = newAnnotCount - origAnnotCount;
        String diffCountStr = diffAnnotCount!=0 ? "     difference: "+ plusMinusNF.format(diffAnnotCount) : "";
        log.info("final annotation count: "+Utils.formatThousands(newAnnotCount)+diffCountStr);
    }

    void handleAnnotations(int humanRgdId, int orthoRgdId, int orthoSpeciesTypeKey, AnnotInfo info) throws Exception {

        List<Annotation> annots = dao.getAnnotations(humanRgdId);

        // hack for DOG,PIG -- DOG,PIG have its GO annotations loaded via MAH GO pipeline
        //  no need to create duplicate transitive orthologs
        if( orthoSpeciesTypeKey==SpeciesType.DOG || orthoSpeciesTypeKey==SpeciesType.PIG ) {
            int count = dropGoAnnots(annots);
            if( count!=0 ) {
                info.droppedGOAnnots.addAndGet(count);
            }
        }

        // turn human annots into ISS annots
        for( Annotation annot: annots ) {

            // clone the annotation object (we do not want to modify original cached human annotations!)
            Annotation a = (Annotation) annot.clone();

            // put into XREF_SOURCE PMIDs associated with original human annotations
            populateXRefSource(a);

            // if WITH for human is empty, set it to RGD:human-gene-rgd_id
            if( Utils.isStringEmpty(a.getWithInfo()) ) {
                a.setWithInfo("RGD:" + a.getAnnotatedObjectRgdId());
            } else {
                // if WITH for human is not empty

                //   and evidence code for human is 'ISS' or 'ISO'
                if( Utils.stringsAreEqual(a.getWithInfo(), "ISS") || Utils.stringsAreEqual(a.getWithInfo(), "ISO") ) {
                    // then keep original WITH INFO
                } else {
                    // otherwise: set WITH to human gene RGD ID
                    a.setWithInfo("RGD:" + a.getAnnotatedObjectRgdId());
                }
            }

            a.setEvidence(getEvidenceCode());
            a.setRefRgdId(getRefRgdId());
            a.setSpeciesTypeKey(info.speciesTypeKey);
            a.setAnnotatedObjectRgdId(orthoRgdId);
            a.setCreatedBy(getCreatedBy());
            a.setLastModifiedBy(getCreatedBy());
            a.setLastModifiedDate(new Date());

            int fullAnnotKey = dao.getAnnotationKey(a);
            if (fullAnnotKey == 0) {
                dao.insertAnnotation(a);
                info.insertedAnnots.incrementAndGet();
            } else {
                info.upToDateFullAnnotKeys.put(fullAnnotKey, 0);
            }
        }
    }

    void populateXRefSource(Annotation a) throws Exception {
        // read pubmed ids from human reference
        Set<String> pubmedIds = new TreeSet<>();
        List<XdbId> humanPubMedIds = dao.getXdbIdsByRgdId(XdbId.XDB_KEY_PUBMED, a.getRefRgdId());
        if( !humanPubMedIds.isEmpty() ) {
            pubmedIds.add("REF_RGD_ID:"+a.getRefRgdId());
            for (XdbId xdbId: humanPubMedIds) {
                pubmedIds.add("PMID:" + xdbId.getAccId());
            }
        }

        // concatenate them with existing xref source
        if( !Utils.isStringEmpty(a.getXrefSource()) ) {
            Collections.addAll(pubmedIds, a.getXrefSource().split("[\\|]"));
        }

        String newXrefSource = Utils.concatenate(pubmedIds, "|");
        if( !Utils.stringsAreEqualIgnoreCase(newXrefSource, a.getXrefSource()) ) {
            a.setXrefSource(newXrefSource);
        }
    }

    int dropGoAnnots(List<Annotation> annots) {

        int count = 0;
        Iterator<Annotation> it = annots.iterator();
        while( it.hasNext() ) {
            Annotation ann = it.next();
            if( ann.getTermAcc().startsWith("GO:") ) {
                it.remove();
                count++;
            }
        }
        return count;
    }

    int updateLastModified(AnnotInfo info) throws Exception {

        int rowsUpdated = 0;

        // do the updates in batches of 999, because Oracle has an internal limit of 1000
        List<Integer> fullAnnotKeys = new ArrayList<>(info.upToDateFullAnnotKeys.keySet());
        for( int i=0; i<fullAnnotKeys.size(); i+= 999 ) {
            int j = i + 999;
            if( j > fullAnnotKeys.size() ) {
                j = fullAnnotKeys.size();
            }
            List<Integer> fullAnnotKeysSubset = fullAnnotKeys.subList(i, j);
            rowsUpdated += dao.updateLastModified(fullAnnotKeysSubset);
        }

        return rowsUpdated;
    }

    public DAO getDao() {
        return dao;
    }

    public void setDao(DAO dao) {
        this.dao = dao;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getVersion() {
        return version;
    }

    public void setPipelineName(String pipelineName) {
        this.pipelineName = pipelineName;
    }

    public String getPipelineName() {
        return pipelineName;
    }

    public void setCreatedBy(int createdBy) {
        this.createdBy = createdBy;
    }

    public int getCreatedBy() {
        return createdBy;
    }

    public void setRefRgdId(int refRgdId) {
        this.refRgdId = refRgdId;
    }

    public int getRefRgdId() {
        return refRgdId;
    }

    public List<String> getSpeciesProcessed() {
        return speciesProcessed;
    }

    public void setSpeciesProcessed(List<String> speciesProcessed) {
        this.speciesProcessed = speciesProcessed;
    }

    public void setEvidenceCode(String evidenceCode) {
        this.evidenceCode = evidenceCode;
    }

    public String getEvidenceCode() {
        return evidenceCode;
    }

    class AnnotInfo {

        public AnnotInfo(int speciesTypeKey) {
            this.speciesTypeKey = speciesTypeKey;
        }

        public AtomicInteger insertedAnnots = new AtomicInteger(0);
        public AtomicInteger droppedGOAnnots = new AtomicInteger(0);
        public int speciesTypeKey; // species type key for ortholog species
        // we store them in a map to avoid multiple updates
        public ConcurrentHashMap<Integer, Object> upToDateFullAnnotKeys = new ConcurrentHashMap<Integer, Object>();
    }
}

