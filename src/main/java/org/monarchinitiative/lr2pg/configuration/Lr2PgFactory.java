package org.monarchinitiative.lr2pg.configuration;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import de.charite.compbio.jannovar.data.JannovarData;
import de.charite.compbio.jannovar.data.JannovarDataSerializer;
import de.charite.compbio.jannovar.data.SerializationException;
import org.apache.commons.io.FilenameUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.h2.mvstore.MVStore;
import org.monarchinitiative.exomiser.core.genome.GenomeAssembly;
import org.monarchinitiative.exomiser.core.genome.jannovar.InvalidFileFormatException;
import org.monarchinitiative.exomiser.core.genome.jannovar.JannovarDataProtoSerialiser;
import org.monarchinitiative.lr2pg.analysis.Gene2Genotype;
import org.monarchinitiative.lr2pg.analysis.Vcf2GenotypeMap;
import org.monarchinitiative.lr2pg.exception.Lr2PgRuntimeException;
import org.monarchinitiative.lr2pg.exception.Lr2pgException;
import org.monarchinitiative.lr2pg.io.GenotypeDataIngestor;
import org.monarchinitiative.lr2pg.io.YamlParser;
import org.monarchinitiative.lr2pg.likelihoodratio.GenotypeLikelihoodRatio;
import org.monarchinitiative.phenol.base.PhenolException;
import org.monarchinitiative.phenol.base.PhenolRuntimeException;
import org.monarchinitiative.phenol.formats.hpo.HpoDisease;
import org.monarchinitiative.phenol.io.OntologyLoader;
import org.monarchinitiative.phenol.io.assoc.HpoAssociationParser;
import org.monarchinitiative.phenol.io.obo.hpo.HpoDiseaseAnnotationParser;
import org.monarchinitiative.phenol.ontology.data.Ontology;
import org.monarchinitiative.phenol.ontology.data.TermId;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Not a full implementation of the factory pattern but rather a convenience class to create objects of various
 * classes that we need as singletons with the various commands.
 * @author <a href="mailto:peter.robinson@jax.org">Peter Robinson</a>
 */
public class Lr2PgFactory {
    private static final Logger logger = LogManager.getLogger();
    /** Path to the {@code hp.obo} file. */
    private final String hpoOboFilePath;
    /** Path to the {@code phenotype.hpoa} file. */
    private final String phenotypeAnnotationPath;
    /** UCSC, RefSeq, Ensembl. */
    private final TranscriptDatabase transcriptdatabase;
    /** Path to the {@code Homo_sapiens_gene_info.gz} file. */
    private final String geneInfoPath;
    /** Path to the mimgene/medgen file with MIM to gene associations. */
    private final String mim2genemedgenPath;
    /** Path to the file that we create with background frequencies for predicted pathogenic variants in genes. */
    private final String backgroundFrequencyPath;
    /** Path to the VCF file that is be evaluated. */
    private final String vcfPath;
    /** List of HPO terms (phenotypic abnormalities) observed in the person being evaluated. */
    private final List<TermId> hpoIdList;
    /** The directory in which several files are stored. */
    private final String datadir;
    /** THe default data directory */
    private final static String DEFAULT_DATA_DIRECTORY="data";
    /** The directory with the Exomiser database and Jannovar transcript files. */
    private String exomiserPath;
    /** Number of variants that were not removed because of the quality filter. */
    private int n_good_quality_variants=0;
    /** Number of variants that were removed because of the quality filter. */
    private int n_filtered_variants=0;

    private final GenomeAssembly assembly;

    private final Ontology ontology;
    /** The path to the Exomiser database file, e.g., {@code 1811_hg19_variants.mv.db}. */
    private String mvStorePath=null;

    private String hpoVersion="n/a";

    /** An object representing the Exomiser database. */
    private MVStore mvstore = null;
    private Multimap<TermId,TermId> gene2diseaseMultiMap=null;
    private Multimap<TermId,TermId> disease2geneIdMultiMap=null;
    private Map<TermId,String> geneId2SymbolMap=null;
    /** If true, filter VCF lines by the FILTER column (variants pass if there is no entry, i.e., ".",
     * or if the value of the field is FALSE. Variant also fail if a reason for the not passing the
     * filter is given in the column, i.e., for allelic imbalance. This is true by default. Filtering
     * can be turned off by entering {@code -q false} or {@code --quality} false. */
    private final boolean filterOnFILTER;

