package edu.mcw.rgd;

import edu.mcw.rgd.datamodel.*;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.process.Utils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author mtutaj
 * @since Dec 12, 2017
 * based on human orthologs and human gene annotations,
 *    create ISS annotations for chinchilla, squirrel and bonobo
 */
public class Manager {

    private DAO dao;
    private String version;
    private String pipelineName;
    private int createdBy;
    private int refRgdId;
    private List<String> speciesProcessed;

    Logger log = Logger.getLogger("core");

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
        log.info("Human-"+species+" orthologs loaded: "+Utils.formatThousands(orthologs.size()));

        orthologs.parallelStream().forEach( o -> {
            try {
                handleAnnotations(o.getSrcRgdId(), o.getDestRgdId(), info);
            } catch(Exception e) {
                throw new RuntimeException(e);
            }
        });

        log.info("annotations inserted: "+Utils.formatThousands(info.insertedAnnots.get()));
        log.info("annotations matching: "+Utils.formatThousands(info.matchingAnnots.get()));

        log.debug("deleting stale annotations...");
        int annotDeleted = dao.deleteAnnotationsCreatedBy(getCreatedBy(), dateStart, getRefRgdId(), speciesTypeKey, log);
        log.info("stale annotations deleted : "+Utils.formatThousands(annotDeleted));
    }

    void handleAnnotations(int humanRgdId, int orthoRgdId, AnnotInfo info) throws Exception {

        List<Annotation> annots = dao.getAnnotations(humanRgdId);

        // hack for DOG -- DOG has its GO annotations loaded via MAH GO pipeline
        //  no need to create duplicate transitive orthologs
        if( orthoRgdId==SpeciesType.DOG ) {
            dropGoAnnots(annots);
        }

        // turn human annots into chinchilla annots
        for( Annotation a: annots ) {

            // put into XREF_SOURCE PMIDs associated with original human annotations
            populateXRefSource(a);

            a.setWithInfo("RGD:" + a.getAnnotatedObjectRgdId());
            a.setEvidence("ISS");
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
                dao.updateLastModified(fullAnnotKey);
                info.matchingAnnots.incrementAndGet();
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

    void dropGoAnnots(List<Annotation> annots) {

        Iterator<Annotation> it = annots.iterator();
        while( it.hasNext() ) {
            Annotation ann = it.next();
            if( ann.getTermAcc().startsWith("GO:") ) {
                it.remove();
            }
        }
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

    class AnnotInfo {

        public AnnotInfo(int speciesTypeKey) {
            this.speciesTypeKey = speciesTypeKey;
        }

        public AtomicInteger insertedAnnots = new AtomicInteger(0);
        public AtomicInteger matchingAnnots = new AtomicInteger(0);
        public int speciesTypeKey; // species type key for ortholog species
    }
}

