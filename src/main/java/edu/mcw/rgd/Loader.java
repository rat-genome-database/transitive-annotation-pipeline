package edu.mcw.rgd;

import edu.mcw.rgd.datamodel.Ortholog;
import edu.mcw.rgd.datamodel.XdbId;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.process.Utils;
import org.apache.log4j.Logger;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.*;

public class Loader {

    private String version;
    private int createdBy;
    private int refRgdId;
    private String evidenceCode;
    private DAO dao;
    private List<String> inputEvidenceCodes;
    private Set<Integer> processedSpeciesTypeKeys;

    Logger log = Logger.getLogger("core");

    public void run() throws Exception {

        Date dateStart = new Date();
        log.info(getVersion());

        SimpleDateFormat sdt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        log.info("  started at: "+sdt.format(dateStart));
        log.info("  "+dao.getConnectionInfo());
        log.info("===");

        int origAnnotCount = dao.getAnnotationCount(getRefRgdId());

        AnnotCache annotCache = processIncomingAnnotations();

        // qc incoming annots to determine annots for insertion / deletion
        annotCache.qcAndLoadAnnots(dao);

        int count = annotCache.insertedAnnots.get();
        if( count!=0 ) {
            log.info("annotations inserted: " + Utils.formatThousands(count));
        }

        count = annotCache.updatedFullAnnotKeys.size();
        if( count!=0 ) {
            log.info("annotations updated: " + Utils.formatThousands(count));
        }

        // update last modified date for matching annots in batches
        updateLastModified(annotCache);

        annotCache.clear();
        annotCache = null;

        log.debug("deleting stale annotations...");
        int annotDeleted = dao.deleteAnnotationsCreatedBy(getCreatedBy(), dateStart, getRefRgdId(), log);
        if( annotDeleted!=0 ) {
            log.info("annotations deleted : " + Utils.formatThousands(annotDeleted));
        }

        int newAnnotCount = dao.getAnnotationCount(getRefRgdId());

        log.info("annotations matching: "+Utils.formatThousands(origAnnotCount-annotDeleted)+"    (last-modified-date updated)");

        NumberFormat plusMinusNF = new DecimalFormat(" +###,###,###; -###,###,###");
        int diffAnnotCount = newAnnotCount - origAnnotCount;
        String diffCountStr = diffAnnotCount!=0 ? "     difference: "+ plusMinusNF.format(diffAnnotCount) : "";
        log.info("final annotation count: "+Utils.formatThousands(newAnnotCount)+diffCountStr);

        log.info("=== DONE ===  elapsed: " + Utils.formatElapsedTime(dateStart.getTime(), System.currentTimeMillis()));
        log.info("");
    }

    AnnotCache processIncomingAnnotations() throws Exception {

        List<Annotation> incomingAnnotations = getIncomingAnnotations();
        log.info("incoming manual gene annotations: "+Utils.formatThousands(incomingAnnotations.size()));

        AnnotCache annotCache = new AnnotCache();

        Collections.shuffle(incomingAnnotations);
        incomingAnnotations.parallelStream().forEach( annot -> {

            try {
                // determine orthologs
                List<Ortholog> orthos = dao.getOrthologsForSourceRgdId(annot.getAnnotatedObjectRgdId(), processedSpeciesTypeKeys);

                for (Ortholog o : orthos) {

                    // clone the annotation object (we do not want to modify original cached human annotations!)
                    Annotation a = (Annotation) annot.clone();

                    // put into XREF_SOURCE PMIDs associated with original human annotations
                    populateXRefSource(a);

                    // if WITH for human is empty, set it to RGD:human-gene-rgd_id
                    if (Utils.isStringEmpty(a.getWithInfo())) {
                        a.setWithInfo("RGD:" + a.getAnnotatedObjectRgdId());
                    }

                    a.setEvidence(getEvidenceCode());
                    a.setRefRgdId(getRefRgdId());
                    a.setSpeciesTypeKey(o.getDestSpeciesTypeKey());
                    a.setAnnotatedObjectRgdId(o.getDestRgdId());
                    a.setCreatedBy(getCreatedBy());
                    a.setLastModifiedBy(getCreatedBy());
                    a.setLastModifiedDate(new Date());

                    annotCache.addIncomingAnnot(a);
                }

            } catch(Exception e) {
                Utils.printStackTrace(e, log);
                throw new RuntimeException(e);
            }
        });
        return annotCache;
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

    List<Annotation> getIncomingAnnotations() throws Exception {

        String speciesClause = Utils.buildInPhrase(processedSpeciesTypeKeys);
        String forbiddenAspectClause = Utils.buildInPhraseQuoted(dao.getExcludedOntologyAspects());
        String evidenceClause = Utils.buildInPhraseQuoted(getInputEvidenceCodes());

        log.info("processed species: "+Utils.concatenate(", ", getProcessedSpeciesTypeKeys(), "getCommonName"));
        log.info("processed manual evidence codes: "+Utils.concatenate(getInputEvidenceCodes(), ", "));
        log.info("   AND TAS evidence code for PW annotations");

        return dao.getIncomingAnnotations(getRefRgdId(), forbiddenAspectClause, evidenceClause, speciesClause);
    }

    int updateLastModified(AnnotCache info) throws Exception {

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

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public int getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(int createdBy) {
        this.createdBy = createdBy;
    }

    public int getRefRgdId() {
        return refRgdId;
    }

    public void setRefRgdId(int refRgdId) {
        this.refRgdId = refRgdId;
    }

    public String getEvidenceCode() {
        return evidenceCode;
    }

    public void setEvidenceCode(String evidenceCode) {
        this.evidenceCode = evidenceCode;
    }

    public void setDao(DAO dao) {
        this.dao = dao;
    }

    public void setInputEvidenceCodes(List<String> inputEvidenceCodes) {
        this.inputEvidenceCodes = inputEvidenceCodes;
    }

    public List<String> getInputEvidenceCodes() {
        return inputEvidenceCodes;
    }

    public Set<Integer> getProcessedSpeciesTypeKeys() {
        return processedSpeciesTypeKeys;
    }

    public void setProcessedSpeciesTypeKeys(Set<Integer> processedSpeciesTypeKeys) {
        this.processedSpeciesTypeKeys = processedSpeciesTypeKeys;
    }
}