    /** Path of the Jannovar UCSC transcript file (from the Exomiser distribution) */
    private String jannovarUcscPath=null;
    /** Path of the Jannovar Ensembl transcript file (from the Exomiser distribution) */
    private String jannovarEnsemblPath=null;
    /** Path of the Jannovar RefSeq transcript file (from the Exomiser distribution) */
    private String jannovarRefSeqPath=null;
    /** Name of sample in VCF file, if any. The default value is n/a to indicate this field has not been initiatilized. */
    private String sampleName="n/a";


    private JannovarData jannovarData=null;

    private Lr2PgFactory(Builder builder) {
        this.hpoOboFilePath = builder.hpOboPath;
        this.exomiserPath = builder.exomiserDataDir;
        if (exomiserPath!=null) {
            initializeExomiserPaths();
        }
        this.assembly=builder.getAssembly();
        if (builder.backgroundFrequencyPath!=null && !builder.backgroundFrequencyPath.isEmpty()) {
            this.backgroundFrequencyPath=builder.backgroundFrequencyPath;
        } else {
            // Note-- background files for hg19 and hg38 are stored in src/main/resources/background
            // and are included in the resources by the maven resource plugin
            ClassLoader classLoader = Lr2PgFactory.class.getClassLoader();
            URL resource;
            if (assembly.equals(GenomeAssembly.HG19)) {
                resource = classLoader.getResource("background/background-hg19.txt");
            } else if (assembly.equals(GenomeAssembly.HG38)) {
                resource = classLoader.getResource("background/background-hg38.txt");
            } else {
                logger.fatal("Did not recognize genome assembly: {}",assembly);
                throw new Lr2PgRuntimeException("Did not recognize genome assembly: "+assembly);
            }
            if (resource==null) {
                logger.fatal("Could not find resource for background file");
                throw new Lr2PgRuntimeException("Could not find resource for background file");
            }
            this.backgroundFrequencyPath=resource.getFile();
        }

        this.geneInfoPath=builder.geneInfoPath;
        this.mim2genemedgenPath=builder.mim2genemedgenPath;

        this.phenotypeAnnotationPath=builder.phenotypeAnnotationPath;
        this.transcriptdatabase=builder.transcriptdatabase;
        this.vcfPath=builder.vcfPath;
        this.datadir=builder.lr2pgDataDir;

        ImmutableList.Builder<TermId> listbuilder = new ImmutableList.Builder<>();
        for (String id : builder.observedHpoTerms) {
            TermId hpoId = TermId.of(id);
            listbuilder.add(hpoId);
        }
        this.hpoIdList=listbuilder.build();
        if (builder.hpOboPath!=null) {
            this.ontology=hpoOntology();
        } else {
            this.ontology=null;
        }
        this.filterOnFILTER=builder.filterFILTER;
    }



    /**
     * @return a list of observed HPO terms (from the YAML file)
     * @throws Lr2pgException if one of the terms is not in the HPO Ontology
     */
    public List<TermId> observedHpoTerms() throws Lr2pgException{
        for (TermId hpoId : hpoIdList) {
            if (! this.ontology.getTermMap().containsKey(hpoId)) {
                throw new Lr2pgException("Could not find HPO term " + hpoId.getValue() + " in ontology");
            }
        }
        return hpoIdList;
    }

    /** @return the genome assembly corresponding to the VCF file. Can be null. */
    public GenomeAssembly getAssembly() {
        return assembly;
    }

    /** @return HpoOntology object. */
    public Ontology hpoOntology() {
        if (ontology != null) return ontology;
        // The HPO is in the default  curie map and only contains known relationships / HP terms
        Ontology ontology =  OntologyLoader.loadOntology(new File(this.hpoOboFilePath));
        if (ontology==null) {
            throw new PhenolRuntimeException("Could not load ontology from \"" + this.hpoOboFilePath +"\"");
        } else {

            Map<String,String> ontologyMetainfo=ontology.getMetaInfo();
            if (ontologyMetainfo.containsKey("data-version")) {
                this.hpoVersion=ontologyMetainfo.get("data-version");
            }
            return ontology;
        }
    }

