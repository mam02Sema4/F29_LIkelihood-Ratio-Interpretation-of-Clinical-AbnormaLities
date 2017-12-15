package org.monarchinitiative.lr2pg.hpo;


import com.github.phenomics.ontolib.formats.hpo.HpoFrequency;
import com.github.phenomics.ontolib.formats.hpo.HpoTerm;
import com.github.phenomics.ontolib.formats.hpo.HpoTermRelation;
import com.github.phenomics.ontolib.ontology.data.*;
import org.junit.BeforeClass;
import org.junit.Test;
import org.monarchinitiative.lr2pg.io.HpoAnnotation2DiseaseParser;
import org.monarchinitiative.lr2pg.io.HpoOntologyParser;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class Disease2TermFrequencyTest {

    private static TermPrefix HP_PREFIX=null;

    private static Disease2TermFrequency d2tf=null;

    private static final double EPSILON=0.000001;


    @BeforeClass
    public static void setup() throws IOException {
        HP_PREFIX = new ImmutableTermPrefix("HP");
        ClassLoader classLoader = Disease2TermFrequencyTest.class.getClassLoader();
        String hpoPath = classLoader.getResource("hp.obo").getFile();
        String annotationPath = classLoader.getResource("small_phenoannot.tab").getFile();
        HpoOntologyParser parser = new HpoOntologyParser(hpoPath);
        parser.parseOntology();
        Ontology<HpoTerm, HpoTermRelation> phenotypeSubOntology = parser.getPhenotypeSubontology();
        Ontology<HpoTerm, HpoTermRelation> inheritanceSubontology = parser.getInheritanceSubontology();
        HpoAnnotation2DiseaseParser annotationParser=new HpoAnnotation2DiseaseParser(annotationPath,phenotypeSubOntology,inheritanceSubontology);
        Map<String,HpoDiseaseWithMetadata> diseaseMap=annotationParser.getDiseaseMap();
        String DEFAULT_FREQUENCY="0040280";
        d2tf=new Disease2TermFrequency(phenotypeSubOntology,inheritanceSubontology,diseaseMap);
    }


    @Test
    public void notNullTest() {
        assertNotNull(d2tf);
    }

    /**
     * $ cut -f 2 small_phenoannot.tab | sort | uniq | wc -l
     196
     */
    @Test
    public void testGetNumberOfDiseases() {
        int expected = 196;
        assertEquals(expected,d2tf.getNumberOfDiseases());
    }

    /**
     * cut -f 5 small_phenoannot.tab | sort
     * shows that there is only one instance of Atrial cardiomyopathy, HP:0200127. The frequency should
     * be 1/196 -- there are no frequency modifiers in {@code small_phenoannot.tab}, so we do not need to worry about
     * weighting
     */
    @Test
    public void testFrequency1() {
        ImmutableTermId tid = new ImmutableTermId(HP_PREFIX,"0200127"); // Atrial cardiomyopathy
        double expected = (double)1/196;
        assertEquals(expected,d2tf.getBackgroundFrequency(tid),EPSILON);
    }
}