    /** returns "n/a if {@link #transcriptdatabase} was not initialized (should not happen). */
    public String transcriptdb() {
            return this.transcriptdatabase!=null?this.transcriptdatabase.toString():"n/a";
    }

    public String getBackgroundFrequencyPath() {
        return backgroundFrequencyPath;
    }

    public String getSampleName() {
        return sampleName;
    }

    public String getExomiserPath() { return this.exomiserPath;}

    public String getHpoVersion() { return hpoVersion; }

    public String getVcfPath() {
        if (this.vcfPath==null) {
            throw new Lr2PgRuntimeException("VCF path not initialized");
        }
        return vcfPath;
    }

    /**
     * This is called if the user passes the {@code --exomiser/-e} option. We expect there to be
     * the Jannovar and the MVStore files in the directory and want to construct the paths here.
     */
    private void initializeExomiserPaths() {
        // Remove the trailing directory slash if any
        this.exomiserPath=getPathWithoutTrailingSeparatorIfPresent(this.exomiserPath);
        String basename=FilenameUtils.getBaseName(this.exomiserPath);
        String filename=String.format("%s_variants.mv.db", basename);
        this.mvStorePath=String.format("%s%s%s", exomiserPath,File.separator,filename);
        filename=String.format("%s_transcripts_ucsc.ser", basename);
        this.jannovarUcscPath=filename;
        filename=String.format("%s_transcripts_ensembl.ser", basename);
        this.jannovarEnsemblPath=filename;
        filename=String.format("%s_transcripts_refseq.ser", basename);
        this.jannovarRefSeqPath=filename;

    }


    /** @return MVStore object with Exomiser data on variant pathogenicity and frequency. */
    public MVStore mvStore() {
        File f = new File(this.mvStorePath);
        if (!f.exists()) {
            throw new Lr2PgRuntimeException("[FATAL] Could not find Exomiser database file/variants.mv.db at " + this.mvStorePath);
        }
        if (mvstore==null) {
            mvstore = new MVStore.Builder()
                    .fileName(this.mvStorePath)
                    .readOnly()
                    .open();
        }
        return mvstore;
    }

    public String vcfPath(){ return vcfPath;}



    private void parseHpoAnnotations() throws Lr2pgException {
        if (this.ontology==null) {
            hpoOntology();
        }
        if (this.geneInfoPath==null) {
            throw new Lr2pgException("Path to Homo_sapiens_gene_info.gz file not found");
        }
        if (this.mim2genemedgenPath==null) {
            throw new Lr2pgException("Path to mim2genemedgen file not found");
        }

        File geneInfoFile = new File(geneInfoPath);
        if (!geneInfoFile.exists()) {
            throw new Lr2pgException("Could not find gene info file at " + geneInfoPath + ". Run download!");
        }
        File mim2genemedgenFile = new File(this.mim2genemedgenPath);
        if (!mim2genemedgenFile.exists()) {
            throw new Lr2PgRuntimeException("Could not find medgen file at " + this.mim2genemedgenPath + ". Run download!");
        }
        File orphafilePlaceholder = null;//we do not need this for now
        HpoAssociationParser assocParser = new HpoAssociationParser(geneInfoFile,
                mim2genemedgenFile,
                orphafilePlaceholder,
                ontology);
        assocParser.parse();
        assocParser.getDiseaseToGeneIdMap();

        this.gene2diseaseMultiMap=assocParser.getGeneToDiseaseIdMap();
        this.disease2geneIdMultiMap=assocParser.getDiseaseToGeneIdMap();
        this.geneId2SymbolMap=assocParser.getGeneIdToSymbolMap();
    }


    /** @return a multimap with key: a gene CURIE such as NCBIGene:123; value: a collection of disease CURIEs such as OMIM:600123. */
    public Multimap<TermId,TermId> gene2diseaseMultimap() throws Lr2pgException {
        if (this.gene2diseaseMultiMap==null) {
            parseHpoAnnotations();
        }
        return this.gene2diseaseMultiMap;
    }

    /** @return multimap with key:disease CURIEs such as OMIM:600123; value: a collection of gene CURIEs such as NCBIGene:123.  */
    public Multimap<TermId,TermId> disease2geneMultimap() throws Lr2pgException {
        if (this.disease2geneIdMultiMap==null) {
            parseHpoAnnotations();
        }
        return this.disease2geneIdMultiMap;
    }
    /** @return a map with key:a gene id, e.g., NCBIGene:2020; value: the corresponding gene symbol. */
    public Map<TermId,String> geneId2symbolMap() throws Lr2pgException{
        if (this.geneId2SymbolMap==null) {
            parseHpoAnnotations();
        }
        return this.geneId2SymbolMap;
    }

    /** @return Map with key: geneId, value: corresponding background frequency of bin P variants. */
    public Map<TermId, Double> gene2backgroundFrequency() throws Lr2pgException{
        if (this.backgroundFrequencyPath==null) {
            throw new Lr2pgException("Path to background-freq.txt file not found");
        }
        GenotypeDataIngestor gdingestor = new GenotypeDataIngestor(this.backgroundFrequencyPath);
        return gdingestor.parse();
    }


    private static String getPathWithoutTrailingSeparatorIfPresent(String path) {
        String sep = File.separator;
        if (path.endsWith(sep)) {
            int i=path.lastIndexOf(sep);
            return path.substring(0,i);
        } else {
            return path;
        }
    }

    /**
     * Create a {@link GenotypeLikelihoodRatio} object that will be used to calculated genotype likelhood ratios.
     * A runtime exception will be thrown if the file cannot be found.
     * @return a {@link GenotypeLikelihoodRatio} object
     */
    public GenotypeLikelihoodRatio getGenotypeLR() {
        File f = new File(backgroundFrequencyPath);
        if (!f.exists()) {
            throw new Lr2PgRuntimeException(String.format("Could not find background frequency file at %s",this.backgroundFrequencyPath));
        }
        GenotypeDataIngestor ingestor = new GenotypeDataIngestor(this.backgroundFrequencyPath);
        Map<TermId,Double> gene2back = ingestor.parse();
        return new GenotypeLikelihoodRatio(gene2back);
    }




    /**
     * Deserialize the Jannovar transcript data file that comes with Exomiser. Note that Exomiser
     * uses its own ProtoBuf serializetion and so we need to use its Deserializser. In case the user
     * provides a standard Jannovar serialzied file, we try the legacy deserializer if the protobuf
     * deserializer doesn't work.
     * @return the object created by deserializing a Jannovar file. */
    public JannovarData jannovarData()  {
        if (jannovarData != null) return jannovarData;
        // Remove the trailing directory slash if any
        this.exomiserPath= getPathWithoutTrailingSeparatorIfPresent(this.exomiserPath);
        String basename=FilenameUtils.getBaseName(this.exomiserPath);
        String fullpath;
        switch (this.transcriptdatabase) {
            case REFSEQ:
                String refseqfilename=String.format("%s_transcripts_refseq.ser", basename);
                fullpath=String.format("%s%s%s", exomiserPath,File.separator,refseqfilename);
                break;
            case ENSEMBL:
                String ensemblfilename=String.format("%s_transcripts_ensembl.ser", basename);
                fullpath=String.format("%s%s%s", exomiserPath,File.separator,ensemblfilename);
                break;
            case UCSC:
            default:
                String ucscfilename=String.format("%s_transcripts_ucsc.ser", basename);
                fullpath=String.format("%s%s%s", exomiserPath,File.separator,ucscfilename);
                break;
        }

        File f = new File(fullpath);
        if (!f.exists()) {
            throw new Lr2PgRuntimeException("[FATAL] Could not find Jannovar transcript file at " + fullpath);
        }
        try {
            Path p = Paths.get(fullpath);
            this.jannovarData=JannovarDataProtoSerialiser.load(p);
            return jannovarData;
        } catch (InvalidFileFormatException e) {
            logger.warn("Could not deserialize Jannovar file with Protobuf deserializer, trying legacy deserializer...");
        }
        try {
            this.jannovarData=new JannovarDataSerializer(fullpath).load();
            return jannovarData;
        } catch (SerializationException e) {
            logger.error("Could not deserialize Jannovar file with legacy deserializer...");
            throw new Lr2PgRuntimeException(String.format("Could not load Jannovar data from %s (%s)",
                    fullpath, e.getMessage()));
        }
    }

    /** @return a map with key: a disease id (e.g., OMIM:654321) and key the corresponding {@link HpoDisease} object.*/
    public Map<TermId, HpoDisease> diseaseMap(Ontology ontology) throws Lr2pgException {
        if (this.phenotypeAnnotationPath==null) {
            throw new Lr2pgException("Path to phenotype.hpoa file not found");
        }
        // phenol 1.3.2
        List<String> desiredDatabasePrefixes=ImmutableList.of("OMIM","DECIPHER");
        HpoDiseaseAnnotationParser annotationParser=new HpoDiseaseAnnotationParser(phenotypeAnnotationPath,ontology,desiredDatabasePrefixes);
       // HpoDiseaseAnnotationParser annotationParser = new HpoDiseaseAnnotationParser(this.phenotypeAnnotationPath, this.ontology);
        try {
            Map<TermId, HpoDisease> diseaseMap = annotationParser.parse();
            if (!annotationParser.validParse()) {
                int n = annotationParser.getErrors().size();
                logger.warn("Parse problems encountered with the annotation file at {}. Got {} errors",
                        this.phenotypeAnnotationPath,n);
            }
            return diseaseMap;
        } catch (PhenolException pe) {
            throw new Lr2pgException("Could not parse annotation file: " + pe.getMessage());
        }
    }

    public  Map<TermId, Gene2Genotype> getGene2GenotypeMap() {
        Vcf2GenotypeMap vcf2geno = new Vcf2GenotypeMap(getVcfPath(),
                jannovarData(),
                mvStore(),
                getAssembly(),
                this.filterOnFILTER);
        Map<TermId, Gene2Genotype> genotypeMap = vcf2geno.vcf2genotypeMap();
        this.sampleName=vcf2geno.getSamplename();
        this.n_filtered_variants=vcf2geno.getN_filtered_variants();
        this.n_good_quality_variants=vcf2geno.getN_good_quality_variants();
        return genotypeMap;
    }

    /** @return a string with today's date in the format yyyy/MM/dd. */
    public String getTodaysDate() {
        DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd");
        Date date = new Date();
        return dateFormat.format(date);
    }

    public int getN_good_quality_variants() {
        return n_good_quality_variants;
    }

    public int getN_filtered_variants() {
        return n_filtered_variants;
    }

    /**
     * This is used by the Builder to check that all of the necessary files in the Data directory are present.
     * It writes one line to the logger for each file it checks, and throws a RunTime exception if a file is
     * missing (in this case we cannot continue with program execution).
     */
    public void qcHumanPhenotypeOntologyFiles() {
        File datadirfile = new File(datadir);
        if (!datadirfile.exists()) {
            logger.fatal("Could not find LR2PG data directory at {}",datadir);
            logger.fatal("Consider running download command.");
            throw new Lr2PgRuntimeException(String.format("Could not find LR2PG data directory at %s",datadir));
        } else if (!datadirfile.isDirectory()) {
            logger.fatal("LR2PG datadir path ({}) is not a directory.",datadir);
            throw new Lr2PgRuntimeException(String.format("LR2PG datadir path (%s) is not a directory.",datadir));
        } else {
            logger.trace("LR2PG datadirectory: {}", datadir);
        }
        File f1 = new File(this.hpoOboFilePath);
        if (!f1.exists() && f1.isFile()) {
            logger.fatal("Could not find valid hp.obo file at {}",hpoOboFilePath);
            throw new Lr2PgRuntimeException(String.format("Could not find valid hp.obo file at %s",hpoOboFilePath));
        } else {
            logger.trace("hp.obo: {}",hpoOboFilePath);
        }
        File f2 = new File(this.phenotypeAnnotationPath);
        if (!f2.exists() && f2.isFile()) {
            logger.fatal("Could not find valid phenotype.hpoa file at {}",phenotypeAnnotationPath);
            throw new Lr2PgRuntimeException(String.format("Could not find valid phenotype.hpoa file at %s",phenotypeAnnotationPath));
        } else {
            logger.trace("phenotype.hpoa: {}",phenotypeAnnotationPath);
        }
    }



    public void qcExternalFilesInDataDir() {
        File f1 = new File(this.mim2genemedgenPath);
        if (!f1.exists() && f1.isFile()) {
            logger.fatal("Could not find valid mim2gene_medgen file at {}",mim2genemedgenPath);
            throw new Lr2PgRuntimeException(String.format("Could not find valid mim2gene_medgen file at %s",mim2genemedgenPath));
        } else {
            logger.trace("mim2gene_medgen: {}",mim2genemedgenPath);
        }
        File f2 = new File(this.geneInfoPath);
        if (!f2.exists() && f2.isFile()) {
            logger.fatal("Could not find valid Homo_sapiens_gene_info.gz file at {}",geneInfoPath);
            throw new Lr2PgRuntimeException(String.format("Could not find valid Homo_sapiens_gene_info.gz file at %s",geneInfoPath));
        } else {
            logger.trace("Homo_sapiens_gene_info.gz: {}",geneInfoPath);
        }
    }

    public void qcExomiserFiles() {
        File exomiserDir = new File(exomiserPath);
        if (!exomiserDir.exists()) {
            logger.fatal("Could not find Exomiser data directory at {}",exomiserPath);
            throw new Lr2PgRuntimeException(String.format("Could not find Exomiser data directory at %s",exomiserPath));
        } else if (!exomiserDir.isDirectory()) {
            logger.fatal("Exomiser data path ({}) is not a directory.",exomiserPath);
            throw new Lr2PgRuntimeException(String.format("Exomiser data path (%s) is not a directory.",exomiserPath));
        } else {
            logger.trace("Exomiser data: {}", exomiserPath);
        }
        File mvStoreFile=new File(this.mvStorePath);
        if (!mvStoreFile.exists()) {
            logger.fatal("Could not find Exomiser database file at {}",this.mvStorePath);
            throw new Lr2PgRuntimeException(String.format("Could not find Exomiser database file at %s",mvStorePath));
        }
    }

    /**
     * This method checks whether the background freqeuncy file can be found.
     */
    private void qcBackgroundFreqFile() {
        File bgf = new File(this.backgroundFrequencyPath);
        if (! bgf.exists()) {
            logger.fatal("Could not find background frequency file at {}",this.backgroundFrequencyPath);
            throw new Lr2PgRuntimeException(String.format("Could not find background frequency file at %s",this.backgroundFrequencyPath));
        } else {
            logger.trace("Background frequency file: {}", this.backgroundFrequencyPath);
        }
    }



    public void qcYaml() {
        qcHumanPhenotypeOntologyFiles();
        qcExternalFilesInDataDir();
        qcExomiserFiles();
        qcBackgroundFreqFile();
    }

    /**
     * Perform Q/C of the input variables to try to ensure that the correct (matching) genome build is being used.
     */
    public void qcGenomeBuild() {
        if (this.assembly.equals(GenomeAssembly.HG19)) {
            if (! this.exomiserPath.contains("hg19")) {
                throw new Lr2PgRuntimeException(String.format("Use of non-matching Exomiser database (%s) for genome assembly hg19", this.exomiserPath));
            }
        } else if (this.assembly.equals(GenomeAssembly.HG38)) {
            if (! this.exomiserPath.contains("hg38")) {
                throw new Lr2PgRuntimeException(String.format("Use of non-matching Exomiser database (%s) for genome assembly hg38", this.exomiserPath));
            }
        } else {
            logger.trace("Genome assembly: {}",this.assembly.toString());
        }
    }

    public void qcVcfFile() {
        if (this.vcfPath==null) {
            throw new Lr2PgRuntimeException("VCF file was not initialzed");
        }
        if (! (new File(vcfPath)).exists()) {
            throw new Lr2PgRuntimeException("We did not find a VCF file at \"" + vcfPath +"\"");
        }
        logger.trace("VCF File: {}",this.vcfPath);
    }


    /**
     * A convenience Builder class for creating {@link Lr2PgFactory} objects
     */
    public static class Builder {
        /** path to hp.obo file.*/
        private String hpOboPath=null;
        private String phenotypeAnnotationPath=null;
        private String lr2pgDataDir=null;
        private String exomiserDataDir=null;
        private String geneInfoPath=null;
        private String mim2genemedgenPath=null;
        private String backgroundFrequencyPath=null;
        private String vcfPath=null;
        private String genomeAssembly=null;
        private boolean filterFILTER=true;
        /** The default transcript database is UCSC> */
        private TranscriptDatabase transcriptdatabase=  TranscriptDatabase.UCSC;
        private List<String> observedHpoTerms=ImmutableList.of();

        public Builder(){
        }

        public Builder yaml(YamlParser yp) {
            Optional<String> datadirOpt=yp.getDataDir();
            if (datadirOpt.isPresent()) {
                this.lr2pgDataDir = getPathWithoutTrailingSeparatorIfPresent(datadirOpt.get());
            } else {
                this.lr2pgDataDir=DEFAULT_DATA_DIRECTORY;
            }
            initDatadirFiles();
            this.exomiserDataDir=yp.getExomiserDataDir();
            this.genomeAssembly=yp.getGenomeAssembly();
            this.observedHpoTerms=new ArrayList<>();
            Collections.addAll(this.observedHpoTerms, yp.getHpoTermList());
            switch (yp.transcriptdb().toUpperCase()) {
                case "ENSEMBL" :
                    this.transcriptdatabase=TranscriptDatabase.ENSEMBL;
                case "REFSEQ":
                    this.transcriptdatabase=TranscriptDatabase.REFSEQ;
                case "UCSC":
                default:
                    this.transcriptdatabase=TranscriptDatabase.UCSC;
            }
            Optional<String> vcfOpt=yp.vcfPath();
            if (vcfOpt.isPresent()) {
                this.vcfPath=vcfOpt.get();
            } else {
                vcfPath=null;
            }
            Optional<String> backgroundOpt = yp.getBackgroundPath();
            backgroundOpt.ifPresent(s -> this.backgroundFrequencyPath = s);


            return this;
        }


        public Builder genomeAssembly(String ga) {
            this.genomeAssembly=ga;
            return this;
        }

        public Builder transcriptdatabase(String tdb) {
            switch (tdb.toUpperCase()) {
                case "ENSEMBL" :
                    this.transcriptdatabase=TranscriptDatabase.ENSEMBL;
                case "REFSEQ":
                    this.transcriptdatabase=TranscriptDatabase.REFSEQ;
                case "UCSC":
                default:
                    this.transcriptdatabase=TranscriptDatabase.UCSC;
            }
            return this;
        }


        /** @return an {@link org.monarchinitiative.exomiser.core.genome.GenomeAssembly} object representing the genome build.*/
        public GenomeAssembly getAssembly() {
            if (genomeAssembly!=null) {
                switch (genomeAssembly.toLowerCase()) {
                    case "hg19":
                    case "hg37":
                    case "grch37":
                    case "grch_37":
                        return GenomeAssembly.HG19;
                    case "hg38":
                    case "grch38":
                    case "grch_38":
                        return GenomeAssembly.HG38;
                }
            }
            return GenomeAssembly.HG38; // the default.
        }


        public Builder backgroundFrequency(String bf) {
            this.backgroundFrequencyPath=bf;
            return this;
        }

        public Builder filter(boolean f) {
            this.filterFILTER=f;
            return this;
        }



        public Builder vcf(String vcf) {
            this.vcfPath=vcf;
            return this;
        }

        public Builder exomiser(String exomiser) {
            this.exomiserDataDir=exomiser;
            return this;
        }


        public Builder observedHpoTerms(String [] terms) {
            this.observedHpoTerms=new ArrayList<>();
            Collections.addAll(observedHpoTerms, terms);
            return this;
        }

        /** Initializes the paths to the four files that should be in the data directory. This method
         * should be called only after {@link #lr2pgDataDir} has been set.
         */
        private void initDatadirFiles() {
            this.hpOboPath=String.format("%s%s%s",this.lr2pgDataDir,File.separator,"hp.obo");
            this.geneInfoPath=String.format("%s%s%s",this.lr2pgDataDir,File.separator,"Homo_sapiens_gene_info.gz");
            this.phenotypeAnnotationPath=String.format("%s%s%s",this.lr2pgDataDir,File.separator,"phenotype.hpoa");
            this.mim2genemedgenPath=String.format("%s%s%s",this.lr2pgDataDir,File.separator,"mim2gene_medgen");
        }



        public Builder datadir(String datadir) {
            this.lr2pgDataDir=getPathWithoutTrailingSeparatorIfPresent(datadir);
           initDatadirFiles();
            return this;
        }


        public Lr2PgFactory build() {
            return new Lr2PgFactory(this);
        }

        public Lr2PgFactory buildForGenomicDiagnostics() {

            Lr2PgFactory factory = new Lr2PgFactory(this);
            factory.qcHumanPhenotypeOntologyFiles();
            factory.qcExternalFilesInDataDir();
            factory.qcExomiserFiles();
            return factory;
        }


    }
}
